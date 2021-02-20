(ns issue-64
  (:gen-class))

(when (= "true" (System/getProperty "clojure.compiler.direct-linking"))
  (binding [*out* *err*]
    (println "We are direct linking!")))

(defn -main [& _])
