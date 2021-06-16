(ns hf.depstar.uberjar
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.deps.alpha :as t]
            [clojure.tools.deps.alpha.gen.pom :as pom]
            [clojure.tools.logging :as logger]
            [clojure.tools.namespace.find :as tnsf])
  (:import (java.io File InputStream PushbackReader InputStreamReader BufferedReader)
           (java.nio.file CopyOption LinkOption OpenOption
                          StandardCopyOption StandardOpenOption
                          FileSystem FileSystems Files
                          FileVisitOption FileVisitResult FileVisitor
                          Path)
           (java.nio.file.attribute FileAttribute FileTime)
           (java.util.zip ZipEntry ZipFile)
           (org.apache.logging.log4j.core.config.plugins.processor PluginCache))
  (:gen-class))

(set! *warn-on-reflection* true)

(def ^:dynamic ^:private *debug* nil)
(def ^:dynamic ^:private *debug-clash* nil)
(def ^:dynamic ^:private *delete-on-exit* nil)
(def ^:dynamic ^:private *exclude* nil)
(def ^:dynamic ^:private *verbose* nil)

(defn- delete-path-on-exit
  "Given a Path, register it for deletion on exit of this process."
  [^Path path]
  (.deleteOnExit (.toFile path)))

(defn- env-prop
  "Given a setting name, get its Boolean value from the environment,
  validate it, and return the value (or nil if no setting is present)."
  [setting]
  (let [env-setting  (str "DEPSTAR_" (str/upper-case setting))
        prop-setting (str "depstar." (str/lower-case setting))]
    (when-let [level (or (System/getenv env-setting)
                         (System/getProperty prop-setting))]
      (case level
        "true"  true
        "false" false ;; because (if (Boolean. "false") :is-truthy :argh!)
        (throw (ex-info (str "depstar " setting
                             " should be true or false")
                        {:level    level
                         :env      (System/getenv env-setting)
                         :property (System/getProperty prop-setting)}))))))

(defonce ^:private ^FileSystem FS (FileSystems/getDefault))

