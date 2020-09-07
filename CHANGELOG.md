# Changes

* 1.1.next -- in progress
  * Streamline the `clojure -X` invocation entry points to `hf.depstar/jar` and `hf.depstar/uberjar`.

* 1.1.104 -- Aug 27, 2020
  * Fix #37 by adding `-X` / `--exclude` to provide one or more regex used to exclude files from the JAR.
  * Fix #35 by providing `hf.depstar.jar/run` and `hf.depstar.uberjar/run` as entry points that can be used by the Clojure CLI `-X` option (to execute a specific function and pass a map of arguments).
  * Fix #34 by adding `-P` / `--classpath` option to specify a classpath to use (based on PR #36 @borkdude).
  * Fix #33 by allowing destination JAR filename to appear anywhere on the command-line as well as adding a `-J` / `--jar` option for it.
  * Address #31 by clarifying in the README that `(:gen-class)` is required for AOT.

* 1.0.97 -- Aug 05, 2020
  * Fix a bug in the workaround for `Log4j2Plugins.dat`.

* 1.0.96 -- Jul 29, 2020
  * Added a workaround for https://issues.apache.org/jira/browse/LOG4J2-954 issue with `Log4j2Plugins.dat` file conflicts.

* 1.0.94 -- Apr 10, 2020
  * Fix #29 by supporting data reader files with `.cljs` and `.cljc` extensions as well as `.clj`.
  * Address #28 by adding `-S` / `--suppress-clash` option to suppress the warning about clashing jar items.
  * Move to MAJOR.MINOR.COMMITS versioning scheme.

* 0.5.2 -- Jan 16, 2020
  * Fix NPE for uberjar when no main class specified #25 @noisesmith.
* 0.5.1 -- Jan 02, 2020
  * Address #24 by transforming the main class (namespace) name.
* 0.5.0 -- Jan 02, 2020
  * Address #23 by managing the AOT compilation folder automatically.
  * Users no longer need to create `classes` or add it to the classpath.
* 0.4.2 -- Dec 31, 2019
  * Address #22 by automatically setting `Multi-Release: true` in the uberjar manifest if any multi-release JAR files are consumed.
* 0.4.1 -- Dec 31, 2019
  * Address #21 by ignoring `.keep` files.
* 0.4.0 -- Dec 31, 2019
  * Address #20 by adding `-C` / `--compile` option to AOT-compile the main namespace for an uberjar.
* 0.3.4 -- Oct 18, 2019
  * Fix #19 by following symlinks when copying directories.
* 0.3.3 -- Sep 06, 2019
  * Fix #18 by using regex instead of `clojure.xml` to extract group ID, artifact ID, and version.
* 0.3.2 -- Aug 26, 2019
  * Fix #16 by adding `:unknown` copy. handler and checking for excluded filenames in it
  * An unknown file type is now ignored, with a warning printed if it is not an excluded filename.
* 0.3.1 -- Aug 05, 2019
  * Address #14 by adding `-m` / `--main` option to override `Main-Class` in the manifest.
* 0.3.0 -- Jul 24, 2019
  * Fix #13 by using the local `pom.xml`, if present, to generate a manifest (and copy `pom.xml` into the JAR file).
* 0.2.4 -- Jul 05, 2019
  * **Important bug fix for tree-walking bug introduced in 0.2.1!**
* 0.2.3 -- Jul 01, 2019 *(do not use)*
  * Back off Clojure version to 1.7.0 so `depstar` can be used to build JARs for older projects.
* 0.2.2 -- Jun 29, 2019 *(do not use)*
  * Fix #11 by adding a `-v`/`--verbose` option to display files added to the archive.
  * Fix #9 properly by creating parent directories prior to move of JAR file.
* 0.2.1 -- May 08, 2019 *(do not use)*
  * Fix #9 by creating parent directories for target JAR file (PR #10 @jarohen).
* 0.2.0 -- May 07, 2019
  * Fix #8 by switching to ZipFileSystem and performing a single copy pass (instead of copying to temporary folder tree and then building a zip file).
* 0.1.7 -- Apr 24, 2019
  * Fix #6 by excluding `*.pom` files.
  * Fix #7 by excluding `module-info.class` files.
  * Lists excluded files if debugging enabled.
* 0.1.6 -- Mar 10, 2019
  * Fix for JARs containing `data_readers.clj` (do not close input stream!).
  * Supports `-Ddepstar.debug=true` and `DEPSTAR_DEBUG=true` to be more verbose.
* 0.1.5 -- Oct 24, 2018
  * Fix for timestamp preservation.
* 0.1.2 -- Oct 23, 2018
  * Initial fork with (incorrect) fix for exception from JARs containing `data_readers.clj`.
  * `hf.depstar.jar` namespace added.
