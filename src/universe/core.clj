(ns universe.core
  (:require
   [clojure.tools.namespace.repl :refer [refresh]]
   [clojure.core.async :as async :refer [<! >! >!! <!! go]]
   [taoensso.timbre :refer [log debug info warn error spy]]
   [me.raynes.fs :as fs]
   [universe.utils :as utils]
   ))

;; utils

(defn in?
  [needle haystack]
  (not (nil? (some #{needle} haystack))))

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

(defn int-or-nil [x]
  (try
    (Integer. x)
    (catch NumberFormatException e
      nil)))

(defn pos-offset
  [idx size]
  (if (neg-int? idx)
    (let [pos-idx (+ size idx)]
      (if (neg-int? pos-idx)
        nil ;; *still* negative
        pos-idx))
    idx))

(defn select-range
  [start end]
  (let [start (int-or-nil start)
        end (int-or-nil end)

        _ (info "select range got" start end)

        num-results (count (get-state :results-list))

        ;; if start position is outside of range, die. this is a non-starter
        start (if (>= start num-results) nil start)

        ;; if end position is outside of range, cap at num-results
        end (if (>= end num-results) num-results end)

        ;; if start is negative, cap at 0
        start (pos-offset start num-results)

        end (pos-offset end num-results)

        _ (info "final range" start end "num results" num-results)
        
        ]
    (if (not (and start end))
      nil ;; range must both be integers
      (subvec (get-state :results-list) start end))))
      
(defn select-idx
  [idx]
  (let [num-results (count (get-state :results-list))

        idx (int-or-nil idx)

        ;; handle too-large positive indices and negative indices
        ;; assume user is using zero-based counting
        idx (cond
              ;; further comparisons require integers
              (nil? idx) nil

              ;; when given index is greater than (or equal to) number of results, cap it at number of results
              (>= idx num-results) (dec num-results) ;; decrement because indices are zero-based

              ;; when given index is negative, subtract from total results to get a positive index
              (neg-int? idx) (let [pos-idx (+ num-results idx)] ;; 12 + -2 = 10; 12 + -20 = -8
                               (if (neg-int? pos-idx)
                                 0 ;; *still* negative! cap at zero
                                 pos-idx))

              ;; positive int within range or zero
              :else idx)]
    (when idx
      (nth (get-state :results-list) idx))))

(defn selecter
  "inspects message and then calls either select-idx or select-range"
  [msg]
  (let [known-selectors {"all" identity}
        selector (:message msg) ;; same as `start`
        [start end] (take 2 (:args msg))]
    (info "got args" (:args msg))
    (cond
      ;; select what?? returning nothing
      (nil? selector) nil

      ;; human readable cases
      (contains? known-selectors selector) ((known-selectors selector) (get-state :results-list))

      ;; we have a range
      (and start end) (select-range start end)

      ;; we just a start/idx/selector
      :else (select-idx start))))

(defn repeater
  [msg]
  (if-let [body (:message msg)]
    body
    (-> (get-state :results-list) last)))

(defn unnester
  [msg]
  (let [last-resp (-> (get-state :results-list) last)
        last-resp (if (sequential? last-resp) last-resp [last-resp])] ;; :foo => [:foo]
    (reduce (fn [a b]
              (if (sequential? b)
                (into a b)
                (conj a b))) [] last-resp)))

(defn apply-service-service
  [msg]
  (let [last-resp (-> (get-state :results-list) last)

        default-predicate some? ;; anything but nil
        
        accepts? (fn [service]
                   (when ((:accept-pred service default-predicate) last-resp)
                     (:topic service)))

        supported-services(->> (get-state :service-list)
                               (map accepts?)
                               (remove nil?)
                               distinct
                               vec)]
    (if-not (empty? supported-services)
      supported-services
      nil)))

;; todo: services that:
;; * change log level
;; * store relationships in persistant storage
;; * 
(def service-list
  [{:id :writer-actor, :topic :write-file, :service file-writer}
   {:id :forwarder, :topic :|forward, :service forwarder}
   {:id :filterer, :topic :|filter, :service filterer}
   {:id :selecter, :topic :select, :service selecter}

   {:id :echo-actor, :service (echo :info), :accept-pred any?} ;; off-topic echo
   {:id :echo-actor, :topic :echo, :service (echo :debug), :accept-pred any?} ;; for testing

   {:id :repeat-actor, :topic :repeat, :service repeater} ;; for testing

   {:id :unnest-actor, :topic :unnest, :service unnester}
   
   ;;{:id :slow-actor :service (wait 5) :input-chan (async/chan (async/buffer 5))}

   {:id :apply-service-actor, :topic :apply-service, :service apply-service-service, :accept-pred any?}
   ])

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
