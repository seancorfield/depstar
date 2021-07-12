
## Other Options

The Clojure CLI added an `-X` option (in 1.10.1.697) to execute a specific function and pass a hash map of arguments. See [Executing a function that takes a map](https://clojure.org/reference/deps_and_cli#_executing_a_function) in the Deps and CLI reference for details.

`depstar` supports this via `hf.depstar/jar` and `hf.depstar/uberjar` which both accept a hash map that mirrors the legacy command-line arguments (of `-M` invocations for `depstar` 1.0 -- although several of the `-X` exec arguments have no equivalent in the legacy command-line arguments):

* `:aliases` -- if specified, a vector of aliases to use while computing the classpath roots from the `deps.edn` files
* `:aot` -- if `true`, perform AOT compilation (like the legacy `-C` / `--compile` option)
* `:artifact-id` -- if specified, the symbol used for the `artifactId` field in `pom.xml` and `pom.properties` when building the JAR file; **your `pom.xml` file will be updated to match!**
* `:basis` -- if specified, the project basis to use instead of computing one for the project
* `:classpath` -- if specified, use this classpath instead of the (current) runtime classpath to build the JAR (like the legacy `-P` / `--classpath` option)
* `:compile-aliases` -- if specified, a vector of aliases to use while computing the classpath roots to use for AOT compilation; otherwise the same classpath is used for both AOT compilation as for JAR building; new in 2.0.211
* `:compile-fn` -- if specified, this function is used instead of `clojure.core/compile`: this is intended to support scenarios where some additional code needs to be run around AOT compilation (such as when working with cljfx)
* `:compile-ns` -- if specified, a vector of symbols and regexes to match namespaces to compile, and whose `.class` files to include in the JAR file; may also be the keyword `:all` as a shorthand for a vector of all namespaces in source code directories found on the classpath
* `:debug-clash` -- if `true`, print warnings about clashing jar items (and what `depstar` did about them; like the legacy `-D` / `--debug-clash` option)
* `:delete-on-exit` -- if `true`, register any temporary files/directories for deletion when `depstar` exits, instead of just letting the O/S clean up "eventually".
* `:exclude` -- if specified, should be a vector of strings to use as regex patterns for excluding files from the JAR (like the legacy `-X` / `--exclude` option)
* `:group-id` -- if specified, the symbol used for the `groupId` field in `pom.xml` and `pom.properties` when building the JAR file (this should generally be a reverse domain name); **your `pom.xml` file will be updated to match!**
* `:jar` -- the name of the destination JAR file (may need to be a quoted string if the path/name is not valid as a Clojure symbol; like the legacy `-J` / `--jar` option)
* `:jar-type` -- can be `:thin` or `:uber` -- defaults to `:thin` for `hf.depstar/jar` and to `:uber` for `hf.depstar/uberjar` (and can therefore be omitted in most cases)
* `:jvm-opts` -- an optional vector of JVM option strings that should be passed to the `java` subprocess that performs AOT compilation
* `:main-class` -- the name of the main class for an uberjar (can be specified as a Clojure symbol or a quoted string; like the legacy `-m` / `--main` option; used as the main namespace to compile if `:aot` is `true`)
* `:manifest` -- an optional hash map of additional properties to add to `MANIFEST.MF`, e.g., `:manifest {:class-path "/path/to/some.jar"}` will add the line `Class-Path: /path/to/some.jar` that file
* `:no-pom` -- if `true`, ignore the `pom.xml` file (like the legacy `-n` / `--no-pom` option)
* `:paths-only` -- if `true`, only use `:paths` and `:extra-paths` from the project basis (and do not treat `:local/root` and `:git/url` as providing source dependencies); new in 2.0.206
* `:pom-file` -- if specified, should be a string that identifies the `pom.xml` file to use (an absolute or relative path)
* `:repro` -- defaults to `true`, which excludes the user `deps.edn` from consideration; specify `:repro false` if you want the user `deps.edn` to be included when computing the project basis and classpath roots
* `:sync-pom` -- if `true`, will run the equivalent of `clojure -Spom` to create or update your `pom.xml` file prior to building the JAR file
* `:target-dir` -- if specified, a folder that `depstar` should generate files into (instead of just using temporary folders): the `pom.xml`, `classes` folder (from AOT), and `.jar` file will be written to this folder and it will be left in place after `depstar` exits (unlike the temporary folders)
* `:verbose` -- if `true`, be verbose about what goes into the JAR file (like the legacy `-v` / `--verbose` option)
* `:version` -- if specified, the symbol used for the `version` field in `pom.xml` and `pom.properties` when building the JAR file (and also for the VCS `tag` field if matches the current `version` field with a prefix of `v`); **your `pom.xml` file will be updated to match!**

You can make this shorter by adding `:exec-fn` to your alias with some of the arguments defaulted since, for a given project, they will likely be fixed values:

```clojure
  ;; a new :uberjar alias to build a project-specific JAR file:
  :uberjar {:replace-deps {com.github.seancorfield/depstar {:mvn/version "2.1.253"}}
            :exec-fn hf.depstar/uberjar
            :exec-args {:jar "MyProject.jar"
                        :aot true
                        :main-class project.core}}
```

Now you can just run:

```bash
clojure -X:uberjar
```

You can choose to override those on the command-line if you wish:

```bash
clojure -X:uberjar :jar '"/tmp/MyTempProject.jar"'
```

For convenience, you can specify the JAR file as a Clojure symbol (e.g., `MyProject.jar` above) if it could legally be one and `depstar` will convert it to a string for you. Per the CLI docs, you would normally specify string arguments as `"..."` values, that need to be wrapped in `'...'` because of shell syntax (so the quoted string is passed correctly into `clojure`).

As of 2.0.206, `depstar` allows the value of any exec argument to be a keyword
which is then looked up as an alias in the full project basis (including your
user `deps.edn` file). For example:

```clojure
  ;; using an alias as a value for :jvm-opts:
  :uberjar
  {:replace-deps
   {com.github.seancorfield/depstar {:mvn/version "2.1.253"}}
            :exec-fn hf.depstar/uberjar
            :exec-args {:jar "MyProject.jar"
                        :aot true
                        :jvm-opts :direct-linking
                        :main-class project.core}}
  :direct-linking ["-Dclojure.compiler.direct-linking=true"]
```

## Debugging `depstar` Behavior

If you are seeing unexpected results with `depstar` and the `:verbose true` option doesn't provide enough information, you can enable "debug mode" with either `DEPSTAR_DEBUG=true` as an environment variable or `depstar.debug=true` as a JVM property. Be warned: this is **very verbose**!
