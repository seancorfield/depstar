# `:main-class`

If you are building an uberjar, the manifest (`META-INF/MANIFEST.MF`) will declare the `Main-Class` (specified by the `:main-class` option, or `clojure.main` if omitted).

`depstar` does no AOT compilation by default -- use the `:aot true` option to enable AOT compilation (see below).

If you build an uberjar without `:main-class` (as in the `pom.xml` examples below), you can run the resulting file as follows:

```bash
clojure -X:uberjar :jar MyProject.jar
java -jar MyProject.jar -m project.core
```

If you build an uberjar with `:main-class` (and AOT compilation), you can run the resulting file as follows:

```bash
clojure -X:uberjar :aot true :jar MyProject.jar :main-class project.core
java -jar MyProject.jar
```
