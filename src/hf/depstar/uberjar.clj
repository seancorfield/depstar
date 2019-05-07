(ns hf.depstar.uberjar
  (:require [clojure.edn :as edn]
            [clojure.java.io :as jio])
  (:import [java.io InputStream OutputStream PushbackReader]
           [java.nio.file CopyOption LinkOption OpenOption
                          StandardCopyOption StandardOpenOption
                          FileSystem FileSystems Files
                          FileVisitResult FileVisitor
                          Path]
           [java.nio.file.attribute BasicFileAttributes FileAttribute]
           [java.util.jar JarInputStream JarOutputStream JarEntry]))

;; future:
;; other knobs?
;; clj -M options?
;; look into MANIFEST entries

(def ^:dynamic ^:private *debug* nil)

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
  [_ in target])
  ;; do nothing, first file wins

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
  [filename ^InputStream in ^Path target & [last-mod]]
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
                        :message (ex-message t)})
                  (swap! errors inc))))))))))

(defn copy-directory
  [^Path src ^Path dest]
  (let [copy-dir
        (reify FileVisitor
          (visitFile [_ p attrs]
            (let [f (.relativize src p)]
              (with-open [is (Files/newInputStream p (make-array OpenOption 0))]
                (copy! (.toString f) is (.resolve dest f))))
            FileVisitResult/CONTINUE)
          (preVisitDirectory [_ p attrs]
            (Files/createDirectories (.resolve dest (.relativize src p))
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
  (copy-directory (path src) dest))

(defmethod copy-source*
  :not-found
  [src _dest _options]
  (prn {:warning "could not find classpath entry" :path src}))

(defn copy-source
  [src dest options]
  (copy-source* src dest options))

(defn write-jar
  [^Path src ^Path target]
  (with-open [os (-> target
                     (Files/newOutputStream open-opts)
                     JarOutputStream.)]
    (let [walker (reify FileVisitor
                   (visitFile [_ p attrs]
                     (let [t (.lastModifiedTime attrs)
                           e (JarEntry. (.toString (.relativize src p)))]
                       (.putNextEntry os (.setLastModifiedTime e t)))
                     (Files/copy p os)
                     FileVisitResult/CONTINUE)
                   (preVisitDirectory [_ p attrs]
                     (when (not= src p) ;; don't insert "/" to zip
                       (.putNextEntry os (JarEntry. (str (.relativize src p) "/")))) ;; directories must end in /
                     FileVisitResult/CONTINUE)
                   (postVisitDirectory [_ p ioexc]
                     (if ioexc (throw ioexc) FileVisitResult/CONTINUE))
                   (visitFileFailed [_ p ioexc] (throw ioexc)))]
      (Files/walkFileTree src walker)))
  :ok)

(defn current-classpath
  []
  (vec (.split ^String
               (System/getProperty "java.class.path")
               (System/getProperty "path.separator"))))

(defn depstar-itself?
  [p]
  (re-find #"depstar" p))

(defn- debug-level
  "Return the requested debug level:
  * if DEPSTAR_DEBUG env var is set, use that,
  * else if depstar.debug system property is set, use that.
  For now, we just treat this as a Boolean (true|false)."
  []
  (when-let [level (or (System/getenv "DEPSTAR_DEBUG")
                       (System/getProperty "depstar.debug"))]
    (case level
      "true"  true
      "false" false ;; because (if (Boolean. "false") :is-truthy :argh!)
      (throw (ex-info "depstar debug should be true or false"
                      {:level    level
                       :env      (System/getenv "DEPSTAR_DEBUG")
                       :property (System/getProperty "depstar.debug")})))))

(defn run
  [{:keys [dest jar] :or {jar :uber} :as options}]
  (let [tmp (Files/createTempDirectory "uberjar" (make-array FileAttribute 0))
        cp (into [] (remove depstar-itself?) (current-classpath))]
    (reset! errors 0)
    (binding [*debug* (debug-level)]
      (run! #(copy-source % tmp options) cp))
    (println "Writing" (name jar) "jar:" dest)
    (write-jar tmp (path dest))
    (when (pos? @errors)
      (println "\nCompleted with errors!")
      (System/exit 1))))

(defn -main
  [destination]
  (run {:dest destination}))
