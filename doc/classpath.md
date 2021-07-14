# Classpath

By default, `depstar` computes a classpath from the system and project `deps.edn` files (and, optionally, the user `deps.edn` file) and then walks that classpath to find resources to add to the JAR:

* For each directory on the classpath, the contents of that directory are copied (recursively) to the output JAR as individual files.
* If `:jar-type :thin` (via the `hf.depstar/jar` exec-fn), JAR files on the classpath are ignored, otherwise (`:jar-type :uber`, via the `hf.depstar/uberjar` exec-fn), each JAR file on the classpath is expanded and its contents are copied to the output JAR as individual files.
* Other types of files on the classpath are ignored (a warning is printed unless the file is on the excluded list, see [Exluding Files](excluding.md)).

By default, only the system and project `deps.edn` files are used (as if the `:repro true` option is provided). _This is intended to correspond to the CLI's `-Srepro` option that ignores the user `deps.edn` file._ If the `:repro false` option is provided instead, the user `deps.edn` file is also used.

If you need to adjust the computed classpath, based on aliases, you can supply a vector of aliases to the `:aliases` exec argument of `depstar`. This classpath is used for both the AOT compilation process and for the JAR building.

For example, you can add web assets into an uberjar by including an alias in your project `deps.edn`:

```clj
{:paths ["src"]
 :aliases {:webassets {:extra-paths ["public-html"]}}}
```

Then invoke `depstar` with the chosen aliases:

```bash
clojure -X:uberjar :jar MyProject.jar :aliases '[:webassets]'
# or:
clojure -X:depstar uberjar :jar MyProject.jar :aliases '[:webassets]'
```

You can also pass an explicit classpath into `depstar` and it will use that instead of the computed classpath for building the JAR:

```bash
clojure -X:uberjar :classpath '"'$(clojure -Spath -A:webassets)'"' :jar MyProject.jar
# or:
clojure -X:depstar uberjar :classpath '"'$(clojure -Spath -A:webassets)'"' :jar MyProject.jar
```

> Note: the `-Sdeps` argument to `clojure` only affects how the initial classpath is computed to run a program -- it cannot affect the classpath `depstar` itself computes from the `deps.edn` files. If you need to use `-Sdeps`, for example to specify alternate repos for dependencies, use the `:classpath` approach shown above.

When building a library JAR (not an uberjar), you can tell `depstar` to use only the `:paths` and
`:extra-paths` from the project basis instead of the classpath by using the `:paths-only true`
option (new in 2.0.206). This can be useful when you have `:local/root` and/or `:git/url`
dependencies and you don't want them considered.
