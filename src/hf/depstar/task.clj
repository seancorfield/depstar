;; copyright (c) 2019-2021 sean corfield

(ns ^:no-doc hf.depstar.task
  "Utilities to allow parts of depstar to run as -X tasks."
  (:require [clojure.tools.deps.alpha :as t]
            [clojure.tools.logging :as logger]))

(set! *warn-on-reflection* true)

(defn- read-edn-files
  "Given as options map, use tools.deps.alpha to read and merge the
  applicable `deps.edn` files."
  [{:keys [repro] :or {repro true}}]
  (let [{:keys [root-edn user-edn project-edn]} (t/find-edn-maps)]
    (t/merge-edns (if repro
                    [root-edn project-edn]
                    [root-edn user-edn project-edn]))))

(defn calc-project-basis
  "Given the options map, use tools.deps.alpha to read and merge the
  applicable `deps.edn` files, combine the specified aliases, calculate
  the project basis (which will resolve/download dependencies), and
  return the calculated project basis."
  [{:keys [aliases] :or {aliases []} :as options}]
  (let [deps     (read-edn-files options)
        combined (t/combine-aliases deps aliases)]
    ;; this could be cleaner, by only selecting the "relevant"
    ;; keys from combined for each of these three uses (but
    ;; I'm waiting for the dust to settle on a possible higher-
    ;; level API appearing in tools.deps.alpha itself):
    (t/calc-basis (t/tool deps combined)
                  {:resolve-args   combined
                   :classpath-args combined})))

(comment
  (calc-project-basis {})
  (calc-project-basis {:aliases [:trial]}))

(defn preprocess-options
  "Given an options hash map, if any of the values are keywords, look them
  up as alias values from the full basis (including user `deps.edn`).

  :jar-type is the only option that is expected to have a keyword value
  and it is generally set automatically so we skip the lookup for that."
  [options]
  (let [kw-opts #{:compile-ns :jar-type} ; :compile-ns can be :all
        aliases (:aliases (read-edn-files {:repro false}))]
    (reduce-kv (fn [opts k v]
                 (if (and (not (contains? kw-opts k)) (keyword? v))
                   (if (contains? aliases v)
                     (assoc opts k (get aliases v))
                     (do
                       (logger/warn k "has value" v "which is an unknown alias")
                       opts))
                   opts))
               options
               options)))
