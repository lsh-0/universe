(ns universe.store
  (:require
   [taoensso.timbre :refer [log debug info warn error spy]]
   [me.raynes.fs :as fs]
   [universe
    [core :as core]
    [utils :as utils :refer [mk-id]]]
   [crux.api :as crux]
   [clojure.java.io :as io]))

(defn start-node
  [storage-dir]
  (if storage-dir
    (crux/start-node {:crux.node/topology '[crux.standalone/topology
                                            crux.kv.rocksdb/kv-store]
                      :crux.kv/db-dir (str (io/file storage-dir "db"))})

    ;; in-memory only
    (crux/start-node {:crux.node/topology '[crux.standalone/topology]
                      :crux.kv/db-dir (str (io/file (fs/temp-dir "universe-crux") "crux-store" "db"))})))

(defn init
  "loads any previous database instance"
  []
  (let [node (start-node (core/get-service-state :db :storage-dir))]
    ;; causes a slight 1.7ms delay straight out of the box regardless of in-memory or rocksdb backed.
    ;; thereafter it's very quick.
    ;; without this call to `sync`, `lein repl` then `(test)` causes indefinite hanging when closing.
    (crux/sync node) 
    (core/set-service-state :db :node node)
    (core/add-cleanup #(try
                         (.close node)
                         (catch Exception uncaught-exc
                           ;; hasn't happened yet
                           (error uncaught-exc "uncaught unexception attempting to close crux node"))))))

(defn node
  []
  (core/get-service-state :db :node))

;;

(defn to-crux-doc
  [blob]
  (cond
    ;; data blob has a already has a crux id (?)
    ;; prefer that over any id it may have picked up and pass it through
    (and (map? blob)
         (contains? blob :crux.db/id)) (dissoc blob :id)

    ;; data blob has an id but no crux id, rename :id to crux id
    (and (map? blob)
         (contains? blob :id)) (clojure.set/rename-keys blob {:id :crux.db/id})

    ;; given something that isn't a map
    ;; wrap in a map, give it an id and pass it through
    (not (map? blob)) {:crux.db/id (mk-id) :data blob}

    ;; otherwise, it *is* a map but is lacking an id or a crux id
    :else (assoc blob :crux.db/id (mk-id))))

(defn from-crux-doc
  [result]
  (if (map? result)
    (clojure.set/rename-keys result {:crux.db/id :id})
    ;; ... ? just issue a warning and pass it through
    (do
      (warn (str "got unknown type attempting to coerce result from crux db:" (type result)))
      result)))


(defn put
  [blob]
  (crux/submit-tx (node) [[:crux.tx/put (to-crux-doc blob)]]))

(defn get-by-id
  [id]
  (crux/entity (crux/db (node)) id))

(defn query-by-type
  [type-kw]
  (crux/q (crux/db (node))
          '{:find [e]
            :where [[e :type type-kw]]}))

;;

(defn store-db-service
  [msg]
  nil)

(def service-list
  [{:id :db, :topic :store-db, :service store-db-service, :init-fn init}])
