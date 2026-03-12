(ns ol.bb-tasks.publish-clojars-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [ol.bb-tasks.publish-clojars :as sut]))

(deftest prompt-token-falls-back-to-stdin
  (let [err-buffer (java.io.StringWriter.)
        err-writer (java.io.PrintWriter. err-buffer)]
    (with-redefs [sut/system-console (constantly nil)]
      (binding [*in* (java.io.BufferedReader. (java.io.StringReader. "secret-token\n"))
                *err* err-writer]
        (is (= "secret-token" (#'sut/prompt-token! {:prompt "Clojars deploy token: "})))
        (.flush err-writer)
        (is (= "Clojars deploy token: " (str err-buffer)))))))

(deftest publish-uses-prompted-token-when-one-is-not-provided
  (let [calls (atom [])]
    (with-redefs [sut/prompt-token! (constantly "prompted-token")
                  sut/run-command! (fn [opts & command]
                                     (swap! calls conj {:opts opts
                                                        :command command})
                                     {:exit 0})]
      (sut/publish! {:project-root "test-project"})
      (is (= [{:opts {:dir       (str (fs/absolutize "test-project"))
                      :extra-env {"CLOJARS_PASSWORD" "prompted-token"
                                  "CLOJARS_USERNAME" "ramblurr"}
                      :inherit   true}
               :command ["clojure" "-T:build" "deploy"]}]
             @calls)))))

(deftest publish-allows-overriding-the-username-and-command
  (let [calls (atom [])]
    (with-redefs [sut/run-command! (fn [opts & command]
                                     (swap! calls conj {:opts opts
                                                        :command command})
                                     {:exit 0})]
      (sut/publish! {:project-root "custom-project"
                     :token        "explicit-token"
                     :username     "custom-user"
                     :command      ["bb" "noop"]})
      (is (= [{:opts {:dir       (str (fs/absolutize "custom-project"))
                      :extra-env {"CLOJARS_PASSWORD" "explicit-token"
                                  "CLOJARS_USERNAME" "custom-user"}
                      :inherit   true}
               :command ["bb" "noop"]}]
             @calls)))))

(deftest publish-rejects-blank-tokens
  (testing "blank prompted token"
    (with-redefs [sut/prompt-token! (constantly "   ")]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Clojars deploy token cannot be blank"
                            (sut/publish! {})))))
  (testing "blank explicit token"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Clojars deploy token cannot be blank"
                          (sut/publish! {:token ""})))))
