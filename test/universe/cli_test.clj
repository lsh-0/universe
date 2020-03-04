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
                      ;; (the :echo service doesn't return anything)
                      :command-history [[":echo \"perfect day\"" nil]]}
            ]
        (is (= expected results)))))

  (testing "the CLI will emit a *request* and capture any response"
    (with-running-app
      (let [results (cli/start {:prompt? false
                                :command-list [":repeat \"perfect day\""]})
            expected {:options {:prompt? false
                                :command-list [":repeat \"perfect day\""]}
                      ;; (the :repeat service is like the :echo service, but returns it's input rather than logging it)
                      :command-history [[":repeat \"perfect day\"" "perfect day"]]}
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

  (testing "the 'forward' service can access the previous result and use it as the input for the next result"
    (with-running-app
      (let [results (cli/start {:prompt? false
                                :command-list [":repeat hi"
                                               ":|forward :repeat"]})
            expected {:options {:prompt? false
                                :command-list [":repeat hi"
                                               ":|forward :repeat"]}
                      :command-history [[":repeat hi" "hi"]
                                        [":|forward :repeat" "hi"]]}
            ]
        (is (= expected results)))))

  (testing "the 'filter' service takes a predicate and applies it to each of the previous items"
    (with-running-app
      (let [results (cli/start {:prompt? false
                                :command-list [":repeat \"Hi There!!\""
                                               ":|filter alpha?"]})
            expected {:options {:prompt? false
                                :command-list [":repeat \"Hi There!!\""
                                               ":|filter alpha?"]}
                      :command-history [[":repeat \"Hi There!!\"" "Hi There!!"]
                                        [":|filter alpha?" "Hi There"]]}
            ]
        (is (= expected results)))))
  )
