;; copyright (c) 2020-2021 sean corfield, all rights reserved

(ns hf.depstar
  "Entry point for clojure -X options."
  (:require [hf.depstar.aot :as aot]
            [hf.depstar.pom :as pom]
            [hf.depstar.task :as task]
            [hf.depstar.uberjar :as uber]))

(defn aot
  "-X entry point for AOT compilation."
  [options]
  (let [[basis options] (task/options-and-basis options)]
    (aot/task* basis options)))

(defn jar
  "Generic entry point for jar invocations.

  Can be used with `clojure -X`:

  In `:aliases`:
```clojure
      :jar {:replace-deps {com.github.seancorfield/depstar {:mvn/version ...}}
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
      :jar {:replace-deps {com.github.seancorfield/depstar {:mvn/version ...}}
            :exec-fn hf.depstar/jar
            :exec-args {:jar MyProject.jar}}
```
  `:jar` can be specified as a symbol or a string."
  [options]
  (uber/build-jar-as-main (merge {:jar-type :thin} options)))

(defn pom
  "-X entry point for pom.xml creation/sync'ing."
  [options]
  (let [[basis options] (task/options-and-basis options)]
    (pom/task* basis options)))

(defn uberjar
  "Generic entry point for uberjar invocations.

  Can be used with `clojure -X`:

  In `:aliases`:
```clojure
      :uberjar {:replace-deps {com.github.seancorfield/depstar {:mvn/version ...}}
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
      :uberjar {:replace-deps {com.github.seancorfield/depstar {:mvn/version ...}}
                :exec-fn hf.depstar/uberjar
                :exec-args {:aot true
                            :jar MyProject.jar
                            :main-class project.core}}
```
  `:jar` can be specified as a symbol or a string."
  [options]
  (uber/build-jar-as-main (merge {:jar-type :uber} options)))
