(ns universe.core
  (:require
   [clojure.tools.namespace.repl :refer [refresh]]
   [clojure.core.async :as async :refer [<! >! >!! <!! go]]
   [taoensso.timbre :refer [log debug info warn error spy]]
   [me.raynes.fs :as fs]
   ))

;; utils

(defn mk-id
  []
  (str (java.util.UUID/randomUUID)))

(defn seq-to-map
  "creates a map from an even, sequential, collection of values"
  [s]
  (if-not (even? (count s))
    (error "expected an even number of elements")
    (->> s (partition 2) (map vec) (into {}))))

;; state

(def -state-template
  {:cleanup []
   :publication nil ;; async/pub, sends messages to subscribers, if any
   :publisher nil ;; async/chan, `publication` reads from this channel and we write to it
   :service-list [] ;; list of known services. each service is a map, each map has a function
})

(def state nil)

(defn get-state
  [& path]
  (if state
    (if path
      (get-in @state path)
      @state)
    (error "application has not been started, cannot access path:" path)))

(defn add-cleanup
  [f]
  (swap! state update-in [:cleanup] conj f))

(def file-dir "resources")
(def temp-dir "/tmp")

;;

(defn message
  "creates a simple message that will go to those listening to the 'off-topic' topic by default"
  [user-msg & [overrides]]
  (merge
   {:type :message
    :id (mk-id)
    :topic :off-topic
    :message user-msg ;; absolutely anything
    :response-chan nil ;; a channel is supplied if the message sender wants to receive a response
    } overrides))

(defn request
  "requests are messages with a response channel"
  [topic-kw user-msg & overrides]
  (message user-msg (merge (seq-to-map overrides)
                           {:topic topic-kw
                            :response-chan (async/chan 1)})))

(defn close-chan!
  "closes channel and empties it of any unconsumed items.
  functions still operating on items will *not* be affected."
  [chan]
  (async/close! chan)
  (async/go-loop []
    (when-let [dropped-val (<! chan)]
      (info (format "dropping %s: %s" (name (:type dropped-val)) (:id dropped-val)))
      (recur))))

;;

(defn actor
  [f & [more-attrs]]
  (merge {:type :actor
          :id (mk-id)
          
          ;; standard unbuffered channel
          ;; all messages will be delivered and consumed sequentially, blocking any other consumers
          :input-chan (async/chan)
          :func f
          } more-attrs))

(defn emit!
  [msg]
  (when-let [publisher (get-state :publisher)]
    (if msg
      (go
        (>! publisher msg))
      (error "cannot emit 'nil' as a message")))
  nil)

(defn -start-listening
  "assumes that the given actor has been subscribed to a topic"
  [actor]
  (debug "actor is listening:" (:id actor))
  (async/go-loop []
    (when-let [msg (<! (:input-chan actor))]
      (let [actor-func (:func actor)
            resp-chan (:response-chan msg)]
        
        (debug (format "actor '%s' received message: %s" (:id actor) msg))
        (when-let [result (try
                            (actor-func msg)
                            (catch Exception e
                              (error e "unhandled exception while calling actor:" e)))]
          (debug "actor function returned a non-nil result...")
          ;; when there is a result and when we have a response channel, stick the response on the channel
          (when resp-chan
            (debug "...response channel found, sending result to it" result)
            (>! resp-chan result)
            ;; this implies a single response only. 
            (async/close! resp-chan)))
        (recur))

      ;; message was nil (channel closed), die
      )))

(defn add-actor! ;; to 'stage' ? 
  [actor topic-kw & [more-filter-fns]]
  ;; subscribe actor to incoming messages for the given topic
  (async/sub (get-state :publication) topic-kw (:input-chan actor))
  ;; init actor's function. this returns immediately
  (-start-listening actor))

;; core services

(defn echo
  [level]
  (fn [{:keys [message]}]
    (log level "echo:" message)))

(defn wait
  [interval-seconds]
  (fn [msg]
    (info (format "sleeping for %s seconds..." interval-seconds))
    (Thread/sleep (* interval-seconds 1000))
    (info "...done sleeping. received message:" (:id msg))))

(defn file-writer
  [msg]
  (let [{:keys [filename message]} msg
        filename (or filename (str "universe-" (mk-id)))
        path (fs/file temp-dir filename)]
    (spit path message)
    path))

(def service-list
  [{:id :writer-actor, :topic :write-file, :service file-writer}
   {:id :echo-actor :service (echo :info)}
   {:id :slow-actor :service (wait 5) :input-chan (async/chan (async/buffer 5))}])

;; bootstrap

(defn test-query
  []
  (emit! (request :write-file "this content is written to file" :filename "foo.temp")))

(defn register-service
  "services are just actors waiting around for stuff they're interested in and then doing it"
  [service-map]
  (let [actor-overrides (select-keys service-map [:id :input-chan])
        new-actor (actor (:service service-map) actor-overrides)

        ;; unfinished thought: service-map allows you to specify an actor, multiple topics and for each topic a further list of filters
        ;; unfinished thought: service-map specifies a :label and not an :id, :ids are generated automatically. this would allow you to specify the same function twice with different topic+filter combinations and keep the label. might be confusing in the logs though ...
        ;;service-request-overrides (select-keys service-map [:

        ;; so given an elaborate :topic map, a variable number of actors could be created.
        ;; each actor created should be added to the service list
        
        topic-kw (get service-map :topic :off-topic)

        subscription-polling (add-actor! new-actor topic-kw)

        ;; closes the actors input channel and empties any pending items
        close-actor-incoming #(close-chan! (:input-chan new-actor))

        ;; break the actor's polling loop on the subscription
        ;; with a closed `:input-chan` it shouldn't receive any new messages and
        ;; it would remain indefinitely parked
        rm-actor #(close-chan! subscription-polling)

        ]
    (swap! state update-in [:service-list] conj new-actor)
    (add-cleanup close-actor-incoming)
    (add-cleanup rm-actor))
  nil)

(defn register-all-services
  [service-list]
  (doseq [service service-list] (register-service service)))

(defn init
  "app has been started at this point and state is available to be derefed."
  [& [service-list]]
  (let [publisher (async/chan)
        publication (async/pub publisher :topic)]
    (swap! state merge {:publisher publisher
                        :publication publication})

    (register-all-services service-list)

    ;;(test-query)))
    ))
