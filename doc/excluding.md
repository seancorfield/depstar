## Excluding Files

By default, the following files are excluded from the output JAR:

* `project.clj`
* `.keep`
* `**/*.pom`
* `module-info.class`
* `META-INF/**/*.MF`, also `*.SF`, `*.RSA`, and `*.DSA`
* `META-INF/INDEX.LIST`, `META-INF/DEPENDENCIES`, optionally with `.txt` suffix

In addition, `depstar` accepts an `:exclude` option: a vector of strings to use as regular expressions to match other files to be excluded. `re-matches` is used so these should be a _complete match for the full relative path and filename_. For example, if you wanted to exclude `clojure.core.specs.alpha` code from your JAR, you would specify `:exclude '["clojure/core/specs/alpha.*"]'` -- note `.*` at the end so it matches the entire filename.
