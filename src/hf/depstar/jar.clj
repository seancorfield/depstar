(ns hf.depstar.jar
  (:require [hf.depstar.uberjar :refer [run]]))

(defn -main
  [destination & args]
  (let [verbose (some #(#{"-v" "--verbose"} %) args)
        no-pom  (some #(#{"-n" "--no-pom"}  %) args)]
    (run {:dest destination :jar :thin
          :verbose verbose :no-pom no-pom})))
