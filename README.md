# Use `tools.build` Instead!

Please use `tools.build` and build your projects responsibility!

## Log4j2Plugins.dat Cache Merging

If you use `log4j2` (or depend on multiple libraries that do), you will need the `Log4j2Plugins.dat` files merged across
libraries. This is something that `depstar` had supported since December 2020 (version 2.0.160) and that is now possible through the `:conflict-handlers`
option added to `uber` in v0.4.0 of `tools.build`, along with my [log4j2 conflict handler library](https://github.com/seancorfield/build-uber-log4j2-handler).

## `depstar` History

`depstar` came into existence, in [March 2018](https://github.com/healthfinch/depstar/commit/4aa7b35189693feebc7d7e4a180b8af0326c9164),
because there was no "built-in" way to create library JAR files
with the Clojure CLI and `deps.edn` at the time. Over the three and a half years since then, `depstar` has added a lot of functionality
with the ability to make uberjars, perform AOT compilation, generate a JAR file manifest, and so on. My intent, as maintainer of
`depstar` since October 2018, has always been to provide an easy-to-use JAR builder as long as there was no "official" solution for that.

In early July, 2021, the Clojure core team [announced `tools.build`](https://clojure.org/news/2021/07/09/source-libs-builds) as an
"official" solution for building JAR files and performing AOT compilation. In addition, `tools.build` was a toolkit that provided
functions to copy files, directories, compile Java code, and run shell processes -- things that `depstar` had never been intended for.

For a while, there was a functionality gap between the `uber` task of `tools.build` and the `uberjar` functionality of `depstar`.
I've been working with [Alex Miller](https://github.com/puredanger) to reduce that gap and with the release of v0.4.0 on September 15,
parity was achieved. I had already switched most of my open source projects over to `tools.build` for running tests and building
the JAR files and with that latest release I was able to switch the `build.clj` script at work over to `tools.build` for building
our production uberjar artifacts! 

At this point, `depstar` is no longer needed and only serves to fragment the tooling around the Clojure CLI and `deps.edn` so I am
sunsetting this library and asking everyone to switch to [`tools.build`](https://github.com/clojure/tools.build) instead.

Thank you for all the support and feedback on `depstar` over the last three years!

# depstar [![Clojure CI](https://github.com/seancorfield/depstar/actions/workflows/test.yml/badge.svg)](https://github.com/seancorfield/depstar/actions/workflows/test.yml) [![Open in Gitpod](https://gitpod.io/button/open-in-gitpod.svg)](https://gitpod.io/#https://github.com/seancorfield/depstar)

<img src="./depstar_logo.png" />

Builds JARs, uberjars, does AOT, manifest generation, etc for `deps.edn` projects (forked from [healthfinch/depstar](https://github.com/healthfinch/depstar) and enhanced).

## TL;DR

The latest versions on Clojars and on cljdoc:

[![Clojars Project](https://clojars.org/com.github.seancorfield/depstar/latest-version.svg)](https://clojars.org/com.github.seancorfield/depstar) [![cljdoc badge](https://cljdoc.org/badge/com.github.seancorfield/depstar?2.1.303)](https://cljdoc.org/d/com.github.seancorfield/depstar/CURRENT)

The documentation on [cljdoc.org](https://cljdoc.org/d/com.github.seancorfield/depstar/CURRENT) is for the current version of `depstar`:

* [Getting Started](https://cljdoc.org/d/com.github.seancorfield/depstar/CURRENT/doc/getting-started)
* [Building a Library JAR](https://cljdoc.org/d/com.github.seancorfield/depstar/CURRENT/doc/getting-started/building-a-library-jar)
* [Building an Application JAR](https://cljdoc.org/d/com.github.seancorfield/depstar/CURRENT/doc/getting-started/building-an-application-jar)
* Feedback via [issues](https://github.com/seancorfield/depstar/issues) or in the [`#depstar` channel on the Clojurians Slack](https://clojurians.slack.com/messages/C01AK5V8HPT/).

The documentation on GitHub is for **develop** since the 2.1.303 release -- [see the CHANGELOG](https://github.com/seancorfield/depstar/blob/develop/CHANGELOG.md) and then read the [corresponding updated documentation](https://github.com/seancorfield/depstar/tree/develop/doc) on GitHub if you want.

This project follows the version scheme MAJOR.MINOR.COMMITS where MAJOR and MINOR provide some relative indication of the size of the change, but do not follow semantic versioning. In general, all changes endeavor to be non-breaking (by moving to new names rather than by breaking existing names). COMMITS is an ever-increasing counter of commits since the beginning of this repository.

# Basic Usage

Add `depstar` via one or more aliases in your project `deps.edn` or user-level `deps.edn` (in `~/.clojure/` or `~/.config/clojure/`):

```clj
{
 :aliases {
  ;; build an uberjar (application) with AOT compilation by default:
  :uberjar {:replace-deps {com.github.seancorfield/depstar {:mvn/version "2.1.303"}}
            :exec-fn hf.depstar/uberjar
            :exec-args {:aot true}}
  ;; build a jar (library):
  :jar {:replace-deps {com.github.seancorfield/depstar {:mvn/version "2.1.303"}}
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
