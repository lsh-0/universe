(ns universe.store
  (:require
   [taoensso.timbre :refer [log debug info warn error spy]]
   [me.raynes.fs :as fs]
   [universe
    [core :as core]
    [utils :as utils :refer [mk-id]]]
   [crux.api :as crux]
   [clojure.java.io :as io]))

(defn node
  []
  (core/get-service-state :db :node))

;;

(defn to-crux-doc
  [blob]
  (cond
    ;; data blob already has a crux id
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
  (when result
    (if (map? result)
      (clojure.set/rename-keys result {:crux.db/id :id})

      ;; ... ? just issue a warning and pass it through
      (do
        (warn (str "got unknown type attempting to coerce result from crux db:" (type result)))
        result))))

(defn put
  [blob]
  (crux/submit-tx (node) [[:crux.tx/put (to-crux-doc blob)]]))

(defn put+wait
  [blob]
  (crux/await-tx (node) (put blob)))

(defn get-by-id
  [id]
  (from-crux-doc (crux/entity (crux/db (node)) id)))

(defn get-by-id+time
  [id time]
  (from-crux-doc (crux/entity (crux/db (node) time) id)))

(defn query-by-type
  [type-kw]
  (crux/q (crux/db (node))
          '{:find [e]
            :where [[e :type type-kw]]}))

;;

(defn start-node
  [storage-dir]
  (info "got storage dir" storage-dir)
  (if storage-dir
    (crux/start-node {:crux.node/topology '[crux.standalone/topology
                                            crux.kv.rocksdb/kv-store]
                      :crux.kv/db-dir (-> storage-dir (io/file "db") fs/absolute str)

                      ;; https://javadoc.io/doc/org.rocksdb/rocksdbjni/6.2.2/org/rocksdb/Options.html
                      :crux.kv.rocksdb/db-options (doto (org.rocksdb.Options.)
                                                    (.optimizeForSmallDb) ;; drops testing from ~560ms to ~320ms
                                                    )
                      })

    ;; in-memory only (for testing)
    (crux/start-node {:crux.node/topology '[crux.standalone/topology]
                      ;;:crux.standalone/event-log-kv-store 'crux.kv.memdb/kv

                      })

    ))
                      ;;:crux.kv/db-dir (-> (fs/temp-dir "universe-crux") (io/file "crux-store" "db") str)})))

(defn init
  "loads any previous database instance"
  []
  (let [node (start-node (core/get-service-state :db :storage-dir))]
    ;; causes a slight 1.7ms delay straight out of the box regardless of in-memory or rocksdb backed.
    ;; thereafter it's very quick.
    ;; without this call to `sync`, `lein repl` then `(test)` causes indefinite hanging when closing.

    (core/set-service-state :db :node node)

    ;; load any data into the 'last session'
    (debug "loading old data, if any")
    (swap! core/state assoc :last-session (:results-list (get-by-id :universe.store/results-list)))

    ;; watch for updates to the results list and update the crux store
    (core/state-bind [:results-list] (fn [new-state]
                                       (let [tx (put {:results-list (:results-list new-state)
                                                      :id :universe.store/results-list})]
                                         ;;(future-call sync)
                                         ;; eventual read consistency means we need to wait for the transaction to
                                         ;; complete/be written before being able to read those results.
                                         ;; not sure how this will play out.
                                         ;;(crux/await-tx node tx)
                                         ;;(info "testing results are retrievable")
                                         ;;(get-by-id :universe.store/results-list)
                                         )))
    
    (core/add-cleanup #(try
                         (when (core/get-service-state :db :storage-dir)
                           ;; this kinda sucks but if we don't sync before we close then
                           ;; nothing is returned when brought back up.
                           (crux/sync node))
                         (.close node)
                         (catch Exception uncaught-exc
                           ;; hasn't happened yet
                           (error uncaught-exc "uncaught unexception attempting to close crux node"))))))

;;

(defn store-db-service
  [msg]
  nil)

(def service-list
  [{:id :db, :topic :store-db, :service store-db-service, :init-fn init}])
