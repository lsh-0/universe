(ns universe.core
  (:require
   [clojure.tools.namespace.repl :refer [refresh]]
   [clojure.core.async :as async :refer [<! >! >!! <!! go]]
   [taoensso.timbre :refer [log debug info warn error spy]]
   [me.raynes.fs :as fs]
   [universe.utils :as utils :refer [in? mk-id]]
   ))

;; state

(def -state-template
  {:cleanup [] ;; a list of functions that are called on application stop
   :publication nil ;; async/pub, sends messages to subscribers, if any
   :publisher nil ;; async/chan, `publication` reads from this channel and we write to it
   :service-list [] ;; list of known services. each service is a map, each map has a function
   ;; a service can store things in state, just not at the top level
   :service-state {:db {:storage-dir "crux-store" ;; set to nil for in-memory store only (faster testing)
                        }} 
   :results-list [] ;; list of non-nil responses to requests
   :last-session nil ;; list of non-nil responses to requests from *previous* running app
   :known-topics #{} ;; set of all known available topics
})

(def state nil)

;;

(defn state-bind
  "executes given callback function when value at path in state map changes. 
  trigger is discarded if old and new values are identical"
  [path callback]
  (let [has-changed (fn [old-state new-state]
                      (not= (get-in old-state path)
                            (get-in new-state path)))
        wid (keyword (gensym callback)) ;; :foo.bar$baz@123456789
        rmwatch #(remove-watch state wid)]

    (add-watch state wid
               (fn [_ _ old-state new-state] ;; key, atom, old-state, new-state
                 (when (has-changed old-state new-state)
                   (debug (format "path %s triggered %s" path wid))
                   (try
                     (callback new-state)
                     (catch Exception uncaught-exc
                       (error uncaught-exc "error caught in watch! your callback *must* be catching these or the thread dies silently:" path))))))

    (swap! state update-in [:cleanup] conj rmwatch)
    nil))

;; --

(defn started?
  []
  (some? state))

(defn get-state
  [& path]
  (if (started?)
    (if path
      (get-in @state path)
      @state)
    (error "application has not been started, cannot access path:" path)))

(defn get-service-state
  [service-id-kw & path]
  (apply get-state (concat [:service-state service-id-kw] path)))

;; todo: update-service-state that accepts an update fn
(defn set-service-state
  [service-id-kw key val]
  (swap! state assoc-in [:service-state service-id-kw key] val))

(defn add-cleanup
  [f]
  (swap! state update-in [:cleanup] conj f))

(def file-dir "resources")
(def temp-dir "/tmp")

;;

(defn known-topic?
  "returns true if somebody is listening for messages on this topic"
  [topic-kw]
  (contains? (get-state :known-topics) topic-kw))

(defn safe-topic
  "if nobody is listening for the given topic, replaces it with :off-topic"
  [topic-kw]
  (if-not (known-topic? topic-kw) :off-topic topic-kw))

(defn safe-message
  "ensures a message is delivered to a topic. if the specified topic is not being listened to, the message is sent to off-topic"
  [msg]
  (update-in msg [:topic] safe-topic))

(defn message
  "creates a simple message that will go to those listening to the 'off-topic' topic by default"
  [user-msg & [overrides]]
  (let [;; messages to these services will always be marked as 'internal' unless overridden
        internal-services [:apply-service]
        internal? (in? (:topic overrides) internal-services)]
    (merge
     {:type :message
      :id (mk-id)
      :topic :off-topic
      :message user-msg ;; absolutely anything
      :response-chan nil ;; a channel is supplied if the message sender wants to receive a response
      :internal? internal?
      } overrides)))

(defn request
  "requests are messages with a response channel"
  [topic-kw & [overrides]]
  (message (:message overrides) ;; may be nil
           (merge {:topic topic-kw
                   :response-chan (async/chan 1)} overrides)))

(defn internal-request
  "just like `request`, but response is not preserved in `:results-list`.
  recommended for services talking to other services."
  [topic-kw & [overrides]]
  (request topic-kw (merge overrides :internal? true)))

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

(defn emit
  [msg]
  (when-let [publisher (get-state :publisher)]
    (if msg
      (go
        (>! publisher msg))
      (error "cannot emit 'nil' as a message")))
  nil)

(defn emit-and-wait
  "like `emit`, but will wait (block) for a response to the message if the message has a response channel"
  [msg]
  (emit msg)
  (when-let [chan (:response-chan msg)]
    (<!! chan)))

(defn -start-listening
  "assumes that the given actor has been subscribed to a topic"
  [actor]
  (debug "actor is listening:" (:id actor))
  (async/go-loop []
    (when-let [msg (<! (:input-chan actor))]
      (let [actor-func (:func actor)
            resp-chan (:response-chan msg)

            _ (debug (format "actor '%s' received message: %s" (:id actor) msg))

            result (try
                     (actor-func msg)
                     (catch Exception e
                       (error e "unhandled exception while calling actor:" e)))
            ]

        ;; when there is a result, add it to the results list
        ;; todo: the size of this could get out of hand for long running programs. perhaps limit size to N items
        (when (and result
                   ;;(not (:internal? result))) ;; ... hang on. why are we pulling 'internal?' from the *result* ?
                   (not (:internal? msg)))
          (info "actor" actor "result" result)
          (swap! state update-in [:results-list] conj result))

        ;; when there is a response channel, stick the response on the channel, even if the response is nil
        (when resp-chan
          (debug "...response channel found, sending result to it:" result)
          ;; this implies a single response only. 
          (when result
            (>! resp-chan result))
          (async/close! resp-chan))

        (recur)))

    ;; message was nil (channel closed), die
    ))

