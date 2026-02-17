(ns ol.bb-tasks.gen-docs
  "Orchestrates all Antora documentation generation tasks.

  Runs sync-readme and gen-api-docs in sequence.
  All options have smart defaults -- typically no arguments are needed:

    (generate! {})

  Or from a babashka task:
    bb gen-docs"
  (:require [ol.bb-tasks.sync-readme :as sync-readme]
            [ol.bb-tasks.gen-api-docs :as gen-api-docs]
            [ol.bb-tasks.gen-manifest :as gen-manifest]
            [clojure.edn :as edn]))

(defn generate!
  "Generate all Antora documentation: sync README + generate API docs.

  All options have smart defaults:
    :project-root      -- default \".\"
    :readme-path       -- default \"README.adoc\"
    :source-paths      -- default from deps.edn :paths, fallback [\"src\"]
    :antora-start-path -- default \"doc\"
    :github-repo       -- default from git remote (upstream > origin)
    :git-branch        -- default from current git branch"
  ([] (generate! {}))
  ([opts]
   (sync-readme/sync! opts)
   (gen-api-docs/generate! opts)
   (gen-manifest/generate! opts)))

(defn -main [& args]
  (generate! (if (seq args)
               (edn/read-string (first args))
               {})))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
