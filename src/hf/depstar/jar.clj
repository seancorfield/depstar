(ns hf.depstar.jar
  (:require [hf.depstar.uberjar :refer [run]]))

(defn -main
  [destination & args]
  (uber-main {:dest destination :jar :thin} args))