(defn add-actor! ;; to 'stage' ? 
  [actor topic-kw & [more-filter-fns]]
  ;; subscribe actor to incoming messages for the given topic
  (async/sub (get-state :publication) topic-kw (:input-chan actor))
  ;; init actor's function. this returns immediately
  (-start-listening actor))

;; bootstrap

(defn test-query
  []
  (emit (request :write-file {:message "this content is written to file"
                               :filename "foo.temp"})))

(defn register-service
  "services are just actors waiting around for stuff they're interested in and then doing it"
  [service-map]
  (let [actor-overrides (select-keys service-map [:id :input-chan :topic :accept-pred])
        new-actor (actor (:service service-map) actor-overrides)

        ;; unfinished thought: service-map allows you to specify an actor, multiple topics and for each topic a further list of filters
        ;; unfinished thought: service-map specifies a :label and not an :id, :ids are generated automatically. this would allow you to specify the same function twice with different topic+filter combinations and keep the label. might be confusing in the logs though ...
        ;;service-request-overrides (select-keys service-map [:

        ;; so given an elaborate :topic map, a variable number of actors could be created.
        ;; each actor created should be added to the service list
        
        topic-kw (get service-map :topic :off-topic)

        ;; a service can do a once-off thing before it starts listening
        ;; todo: add complementary `:close-fn` ? no, that can be done by :cleanup
        _ (when-let [init-fn (get service-map :init-fn)]
            (init-fn))

        subscription-polling (add-actor! new-actor topic-kw)

        ;; closes the actors input channel and empties any pending items
        close-actor-incoming #(close-chan! (:input-chan new-actor))

        ;; break the actor's polling loop on the subscription
        ;; with a closed `:input-chan` it shouldn't receive any new messages and
        ;; it would remain indefinitely parked
        rm-actor #(close-chan! subscription-polling)

        ]
    (swap! state update-in [:service-list] conj new-actor)
    (swap! state update-in [:known-topics] conj topic-kw) 
    (add-cleanup close-actor-incoming)
    (add-cleanup rm-actor))
  nil)

(defn register-all-services
  [service-list]
  (doseq [service service-list] (register-service service)))

;;

(def service-list [])

(defn init
  "app has been started at this point and state is available to be derefed."
  [& [service-list opt-map]]
  (let [publisher (async/chan)
        publication (async/pub publisher :topic)

        state-updates {:publisher publisher
                       :publication publication}

        ;; a map of initial state values can be passed in at init time that
        ;; are deep-merged after all other init has happened.
        ;; note: not sure if `merge-with merge` is best
        state-updates (merge-with merge state-updates (:initial-state opt-map))]

    (swap! state merge state-updates)

    (register-all-services service-list)

    ;;(test-query)))
    ))
