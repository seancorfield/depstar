(ns hf.depstar.jar
  (:require [hf.depstar.uberjar :as uber])
  (:gen-class))

(defn run
  "Legacy entry point for jar invocations.

  Can be used with `clojure -X`:

  In `:aliases`:
```clojure
      :depstar {:extra-deps {seancorfield/depstar {:mvn/version ...}}}
      :jar     {:fn hf.depstar.jar/run
                :args {}}
```
  Then run:
```
      clojure -R:depstar -X:jar :jar MyProject.jar
```
  If the destination JAR file is fixed, it could be added to `:args` in
  `deps.edn`:
```clojure
      :depstar {:extra-deps {seancorfield/depstar {:mvn/version ...}}}
      :jar     {:fn hf.depstar.jar/run
                :args {:jar MyProject.jar}}
```
  `:jar` can be specified as a symbol or a string."
  [options]
  (uber/run (merge {:jar-type :thin} options)))

(defn -main
  [& args]
  (uber/run (assoc (uber/parse-args args) :jar-type :thin)))
