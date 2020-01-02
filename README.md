# depstar

<img src="./depstar_logo.png" />

a clj-based uberjarrer (forked from [healthfinch/depstar](https://github.com/healthfinch/depstar) and enhanced)

# Usage

Install this tool to an alias in `$PROJECT/deps.edn` or `$HOME/.clojure/deps.edn`:

```clj
{
  :aliases {:depstar
              {:extra-deps
                 {seancorfield/depstar {:mvn/version "0.5.1"}}}}
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

This will compile the `project.core` namespace (and transitively everything it requires) into a temporary folder, add that temporary folder to the classpath, build the uberjar based on the `pom.xml` file, including everything on your classpath, with a manifest specifying `project.core` as the main class.

> Note: for the 0.4.x releases of `depstar`, you needed to create a `classes` folder manually and add it to the classpath yourself; as of 0.5.0, this is handled automatically by `depstar`.

# Deploying a Library

After you've generated your JAR file as above with a `pom.xml` file, you can use the `mvn` command below to deploy to Clojars (or other Maven-like repositories).

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

# Changes

* 0.5.1 -- Jan 02, 2020 -- Address #24 by transforming the main class (namespace) name.
* 0.5.0 -- Jan 02, 2020 -- Address #23 by managing the AOT compilation folder automatically; users no longer need to create `classes` or add it to the classpath.
* 0.4.2 -- Dec 31, 2019 -- Address #22 by automatically setting `Multi-Release: true` in the uberjar manifest if any multi-release JAR files are consumed.
* 0.4.1 -- Dec 31, 2019 -- Address #21 by ignoring `.keep` files.
* 0.4.0 -- Dec 31, 2019 -- Address #20 by adding `-C` / `--compile` option to AOT-compile the main namespace for an uberjar.
* 0.3.4 -- Oct 18, 2019 -- Fix #19 by following symlinks when copying directories.
* 0.3.3 -- Sep 06, 2019 -- Fix #18 by using regex instead of `clojure.xml` to extract group ID, artifact ID, and version.
* 0.3.2 -- Aug 26, 2019 -- Fix #16 by adding `:unknown` copy handler and checking for excluded filenames in it; an unknown file type is now ignored, with a warning printed if it is not an excluded filename.
* 0.3.1 -- Aug 05, 2019 -- Address #14 by adding `-m` / `--main` option to override `Main-Class` in the manifest.
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
