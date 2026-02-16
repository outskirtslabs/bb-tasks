(ns ol.bb-tasks.gen-docs
  "Orchestrates all Antora documentation generation tasks.

  Runs sync-readme and gen-api-docs in sequence.

  Usage as a babashka task:
    bb gen-docs '{:antora-start-path \"doc\" :github-repo \"https://github.com/org/repo\"}'

  Or programmatically:
    (generate! {:antora-start-path \"doc\"
                :github-repo \"https://github.com/org/repo\"})"
  (:require [ol.bb-tasks.sync-readme :as sync-readme]
            [ol.bb-tasks.gen-api-docs :as gen-api-docs]
            [clojure.edn :as edn]))

(defn generate!
  "Generate all Antora documentation: sync README + generate API docs.

  opts is a map with keys:
    :antora-start-path -- path to the Antora component root (e.g. \"doc\")
    :github-repo       -- GitHub repo URL for source links
    :readme-path       -- path to README.adoc (default \"README.adoc\")
    :project-root      -- project root (default \".\")
    :source-paths      -- source paths (default [\"src\"])
    :git-branch        -- git branch for source links (auto-detected if omitted)"
  [opts]
  (sync-readme/sync! opts)
  (gen-api-docs/generate! opts))

(defn -main [& args]
  (generate! (edn/read-string (first args))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
