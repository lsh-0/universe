(ns universe.core
  (:require
   [clojure.tools.namespace.repl :refer [refresh]]
   [clojure.core.async :as async :refer [<! >!!]]
   [taoensso.timbre :refer [log debug info warn error spy]]
   ))

;; utils

(defn mk-id
  []
  (str (java.util.UUID/randomUUID)))

;; state

(def -state-template
  {:cleanup []
   :publication nil ;; async/pub, sends messages to subscribers, if any
   :publisher nil ;; async/chan, `publication` reads from this channel and we write to it
})


(def state nil)

;;

(defn message
  [user-msg & [overrides]]
  (merge
   {:type :message
    :id (mk-id)
    :message-type :request ;; :request, :response, :signal, whatever
    :message user-msg} overrides))

(defn actor
  [f & [more-attrs]]
  (merge {:type :actor
          :id (mk-id)
          :input-chan (async/chan)
          :func f} more-attrs))

(defn emit-message!
  [msg]
  (if msg
    (>!! (:publisher @state) msg)
    (error "cannot emit 'nil' as a message"))
  nil)

(defn -start-listening
  "assumes that the given actor has been subscribed to a topic"
  [actor]
  (debug "actor is listening:" (:id actor))
  (async/go-loop []
    (let [msg (<! (:input-chan actor))
          actor-func (:func actor)]
      (debug "actor received message:" (:id msg))
      (emit-message! (message (actor-func (:message msg))
                              {:message-type :response}))
      (recur))))

(defn add-actor! ;; to 'stage' ? 
  [actor topic-kw] ;; pred]
  ;; subscribe actor to incoming messages for the given topic (request/response/broadcast)
  (async/sub (:publication @state) topic-kw (:input-chan actor))
  ;; init actor's function. this returns immediately
  (-start-listening actor))

;; things that do things :)

(defn echo
  [level]
  (fn [msg]
    (log level "echo:" msg)))

(defn wait
  [interval-seconds]
  (fn [msg]
    (info (format "sleeping for %s seconds..." interval-seconds))
    (Thread/sleep (* interval-seconds 1000))
    (info "...done sleeping. received message:" msg)))

;; bootstrap


(defn actor-init
  []
  (let [;;request-listener (actor (echo :info))
        ;;response-listener (actor (echo :warn))
        ]
    ;;(add-actor! request-listener request?)
    ;;(add-actor! response-listener response?)

    (add-actor! (actor (wait 5) {:id :wait-actor}) :request)
    (add-actor! (actor (echo :info) {:id :echo-actor}) :request)

    ;;  :topic :roll-call
    ;;  [request-type topic]
    ;;  [:request :roll-call]
    ;;(add-actor! (actor inspect-self) (message-filter [request? #(-> % :request (= :roll-call))]))
    )
  nil)

(defn init
  []
  ;; todo: any cleanup necessary?
  (let [publisher (async/chan)
        publication (async/pub publisher :message-type)]
    (swap! state merge {:publisher publisher
                        :publication publication})))

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
      (init)
      (actor-init))
    (warn "application already started")))

(defn restart
  []
  (stop state)
  (start))
