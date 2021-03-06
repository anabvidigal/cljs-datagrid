(ns com.kaicode.datagrid
  (:require [com.kaicode.tily :as tily]
            [com.kaicode.teleport :as t]
            [reagent.core :as r]))

(defonce left-corner-block-width 40)
(defonce common-column-style {:display :table-cell
                              :padding 0
                              :border  "1px solid #d9d9d9"})

(defn get-column-config [grid-state column-kw]
  (->> @grid-state :columns-config (filter #(= (first %) column-kw)) first second))

(defn get-window-dimension [grid-state]
  (-> @grid-state :window-dimension))

(defn get-content-height [grid-state]
  (let [padding-bottom 20
        header-button-height 40
        title-bar-height 60
        search-box-height 67]
    (-> grid-state get-window-dimension :height (- title-bar-height search-box-height padding-bottom header-button-height))))

(defn get-invisible-columns [grid-state]
  (->> @grid-state :columns-config
       (filter #(false? (-> % second :visible)))))

(defn get-visible-columns [grid-state]
  (->> @grid-state :columns-config
       (filter #(true? (-> % second :visible)))))

(defn get-content-width [grid-state]
  (-> grid-state get-window-dimension :width))

(defn get-width-for-columns [grid-state]
  (- (get-content-width grid-state) left-corner-block-width 
     (-> grid-state get-visible-columns count (+ 5))))

(defn extra-width-per-visible-column [grid-state]
  (let [width-for-columns        (get-width-for-columns grid-state)
        invisible-columns-config (get-invisible-columns grid-state)
        extra-width              (->> invisible-columns-config
                                      (map #(-> % second :width-weight (* width-for-columns)))
                                      (reduce +))]
    (-> extra-width
        (/ (-> grid-state get-visible-columns count))
        js/Math.floor)))

(defn get-column-width [column-kw grid-state]
  (let [width-for-columns (get-width-for-columns grid-state)
        width-weight        (:width-weight (get-column-config grid-state column-kw))
        width               (+ (* width-weight width-for-columns)
                               (extra-width-per-visible-column grid-state))]
    (js/Math.floor width)))

(defn get-default-column-style [column-kw spreadsheet-state]
  (let [column-width (get-column-width column-kw spreadsheet-state)
        style (merge {:width         column-width
                      :min-width     column-width
                      :max-width     column-width
                      :text-align :center
                      :vertical-align :middle}
                     common-column-style)]
    style))

(defn- data-column-headers [grid-state]
  (doall (for [column-config (-> @grid-state :columns-config)
               :let [[column-kw config] column-config
                     column-width   (get-column-width column-kw grid-state)
                     header-txt     (-> config :render-header-fn (apply nil))
                     sort-indicator (let [sort-column (-> @grid-state :sort-column)
                                          column      (-> sort-column keys first)
                                          descending? (column-kw sort-column)]
                                      (when (= column-kw column)
                                        (if descending?
                                          [:i {:class "material-icons"} "arrow_drop_down"]
                                          [:i {:class "material-icons"} "arrow_drop_up"])))
                     sort-column    (fn [evt]
                                      (let [sort-column (:sort-column @grid-state)
                                            rows        (:rows @grid-state)]
                                        (swap! grid-state (fn [grid-state]
                                                            (let [comparator (cond
                                                                               (nil? sort-column) <
                                                                               :else (if (column-kw sort-column)
                                                                                       >
                                                                                       <))]
                                                              (-> grid-state
                                                                  (update-in [:selected-rows] (constantly #{}))
                                                                  (update-in [:sort-column] (fn [sc]
                                                                                              (let [val (column-kw sc)]
                                                                                                {column-kw (not val)})))
                                                                  (update-in [:rows] (constantly
                                                                                       (vec (sort-by
                                                                                              #(-> % column-kw
                                                                                                   (or "") str
                                                                                                   clojure.string/lower-case)
                                                                                              comparator
                                                                                              rows))))))))))]
               :when (:visible config)]
           [:div {:key      (tily/format "grid-%s-%s" (:id @grid-state) column-kw)
                  :class    "mdl-button mdl-js-button mdl-js-button mdl-button--raised"
                  :style    (merge {:display   :table-cell
                                    :width     column-width
                                    :min-width column-width
                                    :max-width column-width}
                                   common-column-style)
                  :on-click sort-column}
            header-txt
            sort-indicator])))

(defn- column-headers [grid-state]
  (let [left-corner-block (or (:left-corner-block @grid-state)
                              (fn [grid-state]
                                [:div {:class "mdl-button mdl-js-button mdl-js-button mdl-button--raised"
                                       :style {:display   :table-cell
                                               :width     left-corner-block-width
                                               :min-width left-corner-block-width
                                               :max-width left-corner-block-width
                                               :padding   0}}]))]
    [:div {:style {:display :table-row}}
     (left-corner-block grid-state)
     (data-column-headers grid-state)]))

(defn- default-column-render [column-kw row grid-state]
  (let [id           (-> @grid-state :id)
        column-width (get-column-width column-kw grid-state)
        style        (merge {:width     column-width
                             :min-width column-width
                             :max-width column-width}
                            common-column-style)
        value        (str (column-kw @row))
        unique       (-> (get-column-config grid-state column-kw) :unique)
        property     {:key                               (tily/format "grid-%s-default-column-render-%s" id column-kw)
                      :content-editable                  true
                      :suppress-content-editable-warning true
                      :style                             style}
        save-fn      (or (:save-fn (get-column-config grid-state column-kw))
                         #(swap! row update-in [column-kw] (constantly %)))
        save         (fn [evt]
                       (let [div     (. evt -target)
                             content (. div -textContent)]
                         (save-fn content @row column-kw)))
        property     (if unique
                       (assoc property :on-blur save)
                       (assoc property :on-input save))]
    [:div property
     value]))

(defn- number-button [i grid-state]
  (let [selected-rows (r/cursor grid-state [:selected-rows])
        select-row    #(swap! selected-rows conj i)
        unselect-row  (fn [] (swap! selected-rows (fn [selected-rows]
                                                    (set (filter #(not= i %) selected-rows)))))]
    [:div {:draggable       true
           :class           "mdl-button mdl-js-button mdl-js-button mdl-button--raised"
           :style           {:display   :table-cell
                             :width     left-corner-block-width
                             :min-width left-corner-block-width
                             :max-width left-corner-block-width
                             :padding   0}
           :on-click        #(if (tily/is-contained? i :in @selected-rows)
                              (unselect-row)
                              (select-row))
           :on-drag-start   (fn [evt]
                              (let [selected-row-indexes  (-> @grid-state (get-in [:selected-rows]))
                                    selected-entities     (-> @grid-state :rows
                                                              (select-keys selected-row-indexes)
                                                              vals)
                                    entity-ids            (map #(:system/id %) selected-entities)
                                    serialized-entity-ids (t/serialize entity-ids)]
                                (.. evt -dataTransfer (setData "data/transit" serialized-entity-ids))))

           :on-context-menu (fn [evt]
                              (let [rect   (.. evt -target -parentNode -parentNode -parentNode -parentNode getBoundingClientRect)
                                    x      (- (. evt -clientX) 10)
                                    y      (+ (. evt -clientY) 5)
                                    x      (- x (. rect -left))
                                    y      (- y (. rect -top))
                                    delete [:a {:href     "#"
                                                :on-click (fn [evt]
                                                            (let [current-rows   (:rows @grid-state)
                                                                  index-row      (tily/with-index current-rows)
                                                                  new-rows       (->> index-row
                                                                                      (remove (fn [[index row]]
                                                                                                (tily/is-contained? index :in @selected-rows)))
                                                                                      (map #(second %))
                                                                                      vec)
                                                                  rows-to-delete (->> index-row
                                                                                      (filter (fn [[index row]]
                                                                                                (tily/is-contained? index :in @selected-rows)))
                                                                                      (map #(-> % second)))
                                                                  on-delete-rows (or (:on-delete-rows @grid-state)
                                                                                     constantly)]
                                                              (on-delete-rows rows-to-delete)

                                                              (reset! selected-rows #{})
                                                              (tily/set-atom! grid-state [:rows] new-rows)))} "Delete"]]
                                (select-row)
                                (tily/set-atom! grid-state [:context-menu :content] delete)
                                (tily/set-atom! grid-state [:context-menu :coordinate] [x y])
                                (. evt preventDefault)))}
     (inc i)]))

(defn- rows [grid-state]
  (let [id             (-> @grid-state :id)
        total-width    (get-content-width grid-state)
        total-height   (get-content-height grid-state)
        columns-config (-> @grid-state :columns-config)
        selected-rows  (r/cursor grid-state [:selected-rows])
        row-data       (fn [row]
                         (doall (for [[column-kw config] columns-config
                                      :let [render-column-fn (:render-column-fn config)
                                            k                (tily/format "grid-%s-%s-%s" id (:system/id @row) column-kw)]
                                      :when (:visible config)]
                                  (if render-column-fn
                                    ^{:key k} [render-column-fn column-kw row grid-state]
                                    ^{:key k} [default-column-render column-kw row grid-state]))))
        row-div        (fn [i row]
                         (let [style {:display :table-row}
                               style (if (tily/is-contained? i :in @selected-rows)
                                       (assoc style :background-color "#e6faff")
                                       style)]
                           [:div {:key   (tily/format "grid-%s-%s" id i)
                                  :style style}
                            (number-button i grid-state)
                            (row-data row)]))]
    [:div {:id    (tily/format "grid-%s-rows" id)
           :style {:display    :block
                   :height     total-height
                   :width      total-width
                   :overflow-y :auto
                   :overflow-x :hidden}}
     (doall (for [i (range (-> @grid-state :rows count))
                  :let [row (r/cursor grid-state [:rows i])]]
              (row-div i row)))]))

(defn- context-menu [grid-state]
  (let [content    (-> @grid-state :context-menu :content)
        coordinate (-> @grid-state :context-menu :coordinate)]
    (when content
      [:div {:id    "context-menu"
             :style {:background-color :white
                     :border           "1px solid grey"
                     :padding          5
                     :position         :absolute
                     :z-index          1
                     :left             (first coordinate)
                     :top              (second coordinate)}}
       content])))

(defn render [grid-state]
  (r/create-class {:component-will-mount (fn [this-component]
                                           (tily/set-atom! grid-state [:id] (str (rand-int 1000))))
                   :reagent-render       (fn [grid-state]
                                           [:div {:on-click #(when (-> @grid-state :context-menu :content)
                                                              (tily/set-atom! grid-state [:context-menu :content] nil))}
                                            [context-menu grid-state]
                                            [column-headers grid-state]
                                            [rows grid-state]])}))
