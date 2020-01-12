(ns universe.core
  (:require
   [clojure.tools.namespace.repl :refer [refresh]]
   [taoensso.timbre :refer [log debug info warn error spy]]
   ))

;; body == task runner
;; message == just that
;; response == type of message
;; request == type of message
;; alert == type of message
;; listen == a body subscribing to messages

;; utils

(defn mk-id
  []
  (str (java.util.UUID/randomUUID)))

;; state

(def -state-template
  {:messages []
   :cleanup []})

(def state nil)

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

;;

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

(defn actor
  [f]
  {:type :actor
   :id (mk-id)
   :func f})

(defn add-listener
  [actor pred]
  (let [callback (fn [old-state new-state]
                   ;; naive. doesn't handle multiple new messages at once, or truncation
                   (let [newest-message (last (:messages new-state))]
                     (when (and newest-message ;; handles message list being emptied
                                (pred newest-message))
                       (try
                         ;; only emit a response if there was a non-nil result
                         (when-let [result ((:func actor) newest-message)]
                           (emit-message! (message result {:message-type :response
                                                           :request-id (:id newest-message)})))
                         (catch Exception unhandled-exception
                           (emit-message! (message unhandled-exception {:message-type :response
                                                                        :request-id (:id newest-message)})))))))
        ]
    (state-bind [:messages] callback)))

(defn add-actor!
  [actor pred]
  (swap! state assoc-in [:bodies (:id actor)] actor)
  (add-listener actor pred)
  nil)

;; predicates

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

;; things that do things :)

(defn echo
  [level]
  (fn [x]
    (log level "echo:" x)))

;; bootstrap

(defn init
  []
  (let [request-listener (actor (echo :info))
        response-listener (actor (echo :warn))]
    (add-actor! request-listener request?)
    (add-actor! response-listener response?)))

(defn stop
  [state]
  (when state
    (doseq [clean-up-fn (:clean-up @state)]
      (clean-up-fn))
    (alter-var-root #'state (constantly nil))))

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
