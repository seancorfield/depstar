;; copyright (c) 2020-2021 sean corfield, all rights reserved

(ns hf.depstar
  "Entry point for clojure -X options."
  (:require [hf.depstar.aot :as aot]
            [hf.depstar.pom :as pom]
            [hf.depstar.task :as task]
            [hf.depstar.uberjar :as uber]))

(defn aot
  "-X entry point for AOT compilation.

  Inputs:
  * :aot             true   -- perform AOT for :main-class
  * :classpath       str    -- override the default computed classpath
  * :compile-aliases [kws]  -- override :aliases just for compilation
  * :compile-batch   int    -- size of compilation batch
                               (defaults to the size of compile-ns)
  * :compile-fn      sym    -- fully-qualified fn to use instead of compile
  * :compile-ns      [syms] -- namespaces to compile (may be :all)
                               (defaults to :main-class if :aot true)
  * :delete-on-exit  true   -- delete temp files/folder on JVM exit
                               (otherwise it's up to the O/S)
  * :jar-type :jar or :uber -- specify whether to build lib or app JAR
                               (defaults to :uber)
  * :main-class      sym    -- name of main class/namespace
  * :paths-only      true   -- if :jar-type is :jar use just :paths
                               (so this skips :local/root and :git/url)
  * :target-dir      str    -- where to put the `classes` folder
                               (by default a temp folder is used)

  Outputs:
  * :classpath-roots [strs] -- the classpath with the `classes` folder
                               added, for possible use downstream."
  [options]
  (let [[basis options] (task/options-and-basis options)]
    (when-not (:target-dir options)
      (throw (ex-info "Standalone AOT compilation requires :target-dir" {})))
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
  (uber/build-jar-as-exec (merge {:jar-type :thin} options)))

(defn pom
  "-X entry point for pom.xml creation/sync'ing.

  Inputs (all optional):
  * :artifact-id str  -- <artifactId> to write to pom.xml
  * :group-id    str  -- <groupId> to write to pom.xml
  * :no-pom      true -- do not read/update group/artifact/version
  * :pom-file    str  -- override default pom.xml path
  * :sync-pom    true -- sync deps to pom.xml, create if missing
  * :target-dir  str  -- override default pom.xml generation path
                         (implies :sync-pom true)
  * :version     str  -- <version> to write to pom.xml

  For ease of use, :artifact-id, :group-id, and :target-id can
  be symbols instead of strings, if that would be a legal symbol.

  Outputs:
  * :artifact-id str -- if not no-pom, <artifactId> from pom.xml
  * :group-id    str -- if not no-pom, <groupId> from pom.xml
  * :version     str -- if not no-pom, <version> from pom.xml"
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
  (uber/build-jar-as-exec (merge {:jar-type :uber} options)))
