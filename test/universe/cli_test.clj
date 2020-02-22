(ns universe.cli-test
  (:require
   [clojure.test :refer :all]
   [universe
    [core :as core]
    [cli :as cli]
    [test-helper :as test-helper :refer [with-running-app]]]))

(deftest cli-init
  (testing "the CLI can be started, a set of commands can be run and then exited instead of prompting for more commands"
    (let [results (cli/start {:prompt? false
                              :command-list ["hello?"]})
          expected {:options {:prompt? false
                              :command-list ["hello?"]}
                    :success? true
                    :command-history [["hello?" nil]]}
          ]
      (is (= expected results))))

  (testing "the CLI can be started with no prompting and no commands and still exit successfully"
    (let [results (cli/start {:prompt? false})
          expected {:options {:prompt? false
                              :command-list []}
                    :success? true
                    :command-history []}]
      (is (= expected results)))))
