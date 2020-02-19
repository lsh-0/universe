(ns universe.main
  (:refer-clojure :rename {test clj-test})
  (:require
   [clojure.test]
   [taoensso.timbre :as logging :refer [log debug info warn error spy]]
   [universe
    [core :as core]]))

(defn find-service-list
  "given a namespace in the form of a keyword, returns the contents of `'universe.$ns/service-list`, if it exists"
  [ns-kw]
  (let [ns (->> ns-kw name (str "universe.") symbol)]
    (try 
      (var-get (ns-resolve ns 'service-list))
      (catch Exception e
        (warn (format "service list not found: '%s/service-list" ns))))))

(defn find-all-services
  "finds the service-list for all given namespace keywords and returns a single list"
  [ns-kw-list]
  (mapcat find-service-list ns-kw-list))

;;

(def known-services [:core :some-service :poll-service])

(defn stop
  [state]
  (when state
    (doseq [clean-up-fn (:cleanup @state)]
      (debug "calling cleanup fn:" clean-up-fn)
      (clean-up-fn))
    (alter-var-root #'core/state (constantly nil))))

(defn start
  []
  (if-not core/state
    (do
      (alter-var-root #'core/state (constantly (atom core/-state-template)))
      (core/init (find-all-services known-services)))
    (warn "application already started")))

(defn restart
  []
  (stop core/state)
  (start))

;;

(defn test
  [& [ns-kw fn-kw]]
  (clojure.tools.namespace.repl/refresh) ;; reloads all namespaces, including test ones
  (try
    (logging/set-level! :debug)
    (if ns-kw
      (if (some #{ns-kw} [:main :core :cli])
        (if fn-kw
          ;; `test-vars` will run the test but not give feedback if test passes OR test not found
          (clojure.test/test-vars [(resolve (symbol (str "universe." (name ns-kw) "-test") (name fn-kw)))])
          (clojure.test/run-all-tests (re-pattern (str "universe." (name ns-kw) "-test"))))
        (error "unknown test file:" ns-kw))
      (clojure.test/run-all-tests #"universe\..*-test"))
    (finally
      (logging/set-level! :info))))
