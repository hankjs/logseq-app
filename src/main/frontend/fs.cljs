(ns frontend.fs
  (:require [frontend.util :as util :refer-macros [profile]]
            [frontend.config :as config]
            [clojure.string :as string]
            [promesa.core :as p]
            [lambdaisland.glogi :as log]
            [frontend.fs.protocol :as protocol]
            [frontend.fs.nfs :as nfs]
            [frontend.fs.bfs :as bfs]
            [frontend.fs.node :as node]))

(defonce nfs-record (nfs/->Nfs))
(defonce bfs-record (bfs/->Bfs))
(defonce node-record (node/->Node))

(defn local-db?
  [dir]
  (and (string? dir)
       (config/local-db? (subs dir 1))))

(defn get-fs
  [dir]
  (cond
    (util/electron?)
    node-record

    (local-db? dir)
    nfs-record

    :else
    bfs-record))

(defn mkdir!
  [dir]
  (protocol/mkdir! (get-fs dir) dir))

(defn readdir
  [dir]
  (protocol/readdir (get-fs dir) dir))

(defn unlink!
  [path opts]
  (protocol/unlink! (get-fs path) path opts))

(defn rmdir!
  "Remove the directory recursively.
   Warning: only run it for browser cache."
  [dir]
  (protocol/rmdir! (get-fs dir) dir))

(defn read-file
  [dir path]
  (protocol/read-file (get-fs dir) dir path))

(defn write-file!
  [repo dir path content opts]
  (->
   (protocol/write-file! (get-fs dir) repo dir path content opts)
   (p/catch (fn [error]
              (log/error :file/write-failed? {:dir dir
                                              :path path
                                              :error error})
              ;; Disable this temporarily
              ;; (js/alert "Current file can't be saved! Please copy its content to your local file system and click the refresh button.")
))))

(defn rename!
  [repo old-path new-path]
  (cond
    ; See https://github.com/isomorphic-git/lightning-fs/issues/41
    (= old-path new-path)
    (p/resolved nil)

    :else
    (protocol/rename! (get-fs old-path) repo old-path new-path)))

(defn stat
  [dir path]
  (let [append-path (if path
                      (str "/"
                           (if (= \/ (first path))
                             (subs path 1)
                             path))
                      "")]
    (protocol/stat (get-fs dir) dir path)))

(defn mkdir-if-not-exists
  [dir]
  (when dir
    (util/p-handle
     (stat dir nil)
     (fn [_stat])
     (fn [error]
       (mkdir! dir)))))

(defn create-if-not-exists
  ([repo dir path]
   (create-if-not-exists repo dir path ""))
  ([repo dir path initial-content]
   (let [path (if (util/starts-with? path "/")
                path
                (str "/" path))]
     (->
      (p/let [_ (stat dir path)]
        true)
      (p/catch
       (fn [_error]
         (p/let [_ (write-file! repo dir path initial-content nil)]
           false)))))))

(defn file-exists?
  [dir path]
  (util/p-handle
   (stat dir path)
   (fn [_stat] true)
   (fn [_e] false)))
