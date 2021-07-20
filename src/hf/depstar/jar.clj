(ns hf.depstar.jar
  (:require [hf.depstar.uberjar :as uber])
  (:gen-class))

(defn run
  "Deprecated entry point for jar invocation via -X."
  [options]
  (println "DEPRECATED: hf.depstar.jar/run -- use hf.depstar/jar instead.")
  (uber/build-jar-as-main (merge {:jar-type :thin} options)))

(defn -main
  "Deprecated entry point for jar invocation via -M."
  [& args]
  (println "DEPRECATED: -M -m hf.depstar.jar -- use -X hf.depstar/jar instead.")
  (uber/build-jar-as-main (assoc (uber/parse-args args) :jar-type :thin)))
