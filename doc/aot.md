
## AOT Compilation

You can specify namespaces to be AOT-compiled using the `:compile-ns` exec argument. Namespaces specified by `:compile-ns` will be compiled even for thin JAR files, allowing you to build libraries that include `:gen-class`-generated `.class` files. By default, `depstar` creates a temporary folder for the class files and adds it to the classpath roots automatically so that all the classes produced by compilation are added to the JAR (see also **Target Directory** below). `:compile-ns` accepts a vector of namespace symbols or regular expressions to match namespaces. It will also accept the keyword `:all` instead of a vector and it will attempt to find all the Clojure namespaces in source files in directories on the classpath (which normally corresponds to your own project's source files, but will also include `:local/root` dependencies and `:git/url` dependencies, since those show up as directories on the classpath).

```bash
clojure -X:jar :jar MyProject.jar :compile-ns '[project.core]'
```

If you are building an uberjar, you can specify `:main-class` to identify the namespace that contains a `-main` function (and a `(:gen-class)` entry in the `ns` form) and then specify `:aot true` and `depstar` will default `:compile-ns` to a vector containing just that main namespace symbol.

The `:main-class` option also specifies the name of the class that is identified as the `Main-Class` in the manifest instead of the default (`clojure.main`).

```bash
# build the uberjar with AOT compilation
clojure -X:uberjar :jar MyProject.jar :aot true :main-class project.core
# Main-Class: project.core
java -jar MyProject.jar
```

This will compile the `project.core` namespace, **which must have a `(:gen-class)` clause in its `ns` form**, into a temporary folder (by default), add that temporary folder to the classpath (even when you specify an explicit classpath with `:classpath` -- see above), build the uberjar including everything on the classpath, with a manifest specifying `project.core` as the main class.

Remember that AOT compilation is transitive so, in addition to your `project.core` namespace with its `(:gen-class)`, this will also compile everything that `project.core` requires and include those `.class` files (as well as the sources). See the `:exclude` option for ways to exclude unwanted compiled `.class` files.

AOT compilation is performed in a subprocess so the JVM options in effect when you
run `depstar` do not affect the actual compilation of the code going into the JAR file.
By default, the compilation process uses the same classpath computed as described above,
using `:aliases` if provided. Sometimes, you might need the classpath for compilation to
be slightly different than the classpath used for building the JAR: in that case you can
use `:compile-aliases` (new in 2.0.211) to specify aliases that are used to compute a classpath just for
AOT compilation, and then the JAR is built using the regular classpath (above).
The `:jvm-opts` exec argument lets you pass a vector of strings into that subprocess to
provide JVM options that will apply during compilation, such as enabling direct linking:

```bash
clojure -X:uberjar :jar MyProject.jar \
    :aot true :main-class project.core \
    :jvm-opts '["-Dclojure.compiler.direct-linking=true"]'
```

That is equivalent to the following `:exec-args`:

```clojure
  :exec-args {:aot true :main-class project.core
              :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}
```

If `:compile-ns :all` produces a very long list of namespaces, compilation may fail
because a single `clojure -e` command line is created for the compilation. You can
specify `:compile-batch 10`, for example, to compile ten namespaces at a time if you
run into this problem.
