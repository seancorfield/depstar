(ns hf.depstar.jar
  (:require [hf.depstar.uberjar :refer [uber-main]])
  (:gen-class))

(defn -main
  [destination & args]
  (uber-main {:dest destination :jar :thin} args))
