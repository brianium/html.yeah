(ns html.yeah.attrs)

(defmulti option
  "An attribute option is a key added to the schema's option map. If an option key
  is defined for option, that key can be used in the option map of a schema. Values
  will be passed to the multimethod. forms is a list. it is a list of list forms. these list
  forms all assume they are threaded into a (->> attrs-map form-a .... form-z) structure.
  For example, the ::validate attribute option expects a function (fn [tag schema attrs]) and so
  the option implementation pre-pends `(~value ~tag ~schema) - assuming an attributes map will be threaded in."
  (fn [attr forms tag schema value] attr))

(defmethod option ::transform
  [_ forms tag _ value]
  (cons `(~value ~tag) forms))

(defmethod option :default [_ forms _ _ _]
  forms)

(defn options
  "Aggregates all prop-option forms, ensuring that validation happens first"
  [tag schema opts]
  (reduce (fn [forms [k v]]
            (if (some? v)
              (option k forms tag schema v)
              forms)) (list) opts))
