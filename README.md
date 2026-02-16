# bb-tasks

Reusable [babashka](https://github.com/babashka/babashka) tasks for Outskirts Labs projects.

## Available tasks

### gen-api-docs

Generates AsciiDoc API reference pages from Clojure source using clj-kondo static analysis. Produces one `.adoc` page per public namespace and a nav partial for [Antora](https://antora.org/).

## Setup

Add bb-tasks as a git dependency in your project's `bb.edn`:

```clojure
{:deps {outskirtslabs/bb-tasks
        {:git/url "https://github.com/outskirtslabs/bb-tasks"
         :git/sha "<commit-sha>"}}
 :pods {clj-kondo/clj-kondo {:version "2024.11.14"}}
 :tasks
 {gen-api-docs
  {:doc "Generate API docs"
   :task (let [gen (requiring-resolve 'ol.bb-tasks.gen-api-docs/generate!)]
           (gen {:project-root "."
                 :source-paths ["src"]
                 :antora-start-path "doc"
                 :github-repo "https://github.com/your-org/your-repo"
                 :git-branch "main"}))}}}
```

The clj-kondo pod declaration is required in the consuming project's `bb.edn` -- pods cannot be transitively loaded from dependencies.

## Options

`generate!` accepts a map with:

| Key | Description |
|-----|-------------|
| `:project-root` | Absolute or relative path to the project root |
| `:source-paths` | Vector of source paths relative to project-root |
| `:antora-start-path` | Path to the Antora component root (e.g. `"doc"`) |
| `:github-repo` | GitHub repo URL for source links |
| `:git-branch` | Git branch name for source links |

## Output

For each public namespace (excluding `:no-doc` and `:skip-wiki` metadata):

- `<antora-start-path>/modules/ROOT/pages/api/<ns-slug>.adoc` -- one page per namespace
- `<antora-start-path>/modules/ROOT/partials/api-nav.adoc` -- hierarchical nav snippet

The `pages/api/` directory is cleared before each run to remove stale pages.

## License

Copyright (C) 2025 Casey Link https://casey.link

Licensed under the EUPL-1.2.
