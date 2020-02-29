(ns universe.off-topic-recorder
  (:require
   [taoensso.timbre :refer [log debug info warn error spy]]
   [universe.core :as core]))

(comment "a service that records everything sent to :off-topic")

(defn off-topic-recorder
  [off-topic-message]
  (swap! core/state update-in [:off-topic-recorder-state] conj off-topic-message)
  (info (format "%s messages saved, total" (count (core/get-state :off-topic-recorder-state)))))

(def service-list
  [{:id :off-topic-recorder, :topic :off-topic, :service off-topic-recorder}])
