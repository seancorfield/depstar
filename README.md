# depstar

<img src="./depstar_logo.png" />

a clj-based uberjarrer (forked from [healthfinch/depstar](https://github.com/healthfinch/depstar) and enhanced)

# Usage

Install this tool to an alias in `$PROJECT/deps.edn` or `$HOME/.clojure/deps.edn`:

```clj
{
  :aliases {:depstar
              {:extra-deps
                 {seancorfield/depstar {:mvn/version "0.3.0"}}}}
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

If you want to see all of the files that are being copied into the JAR file, add `-v` or `--verbose` after the JAR filename.

`depstar` uses the classpath computed by `clojure`.
For example, add web assets into an uberjar by including an alias in your `deps.edn`:

```clj
{:paths ["src"]
 :aliases {:webassets {:extra-paths ["public-html"]}}}
```

Then invoke `depstar` with the chosen aliases:

```bash
clojure -A:depstar:webassets -m hf.depstar.uberjar MyProject.jar
```

If there is a `pom.xml` file in the current directory, `depstar` will attempt to read it and figure out the group ID, artifact ID, and version of the project. It will use that information to generate `pom.properties` and `MANIFEST.MF` in the JAR file, as well as copying that `pom.xml` file into the JAR file. If you are building an uberjar, the manifest will declare `clojure.main` as the `Main-Class` (otherwise that property will be omitted).

You can suppress the consumption of the `pom.xml` file with the `-n` / `--no-pom` option.

Note that `depstar` does no AOT compilation.

If you build an uberjar, you can run the resulting file as follows:

```bash
java -cp MyProject.jar clojure.main -m project.core
```

If you build an uberjar with a `pom.xml` file present and do not specify `-n` / `--no-pom`, so that a manifest is included, you can run the resulting file as follows:

```bash
java -jar MyProject.jar -m project.core
```

## Building uberjars with a custom main class

As mentioned, depstar does no AOT compilation and therefore the main class of the produced jar is `clojure.main`. In some situations, you may want an uberjar with a custom main class (such as `myapp.main`). For example, this woul allow you to distribute an uberjar that Microsoft Windows users would be able to to double-click in order to launch your application.

In order to cater for this use case, depstar allows you to override the main class of the uberjar, but the main class would have to be compiled ahead of time (AOT) by you. Here is an example of a Clojure script to achieve that:

``` clojure
(require '[hf.depstar.uberjar])

(binding [*compile-path* "build_data"]
  (compile 'myapp.main))

(hf.depstar.uberjar/run {:dest       "target/myapp.jar"
                         :main-class "myapp.core"
                         :verbose    true})
```

Save this under `scripts/packaje.clj` and you can then define your alias like so:

``` clojure
:uberjar {:main-opts   ["scripts/package.clj"]
          :extra-paths ["build_data"]
          :extra-deps  {seancorfield/depstar {:mvn/version "0.3.0"}}}
```

Make sure that the `build_data` directory already exists before running the `:uberjar` alias.


# Changes

* 0.3.0 -- Jul 24, 2019 -- Fix #13 by using the local `pom.xml`, if present, to generate a manifest (and copy `pom.xml` into the JAR file).
* 0.2.4 -- Jul 05, 2019 -- **Important bug fix for tree-walking bug introduced in 0.2.1!**
* 0.2.3 -- Jul 01, 2019 -- (do not use) Back off Clojure version to 1.7.0 so `depstar` can be used to build JARs for older projects.
* 0.2.2 -- Jun 29, 2019 -- (do not use) Fix #11 by adding a `-v`/`--verbose` option to display files added to the archive; Fix #9 properly by creating parent directories prior to move of JAR file.
* 0.2.1 -- May 08, 2019 -- (do not use) Fix #9 by creating parent directories for target JAR file (PR #10 @jarohen).
* 0.2.0 -- May 07, 2019 -- Fix #8 by switching to ZipFileSystem and performing a single copy pass (instead of copying to temporary folder tree and then building a zip file).
* 0.1.7 -- Apr 24, 2019 -- Fix #6 by excluding `*.pom` files; Fix #7 by excluding `module-info.class` files; lists excluded files if debugging enabled.
* 0.1.6 -- Mar 10, 2019 -- Fix for JARs containing `data_readers.clj` (do not close input stream!); supports `-Ddepstar.debug=true` and `DEPSTAR_DEBUG=true` to be more verbose.
* 0.1.5 -- Oct 24, 2018 -- Fix for timestamp preservation.
* 0.1.2 -- Oct 23, 2018 -- Initial fork with (incorrect) fix for exception from JARs containing `data_readers.clj`; `hf.depstar.jar` namespace added.

# License

The use and distribution terms for this software are covered by the
[Eclipse Public License 2.0](https://www.eclipse.org/org/documents/epl-2.0/EPL-2.0.html)
