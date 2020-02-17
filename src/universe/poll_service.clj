(ns universe.poll-service
  (:require
   [universe.core :as core]))

(comment "")

(defn some-service
  [msg]
  {:known-services (mapv :id (core/get-state :service-list))})

(def service-list
  [{:id :roll-call-service, :topic :roll-call, :service some-service}])
