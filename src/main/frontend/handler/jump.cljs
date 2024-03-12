(ns frontend.handler.jump
  "Jump to property key/value"
  (:require [frontend.state :as state]
            [dommy.core :as d]
            [clojure.string :as string]
            [frontend.util :as util]))

(defonce *current-keys (atom nil))
(defonce *jump-data (atom {}))

(def prefix-keys ["j" "k" "l"])
(def keys
  ["a"
   "s"
   "d"
   "f"
   "g"
   "h"
   "q"
   "w"
   "e"
   "r"
   "t"
   "y"
   "u"
   "i"
   "o"
   "p"
   "z"
   "x"
   "c"
   "v"
   "b"
   "n"
   "m"])

(defonce full-start-keys (set (concat prefix-keys keys)))

(defn generate-keys
  "Notice: at most 92 keys for now"
  [n]
  (vec
   (take n
         (concat keys
                 (mapcat
                  (fn [k]
                    (map #(str k %) keys))
                  prefix-keys)))))

(defn clear-jump-hints!
  []
  (dorun (map d/remove! (d/sel ".jtrigger-id")))
  (reset! *current-keys nil))

(defn exit!
  []
  (when-let [event-handler (:key-down-handler @*jump-data)]
    (.removeEventListener js/window "keydown" event-handler))
  (reset! *current-keys nil)
  (reset! *jump-data {})
  (clear-jump-hints!))

(defn get-trigger
  [triggers key]
  (when-let [idx (.indexOf @*current-keys key)]
    (nth triggers idx)))

(defn trigger!
  [key e]
  (let [{:keys [triggers _mode]} @*jump-data]
    (when-let [trigger (get-trigger triggers (string/trim key))]
      (util/stop e)
      (state/clear-selection!)
      (exit!)
      (.click trigger))))

(defn jump-to
  []
  (let [selected-block (first (state/get-selection-blocks))]
    (cond
      selected-block
      (when (empty? (d/sel js/document ".jtrigger-id"))
        (let [triggers (d/sel selected-block ".jtrigger")]
          (when (seq triggers)
            (reset! *jump-data {:mode :property
                                :triggers (d/sel selected-block ".jtrigger")})
            (let [keys (generate-keys (count triggers))
                  key-down-handler (fn [e]
                                     (let [k (util/ekey e)]
                                       (if (= k "Escape")
                                         (exit!)
                                         (when (and (contains? full-start-keys k) (seq (:triggers @*jump-data)))
                                           (swap! *jump-data update :chords (fn [s] (str s (util/ekey e))))
                                           (let [chords (:chords @*jump-data)]
                                             (trigger! chords e))))))]
              (swap! *jump-data assoc :key-down-handler key-down-handler)
              (reset! *current-keys keys)
              (doall
               (map-indexed
                (fn [id dom]
                  (let [class (if (d/has-class? dom "ui__checkbox")
                                "jtrigger-id text-sm border rounded ml-4 px-1 shadow-xs"
                                "jtrigger-id text-sm border rounded ml-2 px-1 shadow-xs")]
                    (d/append! dom (-> (d/create-element :div)
                                       (d/set-attr! :class class)
                                       (d/set-text! (nth keys id))))))
                triggers))
              (.addEventListener js/window "keydown" key-down-handler)))))

      :else                             ; add block jump support
      nil)))
