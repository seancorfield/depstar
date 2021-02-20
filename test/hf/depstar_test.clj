(ns hf.depstar-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hf.depstar.uberjar :as sut])
  (:import (java.io File)
           (java.util.jar JarInputStream)))

(set! *warn-on-reflection* true)

(defn- attrs->map [^java.util.jar.Attributes attrs]
  (into {} (map (fn [[k v]] [(str k) v])) (.entrySet attrs)))

(defn- read-jar [f & [regex]]
  (with-open [in (-> f (io/input-stream) (JarInputStream.))]
    ;; JDK15+ lets you get at the manifest programmatically
    (let [manifest (when-let [m (.getManifest in)]
                     (into (attrs->map (.getMainAttributes m))
                           (map (fn [[k v]] [k (attrs->map v)])
                                (.getEntries m))))]
      (loop [entries (cond-> {:entries [] :files {}}
                       manifest
                       (assoc :manifest manifest))]
        (if-let [entry (.getNextEntry in)]
          (let [name (.getName entry)
                manifest
                ;; up to JDK14 leaves the manifest in as a file
                (when (= "META-INF/MANIFEST.MF" name)
                  (into {}
                        (map (fn [line]
                                (let [[k v] (str/split line #":")]
                                  [(some-> k (str/trim))
                                   (some-> v (str/trim))])))
                        (line-seq (io/reader in))))]
            (recur (cond-> entries
                     manifest
                     (assoc :manifest manifest)
                     (not (.isDirectory entry))
                     (update :entries conj name)
                     (and regex (re-matches regex name))
                     (update :files assoc name
                             (let [w (java.io.StringWriter.)]
                               (io/copy in w)
                               (.toString w))))))
          entries)))))

(deftest simple-thin-jar-test
  (let [jar (File/createTempFile "test" ".jar")]
    (testing "just source"
      (println "[SOURCE]")
      (is (= {:success true}
             (sut/build-jar {:jar-type :thin :no-pom true :jar (str jar)})))
      (let [contents (:entries (read-jar jar))]
        (is (zero? (count (filter #(str/ends-with? % ".class") contents))))
        (is (= 4 (count (filter #(str/ends-with? % ".clj") contents))))))
    (testing "transitive compilation"
      (println "[COMPILATION]")
      (is (= {:success true}
             (sut/build-jar {:jar-type :thin :no-pom true :jar (str jar)
                             :compile-ns '[hf.depstar]})))
      (let [contents (:entries (read-jar jar))]
        ;; this should be valid for a while
        (is (< 1000 (count (filter #(str/starts-with? % "clojure/") contents)) 2000))
        (is (= 4 (count (filter #(str/ends-with? % ".clj") contents))))
        (is (< 50 (count (filter #(and (str/starts-with? % "hf/depstar")
                                       (str/ends-with? % ".class")) contents)) 100))))
    (testing "compilation with exclusion"
      (println "[COMPILATION/EXCLUSION]")
      (is (= {:success true}
             (sut/build-jar {:jar-type :thin :no-pom true :jar (str jar)
                             :compile-ns '[hf.depstar]
                             :exclude ["clojure/.*"]})))
      (let [contents (:entries (read-jar jar))]
        (is (zero? (count (filter #(str/starts-with? % "clojure/") contents))))
        (is (= 4 (count (filter #(str/ends-with? % ".clj") contents))))
        (is (< 50 (count (filter #(str/ends-with? % ".class") contents)) 100))))))

(deftest compile-ns-using-regex-test
  (let [jar (File/createTempFile "test" ".jar")]
    (println "[COMPILATION/REGEX]")
    (testing "Reproducing the same result of :compile-ns with symbol using regex"
      (is (= {:success true}
             (sut/build-jar {:jar-type :thin :no-pom true :jar (str jar)
                             :compile-ns ["hf.depstar.*"]})))
      (let [contents (:entries (read-jar jar))]
        (is (< 50 (count (filter #(and (str/starts-with? % "hf/depstar")
                                       (str/ends-with? % ".class")) contents))
               100))))
    (testing "Regex must be a full file match."
      (is (= {:success true}
             (sut/build-jar {:jar-type :thin :no-pom true :jar (str jar)
                             :compile-ns ["hf.deps"]})))
      (let [contents (:entries (read-jar jar))]
        (is (zero? (count (filter #(and (str/starts-with? % "hf/depstar")
                                        (str/ends-with? % ".class")) contents))))))
    (testing "And symbol with regexp mixed should work too."
      (is (= {:success true}
             (sut/build-jar {:jar-type :thin :no-pom true :jar (str jar)
                             :compile-ns ['hf.depstar "hf.depstar.uber.*"]})))
      (let [contents (:entries (read-jar jar))]
        (is (< 50 (count (filter #(and (str/starts-with? % "hf/depstar")
                                       (str/ends-with? % ".class")) contents))
               100))
        (is (< 50 (count (filter #(and (str/starts-with? % "hf/depstar/uberjar")
                                       (str/ends-with? % ".class")) contents))
               100))))))

(deftest issue-5
  (println "[#5]")
  (let [jar (File/createTempFile "test" ".jar")]
    (is (= {:success true}
           (sut/build-jar {:jar-type :uber :jar (str jar)
                           :pom-file (str (File/createTempFile "pom" ".xml"))
                           :group-id "issue" :artifact-id "bug" :version "5"
                           :exclude ["org/joda/time/.*"]
                           :aliases [:test-issue-5]})))
    (let [contents (read-jar jar #"data_readers.clj")
          readers  (edn/read-string (or (first (vals (:files contents))) ""))]
      ;; check files from both libs were included:
      (is (some #(= "java_time_literals/core.clj" %) (:entries contents)))
      (is (some #(= "clj_time/core.clj" %) (:entries contents)))
      ;; check data readers were combined:
      (is (some #(= "clj-time" (namespace %)) (keys readers)))
      (is (some #(= "clj-time.coerce" (namespace %)) (vals readers)))
      (is (some #(= "time" (namespace %)) (keys readers)))
      (is (some #(= "java-time-literals.core" (namespace %)) (vals readers))))))

(deftest issue-7
  (println "[#7]")
  (let [jar (File/createTempFile "test" ".jar")]
    (is (= {:success true}
           (sut/build-jar {:jar-type :uber :jar (str jar)
                           :pom-file (str (File/createTempFile "pom" ".xml"))
                           :group-id "issue" :artifact-id "bug" :version "7"
                           :aliases [:test-issue-7]})))
    (let [contents (read-jar jar #"module-info.class")]
      ;; verify module-info.class not present:
      (is (not (some #(= "module-info.class" %) (:entries contents))))
      (is (empty? (:files contents))))))

(deftest issue-22
  (println "[#22]")
  (let [jar (File/createTempFile "test" ".jar")]
    (is (= {:success true}
           (sut/build-jar {:jar-type :uber :jar (str jar)
                           :pom-file (str (File/createTempFile "pom" ".xml"))
                           :group-id "issue" :artifact-id "feature" :version "22"
                           :aliases [:test-issue-22]})))
    (let [contents (read-jar jar)]
      ;; check this triggered MR JAR flags:
      (is (deref #'sut/multi-release?))
      (is (= "true" (get-in contents [:manifest "Multi-Release"]))))))

(deftest issue-66
  (println "[#66]")
  (let [jar (File/createTempFile "test" ".jar")]
    (is (= {:success true}
           (sut/build-jar {:jar-type :uber :jar (str jar)
                           :pom-file (str (File/createTempFile "pom" ".xml"))
                           :group-id "issue" :artifact-id "bug" :version "66"
                           :aliases [:test-issue-66]})))
    #_(let [contents (read-jar jar #"module-info.class")]
      ;; verify module-info.class not present:
        (is (not (some #(= "module-info.class" %) (:entries contents))))
        (is (empty? (:files contents))))))
