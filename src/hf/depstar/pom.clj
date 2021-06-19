;; copyright (c) 2019-2021 sean corfield

(ns ^:no-doc hf.depstar.pom
  "Logic for creating, sync'ing, reading, and copying pom.xml files."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.deps.alpha.gen.pom :as pom]
            [clojure.tools.logging :as logger]
            [hf.depstar.task :as task])
  (:import (java.io File)))

(set! *warn-on-reflection* true)

(defn pom-sync
  "Give a (pom) file, the project basis, and the options, synchronize
  the group/artifact/version if requested (using tools.deps.alpha)."
  [^File pom-file basis {:keys [artifact-id group-id version]}]
  (let [new-pom (not (.exists pom-file))]
    ;; #56 require GAV when sync-pom used to create pom.xml:
    (if (and new-pom (not (and group-id artifact-id version)))
      (logger/warn "Ignoring :sync-pom because :group-id, :artifact-id, and"
                   ":version are all required when creating a new 'pom.xml' file!")
      (do
        (logger/info "Synchronizing" (.getName pom-file))
        (pom/sync-pom
         {:basis basis
          :params (cond-> {:target-dir (or (.getParent pom-file) ".")
                           :src-pom    (.getPath pom-file)}
                    (and new-pom group-id artifact-id)
                    (assoc :lib (symbol (name group-id) (name artifact-id)))
                    (and new-pom version)
                    (assoc :version version))})))))

(defn- first-by-tag
  [pom-text tag]
  (-> (re-seq (re-pattern (str "<" (name tag) ">([^<]+)</" (name tag) ">")) pom-text)
      (first)
      (second)))

(defn sync-gav
  "Given a pom file and options, return the group/artifact IDs and
  the version. These are taken from the options, if present, otherwise
  they are read in from the pom file.

  Returns a hash map containing the group/artifact IDs, and the version.

  If the values provided in the options differ from those in the pom file,
  the pom file will be updated to reflect those in the options hash map.

  Throw an exception if we cannot read them from the pom file."
  [pom-file {:keys [artifact-id group-id version]}]
  (let [pom-text     (slurp pom-file)
        artifact-id' (first-by-tag pom-text :artifactId)
        group-id'    (first-by-tag pom-text :groupId)
        version'     (first-by-tag pom-text :version)
        result       {:artifact-id (or artifact-id artifact-id')
                      :group-id    (or group-id    group-id')
                      :version     (or version     version')}]
    (when-not (and (:group-id result) (:artifact-id result) (:version result))
      (throw (ex-info "Unable to establish group/artifact and version!" result)))
    ;; do we need to override any of the pom.xml values?
    (when (or (and artifact-id artifact-id' (not= artifact-id artifact-id'))
              (and group-id    group-id'    (not= group-id    group-id'))
              (and version     version'     (not= version     version')))
      (logger/info "Updating pom.xml file to"
                   (str "{"
                        (:group-id result) "/"
                        (:artifact-id result) " "
                        "{:mvn/version \"" (:version result) "\"}"
                        "}"))
      (spit pom-file
            (cond-> pom-text
              (and artifact-id artifact-id' (not= artifact-id artifact-id'))
              (str/replace-first (str "<artifactId>" artifact-id' "</artifactId>")
                                 (str "<artifactId>" artifact-id  "</artifactId>"))

              (and group-id    group-id'    (not= group-id group-id'))
              (str/replace-first (str "<groupId>" group-id' "</groupId>")
                                 (str "<groupId>" group-id  "</groupId>"))

              (and version     version'     (not= version version'))
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

  Inputs (all optional):
  * artifact-id (string)  -- <artifactId> to write to pom.xml
  * group-id    (string)  -- <groupId> to write to pom.xml
  * no-pom      (boolean) -- do not read/update group/artifact/version
  * pom-file    (string)  -- override default pom.xml path
  * sync-pom    (boolean) -- sync deps to pom.xml, create if missing
  * version     (string)  -- <version> to write to pom.xml

  Outputs:
  * artifact-id (string)  -- if not no-pom, <artifactId> from pom.xml
  * group-id    (string)  -- if not no-pom, <groupId> from pom.xml
  * version     (string)  -- if not no-pom, <version> from pom.xml"
  [options]
  (let [{:keys [group-id no-pom pom-file sync-pom]
         :or   {pom-file "pom.xml"}
         :as   options}
        (task/preprocess-options options)
        _
        (when (and group-id (not (re-find #"\." (str group-id))))
          (logger/warn ":group-id should probably be a reverse domain name, not just" group-id))
        ;; ensure defaulted options are present:
        options    (assoc options :pom-file pom-file)

        basis      (task/calc-project-basis options)
        ^File
        pom-file   (io/file pom-file)
        _
        (when sync-pom
          (pom-sync pom-file basis options))]
    (merge options
           (when (and (not no-pom) (.exists pom-file))
             (sync-gav pom-file options)))))
