(ns universe.test-helper
  (:require
   ;;[envvar.core :refer [env with-env]]
   [taoensso.timbre :as timbre :refer [debug info warn error spy]]
   [me.raynes.fs :as fs :refer [with-cwd]]
   ;;[clj-http.fake :refer [with-fake-routes-in-isolation]]
   [universe
    [core :as core]
    [main :as main]]
   ))

(defmacro with-running-app
  [& form]
  `(try
     (main/start {:initial-state {:service-state {:db {:storage-dir nil}}}})
     ~@form
     (finally
       (main/stop core/state))))

(defmacro with-opts-running-app
  [opts & form]
  `(try
     (main/start ~opts)
     ~@form
     (finally
       (main/stop core/state))))
