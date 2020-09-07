# depstar

<img src="./depstar_logo.png" />

Builds JARs, uberjars, does AOT, manifest generation, etc for deps.edn projects (forked from [healthfinch/depstar](https://github.com/healthfinch/depstar) and enhanced). 

For support, help, general questions, use the [#depstar channel on the Clojurians Slack](https://app.slack.com/client/T03RZGPFR/C01AK5V8HPT).

# Usage

Install this tool to an alias in `$PROJECT/deps.edn` or `$HOME/.clojure/deps.edn`:

```clj
{
  :aliases {:depstar
              {:extra-deps
                 {seancorfield/depstar {:mvn/version "1.1.104"}}}}
}
```

Create an uberjar by invoking `depstar` with the desired jar name:

```bash
clojure -A:depstar -m hf.depstar.uberjar MyProject.jar
```

Create a (library) jar by invoking `depstar` with the desired jar name:

```bash
clojure -A:depstar -m hf.depstar.jar MyLib.jar
```

If you want to see all of the files that are being copied into the JAR file, add `-v` or `--verbose` before or after the JAR filename.

## Classpath

By default, `depstar` uses the classpath computed by `clojure`.
For example, you can add web assets into an uberjar by including an alias in your `deps.edn`:

```clj
{:paths ["src"]
 :aliases {:webassets {:extra-paths ["public-html"]}}}
```

Then invoke `depstar` with the chosen aliases:

```bash
clojure -A:depstar:webassets -m hf.depstar.uberjar MyProject.jar
```

You can also pass an explicit classpath into `depstar` and it will use that instead of the (current) runtime classpath for building the JAR:

```bash
clojure -A:depstar -m hf.depstar.uberjar --classpath "$(clojure -A:webassets -Spath)" MyProject.jar
```

`--classpath` can be abbreviated to `-P`.

## `pom.xml`

If there is a `pom.xml` file in the current directory, `depstar` will attempt to read it and figure out the **group ID**, **artifact ID**, and **version** of the project. It will use that information to generate `pom.properties` and `MANIFEST.MF` in the JAR file, as well as copying that `pom.xml` file into the JAR file. If you are building an uberjar, the manifest will declare the `Main-Class` (specified by the `-m` / `--main` option below, `clojure.main` if omitted).

You can suppress the consumption of the `pom.xml` file with the `-n` / `--no-pom` option.

Note that `depstar` does no AOT compilation by default -- use the `-C` / `--compile` option to enable AOT compilation (see below).

If you build an uberjar, you can run the resulting file as follows:

```bash
clojure -A:depstar -m hf.depstar.uberjar MyProject.jar
java -cp MyProject.jar clojure.main -m project.core
```

If you build an uberjar with a `pom.xml` file present and do not specify `-n` / `--no-pom`, so that a manifest is included, you can run the resulting file as follows:

```bash
# generate pom.xml (or create it manually)
clojure -Spom
# build the uberjar without AOT compilation
clojure -A:depstar -m hf.depstar.uberjar MyProject.jar
# Main-Class: clojure.main
java -jar MyProject.jar -m project.core
```

## AOT Compilation

Finally, if you have a `pom.xml` file and also include a (compiled) class in your JAR file that contains a `main` function, you can use the `-m` / `--main` option to specify the name of that class as the `Main-Class` in the manifest instead of the default (`clojure.main`).
As of 0.4.0, you can ask `depstar` to compile your main namespace via the `-C` / `--compile` option:

```bash
# generate pom.xml (or create it manually)
clojure -Spom
# build the uberjar with AOT compilation
clojure -A:depstar -m hf.depstar.uberjar MyProject.jar -C -m project.core
# Main-Class: project.core
java -jar MyProject.jar
```

This will compile the `project.core` namespace, **which must have a `(:gen-class)` clause in its `ns` form**, into a temporary folder, add that temporary folder to the classpath (even when you specify an explicit classpath with `-P` / `--classpath` -- see above), build the uberjar based on the `pom.xml` file, including everything on the classpath, with a manifest specifying `project.core` as the main class.

Remember that AOT compilation is transitive so, in addition to your `project.core` namespace with its `(:gen-class)`, this will also compile everything that `project.core` requires and include those `.class` files (as well as the sources).

> Note: for the 0.4.x releases of `depstar`, you needed to create a `classes` folder manually and add it to the classpath yourself; as of 0.5.0, this is handled automatically by `depstar`.

## Excluding Files

The `-X` / `--exclude` option can be used to provide one or more regex patterns that will be used to exclude unwanted files from the JAR. Note that the string provided will be treated as a regex (via `re-pattern`) that should be a _complete match for the full relative path and filename_. For example, if you wanted to exclude `clojure.core.specs.alpha` code from your JAR, you would specify `--exclude "clojure/core/specs/alpha.*"` -- note `.*` at the end so it matches the entire filename.

## `clojure -X` Usage

The Clojure CLI is adding a `-X` option to execute a specific function and pass a hash map of arguments. See [Executing a function that takes a map](https://clojure.org/reference/deps_and_cli_prerelease#_executing_a_function) in the Deps and CLI reference for details.

As of 1.1.104, `depstar` supports this via `hf.depstar.jar/run` and `hf.depstar.uberjar/run` which both accept a hash map that mirrors the available command-line arguments:

* `:aot` -- if `true`, perform AOT compilation (like the `-C` / `--compile` option)
* `:classpath` -- if specified, use this classpath instead of the (current) runtime classpath to build the JAR (like the `-P` / `--classpath` option)
* `:exclude` -- if specified, should be a vector of strings to use as regex patterns for excluding files from the JAR
* `:jar` -- the name of the destination JAR file (may need to be a quoted string if the path/name is not valid as a Clojure symbol; also like the `-J` / `--jar` option)
* `:jar-type` -- can be `:thin` or `:uber` -- defaults to `:thin` for `hf.depstar.jar/run` and to `:uber` for `hf.depstar.uberjar/run` (and can therefore be omitted in most cases)
* `:main-class` -- the name of the main class for an uberjar (can be specified as a Clojure symbol or a quoted string; like the `-m` / `--main` option; used as the main namespace to compile if `:aot` is `true`)
* `:no-pom` -- if `true`, ignore the `pom.xml` file (like the `-n` / `--no-pom` option)
* `:suppress-clash` -- if `true`, suppress warnings about clashing items going into the JAR file (like the `-S` / `--suppress-clash` option)
* `:verbose` -- if `true`, be verbose about what goes into the JAR file (like the `-v` / `--verbose` option)

The following commands would be equivalent:

```bash
clojure -A:depstar -m hf.depstar.uberjar MyProject.jar -C -m project.core

clojure -X:depstar hf.depstar.uberjar/run :jar MyProject.jar :aot true :main-class project.core
```

You can make this shorter by adding `:exec-fn` to your alias with some of the arguments defaulted since, for a given project, they will likely be fixed values:

```clojure
  ;; a new :uberjar alias to build a project-specific JAR file:
  :uberjar {:extra-deps {seancorfield/depstar {:mvn/version "1.1.104"}}
            :exec-fn hf.depstar.uberjar/run
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

If you are seeing unexpected results with `depstar` and the `-v` / `--verbose` option doesn't provide enough information, you can enable "debug mode" with either `DEPSTAR_DEBUG=true` as an environment variable or `depstar.debug=true` as a JVM property. Be warned: this is **very verbose**!

# Deploying a Library

After you've generated your JAR file as above with a `pom.xml` file, you can use the `mvn` command below to deploy to Clojars (or other Maven-like repositories) -- or you could use [`deps-deploy`](https://github.com/slipset/deps-deploy) -- see below.

## Deploying with Maven

```bash
mvn deploy:deploy-file -Dfile=MyProject.jar -DpomFile=pom.xml -DrepositoryId=clojars -Durl=https://clojars.org/repo/
```

This assumes that you have credentials for your chosen repository in your `~/.m2/settings.xml` file. It should look like this (with your username and password):

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
    ;; version 0.0.9 was the most recent as of 2020-08-19:
    :deploy {:extra-deps {deps-deploy/deps-deploy {:mvn/version "RELEASE"}}
             :main-opts ["-m" "deps-deploy.deps-deploy" "deploy" "MyProject.jar"]}}}
```

This expects your Clojars username to be in the `CLOJARS_USERNAME` environment variable and your Clojars **token** to be in the `CLOJARS_PASSWORD` environment variable.

# Releases

This project follows the version scheme MAJOR.MINOR.COMMITS where MAJOR and MINOR provide some relative indication of the size of the change, but do not follow semantic versioning. In general, all changes endeavor to be non-breaking (by moving to new names rather than by breaking existing names). COMMITS is an ever-increasing counter of commits since the beginning of this repository.

Latest stable release: 1.1.104

# License

The use and distribution terms for this software are covered by the
[Eclipse Public License 2.0](https://www.eclipse.org/org/documents/epl-2.0/EPL-2.0.html)
