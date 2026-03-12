(ns ol.bb-tasks.publish-clojars
  "Helpers for publishing jars to Clojars from babashka tasks."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn system-console
  "Wrapper around System/console for easier testing."
  []
  (System/console))

(defn prompt-token!
  "Prompt for a Clojars deploy token.

  Uses the JVM console when available so the token is not echoed. Falls back
  to standard input when no console is attached."
  [{:keys [prompt]
    :or   {prompt "Clojars deploy token: "}}]
  (if-let [console (system-console)]
    (some-> (.readPassword console "%s" (into-array Object [prompt]))
            (String.)
            str/trim)
    (do
      (binding [*out* *err*]
        (print prompt)
        (flush))
      (some-> (read-line) str/trim))))

(defn run-command!
  "Run a process, inheriting stdio so the underlying deploy output is visible."
  [opts & command]
  (apply p/shell opts command))

(defn publish!
  "Prompt for a Clojars token, set the deploy env vars, and run the deploy command.

  opts accepts:
    :project-root -- directory to run the deploy command from, default \".\"
    :username     -- Clojars username, default \"ramblurr\"
    :token        -- explicit token override, otherwise prompted for
    :prompt       -- prompt shown when asking for the token
    :command      -- command vector to run, default [\"clojure\" \"-T:build\" \"deploy\"]"
  ([] (publish! {}))
  ([{:keys [project-root username token prompt command]
     :or   {project-root "."
            username     "ramblurr"
            prompt       "Clojars deploy token: "
            command      ["clojure" "-T:build" "deploy"]}}]
   (let [token (or token (prompt-token! {:prompt prompt}))]
     (when (str/blank? token)
       (throw (ex-info "Clojars deploy token cannot be blank"
                       {:prompt prompt})))
     (apply run-command!
            {:dir       (str (fs/absolutize project-root))
             :extra-env {"CLOJARS_PASSWORD" token
                         "CLOJARS_USERNAME" username}
             :inherit   true}
            command))))

(defn -main [& args]
  (publish! (if (seq args)
              (edn/read-string (first args))
              {})))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
