(ns ol.bb-tasks.gen-manifest
  "Generate doc/manifest.edn from project metadata and Antora component config."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [ol.bb-tasks.util :as util]))

(def allowed-statuses
  #{:experimental :maturing :stable :retired :static})

(defn- parse-simple-yaml
  "Parse simple top-level `key: value` pairs from YAML.
  This intentionally ignores nested structures (like nav lists)."
  [text]
  (reduce
    (fn [acc raw-line]
      (let [line (str/trim raw-line)]
        (cond
          (or (str/blank? line)
              (str/starts-with? line "#")
              (str/starts-with? line "---"))
          acc

          :else
          (if-let [[_ key raw-value] (re-matches #"([A-Za-z0-9_-]+):\s*(.*)" line)]
            (let [value (cond
                          (or (= raw-value "true") (= raw-value "false"))
                          (= raw-value "true")

                          (and (str/starts-with? raw-value "'")
                               (str/ends-with? raw-value "'")
                               (>= (count raw-value) 2))
                          (subs raw-value 1 (dec (count raw-value)))

                          (and (str/starts-with? raw-value "\"")
                               (str/ends-with? raw-value "\"")
                               (>= (count raw-value) 2))
                          (subs raw-value 1 (dec (count raw-value)))

                          :else raw-value)]
              (assoc acc (keyword key) value))
            acc))))
    {}
    (str/split-lines text)))

(defn- ensure!
  [pred message data]
  (when-not pred
    (throw (ex-info message data))))

(defn- normalize-platforms
  [platforms]
  (->> platforms
       (map (fn [p]
              (cond
                (keyword? p) p
                (string? p) (keyword p)
                :else p)))
       vec))

(defn- normalize-name
  [x]
  (cond
    (string? x) x
    (symbol? x) (str x)
    :else x))

(defn- load-antora-config
  [antora-file]
  (ensure! (fs/exists? antora-file)
           "Missing Antora config file"
           {:file antora-file})
  (let [cfg (parse-simple-yaml (slurp antora-file))]
    (ensure! (string? (:name cfg))
             "doc/antora.yml is missing required top-level key `name`"
             {:file antora-file})
    (ensure! (string? (:title cfg))
             "doc/antora.yml is missing required top-level key `title`"
             {:file antora-file})
    cfg))

(defn- load-neil-project
  [deps-file]
  (ensure! (fs/exists? deps-file)
           "Missing deps.edn"
           {:file deps-file})
  (let [deps (edn/read-string (slurp deps-file))
        project (get-in deps [:aliases :neil :project])]
    (ensure! (map? project)
             "Missing project metadata at [:aliases :neil :project] in deps.edn"
             {:file deps-file})
    project))

(defn- normalize-status
  [status]
  (cond
    (keyword? status) status
    (string? status) (keyword status)
    :else status))

(defn generate!
  "Generate `<antora-start-path>/manifest.edn` from deps.edn and doc/antora.yml.

  opts keys:
    :project-root      -- project root directory (default \".\")
    :antora-start-path -- path to Antora component root (default \"doc\")"
  ([]
   (generate! {}))
  ([opts]
   (let [project-root (str (fs/absolutize (or (:project-root opts) ".")))
         antora-start-path (or (:antora-start-path opts) "doc")
         deps-file (str (fs/path project-root "deps.edn"))
         antora-file (str (fs/path project-root antora-start-path "antora.yml"))
         manifest-file (str (fs/path project-root antora-start-path "manifest.edn"))
         project (load-neil-project deps-file)
         antora (load-antora-config antora-file)
         project-name (normalize-name (:name project))
         license-id (get-in project [:license :id])
         platforms (normalize-platforms (:platforms project))
         status (normalize-status (:status project))
         repo-url (or (:github-repo opts) (util/github-repo-from-remote project-root))]
     (ensure! (string? project-name)
              "Missing required key [:aliases :neil :project :name] in deps.edn"
              {:file deps-file})
     (ensure! (string? (:description project))
              "Missing required key [:aliases :neil :project :description] in deps.edn"
              {:file deps-file})
     (ensure! (string? license-id)
              "Missing required SPDX license id at [:aliases :neil :project :license :id] in deps.edn"
              {:file deps-file})
     (ensure! (vector? platforms)
              "Missing required vector [:aliases :neil :project :platforms] in deps.edn"
              {:file deps-file})
     (ensure! (and (seq platforms) (every? keyword? platforms))
              "Project :platforms must be a non-empty vector of keywords"
              {:file deps-file :platforms platforms})
     (ensure! (contains? allowed-statuses status)
              (str "Project :status must be one of " (sort allowed-statuses))
              {:file deps-file :status status})
     (ensure! (string? repo-url)
              "Could not detect GitHub repo URL from git remotes; pass :github-repo in opts"
              {:project-root project-root})

     (let [manifest {:manifest/version 1
                     :project {:id (:name antora)
                               :name project-name
                               :description (:description project)
                               :license license-id
                               :platforms platforms
                               :status status
                               :version (:version project)}
                     :docs {:component (:name antora)
                            :title (:title antora)
                            :version (:version antora)
                            :prerelease (boolean (:prerelease antora))
                            :start-path antora-start-path}
                     :repo {:url repo-url}}]
       (spit manifest-file
             (with-out-str
               (binding [*print-namespace-maps* false]
                 (pp/pprint manifest))))
       (println "Wrote" manifest-file)))))

(defn -main [& args]
  (generate! (if (seq args)
               (edn/read-string (first args))
               {})))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
