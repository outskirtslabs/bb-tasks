(ns ol.bb-tasks.util
  "Shared utilities for auto-detecting project configuration."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn current-git-branch
  "Detect the current git branch name."
  ([] (current-git-branch "."))
  ([project-root]
   (str/trim (:out (p/shell {:out :string
                             :dir (str (fs/absolutize project-root))}
                            "git rev-parse --abbrev-ref HEAD")))))

(defn git-remote-url
  "Get the URL of a git remote. Returns nil if the remote doesn't exist."
  ([remote-name] (git-remote-url "." remote-name))
  ([project-root remote-name]
  (try
    (str/trim (:out (p/shell {:out :string
                              :err :string
                              :dir (str (fs/absolutize project-root))}
                             "git" "remote" "get-url" remote-name)))
    (catch Exception _ nil)))
  )

(defn github-repo-from-remote
  "Detect the GitHub repo URL from git remotes.
  Prefers 'upstream' remote, falls back to 'origin'.
  Converts SSH and HTTPS URLs to https://github.com/org/repo format."
  ([] (github-repo-from-remote "."))
  ([project-root]
   (let [url (or (git-remote-url project-root "upstream")
                 (git-remote-url project-root "origin"))]
    (when url
      (-> url
          (str/replace #"\.git$" "")
          (str/replace #"^git@github\.com:" "https://github.com/"))))))

(defn source-paths-from-deps-edn
  "Read :paths from deps.edn in the given project root.
  Returns the paths vector, or [\"src\"] as fallback."
  [project-root]
  (let [deps-file (str (fs/path project-root "deps.edn"))]
    (if (fs/exists? deps-file)
      (let [deps (edn/read-string (slurp deps-file))]
        (or (:paths deps) ["src"]))
      ["src"])))
