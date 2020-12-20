(ns hf.depstar.uberjar
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.tools.logging :as logger]
            [clojure.string :as str]
            [clojure.tools.deps.alpha :as t])
  (:import (java.io File InputStream PushbackReader)
           (java.nio.file CopyOption LinkOption OpenOption
                          StandardCopyOption StandardOpenOption
                          FileSystem FileSystems Files
                          FileVisitOption FileVisitResult FileVisitor
                          Path)
           (java.nio.file.attribute FileAttribute FileTime)
           (java.util.jar JarInputStream JarEntry))
  (:gen-class))

(set! *warn-on-reflection* true)

(def ^:dynamic ^:private *debug* nil)
(def ^:dynamic ^:private *exclude* nil)
(def ^:dynamic ^:private *debug-clash* nil)
(def ^:dynamic ^:private *verbose* nil)

(defn env-prop
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

(defonce ^FileSystem FS (FileSystems/getDefault))

;; could add (StandardOpenOption/valueOf "SYNC") here as well but that
;; could slow things down (and hasn't yet proved to be necessary)
(def open-opts (into-array OpenOption [(StandardOpenOption/valueOf "CREATE")]))

(def copy-opts (into-array CopyOption [(StandardCopyOption/valueOf "REPLACE_EXISTING")]))

(def visit-opts (doto
                 (java.util.HashSet.)
                 (.add (FileVisitOption/valueOf "FOLLOW_LINKS"))))

(defonce errors (atom 0))

(defonce multi-release? (atom false))

(defn path
  ^Path [s]
  (.getPath FS s (make-array String 0)))

(def ^:private idiotic-log4j2-plugins-file
  "Log4j2 has a very problematic binary plugins cache file that needs to
  be merged -- but it's going away in 3.0.0 apparently because it keeps
  breaking build tools... As a workaround, if we run into multiple copies
  of it, we will let the larger file overwrite the smaller file so that
  we are likely to end up with more plugins in the final artifact. This
  is not a real solution -- we should rebuild the PluginsCache object
  but I don't want to face that right now. What we actually do here is
  just allow 'small' versions of this file to be overwritten."
  "META-INF/org/apache/logging/log4j/core/config/plugins/Log4j2Plugins.dat")

(def ^:private idiotic-log4j2-plugins-size
  "This is the threshold up to which we'll allow a duplicate Log4j2Plugins.dat
  file to be overwritten. It's arbitrary based on current versions being about
  3K in the log4j 1.2 bridge and about 20K in the log4j2 core file."
  5000)

(def ^:private ok-to-overwrite-idiotic-log4j2-file
  "Assume we can overwrite it until we hit a large version."
  (atom true))

(defn- legal-file? [filename]
  (re-find #"(?i)^(META-INF/)?(COPYRIGHT|NOTICE|LICENSE)(\.(txt|md))?$" filename))

(defn clash-strategy
  [filename]
  (cond
    (re-find #"^data_readers.clj[sc]?$" filename)
    :merge-edn

    (re-find #"^META-INF/services/" filename)
    :concat-lines

    (legal-file? filename)
    :concat-no-dupe

    (= idiotic-log4j2-plugins-file filename)
    :log4j2-surgery

    :else
    :noop))

(defmulti clash (fn [filename _in _target]
                  (let [strategy (clash-strategy filename)]
                    (when *debug-clash*
                      (println "Found" filename "in multiple dependencies,"
                               (case strategy
                                 :merge-edn      "merged as EDN."
                                 :concat-lines   "concatenated it."
                                 :concat-no-dupe "concatenated (if not dupe)."
                                 :log4j2-surgery "selecting the largest."
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
  [filename ^InputStream in ^Path target]
  ;; we should also set the last mod date/time here but it isn't passed in
  ;; and I'm not going to make that change just to make this hack perfect!
  (if @ok-to-overwrite-idiotic-log4j2-file
    (do
      (when *debug*
        (println "overwriting" filename))
      (Files/copy in target ^"[Ljava.nio.file.CopyOption;" copy-opts)
      (when (< idiotic-log4j2-plugins-size (Files/size target))
        ;; we've copied a big enough file, stop overwriting it!
        (when *debug*
          (println "big enough -- no more copying!"))
        (reset! ok-to-overwrite-idiotic-log4j2-file false)))
    (when *debug*
      (println "ignoring" filename))))

(defmethod clash
  :default
  [_ _in _target]
  ;; do nothing, first file wins
  nil)

(def ^:private exclude-patterns
  "Filename patterns to exclude. These are checked with re-matches and
  should therefore be complete filename matches including any path."
  [#"project.clj"
   #"\.keep"
   #".*\.pom$" #"module-info\.class$"
   #"(?i)META-INF/.*\.(?:MF|SF|RSA|DSA)"
   #"(?i)META-INF/(?:INDEX\.LIST|DEPENDENCIES)(?:\.txt)?"])

(defn excluded?
  [filename]
  (or (some #(re-matches % filename) exclude-patterns)
      (some #(re-matches % filename) *exclude*)))

(defn copy!
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
        ;; record first Log4j2Plugins.dat file:
        (when (= idiotic-log4j2-plugins-file filename)
          (when *debug*
            (println "copied" filename (Files/size target)))
          (when (< idiotic-log4j2-plugins-size (Files/size target))
            ;; we've copied a big enough file, stop overwriting it!
            (when *debug*
              (println "big enough -- no more copying!"))
            (reset! ok-to-overwrite-idiotic-log4j2-file false)))
        (when last-mod
          (Files/setLastModifiedTime target last-mod))))
    (when *debug*
      (prn {:excluded filename}))))

(defn consume-jar
  [^Path path f]
  (with-open [is (-> path
                     (Files/newInputStream (make-array OpenOption 0))
                     java.io.BufferedInputStream.
                     JarInputStream.)]
    (loop []
      (when-let [entry (.getNextJarEntry is)]
        (f is entry)
        (recur)))))

(defn classify
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

(defmulti copy-source*
  (fn [src _dest _options]
    (classify src)))

(defmethod copy-source*
  :jar
  [src dest options]
  (when-not (= :thin (:jar-type options))
    (when *verbose* (println src))
    (consume-jar (path src)
      (fn [inputstream ^JarEntry entry]
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

(defn copy-directory
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

(defn copy-source
  [src dest options]
  (copy-source* src dest options))

(defn- calc-classpath
  "Given the options map, use tools.deps.alpha to read and merge the
  applicable `deps.edn` files, combine the specified aliases, calculate
  the project basis (which will resolve/download dependencies), and
  return a vector of items on the classpath."
  [{:keys [aliases repro]
    :or {aliases [] repro true}}]
  (let [{:keys [root-edn user-edn project-edn]} (t/find-edn-maps)
        deps     (t/merge-edns (if repro
                                 [root-edn project-edn]
                                 [root-edn user-edn project-edn]))
        combined (t/combine-aliases deps aliases)
        ;; this could be cleaner, by only selecting the "relevant"
        ;; keys from combined for each of these three uses (but
        ;; I'm waiting for the dust to settle on a possible higher-
        ;; level API appearing in tools.deps.alpha itself):
        basis    (t/calc-basis (t/tool deps combined)
                               {:resolve-args   combined
                                :classpath-args combined})]
    (:classpath-roots basis)))

(comment
  (calc-classpath {})
  (calc-classpath {:aliases [:trial]})
  ,)

(defn- parse-classpath [^String cp]
  (vec (.split cp (System/getProperty "path.separator"))))

(defn- first-by-tag
  [pom-text tag]
  (-> (re-seq (re-pattern (str "<" (name tag) ">([^<]+)</" (name tag) ">")) pom-text)
      (first)
      (second)))

(defn- copy-pom
  "Using the pom.xml file in the current directory, build a manifest
  and pom.properties, and add both those and the pom.xml file to the JAR."
  [^Path dest ^File pom-file {:keys [jar-type main-class]}]
  (let [pom-text    (slurp pom-file)
        jdk         (str/replace (System/getProperty "java.version")
                                 #"_.*" "")
        group-id    (first-by-tag pom-text :groupId)
        artifact-id (first-by-tag pom-text :artifactId)
        version     (first-by-tag pom-text :version)
        build-now   (java.util.Date.)
        last-mod    (FileTime/fromMillis (.getTime build-now))
        manifest    (str "Manifest-Version: 1.0\n"
                         "Built-By: depstar\n"
                         "Build-Jdk: " jdk "\n"
                         (when @multi-release?
                           "Multi-Release: true\n")
                         (when-not (= :thin jar-type)
                           (str "Main-Class: "
                                (if main-class
                                  (str/replace main-class "-" "_")
                                  "clojure.main")
                                "\n")))
        properties  (str "#Generated by depstar\n"
                         "#" build-now "\n"
                         "version=" version "\n"
                         "groupId=" group-id "\n"
                         "artifactId=" artifact-id "\n")
        maven-dir   (str "META-INF/maven/" group-id "/" artifact-id "/")]
    (when-not (and group-id artifact-id version)
      (throw (ex-info "Unable to read pom.xml file!"
                      {:group-id group-id
                       :artifact-id artifact-id
                       :version version})))
    (when *verbose* (println ""))
    (println "Processing pom.xml for"
             (str "{" group-id "/" artifact-id
                  " {:mvn/version \"" version "\"}}"))
    (with-open [is (io/input-stream (.getBytes manifest))]
      (when *verbose*
        (println "\nGenerating META-INF/MANIFEST.MF:\n")
        (print manifest))
      (let [target (.resolve dest "META-INF/MANIFEST.MF")]
        (Files/createDirectories (.getParent target) (make-array FileAttribute 0))
        (copy! "MANIFEST.MF" is target last-mod)))
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

(defn- print-help []
  (println "library usage:")
  (println "  clojure -M:depstar -m hf.depstar.jar MyProject.jar")
  (println "uberjar usage:")
  (println "  clojure -M:depstar -m hf.depstar.uberjar MyProject.jar")
  (println "  clojure -M:depstar -m hf.depstar.uberjar -C -m project.core MyProject.jar")
  (println "options:")
  (println "  -C / --compile   -- enable AOT compilation for uberjar")
  (println "  -P / --classpath <cp> -- override classpath")
  (println "  -D / --debug-clash -- print warnings about clashing jar items")
  (println "  (can be useful if you are not getting the files you expect in the JAR)")
  (println "  -h / --help      -- show this help (and exit)")
  (println "  -J / --jar       -- an alternative way to specify the JAR file")
  (println "  -m / --main      -- specify the main namespace (or class)")
  (println "  -n / --no-pom    -- ignore pom.xml")
  (println "  -v / --verbose   -- explain what goes into the JAR file")
  (println "  -X / --exclude <regex> -- exclude files via regex")
  (println "note: the -C and -m options require a pom.xml file"))

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
  [{:keys [aot classpath debug-clash exclude help jar jar-type main-class
           no-pom pom-file verbose]
    :or {jar-type :uber}
    :as options}]

  (cond

   help
   {:success false :reason :help}

   (not jar)
   {:success false :reason :no-jar}

   :else
   (let [jar        (some-> jar str) ; ensure we have a string
         main-class (some-> main-class str) ; ensure we have a string
         options    (assoc options ; ensure defaulted/processed options present
                           :jar        jar
                           :jar-type   jar-type
                           :main-class main-class)
         ^File
         pom-file (io/file (or pom-file "pom.xml"))
         do-aot
         (if main-class
           (cond (= :thin jar-type)
                 (println "Ignoring -m / --main because a 'thin' JAR was requested!")
                 no-pom
                 (println "Ignoring -m / --main because -n / --no-pom was specified!")
                 (not (.exists pom-file))
                 (println "Ignoring -m / --main because no 'pom.xml' file is present!")
                 :else
                 aot)
           (when aot
             (println "Ignoring -C / --compile because -m / --main was not specified!")))

         tmp-c-dir (when do-aot
                     (Files/createTempDirectory "depstarc" (make-array FileAttribute 0)))
         tmp-z-dir (Files/createTempDirectory "depstarz" (make-array FileAttribute 0))
         cp        (or (some-> classpath (parse-classpath))
                       (calc-classpath options))
         cp        (into (cond-> [] do-aot (conj (str tmp-c-dir))) cp)
         dest-name (str/replace jar #"^.*[/\\]" "")
         jar-path  (.resolve tmp-z-dir ^String dest-name)
         jar-file  (java.net.URI. (str "jar:" (.toUri jar-path)))
         zip-opts  (doto (java.util.HashMap.)
                         (.put "create" "true")
                         (.put "encoding" "UTF-8"))
         aot-failure
         (when do-aot
           (try
             (println "Compiling" main-class "...")
             (binding [*compile-path* (str tmp-c-dir)]
               (compile (symbol main-class)))
             false ; no AOT failure
             (catch Throwable t
               (println (str "\nCompilation of " main-class " failed!\n"
                             "\n" (.getMessage t)
                             (when-let [^Throwable c (.getCause t)]
                               (str "\nCaused by: " (.getMessage c)))))
               true)))]

     (if aot-failure

       {:success false :reason :aot-failed}

       (do
         ;; copy everything to a temporary ZIP (JAR) file:
         (with-open [zfs (FileSystems/newFileSystem jar-file zip-opts)]
           (let [tmp (.getPath zfs "/" (make-array String 0))]
             (reset! errors 0)
             (reset! multi-release? false)
             (println "Building" (name jar-type) "jar:" jar)
             (binding [*debug* (env-prop "debug")
                       *exclude* (mapv re-pattern exclude)
                       *debug-clash* debug-clash
                       *verbose* verbose]
               (run! #(copy-source % tmp options) cp)
               (when (and (not no-pom) (.exists pom-file))
                 (copy-pom tmp pom-file options)))))
         ;; move the temporary file to its final location:
         (let [dest-path (path jar)
               parent (.getParent dest-path)]
           (when parent (.. parent toFile mkdirs))
           (Files/move jar-path dest-path copy-opts))
         ;; was it successful?
         (if (pos? @errors)
           {:success false :reason :copy-failures}
           {:success true}))))))

(defn run*
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

(defn build-jar-as-main
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
          :aot-failed   nil ; details already printed
          :copy-failure (println "\nCompleted with errors!"))
        (System/exit 1)))))

(defn run
  "Deprecated entry point for uberjar invocations via `-X`."
  [options]
  (logger/warn "DEPRECATED: hf.depstar.uberjar/run -- use hf.depstar/uberjar instead.")
  (build-jar-as-main options))

(defn parse-args
  "Returns a hash map with all the options set from command-line args.

  This is to avoid an external dependency on `clojure.tools.cli`."
  [args]
  (let [merge-args (fn [opts new-arg]
                     (cond (contains? new-arg :exclude)
                           (update opts :exclude (fnil conj []) (:exclude new-arg))
                           (and (contains? new-arg :jar)
                                (contains? opts :jar))
                           (do
                             (println "Multiple JAR files specified:"
                                      (:jar opts) "and" (:jar new-arg))
                             (println "Ignoring" (:jar opts)
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
                    (println "Unknown option" (first args) "ignored!")
                    [{} (next args)])
                  [{:jar (first args)} (next args)]))]
          (recur (merge-args opts arg) more))
        opts))))

(defn -main
  [& args]
  (logger/warn "DEPRECATED: -M -m hf.depstar.uberjar -- use -X hf.depstar/uberjar instead.")
  (build-jar-as-main (assoc (parse-args args) :jar-type :uber)))

(comment
  (parse-args ["foo.jar" "-v"])
  (parse-args ["foo.jar" "-V"])
  (parse-args ["-X" "a" "foo.jar" "-X" "b"])
  nil)
