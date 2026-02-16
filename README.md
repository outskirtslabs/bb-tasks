# bb-tasks

Reusable [babashka](https://github.com/babashka/babashka) tasks for Outskirts Labs projects.

## Available tasks

### gen-api-docs

Generates AsciiDoc API reference pages from Clojure source using clj-kondo static analysis. Produces one `.adoc` page per public namespace and a nav partial for [Antora](https://antora.org/).

### sync-readme

Syncs a project's `README.adoc` as its Antora index page, rewriting relative doc links to Antora xrefs.

## Setup

Add bb-tasks as a git dependency in your project's `bb.edn`:

```clojure
{:deps {outskirtslabs/bb-tasks
        {:git/url "https://github.com/outskirtslabs/bb-tasks"
         :git/sha "<commit-sha>"}}
 :pods {clj-kondo/clj-kondo {:version "2026.01.19"}}
 :tasks
 {:requires ([ol.bb-tasks.gen-api-docs :as gen-api]
             [ol.bb-tasks.sync-readme :as sync-readme])
  sync-readme
  {:doc "Sync README.adoc as Antora index page"
   :task (sync-readme/sync! {:antora-start-path "doc"})}
  gen-api-docs
  {:doc "Generate API docs"
   :task (gen-api/generate! {:project-root "."
                             :source-paths ["src"]
                             :antora-start-path "doc"
                             :github-repo "https://github.com/your-org/your-repo"
                             :git-branch "main"})}}}
```

The clj-kondo pod declaration is required in the consuming project's `bb.edn` -- pods cannot be transitively loaded from dependencies.

## gen-api-docs options

`generate!` accepts a map with:

| Key | Description |
|-----|-------------|
| `:project-root` | Absolute or relative path to the project root |
| `:source-paths` | Vector of source paths relative to project-root |
| `:antora-start-path` | Path to the Antora component root (e.g. `"doc"`) |
| `:github-repo` | GitHub repo URL for source links |
| `:git-branch` | Git branch name for source links |

## sync-readme options

`sync!` accepts a map with:

| Key | Description |
|-----|-------------|
| `:readme-path` | Path to the README file (default `"README.adoc"`) |
| `:antora-start-path` | Path to the Antora component root (default `"doc"`) |

## Output

gen-api-docs produces (for each public namespace, excluding `:no-doc` and `:skip-wiki`):

- `<antora-start-path>/modules/ROOT/pages/api/<ns-slug>.adoc` -- one page per namespace
- `<antora-start-path>/modules/ROOT/partials/api-nav.adoc` -- hierarchical nav snippet

The `pages/api/` directory is cleared before each run to remove stale pages.

sync-readme produces:

- `<antora-start-path>/modules/ROOT/pages/index.adoc` -- copy of README.adoc with rewritten links

## License

Copyright (C) 2025 Casey Link https://casey.link

Licensed under the EUPL-1.2.
