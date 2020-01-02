(ns hf.depstar.jar
  (:require [hf.depstar.uberjar :refer [help uber-main]])
  (:gen-class))

(defn -main
  [& [destination & args]]
  (when-not destination
    (help))
  (uber-main {:dest destination :jar :thin} args))
