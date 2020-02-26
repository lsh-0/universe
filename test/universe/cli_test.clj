(ns universe.cli-test
  (:require
   [clojure.test :refer :all]
   [universe
    [core :as core]
    [cli :as cli]
    [test-helper :as test-helper :refer [with-running-app]]]))

(deftest cli-init
  (testing "the CLI can be started without a running app, no prompting and no commands to run and exit without error"
    (let [results (cli/start {:prompt? false})
          expected {:options {:prompt? false
                              :command-list []}
                    :command-history []}]
      (is (= expected results))))

  (testing "the CLI can be started without a running app and receive a friendly message when attempting to send a message"
    (let [results (cli/start {:prompt? false
                              :command-list ["hello?"]})
          expected {:options {:prompt? false
                              :command-list ["hello?"]}
                    :command-history [["hello?" "(the application hasn't been started yet, sorry)"]]}
          ]
      (is (= expected results))))

  ;; todo: control initial actors listening for messages
  ;; todo: capture log entries?
  (testing "the CLI will emit a message if the app is running"
    (with-running-app
      (let [results (cli/start {:prompt? false
                                :command-list ["hello?"]})
            expected {:options {:prompt? false
                                :command-list ["hello?"]}
                      :command-history [["hello?" nil]]}
            ]
        (is (= expected results)))))

  (testing "the CLI will emit a *request* (different to a regular message) if a command looks a certain way"
    (with-running-app
      (let [results (cli/start {:prompt? false
                                :command-list [":echo \"perfect day\""]})
            expected {:options {:prompt? false
                                :command-list [":echo \"perfect day\""]}
                      :command-history [[":echo \"perfect day\"" "perfect day"]]}
            ]
        (is (= expected results)))))

  (testing "the CLI will not emit a request if nobody is listening for those types of requests"
    (with-running-app
      (let [results (cli/start {:prompt? false
                                :command-list [":nonexistantservice \"perfect day\""]})
            expected {:options {:prompt? false
                                :command-list [":nonexistantservice \"perfect day\""]}
                      :command-history [[":nonexistantservice \"perfect day\"" "(the application isn't listening to ':nonexistantservice' commands)"]]}
            ]
        (is (= expected results)))))


  )

