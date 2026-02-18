(ns ol.bb-tasks.sync-readme
  "Sync project AsciiDoc docs into Antora pages.

  Always syncs README content to Antora index.adoc.
  README can be AsciiDoc (README.adoc) or Markdown (README.md via pandoc).
  If present, also syncs CHANGELOG.adoc, CONTRIBUTING.adoc, and SECURITY.adoc
  to changelog.adoc, contributing.adoc, and security.adoc respectively.
  Relative doc links are rewritten to Antora xrefs.

  Usage as a babashka task:
    bb sync-readme '{:antora-start-path \"doc\"}'

  Or programmatically:
    (sync! {:antora-start-path \"doc\"})"
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn- markdown-path?
  [path]
  (let [p (str/lower-case (str path))]
    (or (str/ends-with? p ".md")
        (str/ends-with? p ".markdown"))))

(defn- markdown-title
  [markdown]
  (some->> (str/split-lines markdown)
           (some (fn [line]
                   (when-let [[_ title] (re-matches #"(?i)^#\s+(.+?)\s*$" (str/trim line))]
                     title)))
           str/trim
           not-empty))

(defn- markdown->asciidoc
  [source-path]
  (let [{:keys [out err exit]} (p/shell {:out :string :err :string :continue true}
                                         "pandoc" "-f" "gfm" "-t" "asciidoc"
                                         "--shift-heading-level-by=-1"
                                         source-path)]
    (when-not (zero? exit)
      (throw (ex-info "pandoc failed while converting README markdown"
                      {:source source-path :exit exit :err err})))
    out))

(defn- rewrite-links
  [content start-path]
  (str/replace content
               (re-pattern (str "link:" start-path "/modules/ROOT/pages/([^\\[]+)\\["))
               "xref:$1["))

(defn- source->target-content
  [{:keys [source readme]} source-path start-path]
  (if (and readme (markdown-path? source))
    (let [markdown (slurp source-path)
          converted (markdown->asciidoc source-path)
          titled (if-let [title (markdown-title markdown)]
                   (str "= " title "\n\n" converted)
                   converted)]
      (rewrite-links titled start-path))
    (rewrite-links (slurp source-path) start-path)))

(defn sync!
  "Sync project AsciiDoc docs into the Antora pages directory.

  opts is a map with keys:
    :project-root      -- project root directory (default \".\")
    :readme-path       -- path to README source relative to project-root
                          (default \"README.adoc\", supports README.md)
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
               :required true
               :readme true}
              {:source (or (:changelog-path opts) "CHANGELOG.adoc")
               :target "changelog.adoc"}
              {:source (or (:contributing-path opts) "CONTRIBUTING.adoc")
               :target "contributing.adoc"}
              {:source (or (:security-path opts) "SECURITY.adoc")
               :target "security.adoc"}]]
    (fs/create-dirs pages-dir)
    (doseq [{:keys [source target required readme]} docs]
      (let [source-path (str (fs/path project-root source))
            target-path (str (fs/path pages-dir target))]
        (cond
          (and required (not (fs/exists? source-path)))
          (throw (ex-info (str "Required file not found: " source-path)
                          {:file source-path}))

          (fs/exists? source-path)
          (let [target-content (source->target-content {:source source
                                                        :readme readme}
                                                       source-path
                                                       start-path)]
            (spit target-path target-content)
            (println "Synced" source-path "->" target-path))

          :else
          (println "Skipped" source-path "(not found)"))))))

(defn -main [& args]
  (sync! (if (seq args)
           (edn/read-string (first args))
           {})))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
