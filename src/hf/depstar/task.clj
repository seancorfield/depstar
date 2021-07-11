;; copyright (c) 2019-2021 sean corfield

(ns ^:no-doc hf.depstar.task
  "Utilities to allow parts of depstar to run as -X tasks."
  (:require [clojure.tools.deps.alpha :as t]
            [clojure.tools.logging :as logger]))

(set! *warn-on-reflection* true)

(comment
  (t/create-basis {})
  (t/create-basis {:aliases [:test-issue-5]})
  .)

(defn- preprocess-options
  "Given an options hash map, if any of the values are keywords, look them
  up as alias values from the full basis (including user `deps.edn`).

  :jar-type is the only option that is expected to have a keyword value
  and it is generally set automatically so we skip the lookup for that."
  [options]
  (let [kw-opts #{:compile-ns :jar-type} ; :compile-ns can be :all
        aliases (:aliases (t/create-basis {}))]
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

(defn options-and-basis
  "Given a raw options hash map, apply our defaults and preprocess the
  options (above), then use the options to calculate the project basis
  (above), and return a pair of the basis and the updated options."
  [options]
  (let [{:keys [aliases aot group-id jar-type jvm-opts paths-only repro]
         :as options}
        (preprocess-options
         (merge {:aliases  []
                 :jar-type :uber
                 :jvm-opts []
                 :pom-file "pom.xml"
                 :repro    true}
                (-> options
                    ;; these can be symbols or strings:
                    (update :jar        #(some-> % str))
                    (update :main-class #(some-> % str))
                    (update :target-dir #(some-> % str)))))]

    (when (and aot (= :thin jar-type))
      (logger/warn ":aot is not recommended for a 'thin' JAR!"))
    (when (and group-id (not (re-find #"\." (str group-id))))
      (logger/warn ":group-id should probably be a reverse domain name, not just" group-id))
    (when (and jvm-opts (not (sequential? jvm-opts)))
      (logger/warn ":jvm-opts should be a vector -- ignoring" jvm-opts))
    (when (and (not= :thin jar-type) paths-only)
      (logger/warn ":paths-only is ignored when building an uberjar"))

    ;; so that we can rely on :deps in the basis for the
    ;; sync pom operation, we add in any :extra-deps here:
    (let [{:keys [resolve-args] :as basis}
          (t/create-basis (cond-> {:aliases aliases}
                            repro (assoc :user nil)))]
      [(update basis :deps merge (:extra-deps resolve-args))
       options])))
