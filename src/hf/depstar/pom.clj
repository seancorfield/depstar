;; copyright (c) 2019-2021 sean corfield

(ns ^:no-doc hf.depstar.pom
  "Logic for creating, sync'ing, reading, and copying pom.xml files."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.deps.alpha.gen.pom :as pom]
            [clojure.tools.logging :as logger]
            [hf.depstar.files :as files])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(set! *warn-on-reflection* true)

(defn- pom-sync
  "Give a (pom) file, the project basis, and the options, synchronize
  the group/artifact/version if requested (using tools.deps.alpha).

  t.d.a always creates pom.xml within the target-dir but src-pom does
  not need to end in pom.xml really.

  Our pom-file option is generally the target version, but if we honor
  target-dir it will become the src-pom (only) and we need to reflect
  that in the options we return."
  [basis
   {:keys [artifact-id group-id pom-file target-dir version]
    :as  options}]
  (let [source-pom (io/file pom-file)
        target-pom (io/file target-dir "pom.xml")
        new-pom    (not (.exists target-pom))]
    ;; #56 require GAV when sync-pom used to create pom.xml, if no source pom:
    (if (and new-pom (not (.exists source-pom))
             (not (and group-id artifact-id version)))
      (logger/warn ":group-id, :artifact-id, and :version are all required"
                   "when creating a new 'pom.xml' file!")
      (do
        (Files/createDirectories (.getParent (files/path (.getPath target-pom)))
                                 (make-array FileAttribute 0))
        (logger/info "Synchronizing"
                     (if (or new-pom
                             (= (.getCanonicalPath source-pom)
                                (.getCanonicalPath target-pom)))
                       (.getName target-pom)
                       (str (.getName source-pom) " to " target-dir "/pom.xml")))
        (pom/sync-pom
         {:basis basis
          :params (cond-> {:target-dir target-dir
                           :src-pom    (.getPath source-pom)}
                    (and new-pom group-id artifact-id)
                    (assoc :lib (symbol (name group-id) (name artifact-id)))
                    (and new-pom version)
                    (assoc :version version))})))
    (assoc options :pom-file (.getPath target-pom) ::gav-update new-pom)))

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
  [^File pom-in ^File pom-out
   {:keys [artifact-id group-id version] :as options}]
  (let [pom-text     (slurp (if (.exists pom-in) pom-in pom-out))
        artifact-id' (first-by-tag pom-text :artifactId)
        group-id'    (first-by-tag pom-text :groupId)
        version'     (first-by-tag pom-text :version)
        result       {:artifact-id (or artifact-id artifact-id')
                      :group-id    (or group-id    group-id')
                      :version     (or version     version')}]
    (when-not (and (:group-id result) (:artifact-id result) (:version result))
      (throw (ex-info "Unable to establish group/artifact and version!" result)))
    ;; do we need to override any of the pom.xml values?
    (when (or (::gav-update options) ; forced update on (target) pom file
              (and artifact-id artifact-id' (not= artifact-id artifact-id'))
              (and group-id    group-id'    (not= group-id    group-id'))
              (and version     version'     (not= version     version')))
      (logger/info "Updating pom.xml file to"
                   (str "{"
                        (:group-id result) "/"
                        (:artifact-id result) " "
                        "{:mvn/version \"" (:version result) "\"}"
                        "}"))
      (spit pom-out
            (cond-> pom-text
              (and artifact-id artifact-id'
                   (or (::gav-update options)
                       (not= artifact-id artifact-id')))
              (str/replace-first (str "<artifactId>" artifact-id' "</artifactId>")
                                 (str "<artifactId>" artifact-id  "</artifactId>"))

              (and group-id    group-id'
                   (or (::gav-update options)
                       (not= group-id group-id')))
              (str/replace-first (str "<groupId>" group-id' "</groupId>")
                                 (str "<groupId>" group-id  "</groupId>"))

              (and version     version'
                   (or (::gav-update options)
                       (not= version version')))
              (->
               (str/replace-first (str "<version>" version' "</version>")
                                  (str "<version>" version  "</version>"))
                ;; also replace <tag> if it matched <version> with any prefix:
               (str/replace-first (re-pattern (str "<tag>([^<]*)"
                                                   (java.util.regex.Pattern/quote version')
                                                   "</tag>"))
                                  (str "<tag>$1" version "</tag>"))))))
    result))

(defn task*
  "Handling of pom.xml as a -X task.

  Inputs (all optional, except :basis):
  * artifact-id (string)  -- <artifactId> to write to pom.xml
  * basis       (map)     -- the basis to use
  * group-id    (string)  -- <groupId> to write to pom.xml
  * no-pom      (boolean) -- do not read/update group/artifact/version
  * pom-file    (string)  -- override default pom.xml path
  * sync-pom    (boolean) -- sync deps to pom.xml, create if missing
  * target-dir  (string)  -- override default pom.xml generation path
  * version     (string)  -- <version> to write to pom.xml

  Outputs:
  * artifact-id (string)  -- if not no-pom, <artifactId> from pom.xml
  * group-id    (string)  -- if not no-pom, <groupId> from pom.xml
  * version     (string)  -- if not no-pom, <version> from pom.xml"
  [{:keys [basis no-pom pom-file sync-pom target-dir]
    :as   options}]
  (let [pom-in     (io/file pom-file)
        target-pom (or target-dir (.getParent pom-in) ".")
        options    (if (or target-dir sync-pom)
                     ;; target-dir for this operation is based on :target-dir
                     ;; or defaults to the path of :pom-file (if any):
                     (pom-sync basis (assoc options :target-dir target-pom))
                     options)
        ^File      ; check the pom-file that pom-sync may have generated:
        pom-out    (io/file (:pom-file options))]
    (merge options
           (when (and (or (::gav-update options) (not no-pom))
                      (.exists pom-out))
             (sync-gav pom-in pom-out options)))))
