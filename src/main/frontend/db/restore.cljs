(ns frontend.db.restore
  "Fns for DB restore(from text or sqlite)"
  (:require [clojure.string :as string]
            [datascript.core :as d]
            [electron.ipc :as ipc]
            [frontend.config :as config]
            [frontend.db.conn :as db-conn]
            [frontend.db.migrate :as db-migrate]
            [frontend.db.persist :as db-persist]
            [frontend.db.react :as react]
            [frontend.db.utils :as db-utils]
            [frontend.state :as state]
            [goog.object :as gobj]
            [logseq.db.default :as default-db]
            [logseq.db.schema :as db-schema]
            [logseq.db.sqlite.restore :as sqlite-restore]
            [promesa.core :as p]
            [frontend.util :as util]
            [cljs-time.core :as t]))

(defn- old-schema?
  "Requires migration if the schema version is older than db-schema/version"
  [db]
  (let [v (db-migrate/get-schema-version db)
        ;; backward compatibility
        v (if (integer? v) v 0)]
    (cond
      (= db-schema/version v)
      false

      (< db-schema/version v)
      (do
        (js/console.error "DB schema version is newer than the app, please update the app. " ":db-version" v)
        false)

      :else
      true)))

(defn- restore-graph-from-text!
  "Swap db string into the current db status
   stored: the text to restore from"
  [repo stored]
  (p/let [db-name (db-conn/datascript-db repo)
          db-conn (d/create-conn (db-conn/get-schema repo))
          _ (swap! db-conn/conns assoc db-name db-conn)
          _ (when stored
              (let [stored-db (try (db-utils/string->db stored)
                                   (catch :default _e
                                     (js/console.warn "Invalid graph cache")
                                     (d/empty-db (db-conn/get-schema repo))))
                    attached-db (d/db-with stored-db
                                           default-db/built-in-pages) ;; TODO bug overriding uuids?
                    db (if (old-schema? attached-db)
                         (db-migrate/migrate attached-db)
                         attached-db)]
                (db-conn/reset-conn! db-conn db)))]
    (d/transact! db-conn [{:schema/version db-schema/version}])))

(defn- set-unloaded-block-ids!
  [repo data]
  (util/profile
   "Set unloaded-block-ids"
   (let [unloaded-block-ids (transient #{})]
     (doseq [b data]
       (conj! unloaded-block-ids (gobj/get b "uuid") (gobj/get b "page_uuid")))
     (state/set-state! [repo :restore/unloaded-blocks] (persistent! unloaded-block-ids)))))

(defn- restore-other-data-from-sqlite!
  [repo data uuid->db-id-map]
  (let [start (util/time-ms)
        conn (db-conn/get-db repo false)
        profiled-init-db (fn profiled-init-db [all-datoms schema]
                           (util/profile
                            (str "DB init! " (count all-datoms) " datoms")
                            (d/init-db all-datoms schema)))
        new-db (sqlite-restore/restore-other-data conn data uuid->db-id-map {:init-db-fn profiled-init-db})]
    
    (reset! conn new-db)
    
    (let [end (util/time-ms)]
      (println "[debug] load others from SQLite: " (int (- end start)) " ms."))

    (p/let [_ (p/delay 150)]          ; More time for UI refresh
      (state/set-state! [repo :restore/unloaded-blocks] nil)
      (state/set-state! [repo :restore/unloaded-pages] nil)
      (state/set-state! :graph/loading? false)
      (state/pub-event! [:ui/re-render-root]))))

(defn- restore-graph-from-sqlite!
  "Load initial data from SQLite"
  [repo]
  (state/set-state! :graph/loading? true)
  (p/let [start-time (t/now)
          data (ipc/ipc :get-initial-data repo)
          {:keys [conn uuid->db-id-map journal-blocks datoms-count]}
          (sqlite-restore/restore-initial-data data {:conn-from-datoms-fn
                                                     (fn profiled-d-conn [& args]
                                                       (util/profile :restore-graph-from-sqlite!-init-db (apply d/conn-from-datoms args)))})
          db-name (db-conn/datascript-db repo)
          _ (swap! db-conn/conns assoc db-name conn)
          end-time (t/now)]
    (println :restore-graph-from-sqlite!-prepare (t/in-millis (t/interval start-time end-time)) "ms"
             " Datoms in total: " datoms-count)

    ;; TODO: Store schema in sqlite
    ;; (db-migrate/migrate attached-db)

    (d/transact! conn [(react/kv :db/type "db")
                       {:schema/version db-schema/version}]
                 {:skip-persist? true})

    (js/setTimeout
     (fn []
       (p/let [other-data (ipc/ipc :get-other-data repo (map :uuid journal-blocks))
               _ (set-unloaded-block-ids! repo other-data)
               _ (p/delay 10)]
         (restore-other-data-from-sqlite! repo other-data uuid->db-id-map)))
     100)))

(defn restore-graph!
  "Restore db from serialized db cache"
  [repo]
  (if (string/starts-with? repo config/db-version-prefix)
    (restore-graph-from-sqlite! repo)
    (p/let [db-name (db-conn/datascript-db repo)
            stored (db-persist/get-serialized-graph db-name)]
      (restore-graph-from-text! repo stored))))