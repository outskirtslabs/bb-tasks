(ns ol.bb-tasks.sync-readme
  "Sync project AsciiDoc docs into Antora pages.

  Always syncs README.adoc to Antora index.adoc.
  If present, also syncs CHANGELOG.adoc, CONTRIBUTING.adoc, and SECURITY.adoc
  to changelog.adoc, contributing.adoc, and security.adoc respectively.
  Relative doc links are rewritten to Antora xrefs.

  Usage as a babashka task:
    bb sync-readme '{:antora-start-path \"doc\"}'

  Or programmatically:
    (sync! {:antora-start-path \"doc\"})"
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn sync!
  "Sync project AsciiDoc docs into the Antora pages directory.

  opts is a map with keys:
    :project-root      -- project root directory (default \".\")
    :readme-path       -- path to README.adoc relative to project-root (default \"README.adoc\")
    :changelog-path    -- optional path to CHANGELOG.adoc (default \"CHANGELOG.adoc\")
    :contributing-path -- optional path to CONTRIBUTING.adoc (default \"CONTRIBUTING.adoc\")
    :security-path     -- optional path to SECURITY.adoc (default \"SECURITY.adoc\")
    :antora-start-path -- path to the Antora component root (e.g. \"doc\")"
  [opts]
  (let [project-root (str (fs/absolutize (or (:project-root opts) ".")))
        start-path (or (:antora-start-path opts) "doc")
        pages-dir (str (fs/path project-root start-path "modules" "ROOT" "pages"))
        docs [{:source (or (:readme-path opts) "README.adoc")
               :target "index.adoc"
               :required true}
              {:source (or (:changelog-path opts) "CHANGELOG.adoc")
               :target "changelog.adoc"}
              {:source (or (:contributing-path opts) "CONTRIBUTING.adoc")
               :target "contributing.adoc"}
              {:source (or (:security-path opts) "SECURITY.adoc")
               :target "security.adoc"}]]
    (fs/create-dirs pages-dir)
    (doseq [{:keys [source target required]} docs]
      (let [source-path (str (fs/path project-root source))
            target-path (str (fs/path pages-dir target))]
        (cond
          (and required (not (fs/exists? source-path)))
          (throw (ex-info (str "Required file not found: " source-path)
                          {:file source-path}))

          (fs/exists? source-path)
          (let [source-content (slurp source-path)
                rewritten-content (str/replace source-content
                                               (re-pattern (str "link:" start-path "/modules/ROOT/pages/([^\\[]+)\\["))
                                               "xref:$1[")]
            (spit target-path rewritten-content)
            (println "Synced" source-path "->" target-path))

          :else
          (println "Skipped" source-path "(not found)"))))))

(defn -main [& args]
  (sync! (if (seq args)
           (edn/read-string (first args))
           {})))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
