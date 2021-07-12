
## `pom.xml`

If you are creating a library and intend to deploy it to Clojars or a similar repository, you will need a `pom.xml` file.

If there is a `pom.xml` file in the current directory, `depstar` will attempt to read it and figure out the **group ID**, **artifact ID**, and **version** of the project. It will use that information to generate `pom.properties` in the JAR file, as well as copying that `pom.xml` file into the JAR file. You can specify `:pom-file` as an exec argument if you want to use a different pom file.

When generating `pom.properties`, `depstar` will attempt to run `git rev-parse HEAD` _in the same directory as the `pom.xml` file_ and will add `revision=<SHA>` to the list of properties if successful.

You can also specify the `:group-id`, `:artifact-id`, and/or `:version` as exec arguments and those values will override what is in the `pom.xml` file. **The `pom.xml` file will be updated to reflect those values.** If the `pom.xml` file contains a VCS `<tag>..</tag>` that matches the version, with any optional prefix, it will also be updated (so `<version>` and `<tag>` will stay in sync).
Again, if you intend to deploy a library to Clojars, please read the [Clojars Verified Group Names policy](https://github.com/clojars/clojars-web/wiki/Verified-Group-Names).

You can suppress the consumption of the `pom.xml` file with the `:no-pom true` option.

You can generate a minimal `pom.xml` file using the `clojure -Spom` command (and that will also update the dependencies in an existing `pom.xml` based on `deps.edn`). `depstar` can run this for you, using the same computed project basis that it uses for building the JAR file: specify the `:sync-pom true` exec argument to perform this step:

* If no `pom.xml` file exists (where `:pom-file` specifies or else in the current directory), you will also need to specify `:group-id`, `:artifact-id`, and `:version`, and a minimal pom file will be created. Your group ID should generally be a reverse domain name, such as `net.clojars.username`, `com.github.username`, `com.mycompany`, etc.
* If a `pom.xml` file already exists (per `:pom-file` or in the current directory), it will be updated to reflect the latest dependencies from the project basis, and any `:group-id`/`:artifact-id` pair and/or `:version` supplied as exec arguments.

> Note: when using `:sync-pom true`, the underlying `tools.deps.alpha` library may print out a `Skipping paths` warning. This happens if you have more than one path specified in `:paths` in your `deps.edn` file: `tools.deps.alpha` treats the first element as the "source path" and uses it to generate the `build` > `sourceDirectory` entry in `pom.xml`. It prints the warning to show you what it is ignoring. Accordingly, your source path should come first, followed by any additional paths needed, such as `"resources"`. If you see `Skipping paths: src` then you probably have `:paths ["resources" "src"]` and you should update that to `:paths ["src" "resources"]` instead.

If you build an uberjar with a `pom.xml` file present and do not specify `:no-pom true`, you can run the resulting file as follows:

```bash
# if pom.xml file is already present:
# build the uberjar without AOT compilation
clojure -X:uberjar :jar MyProject.jar

# else ask depstar to create it for you:
# build the uberjar without AOT compilation
clojure -X:uberjar :sync-pom true \
        :group-id myname :artifact-id myproject \
        :version '"1.2.3"' :jar MyProject.jar

# Main-Class: clojure.main
java -jar MyProject.jar -m project.core
```

See the `:main-class` option above if you want `java -jar` to run your main function by default instead of requiring the `-m` option.

You can use the `:pom-file` exec argument to specify a path to the `pom.xml` file if it is not in the current directory.
