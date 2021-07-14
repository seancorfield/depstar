# Getting Started

> Note: you must have at least version 1.10.1.727 of the Clojure CLI installed! Version 1.10.3.855 is the latest stable version as of `depstar` 2.1.253. See [Clojure Tools Releases](https://clojure.org/releases/tools) for details about the functionality in recent CLI releases.

Add `depstar` via one or more aliases in your project `deps.edn` or user-level `deps.edn` (in `~/.clojure/` or `~/.config/clojure/`):

```clj
{
 :aliases {
  ;; build an uberjar (application) with AOT compilation by default:
  :uberjar {:replace-deps {com.github.seancorfield/depstar {:mvn/version "2.1.253"}}
            :exec-fn hf.depstar/uberjar
            :exec-args {:aot true}}
  ;; build a jar (library):
  :jar {:replace-deps {com.github.seancorfield/depstar {:mvn/version "2.1.253"}}
        :exec-fn hf.depstar/jar
        :exec-args {}}
 }
}
```

Create an uberjar by invoking `depstar` with the desired jar name:

```bash
clojure -X:uberjar :jar MyProject.jar
```

An uberjar created by that command can be run as follows:

```bash
java -cp MyProject.jar clojure.main -m project.core
```

If you want to be able to use `java -jar` to run your uberjar, you'll need to specify the main class (namespace) in the uberjar and you'll probably want to AOT compile your main namespace. See the [`:main-class`](main-class.md) and [AOT Compilation](aot.md) sections for more detail.

Create a (library) jar by invoking `depstar` with the desired jar name:

```bash
clojure -X:jar :jar MyLib.jar
```

> Note: `depstar` assumes that directories it finds on the classpath contain the source of your library and `.jar` files are ignored (for an uberjar, everything on the classpath is included). If you have `:local/root` and/or `:git/url` dependencies in your library, `depstar` will see those as directories and will include them in your (library) JAR. You can either use the `:exclude` option to omit such code from your JAR or you can use the `:paths-only true` option (new in 2.0.206) which tells `depstar` to use `:paths` and `:extra-paths` from the project basis (instead of using the classpath). You may well have good reasons for including such dependencies as source code in your library JAR, e.g., those dependencies aren't published somewhere your library's users could depend on.

> Note: if you have a `user.clj` file on your default classpath -- in any folders that are in `:paths` in your `deps.edn` -- Clojure will attempt to load that at startup, before running `depstar`. In such cases, you will likely need to add `:replace-paths []` along with `:replace-deps` in your aliases for `depstar`.

If you want to deploy a library to Clojars (or Maven Central), you're going to also need a `pom.xml` file -- see below.
For deployment to Clojars, please read the [Clojars Verified Group Names policy](https://github.com/clojars/clojars-web/wiki/Verified-Group-Names).

If you want to see all of the files that are being copied into the JAR file, add `:verbose true` after the JAR filename.