;; could add (StandardOpenOption/valueOf "SYNC") here as well but that
;; could slow things down (and hasn't yet proved to be necessary)
(def ^:private open-opts (into-array OpenOption [(StandardOpenOption/valueOf "CREATE")]))

(def ^:private copy-opts (into-array CopyOption [(StandardCopyOption/valueOf "REPLACE_EXISTING")]))

(def ^:private visit-opts (doto
                            (java.util.HashSet.)
                            (.add (FileVisitOption/valueOf "FOLLOW_LINKS"))))

(defonce ^:private errors (atom 0))

(defonce ^:private multi-release? (atom false))

(defn- path
  ^Path [s]
  (.getPath FS s (make-array String 0)))

(def ^:private log4j2-plugins-file
  "Log4j2 has a very problematic binary plugins cache file that needs to
  be merged -- but it's going away in 3.0.0 apparently because it keeps
  breaking build tools... We use log4j's plugin processor to merge it."
  "META-INF/org/apache/logging/log4j/core/config/plugins/Log4j2Plugins.dat")

(defn- legal-file? [filename]
  (re-find #"(?i)^(META-INF/)?(COPYRIGHT|NOTICE|LICENSE)(\.(txt|md))?$" filename))

(defn- clash-strategy
  [filename]
  (cond
    (re-find #"^data_readers.clj[sc]?$" filename)
    :merge-edn

    (re-find #"^META-INF/services/" filename)
    :concat-lines

    (legal-file? filename)
    :concat-no-dupe

    (= log4j2-plugins-file filename)
    :log4j2-surgery

    :else
    :noop))

(defmulti ^:private clash
  (fn [filename _in _target]
    (let [strategy (clash-strategy filename)]
      (when *debug-clash*
        (logger/info "Found" filename "in multiple dependencies,"
                     (case strategy
                       :merge-edn      "merged as EDN."
                       :concat-lines   "concatenated it."
                       :concat-no-dupe "concatenated (if not dupe)."
                       :log4j2-surgery "merged."
                       :noop           "ignoring duplicate.")))
      strategy)))

(defmethod clash
  :merge-edn
  [_ in target]
  (let [;; read but do not close input stream
        f1 (edn/read (PushbackReader. (io/reader in)))
        ;; read and then close target since we will rewrite it
        f2 (with-open [r (PushbackReader. (Files/newBufferedReader target))]
             (edn/read r))]
    (when-let [conflict (some #(when-let [v (get f1 %)]
                                 (when-not (= v (get f2 %))
                                   {:tag % :new v :old (get f2 %)}))
                              (keys f2))]
      (prn {:warning "conflicting data-reader mapping"
            :conflict conflict
            :in target}))
    (with-open [w (Files/newBufferedWriter target open-opts)]
      (binding [*out* w]
        (prn (merge f1 f2))))))

(defmethod clash
  :concat-lines
  [_ in target]
  (let [f1 (line-seq (io/reader in))
        f2 (Files/readAllLines target)]
    (with-open [w (Files/newBufferedWriter target open-opts)]
      (binding [*out* w]
        (run! println (-> (vec f1)
                          (conj "\n")
                          (into f2)))))))

(def ^:private no-dupe-files (atom {}))

(defmethod clash
  :concat-no-dupe
  [filename in target]
  (let [f1 (line-seq (io/reader in))
        f2 (Files/readAllLines target)
        filename   (str/lower-case filename) ; avoid case-sensitivity
        prev-files (get @no-dupe-files filename)]
    ;; if we haven't already seen this exact same file before, concatenate it:
    (when-not (some #(= % f1) prev-files)
      (with-open [w (Files/newBufferedWriter target open-opts)]
        (binding [*out* w]
          (run! println (-> (vec f1)
                            (conj "\n")
                            (into f2)))))
      (swap! no-dupe-files update filename (fnil conj []) f1))))

(defmethod clash
  :log4j2-surgery
  [_filename ^InputStream in ^Path target]
  (let [cache (PluginCache.)
        temp1 (Files/createTempFile "plugins" ".dat" (make-array FileAttribute 0))
        temp2 (Files/createTempFile "plugins" ".dat" (make-array FileAttribute 0))
        ^java.util.Collection
        urls  (map #(.toURL (.toUri ^Path %)) [temp1 temp2])]
    (Files/copy in temp1 ^"[Ljava.nio.file.CopyOption;" copy-opts)
    (Files/copy target temp2 ^"[Ljava.nio.file.CopyOption;" copy-opts)
    (.loadCacheFiles cache (.elements (java.util.Vector. urls)))
    (with-open [os (Files/newOutputStream target open-opts)]
      (.writeCache cache os))
    (when *delete-on-exit*
      (delete-path-on-exit temp2)
      (delete-path-on-exit temp1))))

(defmethod clash
  :default
  [_ _in _target]
  ;; do nothing, first file wins
  nil)

(def ^:private exclude-patterns
  "Filename patterns to exclude. These are checked with re-matches and
  should therefore be complete filename matches including any path."
  [#"project.clj"
   #"(.*/)?\.DS_Store"
   #"(.*/)?\.keep"
   #".*\.pom" #"module-info\.class"
   #"(?i)META-INF/.*\.(?:MF|SF|RSA|DSA)"
   #"(?i)META-INF/(?:INDEX\.LIST|DEPENDENCIES)(?:\.txt)?"])

(defn- excluded?
  [filename]
  (or (some #(re-matches % filename) exclude-patterns)
      (some #(re-matches % filename) *exclude*)))

(defn- included?
  [filename patterns]
  (some #(re-matches % filename) patterns))

(defn- copy!
  ;; filename drives strategy
  [filename ^InputStream in ^Path target last-mod]
  (if-not (excluded? filename)
    (if (Files/exists target (make-array LinkOption 0))
      (clash filename in target)
      (do
        ;; remember legal files as we copy them:
        (if (legal-file? filename)
          (let [content (line-seq (io/reader in))]
            (with-open [w (Files/newBufferedWriter target open-opts)]
              (binding [*out* w]
                (run! println content)))
            (swap! no-dupe-files
                   update (str/lower-case filename) (fnil conj []) content))
          (Files/copy in target ^"[Ljava.nio.file.CopyOption;" copy-opts))
        (when last-mod
          (Files/setLastModifiedTime target last-mod))))
    (when *debug*
      (prn {:excluded filename}))))

(defn- consume-jar
  [^Path path f]
  (with-open [in-file (ZipFile. (.toFile path))]
    (doseq [entry (enumeration-seq (.entries in-file))]
      (f (.getInputStream in-file entry) entry))))

(defn- classify
  [entry]
  (let [p (path entry)
        symlink-opts (make-array LinkOption 0)]
    (if (Files/exists p symlink-opts)
      (cond
        (Files/isDirectory p symlink-opts)
        :directory

        (and (Files/isRegularFile p symlink-opts)
             (re-find #"\.jar$" (.toString p)))
        :jar

        :else :unknown)
      :not-found)))

(defmulti ^:private copy-source*
  (fn [src _dest _options]
    (classify src)))

(defmethod copy-source*
  :jar
  [src dest options]
  (when-not (= :thin (:jar-type options))
    (when *verbose* (println src))
    (consume-jar (path src)
      (fn [inputstream ^ZipEntry entry]
        (let [^String name (.getName entry)
              last-mod (.getLastModifiedTime entry)
              target (.resolve ^Path dest name)]
          (if (.isDirectory entry)
            (Files/createDirectories target (make-array FileAttribute 0))
            (do (Files/createDirectories (.getParent target) (make-array FileAttribute 0))
              (try
                (when (.startsWith name "META-INF/versions/")
                  (reset! multi-release? true))
                (copy! name inputstream target last-mod)
                (catch Throwable t
                  (prn {:error "unable to copy file"
                        :name name
                        :exception (class t)
                        :message (.getMessage t)})
                  (swap! errors inc))))))))))

(defn- copy-directory
  [^Path src ^Path dest]
  (let [copy-dir
        (reify FileVisitor
          (visitFile [_ p attrs]
            (let [f (.relativize src p)
                  last-mod (Files/getLastModifiedTime p (make-array LinkOption 0))]
              (when *verbose* (println (str (.toString src) "/" (.toString f))))
              (with-open [is (Files/newInputStream p (make-array OpenOption 0))]
                (copy! (.toString f) is (.resolve dest (.toString f)) last-mod)))
            FileVisitResult/CONTINUE)
          (preVisitDirectory [_ p attrs]
            (Files/createDirectories (.resolve dest (.toString (.relativize src p)))
                                     (make-array FileAttribute 0))
            FileVisitResult/CONTINUE)
          (postVisitDirectory [_ p ioexc]
            (if ioexc (throw ioexc) FileVisitResult/CONTINUE))
          (visitFileFailed [_ p ioexc] (throw (ex-info "Visit File Failed" {:p p} ioexc))))]
    (Files/walkFileTree src visit-opts Integer/MAX_VALUE copy-dir)
    :ok))

(defmethod copy-source*
  :directory
  [src dest _options]
  (when *verbose* (println src))
  (copy-directory (path src) dest))

(defmethod copy-source*
  :not-found
  [src _dest _options]
  (prn {:warning "could not find classpath entry" :path src}))

(defmethod copy-source*
  :unknown
  [src _dest _options]
  (if (excluded? src)
    (when *debug* (prn {:excluded src}))
    (prn {:warning "ignoring unknown file type" :path src})))

(defn- copy-source
  [src dest options]
  (copy-source* src dest options))

(defn- read-edn-files
  "Given as options map, use tools.deps.alpha to read and merge the
  applicable `deps.edn` files."
  [{:keys [repro] :or {repro true}}]
  (let [{:keys [root-edn user-edn project-edn]} (t/find-edn-maps)]
    (t/merge-edns (if repro
                    [root-edn project-edn]
                    [root-edn user-edn project-edn]))))

(defn- calc-project-basis
  "Given the options map, use tools.deps.alpha to read and merge the
  applicable `deps.edn` files, combine the specified aliases, calculate
  the project basis (which will resolve/download dependencies), and
  return the calculated project basis."
  [{:keys [aliases] :or {aliases []} :as options}]
  (let [deps     (read-edn-files options)
        combined (t/combine-aliases deps aliases)]
    ;; this could be cleaner, by only selecting the "relevant"
    ;; keys from combined for each of these three uses (but
    ;; I'm waiting for the dust to settle on a possible higher-
    ;; level API appearing in tools.deps.alpha itself):
    (t/calc-basis (t/tool deps combined)
                  {:resolve-args   combined
                   :classpath-args combined})))

(comment
  (calc-project-basis {})
  (calc-project-basis {:aliases [:trial]})
  ,)

(defn- parse-classpath [^String cp]
  (vec (.split cp (System/getProperty "path.separator"))))

(defn- first-by-tag
  [pom-text tag]
  (-> (re-seq (re-pattern (str "<" (name tag) ">([^<]+)</" (name tag) ">")) pom-text)
      (first)
      (second)))

(defn- sync-gav
  "Given a pom file and options, return the group/artifact IDs and
  the version. These are taken from the options, if present, otherwise
  they are read in from the pom file.

  Returns a hash map containing the group/artifact IDs, and the version.

  If the values provided in the options differ from those in the pom file,
  the pom file will be updated to reflect those in the options hash map.

  Throw an exception if we cannot read them from the pom file."
  [pom-file {:keys [artifact-id group-id version]}]
  (let [pom-text     (slurp pom-file)
        artifact-id' (first-by-tag pom-text :artifactId)
        group-id'    (first-by-tag pom-text :groupId)
        version'     (first-by-tag pom-text :version)
        result       {:artifact-id (or artifact-id artifact-id')
                      :group-id    (or group-id    group-id')
                      :version     (or version     version')}]
    (when-not (and (:group-id result) (:artifact-id result) (:version result))
      (throw (ex-info "Unable to establish group/artifact and version!" result)))
    ;; do we need to override any of the pom.xml values?
    (when (or (and artifact-id artifact-id' (not= artifact-id artifact-id'))
              (and group-id    group-id'    (not= group-id    group-id'))
              (and version     version'     (not= version     version')))
      (logger/info "Updating pom.xml file to"
                   (str "{"
                        (:group-id result) "/"
                        (:artifact-id result) " "
                        "{:mvn/version \"" (:version result) "\"}"
                        "}"))
      (spit pom-file
            (cond-> pom-text
              (and artifact-id artifact-id' (not= artifact-id artifact-id'))
              (str/replace-first (str "<artifactId>" artifact-id' "</artifactId>")
                                 (str "<artifactId>" artifact-id  "</artifactId>"))

              (and group-id    group-id'    (not= group-id group-id'))
              (str/replace-first (str "<groupId>" group-id' "</groupId>")
                                 (str "<groupId>" group-id  "</groupId>"))

              (and version     version'     (not= version version'))
              (->
                (str/replace-first (str "<version>" version' "</version>")
                                   (str "<version>" version  "</version>"))
                ;; also replace <tag> if it matched <version> with any prefix:
                (str/replace-first (re-pattern (str "<tag>([^<]*)"
                                                    (java.util.regex.Pattern/quote version')
                                                    "</tag>"))
                                   (str "<tag>$1" version "</tag>"))))))
    result))

(defn- manifest-properties
  "Given a hash map of options, if `:manifest` is present and
  is a hash map, return a string of the contents suitable for
  adding to the manifest. Else return nil."
  [{:keys [manifest]}]
  (str/join
   (reduce-kv (fn [mf k v]
                (conj mf
                      (-> k (name) (str/split #"-")
                          (->> (map str/capitalize) (str/join "-")))
                      ": "
                      v
                      "\n"))
              []
              manifest)))

(comment
  (manifest-properties {:manifest {:class-path "/opt/whatever/whatever.jar"
                                   :some-thing-else "42"}})
  .)

(defn- copy-manifest
  "Create a MANIFEST.MF file and add it to the JAR."
  [^Path dest {:keys [jar-type main-class] :as options}]
  (let [jdk         (str/replace (System/getProperty "java.version")
                                 #"_.*" "")
        build-now   (java.util.Date.)
        last-mod    (FileTime/fromMillis (.getTime build-now))
        manifest    (str "Manifest-Version: 1.0\n"
                         "Created-By: depstar\n"
                         (when-let [user (System/getProperty "user.name")]
                           (str "Built-By: " user "\n"))
                         "Build-Jdk: " jdk "\n"
                         (when @multi-release?
                           "Multi-Release: true\n")
                         (when-not (= :thin jar-type)
                           (str "Main-Class: "
                                (if main-class
                                  (str/replace main-class "-" "_")
                                  "clojure.main")
                                "\n"))
                         (manifest-properties options))]
    (with-open [is (io/input-stream (.getBytes manifest))]
      (when *verbose*
        (println "\nGenerating META-INF/MANIFEST.MF:\n")
        (print manifest))
      (let [target (.resolve dest "META-INF/MANIFEST.MF")]
        (Files/createDirectories (.getParent target) (make-array FileAttribute 0))
        (copy! "MANIFEST.MF" is target last-mod)))))

(defn- git-rev
  "Attempt to return the HEAD SHA from git in the pom file's directory.
  Silently returns `nil` if the command can not be run for any reason."
  [^File pom-file]
  (try
    (let [pom-dir (.getParentFile (.getAbsoluteFile pom-file))
          p (.. (ProcessBuilder.
                 ^"[Ljava.lang.String;"
                 (into-array String ["git" "rev-parse" "HEAD"]))
                (directory pom-dir)
                (start))]
      (.waitFor p)
      (when (zero? (.exitValue p))
        (str/trim (slurp (.getInputStream p)))))
    (catch Throwable _)))

(defn- copy-pom
  "Given a pom.xml file and options, build pom.properties, and add that
  and the pom.xml file to the JAR."
  [^Path dest ^File pom-file options]
  (let [{:keys [artifact-id group-id version]}
        (sync-gav pom-file options)
        build-now   (java.util.Date.)
        last-mod    (FileTime/fromMillis (.getTime build-now))
        properties  (str "#Generated by depstar\n"
                         "#" build-now "\n"
                         (when-let [rev (git-rev pom-file)]
                           (str "revision=" rev "\n"))
                         "version=" version "\n"
                         "groupId=" group-id "\n"
                         "artifactId=" artifact-id "\n")
        maven-dir   (str "META-INF/maven/" group-id "/" artifact-id "/")]
    (when *verbose* (println ""))
    (logger/info "Processing pom.xml for"
                 (str "{" group-id "/" artifact-id
                      " {:mvn/version \"" version "\"}}"))
    (with-open [is (io/input-stream (.getBytes properties))]
      (when *verbose*
        (println (str "\nGenerating " maven-dir "pom.properties:\n"))
        (print properties))
      (let [target (.resolve dest (str maven-dir "pom.properties"))]
        (Files/createDirectories (.getParent target) (make-array FileAttribute 0))
        (copy! "pom.properties" is target last-mod)))
    (with-open [is (io/input-stream pom-file)]
      (when *verbose*
        (println (str "\nCopying pom.xml to " maven-dir "pom.xml")))
      ;; we don't need the createDirectories call here
      (copy! "pom.xml" is
             (.resolve dest (str maven-dir "pom.xml"))
             last-mod))))

(defn- pom-sync
  "Give a (pom) file, the project basis, and the options, synchronize
  the group/artifact/version if requested (using tools.deps.alpha)."
  [^File pom-file basis {:keys [artifact-id group-id version]}]
  (let [new-pom (not (.exists pom-file))]
    ;; #56 require GAV when sync-pom used to create pom.xml:
    (if (and new-pom (not (and group-id artifact-id version)))
      (logger/warn "Ignoring :sync-pom because :group-id, :artifact-id, and"
                   ":version are all required when creating a new 'pom.xml' file!")
      (do
        (logger/info "Synchronizing" (.getName pom-file))
        (pom/sync-pom
         {:basis basis
          :params (cond-> {:target-dir (or (.getParent pom-file) ".")
                           :src-pom    (.getPath pom-file)}
                    (and new-pom group-id artifact-id)
                    (assoc :lib (symbol (name group-id) (name artifact-id)))
                    (and new-pom version)
                    (assoc :version version))})))))

(defn- compile-arguments
  "Given a namespace to compile (a symbol), a vector of JVM
  options to apply, the classpath, a symbol for the compile
  function, and the temporary directory to write the classes
  to, return the process arguments that would compile it
    (java -cp ...).
  If compile-fn is omitted (nil), clojure.core/compile is
  used. Otherwise, a require of the namespace is added and
  a call of resolve is added."
  [ns-syms jvm-opts cp compile-fn tmp-c-dir]
  (let [java     (or (System/getenv "JAVA_CMD") "java")
        windows? (-> (System/getProperty "os.name")
                     (str/lower-case)
                     (str/includes? "windows"))
        comp-req (when-let [comp-ns (and compile-fn (namespace compile-fn))]
                   (str "(require,'" comp-ns "),"))
        comp-fn  (if compile-fn
                   (str "(resolve,'" compile-fn ")")
                   "compile")
        args     (-> [java]
                     (into jvm-opts)
                     (into ["-cp"
                            (str/join (System/getProperty "path.separator") cp)
                            "clojure.main"
                            "-e"
                            (str "(binding,[*compile-path*,"
                                 (pr-str (str tmp-c-dir))
                                 "]," comp-req
                                 (str/join
                                  ","
                                  (for [ns-sym ns-syms]
                                    (str "(println,\"Compiling\",'" (name ns-sym) ")(" comp-fn ",'" (name ns-sym) ")")))
                                 ")")]))]
    (if windows?
      (mapv #(str/replace % "\"" "\\\"") args)
      args)))

(defn- compile-it
  "Given a namespace to compile (a symbol), a vector of JVM
  options to apply, the classpath, a symbol for the compile
  function, and the temporary directory to write the classes
  to, compile the namespace and return a Boolean indicating
  any failures (the failures will be printed to standard
  error)."
  [ns-syms jvm-opts cp compile-fn tmp-c-dir]
  (let [p (.start
           (ProcessBuilder.
            ^"[Ljava.lang.String;"
            (into-array
             String
             (compile-arguments ns-syms jvm-opts cp compile-fn tmp-c-dir))))]
    (with-open [rdr (BufferedReader.
                     (InputStreamReader.
                      (.getInputStream p)))]
      (doseq [line (line-seq rdr)]
        (logger/info line)))
    (.waitFor p)
    (let [stderr (slurp (.getErrorStream p))]
      (when (seq stderr) (println stderr))
      (if (zero? (.exitValue p))
        false ; no AOT failure
        (do
          (logger/error (str "Compilation failed!"))
          true)))))

(defn- print-help []
  (println "library usage:")
  (println "  clojure -X:depstar jar :jar MyProject.jar")
  (println "uberjar usage:")
  (println "  clojure -X:depstar uberjar :name MyProject.jar")
  (println "  clojure -X:depstar uberjar :aot true :main-class project.core :name MyProject.jar")
  (println "options:")
  (println "  :aliases [kws]     -- optional list of aliases to use when computing the classpath")
  (println "  :aot true          -- enable AOT compilation for uberjar")
  (println "  :artifact-id sym   -- specify artifact ID to be used")
  (println "  :classpath <cp>    -- override classpath")
  (println "  :compile-fn sym    -- specify a custom compile function to use")
  (println "  :compile-ns [matches] -- optional list of namespaces to AOT compile")
  (println "                        matches can be symbols or regex strings")
  (println "  :debug-clash true  -- print warnings about clashing jar items")
  (println "  (can be useful if you are not getting the files you expect in the JAR)")
  (println "  :exclude [regex]   -- exclude files via regex")
  (println "  :group-id sym      -- specify group ID to be used (reverse domain name preferred)")
  (println "  :help true         -- show this help (and exit)")
  (println "  :jar sym-or-str    -- specify the name of the JAR file")
  (println "  :jvm-opts [strs]   -- optional list of JVM options for AOT compilation")
  (println "  :main-class sym    -- specify the main namespace (or class)")
  (println "  :manifest {kvs}    -- optional hash map of additional entries for MANIFEST.MF")
  (println "  :no-pom true       -- ignore pom.xml")
  (println "  :paths-only true   -- use only paths from the basis, not the classpath")
  (println "  :pom-file str      -- optional path to a different 'pom.xml' file")
  (println "  :repro false       -- include user 'deps.edn' when computing the classpath")
  (println "  :sync-pom true     -- synchronize 'pom.xml' dependencies, group, artifact, and version")
  (println "  :verbose true      -- explain what goes into the JAR file")
  (println "  :version str       -- specify the version (of the group/artifact)"))

(defn- preprocess-options
  "Given an options hash map, if any of the values are keywords, look them
  up as alias values from the full basis (including user `deps.edn`).

  :jar-type is the only option that is expected to have a keyword value
  and it is generally set automatically so we skip the lookup for that."
  [options]
  (let [kw-opts #{:compile-ns :jar-type} ; :compile-ns can be :all
        aliases (:aliases (read-edn-files {:repro false}))]
    (reduce-kv (fn [opts k v]
                 (if (and (not (contains? kw-opts k)) (keyword? v))
                   (if (contains? aliases v)
                     (assoc opts k (get aliases v))
                     (do
                       (logger/warn k "has value" v "which is an unknown alias")
                       opts))
                   opts))
               options
               options)))

(defn build-jar
  "Core functionality for depstar. Can be called from a REPL or as a library.

  Returns a hash map containing:
  * `:success` -- `true` or `false`
  * `:reason` -- if `:success` is `false`, this explains what failed:
    * `:help` -- help was requested
    * `:no-jar` -- the `:jar` option was missing
    * `:aot-failed` -- `:compile` was requested but it threw an exception
    * `:copy-failure` -- one or more files could not be copied into the JAR

  Additional detail about success and failure is also logged."
  [{:keys [help jar] :as options}]

  (cond

   help
   {:success false :reason :help}

   (not jar)
   {:success false :reason :no-jar}

   :else
   (let [{:keys [aot classpath compile-aliases compile-fn compile-ns
                 debug-clash delete-on-exit exclude
                 group-id jar jar-type jvm-opts main-class no-pom paths-only
                 pom-file sync-pom verbose]
          :or {jar-type :uber}
          :as options}
         (preprocess-options options)
         jar        (some-> jar str) ; ensure we have a string
         _          (when (and (not= :thin jar-type) paths-only)
                      (logger/warn ":paths-only is ignored when building an uberjar"))
         _          (when (and jvm-opts (not (sequential? jvm-opts)))
                      (logger/warn ":jvm-opts should be a vector -- ignoring" jvm-opts))
         _          (when (and group-id (not (re-find #"\." (str group-id))))
                      (logger/warn ":group-id should probably be a reverse domain name, not just" group-id))
         jvm-opts   (if (sequential? jvm-opts) (vec jvm-opts) [])
         main-class (some-> main-class str) ; ensure we have a string
         options    (assoc options ; ensure defaulted/processed options present
                           :jar        jar
                           :jar-type   jar-type
                           :main-class main-class)
         basis      (calc-project-basis options)
         c-basis    (if-let [c-aliases (not-empty compile-aliases)]
                      (calc-project-basis (assoc options :aliases c-aliases))
                      basis)
         ^File
         pom-file   (io/file (or pom-file "pom.xml"))
         _
         (when sync-pom
           (pom-sync pom-file basis options))
         _
         (when (and aot (= :thin jar-type))
           (logger/warn ":aot is not recommended for a 'thin' JAR!"))

         cp          (or (some-> classpath (parse-classpath))
                         (if (and paths-only (= :thin jar-type))
                           (vec (into (set (:paths basis))
                                      (-> basis :classpath-args :extra-paths)))
                           (:classpath-roots basis)))
         c-cp        (or (some-> classpath (parse-classpath))
                         (if (and paths-only (= :thin jar-type))
                           (vec (into (set (:paths c-basis))
                                      (-> c-basis :classpath-args :extra-paths)))
                           (:classpath-roots c-basis)))

          ;; expand :all and regex string using tools.namespace:
         dir-file-ns (comp
                      (filter #(= :directory (classify %)))
                      (map io/file)
                      (mapcat tnsf/find-namespaces-in-dir))
         compile-ns  (cond (= :all compile-ns)
                           (into [] dir-file-ns c-cp)
                           (sequential? compile-ns)
                           (let [patterns (into []
                                                (comp (filter string?)
                                                      (map re-pattern))
                                                compile-ns)]
                             (cond-> (filterv symbol? compile-ns)
                               (seq patterns)
                               (into (comp
                                      dir-file-ns
                                      (filter #(included? (str %) patterns)))
                                     c-cp)))
                           :else
                           (when compile-ns
                             (logger/warn ":compile-ns should be a vector (or :all) -- ignoring")))

          ;; force AOT if compile-ns explicitly requested:
         do-aot      (or aot (seq compile-ns))
          ;; compile main-class at least (if also do-aot):
         compile-ns  (if (and do-aot main-class)
                       (into (or compile-ns []) [main-class])
                       compile-ns)
         _           (when (and aot (empty? compile-ns))
                       (logger/warn ":aot true but no namespaces to compile -- ignoring"))
         tmp-c-dir   (when do-aot
                       (Files/createTempDirectory "depstarc" (make-array FileAttribute 0)))
         _           (when (and tmp-c-dir delete-on-exit)
                       (delete-path-on-exit tmp-c-dir))
         cp          (into (cond-> [] do-aot (conj (str tmp-c-dir))) cp)
         c-cp        (into (cond-> [] do-aot (conj (str tmp-c-dir))) c-cp)
         aot-failure (when do-aot
                       (compile-it compile-ns jvm-opts c-cp compile-fn tmp-c-dir))]

     (if aot-failure

       {:success false :reason :aot-failed}

       (let [tmp-z-dir (Files/createTempDirectory "depstarz" (make-array FileAttribute 0))
             _         (when delete-on-exit
                         ;; the JAR file is created in this directory and then
                         ;; moved to the target location but we may still need
                         ;; to clean up the temporary directory itself:
                         (delete-path-on-exit tmp-z-dir))
             dest-name (str/replace jar #"^.*[/\\]" "")
             jar-path  (.resolve tmp-z-dir ^String dest-name)
             jar-file  (java.net.URI. (str "jar:" (.toUri jar-path)))
             zip-opts  (doto (java.util.HashMap.)
                         (.put "create" "true")
                         (.put "encoding" "UTF-8"))]
          ;; copy everything to a temporary ZIP (JAR) file:
         (with-open [zfs (FileSystems/newFileSystem jar-file zip-opts)]
           (let [tmp (.getPath zfs "/" (make-array String 0))]
             (reset! errors 0)
             (reset! multi-release? false)
             (logger/info "Building" (name jar-type) "jar:" jar)
             (binding [*debug* (env-prop "debug")
                       *debug-clash* debug-clash
                       *delete-on-exit* delete-on-exit
                       *exclude* (mapv re-pattern exclude)
                       *verbose* verbose]
               (run! #(copy-source % tmp options) cp)
               (copy-manifest tmp options)
               (when (and (not no-pom) (.exists pom-file))
                 (copy-pom tmp pom-file options)))))
          ;; move the temporary file to its final location:
         (let [dest-path (path jar)
               parent (.getParent dest-path)]
           (when parent (.. parent toFile mkdirs))
           (Files/move jar-path dest-path copy-opts))
          ;; was it successful?
         (if (pos? @errors)
           {:success false :reason :copy-failure}
           {:success true}))))))

(defn ^:no-doc run*
  "Deprecated entry point for REPL or library usage.

  Returns a hash map containing:
  * `:success` -- `true` or `false`
  * `:reason` -- if `:success` is `false`, this explains what failed:
    * `:help` -- help was requested
    * `:no-jar` -- the `:jar` option was missing
    * `:aot-failed` -- `:compile` was requested but it threw an exception
    * `:copy-failure` -- one or more files could not be copied into the JAR

  More detail about success and failure is printed to stdout."
  [options]
  (logger/warn "DEPRECATED: hf.depstar.uberjar/run* -- use hf.depstar.uberjar/build-jar instead.")
  (build-jar options))

(defn ^:no-doc build-jar-as-main
  "Command-line entry point for `-X` (and legacy `-M`) that performs
  checking on arguments, offers help, and calls `(System/exit 1)` if
  the JAR-building process encounters errors."
  [options]
  (let [result (build-jar options)]
    (if (:success result)
      (shutdown-agents)
      (do
        (case (:reason result)
          :help         (print-help)
          :no-jar       (print-help)
          :aot-failed   (logger/error "AOT FAILED") ;nil ; details already printed
          :copy-failure (logger/error "Completed with errors!"))
        (System/exit 1)))))

(defn ^:no-doc run
  "Deprecated entry point for uberjar invocations via `-X`."
  [options]
  (logger/warn "DEPRECATED: hf.depstar.uberjar/run -- use hf.depstar/uberjar instead.")
  (build-jar-as-main options))

(defn ^:no-doc parse-args
  "Returns a hash map with all the options set from command-line args.

  Essentially deprecated since -M -m invocation is deprecated."
  [args]
  (let [merge-args (fn [opts new-arg]
                     (cond (contains? new-arg :exclude)
                           (update opts :exclude (fnil conj []) (:exclude new-arg))
                           (and (contains? new-arg :jar)
                                (contains? opts :jar))
                           (do
                             (logger/warn "Multiple JAR files specified:"
                                          (:jar opts) "and" (:jar new-arg))
                             (logger/warn "Ignoring" (:jar opts)
                                          "and using" (:jar new-arg) "\n")
                             (merge opts new-arg))
                           :else
                           (merge opts new-arg)))]
    (loop [opts {} args args]
      (if (seq args)
        (let [[arg more]
              (case (first args)
                ("-C" "--compile")   [{:aot true} (next args)]
                ("-P" "--classpath") [{:classpath (fnext args)} (nnext args)]
                ("-D" "--debug-clash") [{:debug-clash true} (next args)]
                ("-h" "--help")      [{:help true} (next args)]
                ("-J" "--jar")       [{:jar (fnext args)} (nnext args)]
                ("-m" "--main")      [{:main-class (fnext args)} (nnext args)]
                ("-n" "--no-pom")    [{:no-pom true} (next args)]
                ("-S" "--suppress-clash") [{:debug-clash false} (next args)]
                ("-v" "--verbose")   [{:verbose true} (next args)]
                ("-X" "--exclude")   [{:exclude (fnext args)} (nnext args)]
                (if (= \- (ffirst args))
                  (do
                    (logger/warn "Unknown option" (first args) "ignored!")
                    [{} (next args)])
                  [{:jar (first args)} (next args)]))]
          (recur (merge-args opts arg) more))
        opts))))

(defn ^:no-doc -main
  "Deprecated entry point for uberjar invocation via -M."
  [& args]
  (logger/warn "DEPRECATED: -M -m hf.depstar.uberjar -- use -X hf.depstar/uberjar instead.")
  (build-jar-as-main (assoc (parse-args args) :jar-type :uber)))

(comment
  (parse-args ["foo.jar" "-v"])
  (parse-args ["foo.jar" "-V"])
  (parse-args ["-X" "a" "foo.jar" "-X" "b"])
  nil)
