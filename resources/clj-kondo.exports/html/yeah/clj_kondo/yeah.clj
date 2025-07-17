(ns clj-kondo.yeah
  (:require [clj-kondo.hooks-api :as api]))

(def destruct-keys
  #{"as" "keys" "or"})

(defn element
  [{:keys [node]}]
  (let [args         (rest (:children node))
        [name schema & body] args
        opts         (fnext (:children schema))
        bindings     (seq
                      (reduce (fn [l [k v]]
                                (let [sexpr (api/sexpr k)]
                                  (if (destruct-keys (clojure.core/name sexpr))
                                    (cons k (cons v l))
                                    l))) (list) (partition 2 (:children opts))))
        new-node     (api/list-node
                      (list*
                       (api/token-node 'defn)
                       name
                       (api/vector-node (if (some? bindings)
                                          [(api/map-node bindings)]
                                          []))
                       body))]
    {:node new-node}))
