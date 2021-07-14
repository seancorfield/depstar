# depstar [![Clojure CI](https://github.com/seancorfield/depstar/actions/workflows/test.yml/badge.svg)](https://github.com/seancorfield/depstar/actions/workflows/test.yml)

<img src="./depstar_logo.png" />

Builds JARs, uberjars, does AOT, manifest generation, etc for `deps.edn` projects (forked from [healthfinch/depstar](https://github.com/healthfinch/depstar) and enhanced).

## TL;DR

The latest versions on Clojars and on cljdoc:

[![Clojars Project](https://clojars.org/com.github.seancorfield/depstar/latest-version.svg)](https://clojars.org/com.github.seancorfield/depstar) [![cljdoc badge](https://cljdoc.org/badge/com.github.seancorfield/depstar?2.1.267)](https://cljdoc.org/d/com.github.seancorfield/depstar/CURRENT)

The documentation on [cljdoc.org](https://cljdoc.org/d/com.github.seancorfield/depstar/CURRENT) is for the current version of `depstar` (with these new sections coming soon):

* [Getting Started](https://cljdoc.org/d/com.github.seancorfield/depstar/CURRENT/doc/getting-started)
* [Building a Library JAR](https://cljdoc.org/d/com.github.seancorfield/depstar/CURRENT/doc/library)
* [Building an Application JAR](https://cljdoc.org/d/com.github.seancorfield/depstar/CURRENT/doc/application)
* Feedback via [issues](https://github.com/seancorfield/depstar/issues) or in the [`#depstar` channel on the Clojurians Slack](https://clojurians.slack.com/messages/C01AK5V8HPT/).

The documentation on GitHub is for **develop** since the 2.1.267 release -- [see the CHANGELOG](https://github.com/seancorfield/depstar/blob/develop/CHANGELOG.md) and then read the [corresponding updated documentation](https://github.com/seancorfield/depstar/tree/develop/doc) on GitHub if you want.

This project follows the version scheme MAJOR.MINOR.COMMITS where MAJOR and MINOR provide some relative indication of the size of the change, but do not follow semantic versioning. In general, all changes endeavor to be non-breaking (by moving to new names rather than by breaking existing names). COMMITS is an ever-increasing counter of commits since the beginning of this repository.

# Basic Usage

Add `depstar` via one or more aliases in your project `deps.edn` or user-level `deps.edn` (in `~/.clojure/` or `~/.config/clojure/`):

```clj
{
 :aliases {
  ;; build an uberjar (application) with AOT compilation by default:
  :uberjar {:replace-deps {com.github.seancorfield/depstar {:mvn/version "2.1.267"}}
            :exec-fn hf.depstar/uberjar
            :exec-args {:aot true}}
  ;; build a jar (library):
  :jar {:replace-deps {com.github.seancorfield/depstar {:mvn/version "2.1.267"}}
        :exec-fn hf.depstar/jar
        :exec-args {}}
 }
}
```

Create an (application) uberjar by invoking `depstar` with the desired jar name:

```bash
clojure -X:uberjar :jar MyProject.jar
```

An uberjar created by that command can be run as follows:

```bash
java -cp MyProject.jar clojure.main -m project.core
```

Create a (library) jar by invoking `depstar` with the desired jar name:

```bash
clojure -X:jar :jar MyLib.jar
```

For more detail, read [Getting Started](https://cljdoc.org/d/com.github.seancorfield/depstar/CURRENT/doc/getting-started) and the applicable sections of the documentation.

# License

The use and distribution terms for this software are covered by the
[Eclipse Public License 2.0](https://www.eclipse.org/org/documents/epl-2.0/EPL-2.0.html)
