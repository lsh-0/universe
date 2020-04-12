(ns universe.core-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [universe
    [core :as core]
    [test-helper :as test-helper :refer [with-running-app]]]))


(deftest core-0
  (testing "test helper"
    (let [expected {}]
      (with-running-app
        (is (= nil (core/get-state :service-state :db :storage-dir)))))))
