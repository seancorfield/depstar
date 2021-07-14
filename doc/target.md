# Target Directory

By default, `depstar` updates the `pom.xml` in place, compiles namespaces to a temporary folder, and builds the JAR in the current directory.
You can specify `:pom-file` if the `pom.xml` file is not in the current directory, and `:jar` can specify a path for the JAR file if you
want it created somewhere else.

To behave more like other tooling, `depstar` supports a `:target-dir` option (as of 2.1.245), which will let `depstar` sync from
a current `pom.xml` to a new one (in the target directory), compile namespaces to a `classes` folder inside the target directory,
and build the JAR file into that directory too. The target directory (and the `classes` folder within it) remain after `depstar`
exits for you to inspect.

You can still specify `:pom-file` to provide a different source `pom.xml` file to use as the basis for the updated one in the target
directory, and you can still specify `:jar` as a path, rather than just a filename, to have `depstar` build the JAR outside the target
directory.

> Note: the `classes` folder in the target directory is not cleaned out by `depstar` prior to compilation so the JAR file will use whatever is already in that folder if you specify AOT compilation without deleting `classes` first. When [using `depstar` with `tools.build`](tools-build.md), you can use the `delete` task to remove the target directory, to ensure a clean work area.
