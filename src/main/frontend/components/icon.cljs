(ns frontend.components.icon
  (:require ["@emoji-mart/data" :as emoji-data]
            ["emoji-mart" :refer [SearchIndex]]
            [promesa.core :as p]
            [cljs-bean.core :as bean]
            [camel-snake-kebab.core :as csk]
            [clojure.string :as string]
            [frontend.search :as search]
            [frontend.storage :as storage]
            [rum.core :as rum]
            [frontend.ui :as ui]
            [logseq.shui.ui :as shui]
            [frontend.util :as util]
            [goog.object :as gobj]
            [goog.functions :refer [debounce]]
            [frontend.config :as config]
            [frontend.handler.property.util :as pu]))

(defn icon
  [icon & [opts]]
  (cond
    (and (= :emoji (:type icon)) (:id icon))
    [:em-emoji (merge {:id (:id icon)}
                      opts)]

    (and (= :tabler-icon (:type icon)) (:id icon))
    (ui/icon (:id icon) opts)))

(defn get-page-icon
  [page-entity opts]
  (let [default-icon (ui/icon "page" (merge opts {:extension? true}))
        page-icon (pu/get-block-property-value page-entity :icon)]
    (or
     (when-not (string/blank? page-icon)
       (icon page-icon opts))
     default-icon)))

(defn- search-emojis
  [q]
  (p/let [result (.search SearchIndex q)]
    (bean/->clj result)))

