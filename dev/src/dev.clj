(ns dev
  (:require [clojure.pprint :refer [pprint]]
            [dev.onionpancakes.chassis.core :as c]
            [html.yeah :as yeah :refer [defelem children]]
            [html.yeah.attrs :as attrs]
            [malli.generator :as mg]
            [malli.dev :as dev]))

;;; Class constants for Tailwind JIT

(def colors
  {:neutral "btn-neutral"
   :primary "btn-primary"
   :secondary "btn-secondary"
   :accent "btn-accent"
   :info "btn-info"
   :success "btn-success"
   :warning "btn-warning"
   :error "btn-error"})

(def Color (into [:enum] (keys colors)))

(def styles
  {:outline "btn-outline"
   :dash "btn-dash"
   :soft "btn-soft"
   :ghost "btn-ghost"
   :link  "btn-link"})

(def Style (into [:enum] (keys styles)))

(def behaviors
  {:active   "btn-active"
   :disabled "btn-disabled"})

(def Behavior (into [:enum] (keys behaviors)))

(def sizes
  {:xs "btn-xs"
   :sm "btn-sm"
   :md "btn-md"
   :lg "btn-lg"
   :xl "btn-xl"})

(def Size (into [:enum] (keys sizes)))

(def modifiers
  {:wide "btn-wide"
   :block "btn-block"
   :square "btn-square"
   :circle "btn-circle"})

(def Modifier (into [:enum] (keys modifiers)))

;;; Add support for :doc+ which appends a pretty printed
;;; schema to an element's inline documentation.

(defmethod yeah/property :doc+
  [_ schema doc]
  (let [formatted (with-out-str (pprint schema))]
    (assoc-in schema [1 :doc]
              (-> "Description:\n"
                  (str "================\n")
                  (str doc)
                  (str "\nAttributes:\n")
                  (str "================\n")
                  (str formatted)))))

(defelem daisy-button
  [:maybe {:doc+ "A robust and correct DaisyUI button"
           :keys [color style behavior size modifier]
           :or   {color :primary}}
   [:map
    [:color {:optional true} Color]
    [:style {:optional true} Style]
    [:behavior {:optional true} Behavior]
    [:size {:optional true} Size]
    [:modifier {:optional true} Modifier]]]
  [:button {:class [(colors color)
                    (styles style)
                    (behaviors behavior)
                    (sizes size)
                    (modifiers modifier)]}
   (children)])

(defn generate
  "We can make custom fun with the fact that a schema is attached to the element"
  [symbol & children]
  (when-some [s (yeah/attributes symbol)]
    [@(resolve symbol) (mg/generate s) children]))

(comment
  ;;; Describe the entire element
  (yeah/describe 'daisy-button)

  ;;; Get the attribute schema for an element
  (yeah/attributes 'daisy-button)

  ;;; Generate a sample element
  (c/html
   (generate 'daisy-button "Click Me"))

  ;;; Render this invalid button and check the error output in the REPL
  (c/html
   [daisy-button {:color :mauve} "Click Me"])

  (do "not make me create a new line just to evaluate my comments"))

;;; Try rendering an invalid daisy-button with malli.dev running

(dev/stop!)

(dev/start! {:ns *ns*}) ;;; Normally won't qualify namespace, but I don't want tests getting pulled in


