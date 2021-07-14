# Terminology and Conventions

Building and deploying JAR files is probably one of the main places where Java's ecosystem bleeds through into the Clojure world and folks coming from non-JVM environment find themselves bombarded with unfamiliar terminology. This page attempts to explain and demystify some of that, in order to help folks using `depstar`.

## JAR Files

First off, a `.jar` file is really just a `.zip` file -- a Java ARchive. In the Java world, these contain compiled Java classes, supporting files such as configuration or other non-Java files (e.g., graphics, license information), and some metadata files. The latter contain information about how the JAR got built and how it can be used. In the Clojure world, we typically package our libraries as source code, instead of compiled class files, but otherwise the same principles apply.

## Artifacts (and Groups)

The Java world overwhelmingly adopted Maven as a build tool and part of the "Maven world" is repositories full of "artifacts" (JAR files) that follow a particular structure. That structure organizes things by "groups", and then by "artifacts", and finally by versions. When you see a library dependency like `org.clojure/tools.logging {:mvn/version "1.1.0"}` in Clojure, that identifies a JAR file (and some metadata) that lives in a repository (Maven Central in this case, because it's a Clojure Contrib library, but most Clojure libraries live on [Clojars](https://clojars.org)) under a directory structure like `/org/clojure/tools.logging/1.1.0`, and the JAR file will be called `tools.logging-1.1.0.jar`. This same structure can be seen in the local cache of these artifacts under the `.m2/repository` directory tree in our home directory (`m2` = Maven version 2.0).

To offer a level of security these repositories typically require some level of proof of ownership for a group ID (`org.clojure` in this case) and only allow a given version to be published once (the repositories are essentially append-only and are otherwise immutable!). Maven Central has pretty strict rules and our own Clojars has recently improved security by adopting [Verified Group Names](https://github.com/clojars/clojars-web/wiki/Verified-Group-Names).

A specific version of a library is identified by its "Maven coordinates": a group ID, an artifact ID, and a version. Clojure tools that fetch dependencies for us check Maven Central first and then check Clojars (by default: you can also have them check additional repositories).

## The Classpath

When we are running simple programs, we are somewhat shielded from this aspect of the Java ecosystem: `lein run`, `clojure -m project.core` let us run our code without compilation, without packaging it up as a JAR file, and without worrying about how dependencies are handled.

Under the hood, our Clojure tooling reads the list of dependencies for our project, locates and downloads all of the libraries we need, and then constructs the "classpath" -- the list of places that `java` should look, in order, to find and load classes when you run a program. Clojure in turn also uses this list of places to find source files to load (and compile into memory) when a namespace is required. Our programs, too, will often rely on the classpath to locate "resources" (via `clojure.java.io/resource` for example).

When we are building simple libraries and applications into JAR files, we can also remain somewhat sheltered from this feature, but for more complex projects we have to be aware of how the constructed classpath affects compilation of our code and what, ultimately, ends up in our JAR files.

You'll find the classpath mentioned casually early on in the [Deps and CLI Guide](https://clojure.org/guides/deps_and_cli), in the [Using Libs Reference](https://clojure.org/reference/libs), and many other places in the official Clojure documentation. It's assumed you know what it is.

## Library vs Application

A library JAR file will generally contain just the code and resources from your project. It will not contain anything your project depends on. Those dependencies will be listed in one of the metadata files that accompanies the JAR file (and is also embedded in the JAR file). That file is called `pom.xml` -- Project Object Model -- which is also part of the Maven world: it lists the group ID, artifact ID, version, and dependencies for a given JAR file (as well as optional information about the developers, source code management, and so on).

An application JAR file, by contrast, will generally contain everything needed to actually _run_ your code. For a Clojure project, at a minimum that means the code and resources from your project, plus all of the things your project depends on, including Clojure itself. And at least some of that will be compiled `.class` files so that the JAR file can be executed directly by the `java` command. An application JAR file contains some metadata called a "manifest" that, among other things, declares a `Main-Class:` which identifies the class that the `java` command should use as the entry point and call that class's `static main` method. In the Clojure world, that corresponds to a namespace in which a `-main` function is declared (and compiled via the `(:gen-class)` directive in the `ns` form). Clojure itself has such an entry point: `clojure.main/-main`.

In the Clojure world, we typically call a library JAR a "thin" JAR, or just "jar", and an application JAR an "uberjar" and you'll see this reflected in our tooling, historically: `lein jar`, `lein uberjar`. This is carried over into `depstar` with its `hf.depstar/jar` and `hf.depstar/uberjar` exec functions and now in [`tools.build`](https://clojure.org/guides/tools_build) with the built-in `jar` and `uber` tasks.

## Source vs Compilation

As noted above, we typically package libraries as source code so that they can be compatible with the widest range of Clojure versions. If you compile code and deploy it as a library, users on different versions of Clojure may have problems using it (such compatibility issues between different versions of Clojure are rare but they can and do occur). We don't need to compile code for an application JAR either because we can execute applications like this:

```bash
java -cp MyProject.jar clojure.main -m project.core
```

This tells `java` that the JAR file contains everything needed for the classpath (`-cp`) and that we want the `clojure.main` class to be used as the starting point. That causes the function `clojure.main/-main` to run, which recognizes the `-m` command-line argument identifying a namespace containing a `-main` function that we want `clojure.main/-main` to require, resolve, and call.

But it is common to specify our entry namespace and to compile at least that code when building an application JAR, so that it can be executed like this instead:

```bash
java -jar MyProject.jar
```
