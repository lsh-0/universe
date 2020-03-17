(ns universe.cli-test
  (:require
   [clojure.test :refer :all]
   [taoensso.timbre :refer [log debug info warn error spy]]
   [universe
    [core :as core]
    [cli :as cli]
    [test-helper :as test-helper :refer [with-running-app]]]))

  ;; todo: control initial actors listening for messages
  ;; todo: capture log entries?


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
      (is (= expected results)))))


(deftest cli-1
  (testing "the CLI will emit a message if the app is running"
    (with-running-app
      (let [results (cli/start {:prompt? false
                                :command-list ["hello?"]})
            expected {:options {:prompt? false
                                :command-list ["hello?"]}
                      :command-history [["hello?" nil]]}
            ]
        (is (= expected results))))))

(deftest cli-2
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

  (testing ":repeat will repeat the last result (if any) if no arguments specified"
    (with-running-app
      (let [results (cli/start {:prompt? false
                                :command-list [":repeat"
                                               ":repeat perfect"
                                               ":repeat"]})
            expected {:options {:prompt? false
                                :command-list [":repeat"
                                               ":repeat perfect"
                                               ":repeat"]}
                      :command-history [[":repeat" nil]
                                        [":repeat perfect" "perfect"]
                                        [":repeat" "perfect"]]}
            ]
        (is (= expected results))))))
  

(deftest cli-3
  (testing "the CLI will not emit a request if nobody is listening for those types of requests"
    (with-running-app
      (let [results (cli/start {:prompt? false
                                :command-list [":nonexistantservice \"perfect day\""]})
            expected {:options {:prompt? false
                                :command-list [":nonexistantservice \"perfect day\""]}
                      :command-history [[":nonexistantservice \"perfect day\"" "(the application isn't listening to ':nonexistantservice' commands)"]]}
            ]
        (is (= expected results))))))

(deftest cli-4
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
        (is (= expected results))))))

(deftest cli-5
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
        (is (= expected results))))))

(deftest cli-6
  (testing "the 'select' service takes a slice expression and returns those selected results from the results list as a new result"
    (with-running-app
      (let [results (cli/start {:prompt? false
                                :command-list [":repeat hi"    ;; 0 , -7
                                               ":repeat there" ;; 1 , -6
                                               ":select all"   ;; 2 , -5
                                               ":select 0"     ;; 3 , -4
                                               ":select 1"     ;; 4 , -3
                                               ":select 2"     ;; 5 , -2
                                               ":select -4"    ;; 6 , -1
                                               ]})
            expected {:options {:prompt? false
                                :command-list [":repeat hi"
                                               ":repeat there"
                                               ":select all"
                                               ":select 0"
                                               ":select 1"
                                               ":select 2"
                                               ":select -4"
                                               ]}
                      :command-history [[":repeat hi" "hi"]
                                        [":repeat there" "there"]
                                        [":select all" ["hi" "there"]]
                                        [":select 0" "hi"]
                                        [":select 1" "there"]
                                        [":select 2" ["hi" "there"]]
                                        [":select -4" ["hi" "there"]]]}

            ]
        (is (= expected results))))))

(deftest cli-7
  (testing "the 'unnest' service takes the last result and does a shallow flatten on it IF it is a collection"
    (with-running-app
      (let [results (cli/start {:prompt? false
                                :command-list [":repeat hi"    
                                               ":repeat there" 
                                               ":select all"
                                               ":repeat"
                                               ":select -2 99"
                                               ":unnest"

                                               ]})
            expected {:options {:prompt? false
                                :command-list [":repeat hi"
                                               ":repeat there"
                                               ":select all"
                                               ":repeat"
                                               ":select -2 99"
                                               ":unnest"
                                               ]}
                      :command-history [[":repeat hi" "hi"]
                                        [":repeat there" "there"]
                                        [":select all" ["hi" "there"]]
                                        [":repeat" ["hi" "there"]]
                                        [":select -2 99" [["hi" "there"] ["hi" "there"]]]
                                        [":unnest" ["hi" "there" "hi" "there"]]]
                      }
            ]
        (is (= expected results))))))

(deftest cli-8
  (testing "the 'apply-service' service will list all services that a result can use"
    (with-running-app
      (let [results (cli/start {:prompt? false
                                :command-list [":apply-service"]})
            expected {:options {:prompt? false
                                :command-list [":apply-service"]}
                      :command-history [[":apply-service" [:echo :apply-service]]]}
            ]
        (is (= expected results)))))
      
  (testing "the 'apply-service' response will return a result, but the response will not be available in the :results-list"
    (with-running-app
      (let [results (cli/start {:prompt? false
                                :command-list [":apply-service" ;; initial case, no previous input to test services against
                                               ":apply-service" ;; same as above, as service is considered 'internal'
                                               ]})

            expected {:options {:prompt? false
                                :command-list [":apply-service"
                                               ":apply-service"]}
                      :command-history [[":apply-service" [:echo :apply-service]]
                                        [":apply-service" [:echo :apply-service]]]}
            ]
        (is (= expected results))))))

