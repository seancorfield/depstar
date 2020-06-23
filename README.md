# depstar

<img src="./depstar_logo.png" />

Builds JARs, uberjars, does AOT, manifest generation, etc for deps.edn projects (forked from [healthfinch/depstar](https://github.com/healthfinch/depstar) and enhanced)

# Usage

Install this tool to an alias in `$PROJECT/deps.edn` or `$HOME/.clojure/deps.edn`:

```clj
{
  :aliases {:depstar
              {:extra-deps
                 {seancorfield/depstar {:mvn/version "1.0.94"}}}}
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

This will compile the `project.core` namespace, which must have a `(:gen-class)` clause in its `ns` form, (and transitively everything that `project.core` requires) into a temporary folder, add that temporary folder to the classpath, build the uberjar based on the `pom.xml` file, including everything on your classpath, with a manifest specifying `project.core` as the main class.

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

# Releases

This project follows the version scheme MAJOR.MINOR.COMMITS where MAJOR and MINOR provide some relative indication of the size of the change, but do not follow semantic versioning. In general, all changes endeavor to be non-breaking (by moving to new names rather than by breaking existing names). COMMITS is an ever-increasing counter of commits since the beginning of this repository.

Latest stable release: 1.0.94

# License

The use and distribution terms for this software are covered by the
[Eclipse Public License 2.0](https://www.eclipse.org/org/documents/epl-2.0/EPL-2.0.html)
