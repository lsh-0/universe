(ns universe.main
  (:require
   [taoensso.timbre :refer [log debug info warn error spy]]
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

(def known-services [:core :some-service])

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
