(ns hf.depstar-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hf.depstar.uberjar :as sut])
  (:import (java.io File)
           (java.util.jar JarInputStream)))

(set! *warn-on-reflection* true)

(defn- read-jar [f]
  (with-open [in (-> f (io/input-stream) (JarInputStream.))]
    (loop [entries (transient [])]
      (if-let [entry (.getNextEntry in)]
        (recur (cond-> entries
                 (not (.isDirectory entry))
                 (conj! (.getName entry))))
        (persistent! entries)))))

(deftest simple-thin-jar-test
  (let [jar (File/createTempFile "test" ".jar")]
    (testing "just source"
      (is (= {:success true}
             (sut/build-jar {:jar-type :thin :jar (str jar)})))
      (let [contents (read-jar jar)]
        (is (zero? (count (filter #(str/ends-with? % ".class") contents))))
        (is (= 4 (count (filter #(str/ends-with? % ".clj") contents))))))
    (testing "transitive compilation"
      (is (= {:success true}
             (sut/build-jar {:jar-type :thin :jar (str jar)
                             :compile-ns '[hf.depstar]})))
      (let [contents  (read-jar jar)]
        ;; this should be valid for a while
        (is (< 1000 (count (filter #(str/starts-with? % "clojure/") contents)) 2000))
        (is (= 4 (count (filter #(str/ends-with? % ".clj") contents))))
        (is (< 50 (count (filter #(and (str/starts-with? % "hf/depstar")
                                       (str/ends-with? % ".class")) contents)) 100))))
    (testing "compilation with exclusion"
      (is (= {:success true}
             (sut/build-jar {:jar-type :thin :jar (str jar)
                             :compile-ns '[hf.depstar]
                             :exclude ["clojure/.*"]})))
      (let [contents (read-jar jar)]
        (is (zero? (count (filter #(str/starts-with? % "clojure/") contents))))
        (is (= 4 (count (filter #(str/ends-with? % ".clj") contents))))
        (is (< 50 (count (filter #(str/ends-with? % ".class") contents)) 100))))))
