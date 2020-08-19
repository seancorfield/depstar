(ns hf.depstar.uberjar
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
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
(def ^:dynamic ^:private *suppress-clash* nil)
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

(defn clash-strategy
  [filename]
  (cond
    (re-find #"data_readers.clj[sc]?$" filename)
    :merge-edn

    (re-find #"^META-INF/services/" filename)
    :concat-lines

    (= idiotic-log4j2-plugins-file filename)
    :log4j2-surgery

    :else
    :noop))

(defmulti clash (fn [filename in target]
                  (let [strategy (clash-strategy filename)]
                    (when-not *suppress-clash*
                      (prn {:warning "clashing jar item"
                            :path filename
                            :strategy strategy}))
                    strategy)))

(defmethod clash
  :merge-edn
  [_ in target]
  (let [;; read but do not close input stream
        f1 (edn/read (PushbackReader. (io/reader in)))
        ;; read and then close target since we will rewrite it
        f2 (with-open [r (PushbackReader. (Files/newBufferedReader target))]
             (edn/read r))]
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
  [_ in target]
  ;; do nothing, first file wins
  nil)

(def ^:private exclude-patterns
  "Filename patterns to exclude. These are checked with re-matches and
  should therefore be complete filename matches including any path."
  [#"project.clj"
   #"LICENSE"
   #"COPYRIGHT"
   #"\.keep"
   #".*\.pom$" #"module-info\.class$"
   #"(?i)META-INF/.*\.(?:MF|SF|RSA|DSA)"
   #"(?i)META-INF/(?:INDEX\.LIST|DEPENDENCIES|NOTICE|LICENSE)(?:\.txt)?"])

(defn excluded?
  [filename]
  (some #(re-matches % filename) exclude-patterns))

(defn copy!
  ;; filename drives strategy
  [filename ^InputStream in ^Path target last-mod]
  (if-not (excluded? filename)
    (if (Files/exists target (make-array LinkOption 0))
      (clash filename in target)
      (do
        (Files/copy in target ^"[Ljava.nio.file.CopyOption;" copy-opts)
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
  (fn [src dest options]
    (classify src)))

(defmethod copy-source*
  :jar
  [src dest options]
  (when-not (= :thin (:jar options))
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
  [src dest options]
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

(defn parse-classpath [^String cp]
  (vec (.split cp (System/getProperty "path.separator"))))

(defn current-classpath
  []
  (System/getProperty "java.class.path"))

(defn depstar-itself?
  [p]
  (re-find #"depstar" p))

(defn- first-by-tag
  [pom-text tag]
  (-> (re-seq (re-pattern (str "<" (name tag) ">([^<]+)</" (name tag) ">")) pom-text)
      (first)
      (second)))

(defn- copy-pom
  "Using the pom.xml file in the current directory, build a manifest
  and pom.properties, and add both those and the pom.xml file to the JAR."
  [^Path dest {:keys [jar main-class pom-file]}]
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
                         (when-not (= :thin jar)
                           (str "Main-Class: "
                                (if main-class
                                  (str/replace main-class "-" "_")
                                  "clojure.main")
                                "\n")))
        properties  (str "#Generated by depstar\n"
                         "#" build-now "\n"
                         "version: " version "\n"
                         "groupId: " group-id "\n"
                         "artifactId: " artifact-id "\n")
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

(defn run
  [{:keys [aot classpath dest jar main-class
           no-pom ^File pom-file suppress verbose]
    :or {jar :uber}
    :as options}]
  (let [do-aot    (and aot main-class (not no-pom) (.exists pom-file))
        tmp-c-dir (when do-aot
                    (Files/createTempDirectory "depstarc" (make-array FileAttribute 0)))
        tmp-z-dir (Files/createTempDirectory "depstarz" (make-array FileAttribute 0))
        cp        (or classpath (current-classpath))
        cp        (parse-classpath cp)
        cp        (into (cond-> [] do-aot (conj (str tmp-c-dir)))
                        (remove depstar-itself?)
                        cp)
        dest-name (str/replace dest #"^.*[/\\]" "")
        jar-path  (.resolve tmp-z-dir ^String dest-name)
        jar-file  (java.net.URI. (str "jar:" (.toUri jar-path)))
        zip-opts  (doto (java.util.HashMap.)
                    (.put "create" "true")
                    (.put "encoding" "UTF-8"))]

    (when do-aot
      (try
        (println "Compiling" main-class "...")
        (binding [*compile-path* (str tmp-c-dir)]
          (compile (symbol main-class)))
        (catch Throwable t
          (throw (ex-info (str "Compilation of " main-class " failed!")
                          (dissoc options :pom-file)
                          t)))))

    (with-open [zfs (FileSystems/newFileSystem jar-file zip-opts)]
      (let [tmp (.getPath zfs "/" (make-array String 0))]
        (reset! errors 0)
        (reset! multi-release? false)
        (println "Building" (name jar) "jar:" dest)
        (binding [*debug* (env-prop "debug")
                  *suppress-clash* suppress
                  *verbose* verbose]
          (run! #(copy-source % tmp options) cp)
          (when (and (not no-pom) (.exists pom-file))
            (copy-pom tmp options)))))

    (let [dest-path (path dest)
          parent (.getParent dest-path)]
      (when parent (.. parent toFile mkdirs))
      (Files/move jar-path dest-path copy-opts))

    (when (pos? @errors)
      (println "\nCompleted with errors!")
      (System/exit 1))))

(defn help []
  (println "library usage:")
  (println "  clojure -A:depstar -m hf.depstar.jar MyProject.jar")
  (println "uberjar usage:")
  (println "  clojure -A:depstar -m hf.depstar.uberjar MyProject.jar")
  (println "options:")
  (println "  -C / --compile   -- enable AOT compilation for uberjar")
  (println "  -P / --classpath -- override classpath")
  (println "  -h / --help      -- show this help (and exit)")
  (println "  -m / --main      -- specify the main namespace (or class)")
  (println "  -n / --no-pom    -- ignore pom.xml")
  (println "  -S / --suppress-clash")
  (println "                   -- suppress warnings about clashing jar items")
  (println "  -v / --verbose   -- explain what goes into the JAR file")
  (println "note: the -C and -m options require a pom.xml file")
  (System/exit 1))

(defn uber-main
  [opts args]
  (when (some #(#{"-h" "--help"} %) (cons (:dest opts) args))
    (help))
  (let [aot         (some #(#{"-C" "--compile"} %) args)
        partitioned (partition 2 1 args)
        main-class  (some #(when (#{"-m" "--main"} (first %)) (second %))
                          partitioned)
        classpath   (some #(when (#{"-P" "--classpath"} (first %)) (second %))
                          partitioned)
        no-pom      (some #(#{"-n" "--no-pom"}  %) args)
        pom-file    (io/file "pom.xml")
        suppress    (some #(#{"-S" "--suppress-clash"} %) args)
        verbose     (some #(#{"-v" "--verbose"} %) args)
        aot-main    ; sanity check options somewhat:
        (if main-class
          (cond (= :thin (:jar opts))
                (println "Ignoring -m / --main because a 'thin' JAR was requested!")
                no-pom
                (println "Ignoring -m / --main because -n / --no-pom was specified!")
                (not (.exists pom-file))
                (println "Ignoring -m / --main because no 'pom.xml' file is present!")
                :else
                {:aot aot :main-class main-class})
          (when aot
            (println "Ignoring -C / --compile because -m / --main was not specified!")))
        opts (merge opts
                    aot-main
                    {:no-pom no-pom :pom-file pom-file
                     :suppress suppress :verbose verbose
                     :classpath classpath})]
    (run opts)))

(defn -main
  [& [destination & args]]
  (when-not destination
    (help))
  (uber-main {:dest destination :jar :uber} args))
