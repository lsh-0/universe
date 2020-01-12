(ns universe.core
  (:require
   [clojure.tools.namespace.repl :refer [refresh]]
   [taoensso.timbre :refer [debug info warn error spy]]
   ))

;; mass == app
;; body == task runner
;; message == just that
;; response == type of message
;; request == type of message
;; alert == type of message
;; listen == a body subscribing to messages

;;

(defn mk-id
  []
  (str (java.util.UUID/randomUUID)))

;;

(def -state-template
  {:masses {}
   :messages []
   :cleanup []})

(def state nil)

(defn mass
  []
  {:type :mass
   :id (mk-id)
   :bodies {}})

(defn add-mass!
  [mass]
  (swap! state assoc-in [:masses (:id mass)] mass)
  nil)

(defn message
  [user-msg & [overrides]]
  (merge
   {:type :message
    :id (mk-id)
    :message-type :signal ;; :request, :response
    :message user-msg} overrides))

(defn emit-message!
  [msg]
  (swap! state update-in [:messages] conj msg)
  nil)


;; hello? is anybody out there?
;; hello! who are you? what can you do?

(defn body
  [f]
  {:type :body
   :id (mk-id)
   :func f})

(defn state-bind
  [path callback & {:keys [prefn]}]
  (let [prefn (or prefn identity)
        has-changed (fn [old-state new-state]
                      (not= (prefn (get-in old-state path))
                            (prefn (get-in new-state path))))
        wid (keyword (gensym callback)) ;; :foo.bar$baz@123456789
        rmwatch #(remove-watch state wid)]

    (add-watch state wid
               (fn [_ _ old-state new-state] ;; key, atom, old-state, new-state
                 (when (has-changed old-state new-state)
                   ;;(debug (format "path %s triggered %s" path wid))
                   (try
                     (callback old-state new-state)
                     (catch Exception e
                       (error e "error caught in watch! your callback *must* be catching these or the thread dies silently:" path))))))

    ;; add a cleanup fn. called on app stop
    (swap! state update-in [:cleanup] conj rmwatch)))

(defn add-listener
  [mass body pred]
  (let [callback (fn [old-state new-state]
                   ;; naive. doesn't handle multiple new messages at once, or truncation
                   (let [newest-message (last (:messages new-state))]
                     (when (and newest-message ;; handles message list being emptied
                                (pred newest-message))
                       (try
                         ;; only emit a response if there was a non-nil result
                         (when-let [result ((:func body) newest-message)]
                           (emit-message! (message result {:message-type :response
                                                           :request-id (:id newest-message)})))
                         (catch Exception unhandled-exception
                           (emit-message! (message unhandled-exception {:message-type :response
                                                                        :request-id (:id newest-message)})))))))
        ]
    (state-bind [:messages] callback)))

(defn request?
  [x]
  (and (map? x)
       (-> x :type (= :message))
       (-> x :message-type (= :request))))

(defn response?
  [x]
  (and (map? x)
       (-> x :type (= :message))
       (-> x :message-type (= :response))))

(defn add-body!
  [mass body pred]
  (swap! state assoc-in [:masses (:id mass) :bodies (:id body)] body)
  (add-listener mass body pred)
  nil)


;;

(defn stop
  [state]
  (when state
    (doseq [clean-up-fn (:clean-up @state)]
      (clean-up-fn))
    (alter-var-root #'state (constantly nil))))

(defn echo-info
  [x]
  (info "received request:" x))

(defn echo-warn
  [x]
  (warn "received response:" x))

(defn init
  []
  (let [m (mass)
        request-listener (body echo-info)
        response-listener (body echo-warn)]
    (add-mass! m)
    (add-body! m request-listener request?)
    (add-body! m response-listener response?)))

(defn start
  []
  (if-not state
    (do
      (alter-var-root #'state (constantly (atom -state-template)))
      (init))
    (warn "application already started")))

(defn restart
  []
  (stop state)
  (start))
