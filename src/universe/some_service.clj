(ns universe.some-service
  (:require
   [taoensso.timbre :refer [log debug info warn error spy]]
   [me.raynes.fs :as fs]
   [universe.core :as core]))

(defn directory-service
  "when asked for the contents of a directory, it returns a list of maps"
  [{path :message}]
  ;;(info "dir-service received message" all)
  (let [exists? (fs/exists? path)
        dir-contents (when (and exists?
                                (fs/directory? path))
                       {:contents (->> path fs/list-dir (mapv str))})
        
        data   {:requested-path path
                :actual-path (-> path fs/normalized fs/absolute str)
                :exists? exists?}
        ]
    (merge data dir-contents)))

;;

(def service-list
  [;;{:id :directory-service-listing, :topic {:list-dir [], :foo [bar baz]}, :service directory-service}])
   {:id :directory-service-listing, :topic :list-dir, :service directory-service}])
