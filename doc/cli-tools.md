# CLI Tools Usage

> If you are using the latest prerelease of the Clojure CLI, 1.10.3.905 onward, you can install `depstar` as a "tool" instead of updating your `deps.edn` file and then invoke it using the following commands:

```bash
clojure -Ttools install com.github.seancorfield/depstar '{:git/tag "v2.1.253"}' :as depstar
# make an uberjar:
clojure -Tdepstar uberjar :jar MyProject.jar
# make a thin JAR:
clojure -Tdepstar jar :jar MyLib.jar
```
