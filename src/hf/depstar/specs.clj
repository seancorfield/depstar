(ns hf.depstar.specs
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [hf.depstar.uberjar :as u]))

(s/def ::filename string?)
(s/def ::cp-entry string?)
(s/def ::path #(instance? java.nio.file.Path %))

(s/def ::entry-type #{:jar :directory :not-found :unknown})

(s/fdef u/path
  :args (s/cat :s ::filename))

(s/fdef u/copy!
        :args (s/cat :f ::filename
                     :i #(instance? java.io.InputStream %)
                     :target ::path
                     :last-mod inst?))

(s/fdef u/consume-jar
        :args (s/cat :p ::path
                     :f ifn?))

(s/fdef u/classify
        :args (s/cat :e ::cp-entry)
        :ret ::entry-type)

(s/def ::jarfile (s/and string?
                        #(string/ends-with? % ".jar")))
(s/def ::dest ::jarfile)

(s/fdef u/run
        :args (s/cat :options (s/keys :req-un [::dest])))
