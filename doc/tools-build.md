# Usage with `tools.build`

[`tools.build`](https://clojure.org/guides/tools_build) is a recently released library that helps you write build scripts in Clojure. Whilst it includes basic functions for building JAR files, `depstar` offers more sophisticated functionality, with more control over how AOT compilation is performed and how the JAR files are built. For example, `depstar` handles more types of files when merging resources into the JAR (including concatenation of LICENSE files and full merging of log4j2 plugin cache files), lets you exclude files via regex from the JAR, accepts additional key/value pairs for the manifest file, and so on.

To use `depstar` as a library, in your `build.clj` script, your `:build` alias should look something like this:

```clojure
  :build {:deps {io.github.clojure/tools.build
                 {:tag "v0.1.3" :sha "688245e"
                  :exclusions [org.slf4j/slf4j-nop]}
                com.github.seancorfield/depstar
                {:tag "v2.1.253" :sha "a970a33"}}
          :ns-default build}
```

Since `depstar` relies on `tools.deps.alpha` and `tools.logging`, you need to exclude the no-op version of the slf4j logging library from `tools.build` to avoid a conflict.

> Note: the above assumes you have the prerelease version 1.10.3.905 of the Clojure CLI installed! Version 1.10.3.855 is the latest stable version as of `depstar` 2.1.253. See [Source Libs and Builds](https://clojure.org/news/2021/07/09/source-libs-builds) for details about the new functionality in this recent prerelease, which includes support for `:tag`/`:sha` source coordinates.

There are two approaches available for using `depstar` for build tasks:
* Use all of `tools.build` as usual, except use `depstar` for `jar` and `uber` tasks,
* Use `depstar`'s higher-level tasks for building JAR files instead.

## `tools.build`-compatible API

Your `build.clj` script will start like this:

```clojure
(ns build
  (:require [clojure.tools.build.api :as b]
            [hf.depstar.api :as d]))
```

With this approach, you use `tools.build` functions for everything except `jar` and `uber`, where you use `depstar`'s versions, that accept the exact same input parameters. For example:

```clojure
(defn jar [_]
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis basis
                :src-dirs ["src"]})
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  ;; replacement for b/jar:
  (d/jar {:class-dir class-dir
          :jar-file jar-file}))
```

`hf.depstar.api/jar` accepts `:class-dir`, `:jar-file`, and `:main`. It additionally accepts any of `depstar`'s options that can affect building a JAR file, such as `:aliases` (for calculating the project basis), `:exclude`, `:manifest`, `:paths-only`, `:repro` (to exclude consideration of the user `deps.edn` file), `:verbose`, etc.

Similarly for `uber`:

```clojure
  ;; replacement for b/uber:
  (d/uber {:class-dir class-dir
           :uber-file uber-file
           :basis basis})
```

`hf.depstar.api/uber` accepts `:basis`, `:class-dir`, `:uber-file`, and `:main`. It additionally accepts any of `depstar`'s options that can affect building an uberjar file, such as `:exclude`, `:manifest`, `:repro`, `:verbose`, etc.

_The example above comes from the [tools.build Guide](https://clojure.org/guides/tools_build)._

## Higher-level Tasks

Alternatively, you can use the `hf.depstar` tasks instead:
* `jar` -- the complete (library) JAR building process, including optional `pom.xml` creation/sync'ing, AOT compilation, etc,
* `uberjar` -- the complete (application) uberjar building process, including optional `pom.xml` creation/sync'ing, AOT compilation, etc,
* `aot` -- perform AOT compilation (to a `:target-dir` directory),
* `pom` -- create/sync `pom.xml`, optionally to a `:target-dir` directory,
* `build` -- generic JAR builder to be used with `aot` and/or `pom`.

In this case, your `build.clj` script will start like this:

```clojure
(ns build
  (:require [clojure.tools.build.api :as b]
            [hf.depstar :as depstar]))
```

Then you can either use `depstar/jar` or `depstar/uberjar` with a parameter hash map containing any/all of the options that `depstar` understands, or you can use `depstar/aot`, `depstar/pom`, and `depstar/build` in a threaded combination, passing in the parameter hash map:

```clojure
(defn jar [params]
  (depstar/jar (merge {:jar "MyLib.jar"} params)))

(defn uber-1 [params]
  (depstar/uberjar (merge {:jar "MyProject.jar"
                           :aot true
                           :main-class "project.core"
                           :sync-pom true}
                          params)))

(defn uber-2 [params]
  ;; ensure clean target directory:
  (b/delete {:path "target"})
  (-> (merge {:jar "MyProject.jar"
              :aot true
              :main-class "project.core"
              :sync-pom true
              :target-dir "target"}
             params})
      (depstar/aot)
      (depstar/pom)
      ;; build requires this as well:
      (assoc :jar-type :uber)
      (depstar/build)))
```

When the `aot` task is used, a `:target-dir` must be provided so that `build` knows where to find the freshly-compiled `.class` files. When `:target-dir` is provided, `pom` will write `pom.xml` into that directory, based on the source `pom.xml` if present. See [Target Directory](target.md) for more details.
