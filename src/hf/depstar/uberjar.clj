(ns hf.depstar.uberjar
  (:require [clojure.edn :as edn]
            [clojure.java.io :as jio]
            [clojure.string :as str])
  (:import [java.io InputStream PushbackReader]
           [java.nio.file CopyOption LinkOption OpenOption
                          StandardCopyOption StandardOpenOption
                          FileSystem FileSystems Files
                          FileVisitResult FileVisitor
                          Path]
           [java.nio.file.attribute FileAttribute]
           [java.util.jar JarInputStream JarEntry]))

(set! *warn-on-reflection* true)

(def ^:dynamic ^:private *debug* nil)
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

(defonce errors (atom 0))

(defn path
  ^Path [s]
  (.getPath FS s (make-array String 0)))

(defn clash-strategy
  [filename]
  (cond
    (= "data_readers.clj" filename)
    :merge-edn

    (re-find #"^META-INF/services/" filename)
    :concat-lines

    :else
    :noop))

(defmulti clash (fn [filename in target]
                  (let [stategy (clash-strategy filename)]
                    (prn {:warning "clashing jar item" :path filename :strategy stategy})
                    stategy)))

(defmethod clash
  :merge-edn
  [_ in target]
  (let [;; read but do not close input stream
        f1 (edn/read (PushbackReader. (jio/reader in)))
        ;; read and then close target since we will rewrite it
        f2 (with-open [r (PushbackReader. (Files/newBufferedReader target))]
             (edn/read r))]
    (with-open [w (Files/newBufferedWriter target open-opts)]
      (binding [*out* w]
        (prn (merge f1 f2))))))

(defmethod clash
  :concat-lines
  [_ in target]
  (let [f1 (line-seq (jio/reader in))
        f2 (Files/readAllLines target)]
    (with-open [w (Files/newBufferedWriter target open-opts)]
      (binding [*out* w]
        (run! println (-> (vec f1)
                          (conj "\n")
                          (into f2)))))))

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
        (let [name (.getName entry)
              last-mod (.getLastModifiedTime entry)
              target (.resolve ^Path dest name)]
          (if (.isDirectory entry)
            (Files/createDirectories target (make-array FileAttribute 0))
            (do (Files/createDirectories (.getParent target) (make-array FileAttribute 0))
              (try
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
    (Files/walkFileTree src copy-dir)
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

(defn copy-source
  [src dest options]
  (copy-source* src dest options))

(defn current-classpath
  []
  (vec (.split ^String
               (System/getProperty "java.class.path")
               (System/getProperty "path.separator"))))

(defn depstar-itself?
  [p]
  (re-find #"depstar" p))

(defn run
  [{:keys [dest jar verbose] :or {jar :uber} :as options}]
  (let [cp        (into [] (remove depstar-itself?) (current-classpath))
        tmp-dir   (Files/createTempDirectory "depstar" (make-array FileAttribute 0))
        dest-name (str/replace dest #"^.*[/\\]" "")
        jar-path  (.resolve tmp-dir ^String dest-name)
        jar-file  (java.net.URI. (str "jar:" (.toUri jar-path)))
        zip-opts  (doto (java.util.HashMap.)
                        (.put "create" "true")
                        (.put "encoding" "UTF-8"))]

    (with-open [zfs (FileSystems/newFileSystem jar-file zip-opts)]
      (let [tmp (.getPath zfs "/" (make-array String 0))]
        (reset! errors 0)
        (println "Building" (name jar) "jar:" dest)
        (binding [*debug* (env-prop "debug")
                  *verbose* verbose]
          (run! #(copy-source % tmp options) cp))))

    (let [dest-path (path dest)
          parent (.getParent dest-path)]
      (when parent (.. parent toFile mkdirs))
      (Files/move jar-path dest-path copy-opts))

    (when (pos? @errors)
      (println "\nCompleted with errors!")
      (System/exit 1))))

(defn -main
  [destination & [verbose]]
  (when verbose
    (when-not (#{"-v" "--verbose"} verbose)
      (throw (ex-info "Expected -v or --verbose option" {:option verbose}))))
  (run {:dest destination :verbose verbose}))
