(ns universe.core
  (:require
   [clojure.tools.namespace.repl :refer [refresh]]
   [clojure.core.async :as async :refer [<! >!!]]
   [taoensso.timbre :refer [log debug info warn error spy]]
   [me.raynes.fs :as fs]
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
  [topic-kw user-msg & [overrides]]
  (message user-msg (merge overrides {:topic topic-kw
                                      :response-chan (async/chan)})))

;; response ..?

;;

(defn actor
  [f & [more-attrs]]
  (merge {:type :actor
          :id (mk-id)
          :input-chan (async/chan)
          :func f} more-attrs))

(defn emit!
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
          actor-func (:func actor)
          resp-chan (:response-chan msg)]
      (debug (format "actor '%s' received message: %s" (:id actor) (:id msg)))
      (when-let [result (actor-func (:message msg))]
        (info "actor function returned a result...")
        ;; when there is a result and when we have a response channel, stick the response on the channel
        (when resp-chan
          (info "...response channel found, sending result to it" result)
          (>!! resp-chan result))))
    (recur)))

(defn add-actor! ;; to 'stage' ? 
  [actor topic-kw & [more-filter-fns]]
  ;; subscribe actor to incoming messages for the given topic (request/response/etc)
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

(defn file-writer
  [msg]
  (let [{:keys [filename filebody]} (:message msg)
        temp-name (str "universe-" (mk-id))
        filename (or filename temp-name)
        path (fs/file temp-dir filename)]
    (spit path filebody)
    path))

  

;; bootstrap

;; publication whose topic really is :topic, and it only receives requests
;; publication whose topic is :origin and it only receives responses to requests
;; an 'origin' is the sender of a request
;; so an actor is always subscribed to the ... no this is impractical.
;; an actor should be able to emit messages like we currently do as well as emit and block until there is a response
;; what if there is no response? 

(defn actor-init
  []
  (let [;; just prints the message
        echo-actor (actor (echo :info) {:id :echo-actor})

        ;; acknowledges message then waits before *eventually* printing the message    
        slow-actor (actor (wait 5) {:id :slow-actor})

        ;; writes message to a named temporary file
        writer-actor (actor file-writer {:id :writer-actor})

        ;;middleman-actor (actor middleman {:id :middleman})
        ]

    (add-actor! echo-actor :off-topic)
    (add-actor! slow-actor :off-topic)

    ;; the middleman receives a request to do a thing.
    ;; the middleman knows how to craft requests to the writer so they do the job properly
    ;; the middleman takes credit for the writer-actors work
    ;;(add-actor! middleman-actor :off-topic)
    
    (add-actor! writer-actor :write-file)

    )
  nil)

(defn init
  []
  (let [publisher (async/chan)
        publication (async/pub publisher :topic)]
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
