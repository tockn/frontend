(ns frontend.components.insights
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [clojure.string :as string]
            [frontend.async :refer [raise!]]
            [frontend.components.common :as common]
            [frontend.components.forms :refer [managed-button]]
            [frontend.datetime :as datetime]
            [frontend.models.repo :as repo-model]
            [frontend.models.user :as user-model]
            [frontend.state :as state]
            [frontend.utils :as utils :refer-macros [inspect]]
            [frontend.utils.github :as gh-utils]
            [frontend.utils.vcs-url :as vcs-url]
            [goog.string :as gstring]
            [goog.string.format]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [frontend.models.build :as build])
  (:require-macros [cljs.core.async.macros :as am :refer [go go-loop alt!]]
                   [frontend.utils :refer [html defrender]]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                         ;;
;; Note that unexterned properties must be accessed with `aget` instead of ;;
;; the `.-` or `..` shortcut notations.                                    ;;
;;                                                                         ;;
;; Google closure compiler with "advanced" optimizations will mangle       ;;
;; unexterned field names.                                                 ;;
;;                                                                         ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def svg-info
  {:width 425
   :height 100
   :top 30, :right 10, :bottom 10, :left 40})

(def plot-info
  {:width (- (:width svg-info) (:left svg-info) (:right svg-info))
   :height (- (:height svg-info) (:top svg-info) (:bottom svg-info))
   :max-bars 50
   :left-legend-items [{:classname "success"
                        :text "Passed"}
                       {:classname "failed"
                        :text "Failed"}
                       {:classname "canceled"
                        :text "Canceled"}]
   :right-legend-items [{:classname "queue"
                         :text "Queue time"}]
   :legend-info {:square-size 10
                 :item-width 80
                 :item-height 14   ; assume font is 14px
                 :spacing 4}
   :positive-y% 0.65})

(defn add-queued-time [build]
  (let [queued-time (max (build/queued-time build) 0)]
    (assoc build :queued_time_millis queued-time)))

