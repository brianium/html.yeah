(ns user
  (:require [clj-reload.core :as reload]
            [clj-kondo.hooks-api :as api]))

(reload/init
 {:dirs      ["src" "dev" "test"]
  :no-reload '#{user}})

(defn dev []
  (require 'dev)
  (in-ns 'dev))

(comment
  (load-file "resources/clj-kondo.exports/html/yeah/clj_kondo/yeah.clj")

  (require '[clj-kondo.yeah :refer [element]])

  (element
   {:node
    (api/parse-string
     (pr-str
      '(defelem let-button
         [:map {:keys [type]}
          [:type [:enum :submit :button]]]
         (let [as-string (name type)]
           [:button {:type as-string}
            (children)]))))})

  (do "test clj-kondo hooks"))
