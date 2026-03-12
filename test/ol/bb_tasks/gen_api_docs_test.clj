(ns ol.bb-tasks.gen-api-docs-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ol.bb-tasks.gen-api-docs :as sut]))

(deftest merge-ns-defs-collapses-identical-lang-variants
  (let [merged (#'sut/merge-ns-defs
                [{:name 'ol.dirs
                  :lang :clj
                  :filename "src/ol/dirs.cljc"
                  :row 1
                  :end-row 12
                  :doc "Shared namespace doc."
                  :meta {}}
                 {:name 'ol.dirs
                  :lang :cljs
                  :filename "src/ol/dirs.cljc"
                  :row 1
                  :end-row 12
                  :doc "Shared namespace doc."
                  :meta {}}])]
    (is (= 1 (count merged)))
    (is (= 'ol.dirs (:name (first merged))))
    (is (= [:clj :cljs] (:platforms (first merged))))
    (is (= 1 (count (:variant-groups (first merged)))))))

(deftest merge-var-defs-groups-platform-variants-under-one-entry
  (testing "identical variants collapse into one rendered entry"
    (let [merged (#'sut/merge-var-defs
                  [{:ns 'ol.dirs
                    :name 'config-home
                    :lang :clj
                    :filename "src/ol/dirs.cljc"
                    :row 21
                    :end-row 26
                    :doc "Shared doc."
                    :arglist-strs ["[]" "[application]"]
                    :meta {}}
                   {:ns 'ol.dirs
                    :name 'config-home
                    :lang :cljs
                    :filename "src/ol/dirs.cljc"
                    :row 21
                    :end-row 26
                    :doc "Shared doc."
                    :arglist-strs ["[]" "[application]"]
                    :meta {}}])
          page (#'sut/render-var-entry
                (first merged)
                'ol.dirs
                {'ol.dirs merged}
                [{:name 'ol.dirs}]
                "https://github.com/outskirtslabs/ol.dirs"
                "main"
                ".")]
      (is (= 1 (count merged)))
      (is (= 1 (count (re-seq #"(?m)^== config-home$" page))))
      (is (str/includes? page "_platforms: clj, cljs_"))
      (is (not (str/includes? page "=== clj")))
      (is (not (str/includes? page "=== cljs")))))
  (testing "different variants render platform subsections under one anchor"
    (let [merged (#'sut/merge-var-defs
                  [{:ns 'ol.dirs
                    :name 'runtime-dir
                    :lang :clj
                    :filename "src/ol/dirs/runtime/current.clj"
                    :row 10
                    :end-row 12
                    :doc "JVM variant."
                    :arglist-strs ["[]"]
                    :meta {}}
                   {:ns 'ol.dirs
                    :name 'runtime-dir
                    :lang :cljs
                    :filename "src/ol/dirs/runtime/current.cljs"
                    :row 10
                    :end-row 12
                    :doc "Node variant."
                    :arglist-strs ["[]" "[application]"]
                    :meta {}}])
          page (#'sut/render-var-entry
                (first merged)
                'ol.dirs
                {'ol.dirs merged}
                [{:name 'ol.dirs}]
                "https://github.com/outskirtslabs/ol.dirs"
                "main"
                ".")]
      (is (= 1 (count merged)))
      (is (= 1 (count (re-seq #"\[#runtime-dir\]" page))))
      (is (= 1 (count (re-seq #"(?m)^== runtime-dir$" page))))
      (is (str/includes? page "=== clj"))
      (is (str/includes? page "=== cljs"))
      (is (str/includes? page "JVM variant."))
      (is (str/includes? page "Node variant.")))))