(defn build-graphable [{:keys [outcome]}]
  (#{"success" "failed" "canceled"} outcome))

(defn add-legend [plot]
  (let [{:keys [square-size item-width item-height spacing]} (:legend-info plot-info)
        left-legend-enter (-> plot
                             (.select ".legend-container")
                             (.selectAll ".legend")
                             (.data (clj->js (:left-legend-items plot-info)))
                             (.enter)
                             (.append "g")
                             (.attr #js {"class" "legend left"
                                         "transform"
                                         (fn [item i]
                                           (let [tr-x (* i item-width)
                                                 tr-y (- (- item-height
                                                            (/ (- item-height square-size)
                                                               2)))]
                                             (gstring/format "translate(%s,%s)" tr-x  tr-y)))}))
        right-legend-enter (-> plot
                              (.select ".legend-container")
                              (.selectAll ".legend-right")
                              (.data (clj->js (:right-legend-items plot-info)))
                              (.enter)
                              (.append "g")
                              (.attr #js {"class" "legend right"
                                          "transform"
                                          (fn [item i]
                                            (let [tr-x (- (:width plot-info) (* (inc i) item-width))
                                                  tr-y (- (- item-height
                                                             (/ (- item-height square-size)
                                                                2)))]
                                              (gstring/format "translate(%s,%s)" tr-x  tr-y)))}))]
    ;; left legend
    (-> left-legend-enter
        (.append "rect")
        (.attr #js {"width" square-size
                    "height" square-size
                    ;; `aget` must be used here instead of direct field access.  See note in preamble.
                    "class" #(aget % "classname")
                    "transform"
                    (fn [item i]
                      (gstring/format "translate(%s,%s)" 0  (- (+ square-size))))}))
    (-> left-legend-enter
        (.append "text")
        (.attr #js {"x" (+ square-size spacing)})
        (.text #(aget % "text")))

    ;; right legend
    (-> right-legend-enter
        (.append "rect")
        (.attr #js {"width" square-size
                    "height" square-size
                    "class" #(aget % "classname")
                    "transform"
                    (fn [item i]
                      (gstring/format "translate(%s,%s)" 0  (- (+ square-size))))}))
    (-> right-legend-enter
        (.append "text")
        (.attr #js {"x" (+ square-size
                           spacing)})
        (.text #(aget % "text")))))

(defn visualize-insights-bar! [el builds owner]
  (let [[y-pos-max y-neg-max] (->> [:build_time_millis :queued_time_millis]
                                   (map #(->> builds
                                              (map %)
                                              (apply max))))
        y-zero (->> [:height :positive-y%]
                    (map plot-info)
                    (apply *))
        y-pos-scale (-> (js/d3.scale.linear)
                        (.domain #js[0 y-pos-max])
                        (.range #js[y-zero 0]))
        y-neg-scale (-> (js/d3.scale.linear)
                        (.domain #js[0 y-neg-max])
                        (.range #js[y-zero (:height plot-info)]))
        y-pos-floored-max (datetime/nice-floor-duration y-pos-max)
        y-pos-tick-values (list y-pos-floored-max 0)
        y-neg-tick-values [(datetime/nice-floor-duration y-neg-max)]
        y-pos-axis (-> (js/d3.svg.axis)
                       (.scale y-pos-scale)
                       (.orient "left")
                       (.tickValues (clj->js y-pos-tick-values))
                       (.tickFormat #(first (datetime/millis-to-float-duration % {:decimals 0}))))
        y-neg-axis (-> (js/d3.svg.axis)
                       (.scale y-neg-scale)
                       (.orient "left")
                       (.tickValues (clj->js y-neg-tick-values))
                       (.tickFormat #(first (datetime/millis-to-float-duration % {:decimals 0}))))
        scale-filler (->> (list (:max-bars plot-info) (count builds))
                          (apply -)
                          range
                          (map (partial str "xx-")))
        x-scale (-> (js/d3.scale.ordinal)
                    (.domain (clj->js
                              (concat (map :build_num builds) scale-filler)))
                    (.rangeBands #js[0 (:width plot-info)] 0.4))
        plot (-> js/d3
                (.select el)
                (.select "svg g.plot-area"))
        bars-join (-> plot
                      (.select "g > g.bars")
                      (.selectAll "g.bar-pair")
                      (.data (clj->js builds)))
        bars-enter-g (-> bars-join
                         (.enter)
                         (.append "g")
                         (.attr "class" "bar-pair"))
        grid-y-pos-vals (for [tick (remove zero? y-pos-tick-values)] (y-pos-scale tick))
        grid-y-neg-vals (for [tick (remove zero? y-neg-tick-values)] (y-neg-scale tick))
        grid-lines-join (-> plot
                            (.select "g.grid-lines")
                            (.selectAll "line.horizontal")
                            (.data (clj->js (concat grid-y-pos-vals grid-y-neg-vals))))]

    ;; top bar enter
    (-> bars-enter-g
        (.append "a")
        (.attr "class" "top")
        (.append "rect")
        (.attr "class" "bar"))

    ;; bottom (queue time) bar enter
    (-> bars-enter-g
        (.append "a")
        (.attr "class" "bottom")
        (.append "rect")
        (.attr "class" "bar bottom queue"))

    ;; top bars enter and update
    (-> bars-join
        (.select ".top")
        (.attr #js {"xlink:href" #(utils/uri-to-relative (aget % "build_url"))
                    "xlink:title" #(let [[duration-str] (datetime/millis-to-float-duration (aget % "build_time_millis"))]
                                     (gstring/format "%s in %s"
                                                     (gstring/toTitleCase (aget % "outcome"))
                                                     duration-str))})
        (.select "rect.bar")
        (.attr #js {"class" #(str "bar " (aget % "outcome"))
                    "y" #(y-pos-scale (aget % "build_time_millis"))
                    "x" #(x-scale (aget % "build_num"))
                    "width" (.rangeBand x-scale)
                    "height" #(- y-zero (y-pos-scale (aget % "build_time_millis")))}))

    ;; bottom bar enter and update
    (-> bars-join
        (.select ".bottom")
        (.attr #js {"xlink:href" #(utils/uri-to-relative (aget % "build_url"))
                    "xlink:title" #(let [[duration-str] (datetime/millis-to-float-duration (aget % "queued_time_millis"))]
                                     (gstring/format "Queue time %s" duration-str))})
        (.select "rect.bar")
        (.attr #js {"y" y-zero
                    "x" #(x-scale (aget % "build_num"))
                    "width" (.rangeBand x-scale)
                    "height" #(- (y-neg-scale (aget % "queued_time_millis")) y-zero)}))

    ;; bars exit
    (-> bars-join
        (.exit)
        (.remove))


    ;; legend
    (add-legend plot)

    ;; y-axis
    (-> plot
        (.select ".axis-container g.y-axis.positive")
        (.call y-pos-axis))
    (-> plot
        (.select ".axis-container g.y-axis.negative")
        (.call y-neg-axis))
    ;; x-axis
    (-> plot
        (.select ".axis-container g.axis.x-axis line")
        (.attr #js {"y1" y-zero
                    "y2" y-zero
                    "x1" 0
                    "x2" (:width plot-info)}))

    ;; grid lines enter
    (-> grid-lines-join
        (.enter)
        (.append "line"))
    ;; grid lines enter and update
    (-> grid-lines-join
        (.attr #js {"class" "horizontal"
                    "y1" (fn [y] y)
                    "y2" (fn [y] y)
                    "x1" 0
                    "x2" (:width plot-info)}))))

(defn insert-skeleton [el]
  (let [plot-area (-> js/d3
                      (.select el)
                      (.append "svg")
                      (.attr #js {"xlink" "http://www.w3.org/1999/xlink"
                                  "width" (:width svg-info)
                                  "height" (:height svg-info)})
                      (.append "g")
                      (.attr "class" "plot-area")
                      (.attr "transform" (gstring/format "translate(%s,%s)"
                                                         (:left svg-info)
                                                         (:top svg-info))))]

    (-> plot-area
        (.append "g")
        (.attr "class" "legend-container"))
    (-> plot-area
        (.append "g")
        (.attr "class" "grid-lines"))
    (-> plot-area
        (.append "g")
        (.attr "class" "bars"))

    (let [axis-container (-> plot-area
                             (.append "g")
                             (.attr "class" "axis-container"))]
      (-> axis-container
          (.append "g")
          (.attr "class" "x-axis axis")
          (.append "line"))
      (-> axis-container
          (.append "g")
          (.attr "class" "y-axis positive axis"))
      (-> axis-container
          (.append "g")
          (.attr "class" "y-axis negative axis")))))

(defn chartable-builds [builds]
  (->> builds
       (filter build-graphable)
       reverse
       (map add-queued-time)
       (take (:max-bars plot-info))))

(defn project-insights-bar [builds owner]
  (reify
    om/IDidMount
    (did-mount [_]
      (let [el (om/get-node owner)]
        (insert-skeleton el)
        (visualize-insights-bar! el builds owner)))
    om/IDidUpdate
    (did-update [_ prev-props prev-state]
      (let [el (om/get-node owner)]
        (visualize-insights-bar! el builds owner)))
    om/IRender
    (render [_]
      (html
       [:div.build-time-visualization]))))

(defrender project-insights [{:keys [reponame username default_branch recent-builds]} owner]
  (let [builds (chartable-builds recent-builds)]
    (when (not-empty builds)
      (html
       [:div.project-block
        [:h1 (gstring/format "Build Status: %s/%s/%s" username reponame default_branch)]
        (om/build project-insights-bar builds)]))))

(defrender build-insights [state owner]
  (let [projects (get-in state state/projects-path)]
    (html
       [:div#build-insights
        [:header.main-head
         [:div.head-user
          [:h1 "Insights » Repositories"]]]
        (om/build-all project-insights projects)])))
