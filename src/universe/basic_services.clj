(ns universe.basic-services
  (:require
   ;;[clojure.core.async :as async :refer [<! >! >!! <!! go]]
   [taoensso.timbre :refer [log debug info warn error spy]]
   [me.raynes.fs :as fs]
   [universe.utils :as utils :refer [in? mk-id]]
   [universe.core :as core :refer [get-state request]]
   ))


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
        path (str (fs/file core/temp-dir filename))]
    (spit path message)
    path))

(defn forwarder
  "given a topic, sends a request to it with the last response as the message body"
  [msg & [overrides]]
  (let [topic-kw (some-> msg :message (subs 1) keyword)
        last-result (-> (core/get-state :results-list) last)]
    (when (and topic-kw
               last-result)
      (core/emit-and-wait (request topic-kw (merge overrides {:message last-result}))))))

(defn filterer
  [msg]
  (let [known-predicates {"alpha?" (partial re-matches #"[a-zA-Z_\- ]")}
        predicate-key (:message msg)
        predicate (get known-predicates predicate-key)
        last-result (-> (core/get-state :results-list) last)]
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

        num-results (count (core/get-state :results-list))

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
      (subvec (core/get-state :results-list) start end))))
      
(defn select-idx
  [idx]
  (let [num-results (count (core/get-state :results-list))

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
      (nth (core/get-state :results-list) idx))))

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
      (contains? known-selectors selector) ((known-selectors selector) (core/get-state :results-list))

      ;; we have a range
      (and start end) (select-range start end)

      ;; we just a start/idx/selector
      :else (select-idx start))))

(defn repeater
  [msg]
  (if-let [body (:message msg)]
    body
    (-> (core/get-state :results-list) last)))

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
