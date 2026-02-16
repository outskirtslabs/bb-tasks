(ns ol.bb-tasks.sync-readme
  "Sync a project's README.adoc as its Antora index page.

  Copies README.adoc to the Antora pages directory as index.adoc,
  rewriting relative doc links to Antora xrefs.

  Usage as a babashka task:
    bb sync-readme '{:antora-start-path \"doc\"}'

  Or programmatically:
    (sync! {:antora-start-path \"doc\"})"
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn sync!
  "Sync README.adoc as the Antora index page.

  opts is a map with keys:
    :project-root      -- project root directory (default \".\")
    :readme-path       -- path to the README.adoc file relative to project-root (default \"README.adoc\")
    :antora-start-path -- path to the Antora component root (e.g. \"doc\")"
  [opts]
  (let [project-root (str (fs/absolutize (or (:project-root opts) ".")))
        readme-path (str (fs/path project-root (or (:readme-path opts) "README.adoc")))
        start-path (or (:antora-start-path opts) "doc")
        target (str (fs/path project-root start-path "modules" "ROOT" "pages" "index.adoc"))
        readme (slurp readme-path)
        index (str/replace readme
                (re-pattern (str "link:" start-path "/modules/ROOT/pages/([^\\[]+)\\["))
                "xref:$1[")]
    (spit target index)
    (println "Synced" readme-path "->" target)))

(defn -main [& args]
  (sync! (if (seq args)
           (edn/read-string (first args))
           {})))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
