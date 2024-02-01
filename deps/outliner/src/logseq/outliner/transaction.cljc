(ns logseq.outliner.transaction
  "Provides a wrapper around logseq.outliner.datascript/transact! using
   transient state from logseq.outliner.core"
  #?(:cljs (:require-macros [logseq.outliner.transaction]))
  #?(:cljs (:require [malli.core :as m])))

#_:clj-kondo/ignore
(def ^:private transact-opts [:or :symbol :map])

#?(:org.babashka/nbb nil
   :cljs (m/=> transact! [:=> [:cat transact-opts :any] :any]))

(defmacro ^:api transact!
  "Batch all the transactions in `body` to a single transaction, Support nested transact! calls.
  Currently there are no options, it'll execute body and collect all transaction data generated by body.
  If no transactions are included in `body`, it does not save a transaction.
  `Args`:
    `opts`: Every key is optional, opts except `additional-tx` will be transacted as `tx-meta`.
            {:outliner-op \"For example, :save-block, :insert-blocks, etc. \"
             :additional-tx \"Additional tx data that can be bundled together
                              with the body in this macro.\"
             :persist-op? \"Boolean, store ops into db (sqlite), by default,
                            its value depends on (config/db-based-graph? repo)\"}
  `Example`:
  (transact! {:graph \"test\"}
    (insert-blocks! ...)
    ;; do something
    (move-blocks! ...)
    (delete-blocks! ...))"
  [opts & body]
  `(let [transact-data# logseq.outliner.core/*transaction-data*
         transaction-opts# logseq.outliner.core/*transaction-opts*
         opts*# ~opts
         _# (assert (or (map? opts*#) (symbol? opts*#)) (str "opts is not a map or symbol, type: " (type opts*#)))
         opts# (if transact-data#
                 (assoc opts*# :nested-transaction? true)
                 opts*#)]
     (if transact-data#
       (do
         (when transaction-opts#
           (conj! transaction-opts# opts#))
         ~@body)
       (let [transaction-args# (cond-> {}
                                 (get opts*# :persist-op? true)
                                 (assoc :persist-op? true))]
         (binding [logseq.outliner.core/*transaction-data* (transient [])
                   logseq.outliner.core/*transaction-opts* (transient [])]
           (conj! logseq.outliner.core/*transaction-opts* opts#)
           ~@body
           (let [r# (persistent! logseq.outliner.core/*transaction-data*)
                 tx# (mapcat :tx-data r#)
                 ;; FIXME: should we merge all the tx-meta?
                 tx-meta# (first (map :tx-meta r#))
                 all-tx# (concat tx# (:additional-tx opts#))
                 o# (persistent! logseq.outliner.core/*transaction-opts*)
                 full-opts# (apply merge (reverse o#))
                 opts## (merge (dissoc full-opts# :additional-tx :current-block :nested-transaction?) tx-meta#)]

             (when (seq all-tx#) ;; If it's empty, do nothing
               (when-not (:nested-transaction? opts#) ; transact only for the whole transaction
                 (logseq.outliner.datascript/transact! all-tx# (dissoc opts## :transact-opts) (:transact-opts opts##))))))))))