(defonce *tabler-icons (atom nil))
(defn- get-tabler-icons
  []
  (if @*tabler-icons
    @*tabler-icons
    (let [result (->> (keys (bean/->clj js/tablerIcons))
                      (map (fn [k]
                             (-> (string/replace (csk/->Camel_Snake_Case (name k)) "_" " ")
                                 (string/replace-first "Icon " ""))))
                      ;; FIXME: somehow those icons don't work
                      (remove #{"Ab" "Ab 2" "Ab Off"}))]
      (reset! *tabler-icons result)
      result)))

(defonce emojis
  (vals (bean/->clj (gobj/get emoji-data "emojis"))))

(defn- search-tabler-icons
  [q]
  (search/fuzzy-search (get-tabler-icons) q :limit 100))

(defn- search
  [q tab]
  (p/let [icons (when (not= tab :emoji) (search-tabler-icons q))
          emojis (when (not= tab :icon) (search-emojis q))]
    {:icons icons
     :emojis emojis}))

(rum/defc pane-block
  [label items]
  [:div.pane-block
   [:div.hd.px-1.pb-1.leading-none
    [:strong.text-xs.font-medium.text-gray-07.dark:opacity-80 label]]
   [:div.its items]])

(rum/defc emoji-cp < rum/static
  [{:keys [id name] :as emoji} {:keys [on-chosen hover]}]
  [:button.text-2xl.w-9.h-9.transition-opacity
   (cond->
     {:tabIndex "0"
      :title name
      :on-click (fn [e]
                  (on-chosen e (assoc emoji :type :emoji)))}
     (not (nil? hover))
     (assoc :on-mouse-over #(reset! hover emoji)
            :on-mouse-out #()))
   [:em-emoji {:id id}]])

(rum/defc emojis-cp < rum/static
  [emojis opts]
  (pane-block
    (util/format "Emojis (%s)" (count emojis))
    (for [emoji emojis]
      (rum/with-key (emoji-cp emoji opts) (:id emoji)))))

(rum/defc icon-cp < rum/static
  [icon {:keys [on-chosen hover]}]
  [:button.w-9.h-9.transition-opacity
   {:key icon
    :tabIndex "0"
    :title icon
    :on-click (fn [e]
                (on-chosen e {:type :tabler-icon
                              :id icon
                              :name icon}))
    :on-mouse-over #(reset! hover {:type :tabler-icon
                                   :id icon
                                   :name icon
                                   :icon icon})
    :on-mouse-out #()}
   (ui/icon icon {:size 24})])

(rum/defc icons-cp < rum/static
  [icons opts]
  (pane-block
    (util/format "Icons (%s)" (count icons))
    (for [icon icons]
      (icon-cp icon opts))))

(defn get-used-items
  []
  (storage/get :ui/ls-icons-used))

(defn add-used-item!
  [m]
  (let [s (some->> (or (get-used-items) [])
            (take 24)
            (filter #(not= m %))
            (cons m))]
    (storage/set :ui/ls-icons-used s)))

(rum/defc all-cp
  [opts]
  (let [used-items (get-used-items)
        emoji-items (take 32 emojis)
        icon-items (take 48 (get-tabler-icons))
        item-cp (fn [d]
                  (if (or (string? d)
                        (= :tabler-icon (:type d)))
                    (icon-cp (if (string? d) d (:id d)) opts)
                    (emoji-cp d opts)))]
    [:div.all-pane.pb-10
     (when (count used-items)
       (pane-block "Frequently used"
         (->> used-items (map item-cp))))
     (pane-block (util/format "Emojis (%s)" (count emojis))
       (->> emoji-items (map item-cp)))
     (pane-block (util/format "Icons (%s)" (count (get-tabler-icons)))
       (->> icon-items (map item-cp)))]))

(rum/defc tab-observer
  [tab {:keys [reset-q!]}]
  (rum/use-effect!
    #(reset-q!)
    [tab])
  nil)

(rum/defcs icon-search <
  (rum/local "" ::q)
  (rum/local nil ::result)
  (rum/local :all ::tab)
  (rum/local nil ::hover)
  [state {:keys [on-chosen] :as opts}]
  (let [*q (::q state)
        *result (::result state)
        *tab (::tab state)
        *hover (::hover state)
        *input-ref (rum/create-ref)
        *result-ref (rum/create-ref)
        result @*result
        opts (assoc opts :hover *hover
                    :on-chosen (fn [e m]
                                 (and on-chosen (on-chosen e m))
                                 (when (:type m) (add-used-item! m))))
        reset-q! #(when-let [^js input (rum/deref *input-ref)]
                    (reset! *q "")
                    (reset! *result {})
                    (set! (. input -value) "")
                    (js/setTimeout
                     (fn [] (.focus input)
                       (util/scroll-to (rum/deref *result-ref) 0 false))
                     64))]
    [:div.cp__emoji-icon-picker
     ;; header
     [:div.hd
      (tab-observer @*tab {:reset-q! reset-q!})
      [:div.search-input
       (shui/tabler-icon "search" {:size 16})
       [:input.form-input
        {:auto-focus true
         :ref *input-ref
         :placeholder (util/format "Search %s items" (string/lower-case (name @*tab)))
         :default-value ""
         :on-key-down (fn [^js e]
                        (case (.-keyCode e)
                          ;; esc
                          27 (do (util/stop e)
                                 (if (string/blank? @*q)
                                    (some-> (rum/deref *input-ref) (.blur))
                                   (reset-q!)))
                          ;; up
                          38 (do (util/stop e))
                          ;; down
                          40 (do (util/stop e))
                          :dune))
         :on-change (debounce
                      (fn [e]
                        (reset! *q (util/evalue e))
                        (if (string/blank? @*q)
                          (reset! *result {})
                          (p/let [result (search @*q @*tab)]
                            (reset! *result result))))
                      200)}]
       (when-not (string/blank? @*q)
         [:a.x {:on-click reset-q!} (shui/tabler-icon "x" {:size 14})])]]
     ;; body
     [:div.bd
      {:ref *result-ref
       :on-mouse-leave #(reset! *hover nil)}
      [:div.search-result
       (if (seq result)
         [:div.flex.flex-1.flex-col.gap-1
          (when (seq (:emojis result))
            (emojis-cp (:emojis result) opts))
          (when (seq (:icons result))
            (icons-cp (:icons result) opts))]
         [:div.flex.flex-1.flex-col.gap-1
          (case @*tab
            :emoji (emojis-cp emojis opts)
            :icon (icons-cp (get-tabler-icons) opts)
            (all-cp opts))])]]

     ;; footer
     [:div.ft
      (if-not @*hover
        ;; tabs
        [:div.flex.flex-1.flex-row.items-center.gap-2
         (let [tabs [[:all "All"] [:emoji "Emojis"] [:icon "Icons"]]]
           (for [[id label] tabs
                 :let [active? (= @*tab id)]]
             (shui/button
               {:variant :ghost
                :size :sm
                :class (util/classnames [{:active active?} "tab-item"])
                :on-click #(reset! *tab id)}
               label)))]

        ;; preview
        [:div.hover-preview
         [:strong (:name @*hover)]
         [:button
          {:style {:font-size 30}
           :key   (:id @*hover)
           :title (:name @*hover)}
          (if (= :tabler-icon (:type @*hover))
            (ui/icon (:icon @*hover) {:size 32})
            (:native (first (:skins @*hover))))]])]]))

(rum/defc icon-picker
  [icon-value {:keys [disabled? on-chosen icon-props]}]
  (let [content-fn
        (if config/publishing?
          (constantly [])
          (fn [{:keys [id]}]
            (icon-search
              {:on-chosen (fn [e icon-value]
                            (on-chosen e icon-value)
                            (shui/popup-hide! id))})))]
    ;; trigger
    (shui/button
      {:variant  :ghost
       :size     :sm
       :class    "px-1 leading-none"
       :on-click #(when-not disabled?
                    (shui/popup-show! % content-fn
                      {:as-menu?      true
                       :content-props {:class "w-auto"}}))}
      (if icon-value
        (icon icon-value (merge {:size 18} icon-props))
        [:div.opacity-50.text-sm
         "Empty"]))))