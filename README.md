# depstar

<img src="./depstar_logo.png" />

Builds JARs, uberjars, does AOT, manifest generation, etc for `deps.edn` projects (forked from [healthfinch/depstar](https://github.com/healthfinch/depstar) and enhanced).

For support, help, general questions, use the [#depstar channel on the Clojurians Slack](https://app.slack.com/client/T03RZGPFR/C01AK5V8HPT).

# Basic Usage

> Note: these instructions assume you have at least version 1.10.1.727 of the Clojure CLI installed.

Install this tool to an alias in your project `deps.edn` or user-level `deps.edn` (in `~/.clojure/` or `~/.config/clojure/`):

```clj
{
  :aliases {:depstar
              {:replace-deps
                 {seancorfield/depstar {:mvn/version "2.0.171"}}
               :ns-default hf.depstar
               :exec-args {}}}
}
```

Create a (library) jar by invoking `depstar` with the desired jar name:

```bash
clojure -X:depstar jar :jar MyLib.jar
```

If you want to deploy a library to Clojars (or Maven Central), you're going to also need a `pom.xml` file -- see below.

Create an uberjar by invoking `depstar` with the desired jar name:

```bash
clojure -X:depstar uberjar :jar MyProject.jar
```

An uberjar created by that command can be run as follows:

```bash
java -cp MyProject.jar clojure.main -m project.core
```

If you want to be able to use `java -jar` to run your uberjar, you'll need to specify the main class (namespace) in the uberjar and you'll probably want to AOT compile your main namespace. See the sections below for more information about both of those.

If you want to see all of the files that are being copied into the JAR file, add `:verbose true` after the JAR filename.

## Classpath

`depstar` computes a classpath from the system and project `deps.edn` files (and, optionally, the user `deps.edn` file) and then walks that classpath to find resources to add to the JAR:

* For each directory on the classpath, the contents of that directory are copied (recursively) to the output JAR as individual files.
* If `:jar-type :thin` (via the `hf.depstar/jar` exec-fn), JAR files on the classpath are ignored, otherwise (`:jar-type :uber`, via the `hf.depstar/uberjar` exec-fn), each JAR file on the classpath is expanded and its contents are copied to the output JAR as individual files.
* Other types of files on the classpath are ignored (a warning is printed unless the file is on the excluded list, see below).

By default, only the system and project `deps.edn` files are used (as if the `:repro true` option is provided). _This is intended to correspond to the CLI's `-Srepro` option that ignores the user `deps.edn` file._ If the `:repro false` option is provided instead, the user `deps.edn` file is also used.

If you need to adjust the computed classpath, based on aliases, you can supply a vector of aliases to the `:aliases` exec argument of `depstar`.

For example, you can add web assets into an uberjar by including an alias in your project `deps.edn`:

```clj
{:paths ["src"]
 :aliases {:webassets {:extra-paths ["public-html"]}}}
```

Then invoke `depstar` with the chosen aliases:

```bash
clojure -X:depstar uberjar :jar MyProject.jar :aliases '[:webassets]'
```

You can also pass an explicit classpath into `depstar` and it will use that instead of the computed classpath for building the JAR:

```bash
clojure -X:depstar uberjar :classpath "$(clojure -Spath -A:webassets)" :jar MyProject.jar
```

> Note: the `-Sdeps` argument to `clojure` only affects how the initial classpath is computed to run a program -- it cannot affect the classpath `depstar` itself computes from the `deps.edn` files. If you need to use `-Sdeps`, for example to specify alternate repos for dependencies, use the `:classpath` approach shown above.

## `:main-class`

If you are building an uberjar, the manifest (`META-INF/MANIFEST.MF`) will declare the `Main-Class` (specified by the `:main-class` option, or `clojure.main` if omitted).

`depstar` does no AOT compilation by default -- use the `:aot true` option to enable AOT compilation (see below).

If you build an uberjar without `:main-class` (as in the `pom.xml` examples below), you can run the resulting file as follows:

```bash
clojure -X:depstar uberjar :jar MyProject.jar
java -jar MyProject.jar -m project.core
```

If you build an uberjar with `:main-class` (and AOT compilation), you can run the resulting file as follows:

```bash
clojure -X:depstar uberjar :aot true :jar MyProject.jar :main-class project.core
java -jar MyProject.jar
```

## AOT Compilation

You can specify namespaces to be AOT-compiled using the `:compile-ns` exec argument. Namespaces specified by `:compile-ns` will be compiled even for thin JAR files, allowing you to build libraries that include `:gen-class`-generated `.class` files. `depstar` creates a temporary folder for the class files and adds it to the classpath roots automatically so that all the classes produced by compilation are added to the JAR. `:compile-ns` accepts a vector of namespace symbols (not regular expressions). It will also accept the keyword `:all` instead of a vector and it will attempt to find all the Clojure namespaces in source files in directories on the classpath (which normally corresponds to your own project's source files, but will also include `:local/root` dependencies and `:git/url` dependencies, since those show up as directories on the classpath).

```bash
clojure -X:depstar jar :jar MyProject.jar :compile-ns '[project.core]'
```

If you are building an uberjar, you can specify `:main-class` to identify the namespace that contains a `-main` function (and a `(:gen-class)` entry in the `ns` form) and then specify `:aot true` and `depstar` will default `:compile-ns` to a vector containing just that main namespace.

The `:main-class` option also specifies the name of the class that is identified as the `Main-Class` in the manifest instead of the default (`clojure.main`).

```bash
# build the uberjar with AOT compilation
clojure -X:depstar uberjar :jar MyProject.jar :aot true :main-class project.core
# Main-Class: project.core
java -jar MyProject.jar
```

This will compile the `project.core` namespace, **which must have a `(:gen-class)` clause in its `ns` form**, into a temporary folder, add that temporary folder to the classpath (even when you specify an explicit classpath with `:classpath` -- see above), build the uberjar including everything on the classpath, with a manifest specifying `project.core` as the main class.

Remember that AOT compilation is transitive so, in addition to your `project.core` namespace with its `(:gen-class)`, this will also compile everything that `project.core` requires and include those `.class` files (as well as the sources). See the `:exclude` option for ways to exclude unwanted compiled `.class` files.

## `pom.xml`

If you are creating a library and intend to deploy it to Clojars or a similar repository, you will need a `pom.xml` file.

If there is a `pom.xml` file in the current directory, `depstar` will attempt to read it and figure out the **group ID**, **artifact ID**, and **version** of the project. It will use that information to generate `pom.properties` in the JAR file, as well as copying that `pom.xml` file into the JAR file. You can specify `:pom-file` as an exec argument if you want to use a different pom file.

When generating `pom.properties`, `depstar` will attempt to run `git rev-parse HEAD` _in the same directory as the `pom.xml` file_ and will add `revision=<SHA>` to the list of properties if successful.

You can also specify the `:group-id`, `:artifact-id`, and/or `:version` as exec arguments and those values will override what is in the `pom.xml` file. **The `pom.xml` file will be updated to reflect those values.** If the `pom.xml` file contains a VCS `<tag>..</tag>` that matches the version, with any optional prefix, it will also be updated (so `<version>` and `<tag>` will stay in sync).

You can suppress the consumption of the `pom.xml` file with the `:no-pom true` option.

You can generate a minimal `pom.xml` file using the `clojure -Spom` command (and that will also update the dependencies in an existing `pom.xml` based on `deps.edn`). `depstar` can run this for you, using the same computed project basis that it uses for building the JAR file: specify the `:sync-pom true` exec argument to perform this step:

* If no `pom.xml` file exists (where `:pom-file` specifies or else in the current directory), you will also need to specify `:group-id`, `:artifact-id`, and `:version`, and a minimal pom file will be created.
* If a `pom.xml` file already exists (per `:pom-file` or in the current directory), it will be updated to reflect the latest dependencies from the project basis, and any `:group-id`/`:artifact-id` pair and/or `:version` supplied as exec arguments.

If you build an uberjar with a `pom.xml` file present and do not specify `:no-pom true`, you can run the resulting file as follows:

```bash
# if pom.xml file is already present:
# build the uberjar without AOT compilation
clojure -X:depstar uberjar :jar MyProject.jar

# else ask depstar to create it for you:
# build the uberjar without AOT compilation
clojure -X:depstar uberjar :sync-pom true \
        :group-id myname :artifact-id myproject \
        :version '"1.2.3"' :jar MyProject.jar

# Main-Class: clojure.main
java -jar MyProject.jar -m project.core
```

See the `:main-class` option above if you want `java -jar` to run your main function by default instead of requiring the `-m` option.

You can use the `:pom-file` exec argument to specify a path to the `pom.xml` file if it is not in the current directory.

## Excluding Files

By default, the following files are excluded from the output JAR:

* `project.clj`
* `.keep`
* `**/*.pom`
* `module-info.class`
* `META-INF/**/*.MF`, also `*.SF`, `*.RSA`, and `*.DSA`
* `META-INF/INDEX.LIST`, `META-INF/DEPENDENCIES`, optionally with `.txt` suffix

In addition, `depstar` accepts an `:exclude` option: a vector of strings to use as regular expressions to match other files to be excluded. `re-matches` is used so these should be a _complete match for the full relative path and filename_. For example, if you wanted to exclude `clojure.core.specs.alpha` code from your JAR, you would specify `:exclude '"clojure/core/specs/alpha.*"'` -- note `.*` at the end so it matches the entire filename.

## Other Options

The Clojure CLI added an `-X` option (in 1.10.1.697) to execute a specific function and pass a hash map of arguments. See [Executing a function that takes a map](https://clojure.org/reference/deps_and_cli#_executing_a_function) in the Deps and CLI reference for details.

`depstar` supports this via `hf.depstar/jar` and `hf.depstar/uberjar` which both accept a hash map that mirrors the legacy command-line arguments (of `-M` invocations for `depstar` 1.0 -- although several of the `-X` exec arguments have no equivalent in the legacy command-line arguments):

* `:aliases` -- if specified, a vector of aliases to use while computing the classpath roots from the `deps.edn` files
* `:aot` -- if `true`, perform AOT compilation (like the legacy `-C` / `--compile` option)
* `:artifact-id` -- if specified, the symbol used for the `artifactId` field in `pom.xml` and `pom.properties` when building the JAR file; **your `pom.xml` file will be updated to match!**
* `:classpath` -- if specified, use this classpath instead of the (current) runtime classpath to build the JAR (like the legacy `-P` / `--classpath` option)
* `:compile-ns` -- if specified, a vector of namespaces to compile and whose `.class` files to include in the JAR file; may also be the keyword `:all` as a shorthand for a vector of all namespaces in source code directories found on the classpath
* `:debug-clash` -- if `true`, print warnings about clashing jar items (and what `depstar` did about them; like the legacy `-D` / `--debug-clash` option)
* `:exclude` -- if specified, should be a vector of strings to use as regex patterns for excluding files from the JAR (like the legacy `-X` / `--exclude` option)
* `:group-id` -- if specified, the symbol used for the `groupId` field in `pom.xml` and `pom.properties` when building the JAR file; **your `pom.xml` file will be updated to match!**
* `:jar` -- the name of the destination JAR file (may need to be a quoted string if the path/name is not valid as a Clojure symbol; like the legacy `-J` / `--jar` option)
* `:jar-type` -- can be `:thin` or `:uber` -- defaults to `:thin` for `hf.depstar/jar` and to `:uber` for `hf.depstar/uberjar` (and can therefore be omitted in most cases)
* `:main-class` -- the name of the main class for an uberjar (can be specified as a Clojure symbol or a quoted string; like the legacy `-m` / `--main` option; used as the main namespace to compile if `:aot` is `true`)
* `:no-pom` -- if `true`, ignore the `pom.xml` file (like the legacy `-n` / `--no-pom` option)
* `:pom-file` -- if specified, should be a string that identifies the `pom.xml` file to use (an absolute or relative path)
* `:repro` -- defaults to `true`, which excludes the user `deps.edn` from consideration; specify `:repro false` if you want the user `deps.edn` to be included when computing the project basis and classpath roots
* `:sync-pom` -- if `true`, will run the equivalent of `clojure -Spom` to create or update your `pom.xml` file prior to building the JAR file
* `:verbose` -- if `true`, be verbose about what goes into the JAR file (like the legacy `-v` / `--verbose` option)
* `:version` -- if specified, the symbol used for the `version` field in `pom.xml` and `pom.properties` when building the JAR file (and also for the VCS `tag` field if matches the current `version` field with a prefix of `v`); **your `pom.xml` file will be updated to match!**

You can make this shorter by adding `:exec-fn` to your alias with some of the arguments defaulted since, for a given project, they will likely be fixed values:

```clojure
  ;; a new :uberjar alias to build a project-specific JAR file:
  :uberjar {:replace-deps {seancorfield/depstar {:mvn/version "2.0.171"}}
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

## Debugging `depstar` Behavior

If you are seeing unexpected results with `depstar` and the `:verbose true` option doesn't provide enough information, you can enable "debug mode" with either `DEPSTAR_DEBUG=true` as an environment variable or `depstar.debug=true` as a JVM property. Be warned: this is **very verbose**!

# Deploying a Library

After you've generated your JAR file as above with a `pom.xml` file, you can use the `mvn` command below to deploy to Clojars (or other Maven-like repositories) -- or you could use [`deps-deploy`](https://github.com/slipset/deps-deploy) -- see below.

## Deploying with Maven

```bash
mvn deploy:deploy-file -Dfile=MyProject.jar -DpomFile=pom.xml -DrepositoryId=clojars -Durl=https://clojars.org/repo/
```

This assumes that you have credentials for your chosen repository in your `~/.m2/settings.xml` file. It should look like this (with your username and **token**):

```xml
<settings>
  <servers>
    <server>
      <id>clojars</id>
      <username>someperson</username>
      <password>topsecret</password>
    </server>
  </servers>
</settings>
```

If you only want to install the artifact locally (for use in local development, similar to `lein install`), you can use the following `mvn` command:

```bash
mvn install:install-file -Dfile=MyProject.jar -DpomFile=pom.xml
```

After that you can require the dependency coordinates as usual, using the **group ID**, **artifact ID**, and **version** that you had setup in the `pom.xml` file.

## Deploying with `deps-deploy`

As noted above, you could also use `deps-deploy` to deploy your JAR file to Clojars.
Add the following alias to your `deps.edn` file:

```clojure
    ;; version 0.1.5 was the most recent as of 2021-01-29:
    :deploy {:replace-deps {slipset/deps-deploy {:mvn/version "RELEASE"}}
             :exec-fn deps-deploy.deps-deploy/deploy
             :exec-args {:installer :remote :artifact "MyProject.jar"}}
```

Then:


```bash
clojure -X:deploy
```

This expects your Clojars username to be in the `CLOJARS_USERNAME` environment variable and your Clojars **token** to be in the `CLOJARS_PASSWORD` environment variable.

# Releases

This project follows the version scheme MAJOR.MINOR.COMMITS where MAJOR and MINOR provide some relative indication of the size of the change, but do not follow semantic versioning. In general, all changes endeavor to be non-breaking (by moving to new names rather than by breaking existing names). COMMITS is an ever-increasing counter of commits since the beginning of this repository.

Latest stable release: 2.0.171

# License

The use and distribution terms for this software are covered by the
[Eclipse Public License 2.0](https://www.eclipse.org/org/documents/epl-2.0/EPL-2.0.html)
