(ns universe.core
  (:require
   [clojure.tools.namespace.repl :refer [refresh]]
   [clojure.core.async :as async :refer [<! >! >!! <!! go]]
   [taoensso.timbre :refer [log debug info warn error spy]]
   [me.raynes.fs :as fs]
   [universe.utils :as utils]
   ))

;; utils

(defn mk-id
  []
  (str (java.util.UUID/randomUUID)))

(defn seq-to-map
  "creates a map from an even, sequential, collection of values"
  [s]
  (if-not (even? (count s))
    (error "expected an even number of elements:" s)
    (->> s (partition 2) (map vec) (into {}))))

;; state

(def -state-template
  {:cleanup []
   :publication nil ;; async/pub, sends messages to subscribers, if any
   :publisher nil ;; async/chan, `publication` reads from this channel and we write to it
   :service-list [] ;; list of known services. each service is a map, each map has a function
   :results-list [] ;; list of non-nil responses to requests
   :known-topics #{} ;; set of all known available topics
})

(def state nil)

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
  [msg]
  (update-in msg [:topic] safe-topic))

(defn message
  "creates a simple message that will go to those listening to the 'off-topic' topic by default"
  [user-msg & [overrides]]
  (merge
   {:type :message
    :id (mk-id)
    :topic :off-topic
    :message user-msg ;; absolutely anything
    :response-chan nil ;; a channel is supplied if the message sender wants to receive a response
    :internal? false
    } overrides))

(defn request
  "requests are messages with a response channel"
  [topic-kw & [overrides]]
  ;;(info "got topic" topic-kw "message" user-msg "overrides" overrides)
  (message (:message overrides) ;; may be nil
           {:topic topic-kw
            :response-chan (async/chan 1)}))

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
                   (not (:internal? result)))
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

;; core services

(defn echo
  "echos the given message to the logger and returns nil."
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
  (let [{:keys [filename message]
         :or {filename (str "universe-" (mk-id)), message "bork bork bork"}} msg
        filename (or filename (str "universe-" (mk-id)))
        path (str (fs/file temp-dir filename))]
    (spit path message)
    path))

(defn forwarder
  "given a topic, sends a request to it with the last response as the message body"
  [msg & [overrides]]
  (let [topic-kw (some-> msg :message (subs 1) keyword)
        last-result (-> (get-state :results-list) last)]
    (when (and topic-kw
               last-result)
      (emit-and-wait (request topic-kw (merge overrides {:message last-result}))))))

(defn filterer
  [msg]
  (let [known-predicates {"alpha?" (partial re-matches #"[a-zA-Z_\- ]")}
        predicate-key (:message msg)
        predicate (get known-predicates predicate-key)
        last-result (-> (get-state :results-list) last)]
    (when (and predicate
               last-result)
      (cond
        (string? last-result) (utils/string-filter predicate last-result)

        :else (filterv predicate last-result)))))

(defn selecter
  [msg]
  (let [known-selectors {"all" identity}
        selector (:message msg)

        int-or-nil (fn [x]
                     (try
                       (Integer. x)
                       (catch NumberFormatException e
                         nil)))

        all-results (get-state :results-list)
        num-results (count all-results)

        
        idx (int-or-nil selector)
        idx (cond
              (pos-int? idx) (when-not (> idx num-results)
                               idx)

              (neg-int? idx) (let [neg-idx (- (count all-results) (- idx))]
                               (when-not (neg-int? neg-idx)
                                 neg-idx))

              ;; zero
              :else idx)]
    (when selector
      (cond
        ;; bogus input, returning nothing
        (nil? selector) nil

        ;; human readable cases
        (contains? known-selectors selector) ((known-selectors selector) all-results)

        ;; todo: slicing by pattern. 1-2,1-*,-1
        
        ;; lastly, try indexing straight into the results

        ;; given index couldn't be coerced or was too positive or too negative. return nothing
        (nil? idx) nil
        

        :else (.get all-results idx)))))

;; todo: services that:
;; * change log level
;; * store relationships in persistant storage
;; * 
(def service-list
  [{:id :writer-actor :topic :write-file :service file-writer}
   {:id :forwarder :topic :|forward :service forwarder}
   {:id :filterer :topic :|filter :service filterer}
   {:id :selecter :topic :select :service selecter}

   {:id :echo-actor :service (echo :info)} ;; off-topic echo
   {:id :echo-actor :topic :echo :service (echo :debug)} ;; for testing

   {:id :repeat-actor :topic :repeat :service :message} ;; for testing
   
   ;;{:id :slow-actor :service (wait 5) :input-chan (async/chan (async/buffer 5))}
   ])

;; bootstrap

(defn test-query
  []
  (emit (request :write-file {:message "this content is written to file"
                               :filename "foo.temp"})))

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
    (swap! state update-in [:known-topics] conj topic-kw) 
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
