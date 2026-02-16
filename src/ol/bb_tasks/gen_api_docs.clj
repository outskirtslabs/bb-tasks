(ns ol.bb-tasks.gen-api-docs
  "Generate AsciiDoc API reference pages from Clojure source using clj-kondo.

  Produces one .adoc page per public namespace and a nav partial for Antora.

  All options have smart defaults -- in most cases no arguments are needed:
    (generate! {})

  Or with explicit overrides:
    (generate! {:project-root \"/path/to/project\"
                :source-paths [\"src\"]
                :antora-start-path \"doc\"
                :github-repo \"https://github.com/org/repo\"
                :git-branch \"main\"})"
  (:require [babashka.pods :as pods]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [ol.bb-tasks.util :as util]))

;; Pod must also be declared in consuming project's bb.edn:
;;   :pods {clj-kondo/clj-kondo {:version "2026.01.19"}}
(pods/load-pod 'clj-kondo/clj-kondo "2026.01.19")
(require '[pod.borkdude.clj-kondo :as clj-kondo])

;; -- Filtering ----------------------------------------------------------------

(defn- skip-ns? [ns-def]
  (or (get-in ns-def [:meta :no-doc])
      (get-in ns-def [:meta :skip-wiki])))

(defn- skip-var? [v]
  (or (:private v)
      (get-in v [:meta :no-doc])
      (get-in v [:meta :skip-wiki])
      ;; filter out defrecord factory fns (map->X, ->X for records)
      (and (= 'clojure.core/deftype (:defined-by v))
           (let [n (str (:name v))]
             (or (str/starts-with? n "->")
                 (str/starts-with? n "map->"))))
      ;; filter out deftype class names (no arglists, defined-by deftype)
      (and (= 'clojure.core/deftype (:defined-by v))
           (nil? (:arglist-strs v)))))

;; -- Protocol member grouping -------------------------------------------------

(defn- protocol-var? [v]
  (= 'clojure.core/defprotocol (:defined-by v)))

(defn- group-protocol-members
  "Given vars for a namespace, return a seq of maps:
   - regular vars as-is (with :kind :var)
   - protocol vars with :kind :protocol and :members [...]"
  [vars]
  (let [protocol-defs (->> vars
                            (filter protocol-var?)
                            (remove :arglist-strs))
        protocol-name-set (set (map :name protocol-defs))
        protocol-methods (->> vars
                              (filter protocol-var?)
                              (filter :arglist-strs)
                              (remove #(contains? protocol-name-set (:name %))))
        protos-with-members
        (for [p protocol-defs]
          (let [members (->> protocol-methods
                             (filter #(= (:filename %) (:filename p)))
                             (filter #(and (>= (:row %) (:row p))
                                           (<= (:row %) (:end-row p))))
                             (sort-by :row))]
            (assoc p :kind :protocol :members members)))
        proto-map (into {} (map (fn [p] [(:name p) p]) protos-with-members))
        regular (->> vars
                     (remove protocol-var?)
                     (map #(assoc % :kind :var)))]
    (->> (concat regular (vals proto-map))
         (sort-by :row))))

;; -- Naming helpers -----------------------------------------------------------

(defn- ns->slug
  "ol.client-ip.core -> ol-client-ip-core"
  [ns-sym]
  (str/replace (str ns-sym) #"\." "-"))

(defn- var->anchor
  "Munge a var name for use as an AsciiDoc anchor ID."
  [var-name]
  (-> (str var-name)
      (munge)
      (str/replace "_" "-")))

(defn- filename-relative
  "Get filename relative to project root."
  [project-root abs-path]
  (str (fs/relativize project-root abs-path)))

(defn- source-url
  "Build GitHub source URL with line range."
  [github-repo git-branch project-root v]
  (let [rel (filename-relative project-root (:filename v))]
    (str github-repo "/blob/" git-branch "/" rel
         "#L" (:row v) "-L" (:end-row v))))

;; -- Docstring processing -----------------------------------------------------

(defn- convert-fenced-code-blocks
  "Convert markdown fenced code blocks to AsciiDoc listing blocks."
  [s]
  (if (nil? s) ""
      (-> s
          (str/replace #"(?m)```(\w+)\n([\s\S]*?)```"
                       (fn [[_ lang body]]
                         (str "[source," lang "]\n----\n" (str/trimr body) "\n----")))
          (str/replace #"(?m)```\n([\s\S]*?)```"
                       (fn [[_ body]]
                         (str "----\n" (str/trimr body) "\n----"))))))

(defn- resolve-var-references
  "Resolve [[var-name]] wikilink patterns to AsciiDoc xrefs."
  [s current-ns all-vars-by-ns all-ns-names]
  (if (nil? s) ""
      (let [var-index (reduce (fn [idx [ns-sym vars]]
                                (reduce (fn [idx2 v]
                                          (update idx2 (str (:name v)) (fnil conj #{}) ns-sym))
                                        idx vars))
                              {} all-vars-by-ns)
            ns-name-set (set (map str all-ns-names))
            resolve-ref (fn [ref-text]
                          (cond
                            ;; Qualified: ns/var
                            (str/includes? ref-text "/")
                            (let [[ns-part var-part] (str/split ref-text #"/" 2)
                                  ns-sym (symbol ns-part)]
                              (if (contains? all-vars-by-ns ns-sym)
                                (str "xref:api/" (ns->slug ns-sym) ".adoc#" (var->anchor var-part)
                                     "[`" ref-text "`]")
                                (str "`" ref-text "`")))
                            ;; Namespace reference
                            (contains? ns-name-set ref-text)
                            (str "xref:api/" (ns->slug (symbol ref-text)) ".adoc[`" ref-text "`]")
                            ;; Same-namespace var
                            (contains? var-index ref-text)
                            (let [nses (get var-index ref-text)]
                              (if (contains? nses current-ns)
                                (str "<<" (var->anchor ref-text) ",`" ref-text "`>>")
                                (let [target-ns (first nses)]
                                  (str "xref:api/" (ns->slug target-ns) ".adoc#" (var->anchor ref-text)
                                       "[`" ref-text "`]"))))
                            :else (str "`" ref-text "`")))]
        (-> s
            (str/replace #"\[\[([^\]]+)\]\]" (fn [[_ ref]] (resolve-ref ref)))))))

(defn- convert-md-headings
  "Convert markdown headings (## Foo) to AsciiDoc (=== Foo).
   Bumps heading level by 2 so ## becomes ==== (level 4) to nest under var == headings."
  [s]
  (if (nil? s) ""
      (-> s
          (str/replace #"(?m)^#### " "====== ")
          (str/replace #"(?m)^### " "===== ")
          (str/replace #"(?m)^## " "==== ")
          (str/replace #"(?m)^# " "=== "))))

(defn- process-docstring [s current-ns all-vars-by-ns all-ns-names]
  (-> s
      (convert-fenced-code-blocks)
      (convert-md-headings)
      (resolve-var-references current-ns all-vars-by-ns all-ns-names)
      (str/replace #"(?m)^  " "")))

;; -- AsciiDoc rendering -------------------------------------------------------

(defn- render-arglist
  [var-name arglist-str]
  (let [args (-> arglist-str
                 (str/replace #"^\[" "")
                 (str/replace #"\]$" "")
                 str/trim)]
    (if (str/blank? args)
      (str "(" var-name ")")
      (str "(" var-name " " args ")"))))

(defn- render-arglists-block [var-name arglist-strs]
  (when (seq arglist-strs)
    (let [lines (map #(render-arglist (str var-name) %) arglist-strs)]
      (str "[source,clojure]\n----\n"
           (str/join "\n" lines)
           "\n----"))))

(defn- render-meta-line [v]
  (let [parts (cond-> []
                (= 'clojure.core/defmacro (:defined-by v))
                (conj "macro")
                (= :protocol (:kind v))
                (conj "protocol")
                (:deprecated v)
                (conj "deprecated")
                (get-in v [:meta :added])
                (conj (str "added in " (get-in v [:meta :added]))))]
    (when (seq parts)
      (str "_" (str/join " | " parts) "_"))))

(defn- render-var-entry [v current-ns vars-by-ns kept-ns-defs github-repo git-branch project-root]
  (let [anchor (var->anchor (str (:name v)))
        name-str (str (:name v))
        arglists (:arglist-strs v)
        doc-str (when (:doc v)
                  (process-docstring (:doc v) current-ns vars-by-ns (map :name kept-ns-defs)))
        meta-line (render-meta-line v)
        src-link (str "[.api-source]\nlink:" (source-url github-repo git-branch project-root v) "[source,window=_blank]")]
    (str/join "\n"
              (filterv some?
                       [(str "[#" anchor "]")
                        (str "== " name-str)
                        ""
                        (render-arglists-block name-str arglists)
                        ""
                        doc-str
                        ""
                        meta-line
                        ""
                        src-link]))))

(defn- render-protocol-member [m vars-by-ns kept-ns-defs]
  (let [anchor (var->anchor (str (:name m)))
        name-str (str (:name m))
        arglists (:arglist-strs m)
        doc-str (when (:doc m)
                  (process-docstring (:doc m) (:ns m) vars-by-ns (map :name kept-ns-defs)))]
    (str/join "\n"
              (filterv some?
                       [(str "[#" anchor "]")
                        (str "=== " name-str)
                        ""
                        (render-arglists-block name-str arglists)
                        ""
                        doc-str]))))

(defn- render-protocol-entry [v current-ns vars-by-ns kept-ns-defs github-repo git-branch project-root]
  (let [anchor (var->anchor (str (:name v)))
        name-str (str (:name v))
        doc-str (when (:doc v)
                  (process-docstring (:doc v) current-ns vars-by-ns (map :name kept-ns-defs)))
        meta-line "_protocol_"
        src-link (str "[.api-source]\nlink:" (source-url github-repo git-branch project-root v) "[source,window=_blank]")
        members-str (when (seq (:members v))
                      (str/join "\n\n'''\n\n"
                                (map #(render-protocol-member % vars-by-ns kept-ns-defs) (:members v))))]
    (str/join "\n"
              (filterv some?
                       [(str "[#" anchor "]")
                        (str "== " name-str)
                        ""
                        doc-str
                        ""
                        meta-line
                        ""
                        src-link
                        ""
                        (when members-str (str "\n" members-str))]))))

(defn- render-ns-page [ns-def vars vars-by-ns kept-ns-defs github-repo git-branch project-root]
  (let [ns-name (str (:name ns-def))
        grouped (group-protocol-members vars)
        doc-str (when (:doc ns-def)
                  (process-docstring (:doc ns-def) (:name ns-def) vars-by-ns (map :name kept-ns-defs)))
        meta-line (when (get-in ns-def [:meta :added])
                    (str "_added in " (get-in ns-def [:meta :added]) "_"))
        entries (map (fn [v]
                       (if (= :protocol (:kind v))
                         (render-protocol-entry v (:name ns-def) vars-by-ns kept-ns-defs github-repo git-branch project-root)
                         (render-var-entry v (:name ns-def) vars-by-ns kept-ns-defs github-repo git-branch project-root)))
                     grouped)]
    (str "= " ns-name "\n"
         (when doc-str (str "\n" doc-str "\n"))
         (when meta-line (str "\n" meta-line "\n"))
         "\n"
         (str/join "\n\n'''\n\n" entries)
         "\n")))

;; -- Nav rendering (hierarchical) ---------------------------------------------

(defn- ns-segments [ns-name]
  (str/split (str ns-name) #"\."))

(defn- longest-common-prefix-segments [segment-seqs]
  (if (empty? segment-seqs)
    []
    (let [min-len (apply min (map count segment-seqs))
          first-seq (first segment-seqs)]
      (loop [i 0]
        (if (>= i min-len)
          (vec (take i first-seq))
          (let [seg (nth first-seq i)]
            (if (every? #(= seg (nth % i)) segment-seqs)
              (recur (inc i))
              (vec (take i first-seq)))))))))

(defn- add-to-trie [trie segments ns-def]
  (if (empty? segments)
    (assoc trie :ns-def ns-def)
    (update-in trie [:children (first segments)]
               (fnil add-to-trie {})
               (rest segments) ns-def)))

(defn- build-ns-trie [ns-defs common-prefix]
  (let [prefix-len (count common-prefix)]
    (reduce
      (fn [trie ns-def]
        (let [segments (ns-segments (:name ns-def))
              suffix (vec (drop prefix-len segments))]
          (add-to-trie trie suffix ns-def)))
      {}
      ns-defs)))

(defn- render-nav-tree [node depth]
  (when-let [children (seq (:children node))]
    (let [sorted-children (sort-by key children)]
      (vec
        (mapcat
          (fn [[segment child]]
            (let [stars (apply str (repeat depth "*"))
                  has-ns? (:ns-def child)
                  entry (if has-ns?
                          (let [slug (ns->slug (:name (:ns-def child)))]
                            (str stars " xref:api/" slug ".adoc[" segment "]"))
                          (str stars " " segment))]
              (cons entry (render-nav-tree child (inc depth)))))
          sorted-children)))))

(defn- render-nav [kept-ns-defs]
  (if (empty? kept-ns-defs)
    ".API Reference\n"
    (let [all-segments (mapv #(ns-segments (:name %)) kept-ns-defs)
          common-prefix (longest-common-prefix-segments all-segments)
          trie (build-ns-trie kept-ns-defs common-prefix)]
      (if (empty? common-prefix)
        (let [lines (render-nav-tree trie 1)]
          (str ".API Reference\n"
               (str/join "\n" lines) "\n"))
        (let [prefix-str (str/join "." common-prefix)]
          (if (and (empty? (:children trie)) (:ns-def trie))
            (let [slug (ns->slug (:name (:ns-def trie)))]
              (str ".API Reference\n"
                   "* xref:api/" slug ".adoc[" prefix-str "]\n"))
            (let [root-line (if (:ns-def trie)
                              (let [slug (ns->slug (:name (:ns-def trie)))]
                                (str "* xref:api/" slug ".adoc[" prefix-str "]"))
                              (str "* " prefix-str))
                  children-lines (render-nav-tree trie 2)]
              (str ".API Reference\n"
                   root-line "\n"
                   (str/join "\n" children-lines) "\n"))))))))

;; -- Public API ---------------------------------------------------------------

(defn generate!
  "Generate AsciiDoc API reference pages from Clojure source.

  All options have smart defaults:
    :project-root      -- default \".\"
    :source-paths      -- default from deps.edn :paths, fallback [\"src\"]
    :antora-start-path -- default \"doc\"
    :github-repo       -- default from git remote (upstream > origin)
    :git-branch        -- default from current git branch"
  [opts]
  (let [project-root (str (fs/absolutize (or (:project-root opts) ".")))
        source-paths (mapv #(str (fs/path project-root %))
                           (or (:source-paths opts)
                               (util/source-paths-from-deps-edn project-root)))
        antora-start-path (or (:antora-start-path opts) "doc")
        antora-module-root (str (fs/path project-root antora-start-path "modules" "ROOT"))
        pages-dir (str (fs/path antora-module-root "pages" "api"))
        partials-dir (str (fs/path antora-module-root "partials"))
        github-repo (or (:github-repo opts) (util/github-repo-from-remote))
        git-branch (or (:git-branch opts) (util/current-git-branch))]
    (assert github-repo "Could not detect :github-repo from git remotes. Pass it explicitly.")

    (println "Analyzing" (str/join ", " source-paths) "...")

    (let [analysis (let [result (clj-kondo/run!
                                  {:lint source-paths
                                   :config {:skip-comments true
                                            :output {:analysis
                                                     {:arglists true
                                                      :var-definitions {:meta [:no-doc :skip-wiki :arglists]}
                                                      :namespace-definitions {:meta [:no-doc :skip-wiki]}}}}})]
                     (:analysis result))
          ns-defs (:namespace-definitions analysis)
          var-defs (:var-definitions analysis)
          kept-ns-names (->> ns-defs
                             (remove skip-ns?)
                             (map :name)
                             set)
          public-vars (->> var-defs
                           (remove skip-var?)
                           (filter #(contains? kept-ns-names (:ns %)))
                           (sort-by (juxt :ns :row)))
          vars-by-ns (group-by :ns public-vars)
          kept-ns-defs (->> ns-defs
                            (remove skip-ns?)
                            (filter #(contains? kept-ns-names (:name %)))
                            (sort-by :name))]

      ;; Clean up old generated files
      (when (fs/exists? pages-dir)
        (fs/delete-tree pages-dir))
      (fs/create-dirs pages-dir)
      (fs/create-dirs partials-dir)

      ;; Write namespace pages
      (doseq [ns-def kept-ns-defs]
        (let [vars (get vars-by-ns (:name ns-def))
              slug (ns->slug (:name ns-def))
              file (str (fs/path pages-dir (str slug ".adoc")))
              content (render-ns-page ns-def vars vars-by-ns kept-ns-defs github-repo git-branch project-root)]
          (println "  Writing" file)
          (spit file content)))

      ;; Write nav partial
      (let [nav-file (str (fs/path partials-dir "api-nav.adoc"))
            content (render-nav kept-ns-defs)]
        (println "  Writing" nav-file)
        (spit nav-file content))

      (println "Done. Generated" (count kept-ns-defs) "namespace pages."))))

(defn -main
  "CLI entry point. First argument is an EDN string with opts."
  [& args]
  (generate! (edn/read-string (first args))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
