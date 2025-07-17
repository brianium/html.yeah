(ns html.yeah
  "HTML YEAH BROTHER"
  (:require [dev.onionpancakes.chassis.core :as c]
            [dev.onionpancakes.chassis.compiler :as cc]
            [html.yeah.attrs :as attrs]))

(def ^:private destruct-keys
  #{"as" "keys" "or"})

(defn- attr-bindings
  "This is what adds destructuring support to the attribute schema"
  [opts]
  (reduce-kv
   (fn [m k v]
     (if (or (map? k)
             (destruct-keys (clojure.core/name k)))
       (assoc m k v)
       m)) {} opts))

(def ^:private bind-forms
  "binding forms supported by defelem. If these forms lead a defelem form
   then they will be hoisted outside of the cc/compile. In the case of if-let and if-some,
   both branches will be compiled"
  #{'clojure.core/let
    'clojure.core/when-some
    'clojure.core/when-let
    'clojure.core/if-some
    'clojure.core/if-let})

(defmulti ^:private hoist-bindings (fn [binding _] binding))

(defn- decompose-if
  [head]
  (let [test    (->> head (drop 1) (take 1))
        tbranch (->> head (drop 2) (take 1))
        fbranch (->> head (drop 3) (take 1))]
    [test tbranch fbranch]))

(defmethod hoist-bindings 'clojure.core/if-let
  [_ head]
  (let [[test tbranch fbranch] (decompose-if head)]
    `(if-let ~@test
       (cc/compile ~@tbranch)
       (cc/compile ~@fbranch))))

(defmethod hoist-bindings 'clojure.core/if-some
  [_ head]
  (let [[test tbranch fbranch] (decompose-if head)]
    `(if-some ~@test
       (cc/compile ~@tbranch)
       (cc/compile ~@fbranch))))

(defmethod hoist-bindings :default [_ head]
  (concat
   (take 2 head)
   `((cc/compile ~@(drop 2 head)))))

(defn- attributes-form
  "Creates the form that will be applied to html attributes
  as part of a render"
  [tag schema]
  (let [[_ opts & _] schema
        attr-sym `attrs#]
    (some->>
     (attrs/options tag schema opts)
     (cons `~attr-sym)
     (cons `->>)
     (list)
     (concat `(fn [~attr-sym])))))

(defmulti property
  "Properties are meant to extend schema functionality. Implementations
   will be expected to return a schema in vector form."
  (fn [id _ _]
    id))

(defmethod property :default [_ schema _]
  schema)

(defn- property*
  [id schema val]
  (let [result (property id schema val)]
    (if (vector? result)
      result
      (throw (ex-info "html.yeah/property must return a vector" {:id id :schema schema :result result})))))

(defn- create-schema
  "Creates a new schema free of binding forms. Handles
  merging schemas (left-to-right) based on the presence of
  an ::attrs/merge key in opts"
  [root attribute-bindings opts fields]
  (let [new-schema (into [root (->> (keys attribute-bindings)
                                    (apply dissoc opts))] fields)]
    (reduce (fn [schema [k v]]
              (if (some? v)
                (property* k schema v)
                schema)) new-schema opts)))

;;; Public API

(defmacro children
  "A placeholder representing all children passed to an element. Use this in
   element definitions to mark where children are rendered"
  []
  '<children>)

(defn describe
  "Given an element symbol, return schema metadata for the element's
  attributes and children"
  [symbol]
  (some->
   (resolve symbol)
   (meta)
   (select-keys [::attributes ::children ::render])))

(defn attributes
  "Returns the attribute schema for the given element symbol"
  [symbol]
  (::attributes (describe symbol)))

(defmacro defelem
  "Define a chassis alias in terms of an attribute schema. Schemas must be
  malli schemas using the vector syntax. malli schema syntax is extended to support
  destructuring via the root schema's properties.

  ```clojure
  (defelem button
    [:map {:doc  \"A docstring can be attached by adding the :doc key to schema properties\"
           :as   attrs}
      [:type [:enum :button :submit]]]
    [:button attrs (children)])
  ```

  Note: schema bindings cannot be used in the schema itself, only in th element body.

  All elements are compiled by the chassis compiler. Top level binding forms - such as
  let, when-let, when-some, if-let, and if-some - will be hoisted to the top of the syntax
  tree. In the case of if-let and if-some - both the true and false branches will be compiled

  The following forms are equivalent in terms of compilation:

  ```clojure
  (defelem button
    [:map {:as attrs}
      [:type :string]]
    (let [{:keys [type]} attrs]
      [:button {:type type} (children)]))

  (defn button
    [attrs children]
    (let [{:keys [type]} attrs]
      (cc/compile
        [:button {:type type} children])))

  (defelem icon-button
    [:map {:keys [type]
           :as attrs}
      [:icon {:optional true} :string]
      [:type :string]]
    (if-some [icon (:icon attrs)]
      [button {:type type} [:i {:class icon}] (children)]
      [button {:type type} (children)]))

  (defn icon-button
    [attrs children]
    (if-some [icon (:icon attrs)]
      (cc/compile
        [button {:type type} [:i {:class icon}] children])
      (cc/compile
        [button {:type type} children])))
  ```

  In the examples above, (children) or (html.yeah/children) designates a placeholder
  for any children that will be passed to the element.

  An optional schema for children can be specified as a schema property

  ```clojure
  (defelem ul
    [:map {:html.yeah/children [:vector {:min 1} [:type [:enum :li] :string]]
           :as attrs}]
    [:ul attrs (children)])
  ```

  The underlying render function will be given a :malli/schema function schema
  derived from the main attribute schema and child schema - making them fair game
  for malli.instrument/collect! and malli.instrument/instrument!

  Returns the namespace qualified keyword used by chassis.core/resolve-alias. Can be
  used directly in a hiccup form, but so can the keyword - i.e [button] and [::button] are equivalent"
  [name schema & body]
  (let [[root opts & fields] schema
        ab                   (attr-bindings opts)
        tag                  (keyword (str *ns*) (clojure.core/name name))
        head                 (first body)
        ?binding             (when (seq? head)
                               (let [sym (first head)]
                                 (when (symbol? sym)
                                   (some-> sym resolve symbol bind-forms))))
        compile-body         (if ?binding
                               (hoist-bindings ?binding head)
                               `(cc/compile ~@body))
        schema'              (create-schema root ab opts fields)
        render-sym           (symbol (str "render-" name "-html"))
        attr-sym             `attrs#
        attr-form            (if-some [f (attributes-form tag schema')]
                               `(~f ~attr-sym)
                               `~attr-sym)
        children-schema      (get-in schema' [1 ::children] [:* :any])
        ?docstring           (:doc (schema' 1))
        var-meta             (cond-> {::attributes schema'
                                      ::children   children-schema}
                               (some? ?docstring) (assoc :doc ?docstring))]
    `(do
       ;;; 
       (defn ~render-sym
         {:malli/schema [:=> [:cat ~schema' ~children-schema] :any]}
         [~attr-sym children#]
         (let [~ab ~attr-form
               ~'<children> children#]
           ~compile-body))

       ;;; Chassis alias element
       (defmethod c/resolve-alias ~tag
         [_# props# children#]
         (~render-sym props# children#))
       
       (def ~name ~tag)
       (alter-meta! (resolve '~name) merge ~var-meta {:html.yeah/render '~render-sym})
       (var ~name))))
