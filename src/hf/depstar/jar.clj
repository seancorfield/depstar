(ns hf.depstar.jar
  (:require [hf.depstar.uberjar :refer [run]]))

(defn -main
  [destination & [verbose]]
  (when verbose
    (when-not (#{"-v" "--verbose"} verbose)
      (throw (ex-info "Expected -v or --verbose option" {:option verbose}))))
  (run {:dest destination :jar :thin :verbose verbose}))
