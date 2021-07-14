;; this is based on the tools.build guide examples but
;; uses hf.depstar.api functions for jar and uber instead
;; of the "built-in" versions (from tools.build.api) so
;; that you can get the exclude/merge behavior from depstar

;; to use depstar with tools.build, add a dependency on
;; com.github.seancorfield/depstar to your :build alias
;; and exclude slf4j-nop from the tools.build dependency:

;; :build {:deps {io.github.clojure/tools.build
;;                {:tag "v0.1.2" :sha "81f05b7"
;;                 :exclusions [org.slf4j/slf4j-nop]}
;;                com.github.seancorfield/depstar
;;                {:mvn/version "2.1.267"}}
;;         :ns-default build}

(ns build
  (:require [clojure.tools.build.api :as b]
            [hf.depstar.api :as d]))

(def lib 'my/lib1)
(def version (format "2.1.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def jar-file (format "target/%s-%s.jar" (name lib) version))
(def uber-file (format "target/%s-%s-standalone.jar" (name lib) version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis basis
                :src-dirs ["src"]})
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  ;; replacement for b/jar:
  (d/jar {:class-dir class-dir
          :jar-file jar-file}))

(defn prep [_]
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis basis
                :src-dirs ["src"]})
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir}))

(defn uber [_]
  (b/compile-clj {:basis basis
                  :src-dirs ["src"]
                  :class-dir class-dir})
  ;; replacement for b/uber:
  (d/uber {:class-dir class-dir
           :uber-file uber-file :main 'hf.depstar.uberjar
           :basis basis}))
