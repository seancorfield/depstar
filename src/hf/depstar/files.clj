;; copyright (c) 2018-2021 sean corfield, ghadi shayban

(ns ^:no-doc hf.depstar.files
  "Low-level file handling and classification utilities."
  (:import (java.nio.file LinkOption FileSystem FileSystems Files Path)))

(set! *warn-on-reflection* true)

(def ^:dynamic *exclude* nil)

(defonce ^:private ^FileSystem FS (FileSystems/getDefault))

(defn path
  ^Path [s]
  (.getPath FS s (make-array String 0)))

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

(def ^:private exclude-patterns
  "Filename patterns to exclude. These are checked with re-matches and
  should therefore be complete filename matches including any path."
  [#"project.clj"
   #"(.*/)?\.DS_Store"
   #"(.*/)?\.keep"
   #".*\.pom" #"module-info\.class"
   #"(?i)META-INF/.*\.(?:MF|SF|RSA|DSA)"
   #"(?i)META-INF/(?:INDEX\.LIST|DEPENDENCIES)(?:\.txt)?"])

(defn excluded?
  [filename]
  (or (some #(re-matches % filename) exclude-patterns)
      (some #(re-matches % filename) *exclude*)))

(defn included?
  [filename patterns]
  (some #(re-matches % filename) patterns))

(defn delete-path-on-exit
  "Given a Path, register it for deletion on exit of this process."
  [^Path path]
  (.deleteOnExit (.toFile path)))

(defn parse-classpath [^String cp]
  (vec (.split cp (System/getProperty "path.separator"))))
