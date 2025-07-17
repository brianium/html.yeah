(ns html.yeah-test
  (:require [charred.api :as json]
            [clojure.string :as string]
            [html.yeah :as yeah :refer [defelem children]]
            [html.yeah.attrs :as attrs]
            [clojure.test :refer [deftest is testing]]
            [dev.onionpancakes.chassis.core :as c]
            [dev.onionpancakes.chassis.compiler :as cc]
            [malli.core :as m]
            [malli.instrument :as mi]
            [malli.util :as mu]
            [squint.compiler :as compiler])
  (:import (clojure.lang ExceptionInfo)
           (dev.onionpancakes.chassis.core OpeningTag RawString)))

;;; Test helpers

(defn html?
  [c text & xs]
  (and (every? #(instance? c %) xs)
       (every? #(= (c/html %) text) xs)))

(def opening-tag? (partial html? OpeningTag))

(def raw-string?  (partial html? RawString))

;;; Basic defelem usage. Demonstrates schema destructuring and compile hoisting

(defelem simple-button
  [:map {:doc "A simple button with a schema for type"
         :as  attrs}
   [:type [:enum :submit :button]]]
  [:button (update attrs :type name)
   (children)])

(deftest render-elem-as-data
  (is (= "<button type=\"submit\">Hello</button>"
         (c/html [simple-button {:type :submit} "Hello"]))))

(defelem let-button
  [:map {:doc "Binding forms should be hoisted above chassis compiles. This allows
               a more natural way to write elements while ensuring properly compiled
               html forms"
         :keys [type]}
   [:type [:enum :submit :button]]]
  (let [as-string (name type)]
    [:button {:type as-string} ;;; Chassis will happily support a keyword, but we are explicitly casting to a string for the test
     (children)]))

(defelem when-some-button
  [:map {:keys [type]}]
  (when-some [as-string (name type)]
    [:button {:type as-string}
     (children)]))

(defelem when-let-button
  [:map {:keys [types]}]
  (when-let [s (seq types)]
    [:button {:type (first s)}
     (children)]))

(defelem if-some-button
  [:map {:as attrs}]
  (if-some [type (:some-type attrs)]
    [:button {:type type} (children)]
    [:button {:type (:nil-type attrs)} (children)]))

(defelem if-let-button
  [:map {:keys [types] :as attrs}]
  (if-let [s (seq types)]
    [:button {:type (first s)}
     (children)]
    [:button {:type (:neg-type attrs)}
     (children)]))

(deftest element-binding-forms-hoisted-above-compile
  (let [vanilla
        (fn [btn-type & children]
          (let [as-string (name btn-type)]
            (cc/compile [:button {:type as-string}
                         children])))
        equivalent? (fn [yc & [type]]
                      (let [btn-type        (or type :button)
                            vanilla-compile (vanilla btn-type "Hello")]
                        (and (= 3 (count vanilla-compile) (count yc))
                             (opening-tag? (format "<button type=\"%s\">" (or (name btn-type) "button")) (vanilla-compile 0) (yc 0))
                             (= (list "Hello") (vanilla-compile 1) (yc 1))
                             (raw-string? "</button>" (vanilla-compile 2) (yc 2)))))]
    (testing "compilation with let bindings"
      (let [yeah-compile (c/resolve-alias let-button {:type :button} (list "Hello"))]
        (is (equivalent? yeah-compile))))
    (testing "compilation with when-some bindings"
      (let [yeah-compile (c/resolve-alias when-some-button {:type :button} (list "Hello"))]
        (is (equivalent? yeah-compile))))
    (testing "compilation with when-let bindings"
      (let [yeah-compile (c/resolve-alias when-let-button {:types [:button]} (list "Hello"))]
        (is (equivalent? yeah-compile))))
    (testing "compilation with if-some bindings"
      (let [some-compile (c/resolve-alias if-some-button {:some-type :button} (list "Hello"))
            nil-compile  (c/resolve-alias if-some-button {:nil-type  :submit} (list "Hello"))]
        (is (and (equivalent? some-compile)
                 (equivalent? nil-compile :submit)))))
    (testing "compilation with if-let bindings"
      (let [pos-compile  (c/resolve-alias if-let-button {:types [:button]} (list "Hello"))
            neg-compile  (c/resolve-alias if-let-button {:neg-type :submit} (list "Hello"))]
        (is (and (equivalent? pos-compile)
                 (equivalent? neg-compile :submit)))))))

(defelem checked-button
  [:map {:keys [type data-on-click class]
         :or   {class []}}
   [:type [:enum :button :submit]]
   [:data-on-click :string]
   [:class {:optional true} [:vector :string]]]
  [:button {:type type :data-on-click data-on-click :class class}
   (children)])

(deftest element-validation
  (testing "A valid button"
    (is (string? (c/html [checked-button {:type :button :data-on-click "alert('hello')"} "Hello"]))))
  (testing "An invalid button"
    (is (thrown-with-msg?
         ExceptionInfo
         #":malli.core/invalid-input"
         (c/html [checked-button {:type :button :data-on-click "alert('hello')" :class "just-a-string"}])))))

(defelem mutually-exclusive
  [:and {:hair/keys [style color]}
   [:map
    [:hair/style [:enum :parted :bald]]
    [:hair/color {:optional true} [:enum :blonde :red :black :brown]]]
   [:fn {:error/message "no hair color for the balds"} (fn [{:hair/keys [style color]}]
                                                         (if (= style :bald) ;;; no hair color for the bald
                                                           (nil? color)
                                                           (some? color)))]]
  (let [message (if (= style :bald)
                  "Bald is beautiful"
                  (format "Enjoy your %s %s hair" (name color) (name style)))]
    (into [:div [:h1 message]] (children))))

(deftest element-validation-with-function-schema
  (testing "function schema passes"
    (let [result1 (c/html [mutually-exclusive {:hair/style :parted :hair/color :blonde} [:p "The End"]])
          result2 (c/html [mutually-exclusive {:hair/style :bald} [:p "The End"]])]
      (is (= "<div><h1>Enjoy your blonde parted hair</h1><p>The End</p></div>" result1))
      (is (= "<div><h1>Bald is beautiful</h1><p>The End</p></div>" result2))))
  (testing "funcion schema fails"
    (is (thrown-with-msg?
         ExceptionInfo
         #":malli.core/invalid-input"
         (c/html [mutually-exclusive {:hair/style :bald :hair/color :blonde} [:p "The End"]])))))

(defelem unordered-list
  [:map {::yeah/children [:vector {:min 1} [:tuple [:enum :li] :string]]
         :as attrs}]
  [:ul attrs
   (children)])

(deftest children-schema
  (testing "with valid children"
    (let [result (c/html [unordered-list {:class ["mx-auto"]}
                          [:li "Item 1"]
                          [:li "Item 2"]])]
      (is (= "<ul class=\"mx-auto\"><li>Item 1</li><li>Item 2</li></ul>" result))))
  (testing "with invalid children"
    (is (thrown-with-msg?
         ExceptionInfo
         #"malli.core/invalid-input"
         (c/html [unordered-list {:class ["mx-auto"]}
                  [:p "Oh no"]])))))

;;; Attribute Options
;;; =================
;;; Attribute options allow control over the form built up for runtime modification of attributes
;;; For example, you may want an element render to transform a Clojure map to JSON or transpile an
;;; arbitrary form to some HTML specific expression language.
;;;
;;; Built-in attribute options:
;;; =================================================================================================================
;;; | name                       | description                                                                      |
;;; -----------------------------------------------------------------------------------------------------------------
;;; | :html.yeah.attrs/transform | function. receives the element tag and an attrs map. must return a new attrs map |

(defn transform
  [_ attrs]
  (if (map? (:data-signals attrs))
    (update attrs :data-signals json/write-json-str)
    attrs))

(defelem signal-div
  [:map {::attrs/transform transform  ;;; Use a var if using a multimethod - i.e #'transform
         :as attrs}]
  [:div attrs (children)])

(deftest element-attribute-transformation
  (let [result   (c/html
                  [signal-div {:class ["mx-auto"]
                               :data-on-load "alert($turjan)"
                               :data-signals {:name "turjan"}}
                   [:h1 "Child"]])
        expected "<div class=\"mx-auto\" data-on-load=\"alert($turjan)\" data-signals=\"{&quot;name&quot;:&quot;turjan&quot;}\"><h1>Child</h1></div>"]
    (is (= expected result))))


;;; Attribute options can be used to enable multiple features for a specific use case.
;;; The below demonstrates an Alpine.js feature enabling Clojure maps for x-data, and ClojureScript
;;; expressions for x-init and @click

(defn js
  [body]
  (let [s        (pr-str body)
        compiled (compiler/compile-string* s {:elide-imports true
                                              :core-alias "$squint"
                                              :elide-exports true})]
    (string/trim
     (:body compiled))))

(defmethod attrs/option ::alpinejs
  [_ forms _ _ value]
  (if-not value
    forms
    (cons `((fn [attrs#]
              (cond-> attrs#
                (some? (:x-data attrs#)) (update :x-data json/write-json-str)
                (seq? (attrs# "@click")) (update "@click" js)
                (seq? (:x-init attrs#)) (update :x-init js)))) forms)))

(defelem alpine-element
  [:map {::alpinejs true
         :as        attrs}
   ["@click" [:sequential :any]]]
  [:div attrs (children)])

(deftest custom-attr-options
  (let [result (c/html
                [alpine-element {:x-data {:name "turjan"}
                                 "@click" '(js/alert name)
                                 :x-init '(set! name "otherguy")}
                 "Click me"])
        expected "<div x-data=\"{&quot;name&quot;:&quot;turjan&quot;}\" @click=\"alert(name);\" x-init=\"name = &quot;otherguy&quot;;\">Click me</div>"]
    (is (= expected result))))

;;; Schema Properties
;;; =================
;;; Schema properties can be used to add declarative functionality to the attribute schema.
;;; Define an implementation of the html.yeah/property multimethod and it's id can be used
;;; as a schema property. Below is an implementation of a property that supports merging
;;; element schemas in order to share behavior and attribute guarantees

(defmethod yeah/property ::merge [_ schema element-syms]
  (m/form
   (->> (mapv yeah/attributes element-syms)
        (reduce
         (fn [result target]
           (mu/merge target result)) schema))))

(defelem alpine-button
  [:map {::merge [alpine-element]
         :as attrs}
   ["@click" :string] ;;; left to right merging of attribute schemas
   [:type [:enum :button :submit]]]
  [:button attrs (children)])

(deftest simple-merged-element
  (testing "rendering an element with a merged schema"
    (let [result (c/html [alpine-button {:x-data {:name "turjan"}
                                         "@click" "alert(name);"
                                         :x-init '(set! name "otherguy")
                                         :type    :button}
                          "Click me"])
          expected "<button x-data=\"{&quot;name&quot;:&quot;turjan&quot;}\" @click=\"alert(name);\" x-init=\"name = &quot;otherguy&quot;;\" type=\"button\">Click me</button>"]
      (is (= expected result))))
  (testing "rendering an element using an incorrect schema"
    (is (thrown? ExceptionInfo
                 (c/html [alpine-button {:x-data {:name "turjan"}
                                         "@click" '(js/alert name)
                                         :x-init '(set! name "otherguy")
                                         :type    :button}])))))

(defelem merge-and-into-map
  [:map {::merge [alpine-element]
         :as attrs}]
  [:div {"@click" (attrs "@click")}
   [mutually-exclusive (dissoc attrs "@click") [:p "The End"]]])

(deftest merge-schemas-with-different-roots
  (testing "merge :and into :map"
    (let [result   (c/html [merge-and-into-map {:hair/style :bald
                                                "@click"    '(js/alert "Mama Mia!")}])
          expected "<div @click=\"alert(&quot;Mama Mia!&quot;);\"><div><h1>Bald is beautiful</h1><p>The End</p></div></div>"]
      (is (= result expected))
      (is (thrown-with-msg?
           ExceptionInfo
           #":malli.core/invalid-input"
           (c/html [mutually-exclusive {:hair/style :bald :hair/color :blonde "@click" '(js/alert "Hello")}]))))))


;;; Instrument schemas for tests 

(mi/collect! {:ns *ns*})

(mi/instrument!)
