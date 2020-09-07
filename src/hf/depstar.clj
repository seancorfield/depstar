;; copyright (c) 2020 sean corfield, all rights reserved

(ns hf.depstar
  "Entry point for clojure -X options."
  (:require [hf.depstar.uberjar :as uber]))

(defn jar
  "Generic entry point for jar invocations.

  Can be used with `clojure -X`:

  In `:aliases`:
```clojure
      :jar {:extra-deps {seancorfield/depstar {:mvn/version ...}}
            :exec-fn hf.depstar/jar
            :exec-args {}}
```
  Then run:
```
      clojure -X:jar :jar MyProject.jar
```
  If the destination JAR file is fixed, it could be added to `:exec-args` in
  `deps.edn`:
```clojure
      :jar {:extra-deps {seancorfield/depstar {:mvn/version ...}}
            :exec-fn hf.depstar/jar
            :exec-args {:jar MyProject.jar}}
```
  `:jar` can be specified as a symbol or a string."
  [options]
  (uber/run (merge {:jar-type :thin} options)))

(defn uberjar
  "Generic entry point for uberjar invocations.

  Can be used with `clojure -X`:

  In `:aliases`:
```clojure
      :uberjar {:extra-deps {seancorfield/depstar {:mvn/version ...}}
                :exec-fn hf.depstar/uberjar
                :exec-args {}}
```
  Then run:
```
      clojure -X:uberjar :aot true :jar MyProject.jar :main-class project.core
```
  If any of the destination JAR file, main class, and/or AOT setting are fixed,
  they could be added to `:exec-args` in
  `deps.edn`:
```clojure
      :uberjar {:extra-deps {seancorfield/depstar {:mvn/version ...}}
                :exec-fn hf.depstar/uberjar
                :exec-args {:aot true
                            :jar MyProject.jar
                            :main-class project.core}}
```
  `:jar` can be specified as a symbol or a string."
  [options]
  (uber/run (merge {:jar-type :uber} options)))
