# bb-tasks

Reusable [babashka](https://github.com/babashka/babashka) tasks for Outskirts Labs projects.

## Available tasks

### gen-api-docs

Generates AsciiDoc API reference pages from Clojure source using clj-kondo static analysis. Produces one `.adoc` page per public namespace and a nav partial for [Antora](https://antora.org/).

### sync-readme

Syncs a project's README as its Antora index page, rewriting relative doc links to Antora xrefs. If present, it also syncs `CHANGELOG.adoc`, `CONTRIBUTING.adoc`, and `SECURITY.adoc` into Antora pages.

`sync-readme` supports both:

- `README.adoc` (copied directly)
- `README.md` (converted via `pandoc -f gfm -t asciidoc --shift-heading-level-by=-1`)

### gen-manifest

Generates `<antora-start-path>/manifest.edn` from `deps.edn` `[:aliases :neil :project]` metadata and `doc/antora.yml`.

## Setup

Add bb-tasks as a git dependency in your project's `bb.edn`:

```clojure
{:deps {outskirtslabs/bb-tasks
        {:git/url "https://github.com/outskirtslabs/bb-tasks"
         :git/sha "<commit-sha>"}}
 :pods {clj-kondo/clj-kondo {:version "2026.01.19"}}
 :tasks
 {:requires ([ol.bb-tasks.gen-api-docs :as gen-api]
             [ol.bb-tasks.gen-manifest :as gen-manifest]
             [ol.bb-tasks.sync-readme :as sync-readme])
  sync-readme
  {:doc "Sync README as Antora index page"
   :task (sync-readme/sync! {:antora-start-path "doc"})}
  gen-api-docs
  {:doc "Generate API docs"
   :task (gen-api/generate! {:project-root "."
                             :source-paths ["src"]
                             :antora-start-path "doc"
                             :github-repo "https://github.com/your-org/your-repo"
                             :git-branch "main"})}
  gen-manifest
  {:doc "Generate doc/manifest.edn"
   :task (gen-manifest/generate! {:antora-start-path "doc"})}}}
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
| `:readme-path` | Path to the README file (default `"README.adoc"`). Markdown (`.md`) is converted with pandoc. |
| `:changelog-path` | Optional path to changelog file (default `"CHANGELOG.adoc"`) |
| `:contributing-path` | Optional path to contributing file (default `"CONTRIBUTING.adoc"`) |
| `:security-path` | Optional path to security file (default `"SECURITY.adoc"`) |
| `:antora-start-path` | Path to the Antora component root (default `"doc"`) |

For non-Clojure repos that keep `README.md` as source:

```clojure
sync-readme
{:doc "Sync markdown README and optional docs into Antora pages"
 :task (sync-readme/sync! {:antora-start-path "doc"
                           :readme-path "README.md"})}
```

## gen-manifest options

`generate!` accepts a map with:

| Key | Description |
|-----|-------------|
| `:project-root` | Absolute or relative path to the project root (default `"."`) |
| `:antora-start-path` | Path to the Antora component root (default `"doc"`) |
| `:github-repo` | Optional explicit GitHub repo URL override |

## Output

gen-api-docs produces (for each public namespace, excluding `:no-doc` and `:skip-wiki`):

- `<antora-start-path>/modules/ROOT/pages/api/<ns-slug>.adoc` -- one page per namespace
- `<antora-start-path>/modules/ROOT/partials/api-nav.adoc` -- hierarchical nav snippet

The `pages/api/` directory is cleared before each run to remove stale pages.

sync-readme produces:

- `<antora-start-path>/modules/ROOT/pages/index.adoc` -- copy of README.adoc with rewritten links
- `<antora-start-path>/modules/ROOT/pages/changelog.adoc` -- copy of CHANGELOG.adoc (if present)
- `<antora-start-path>/modules/ROOT/pages/contributing.adoc` -- copy of CONTRIBUTING.adoc (if present)
- `<antora-start-path>/modules/ROOT/pages/security.adoc` -- copy of SECURITY.adoc (if present)

gen-manifest produces:

- `<antora-start-path>/manifest.edn` -- normalized project metadata consumed by docs aggregation

Manifest `:project` contains:

- `:id` from `doc/antora.yml` component name.
- `:coord` from `deps.edn` `[:aliases :neil :project :name]` (group/artifact coordinate).
- `:created` from `deps.edn` `[:aliases :neil :project :created]` (ISO date `YYYY-MM-DD`).

Required metadata in `deps.edn`:

```clojure
{:aliases
 {:neil
  {:project
   {:name "com.outskirtslabs/your-lib"
    :description "..."
    :license {:id "MIT"} ; SPDX identifier
    :platforms [:clj :bb]
    :created "2026-02-16"
    :status :maturing}}}}
```

Allowed status values:

- `:experimental`
- `:maturing`
- `:stable`
- `:retired`
- `:static`

## License

Copyright (C) 2025 Casey Link https://casey.link

Licensed under the EUPL-1.2.
