(ns hive-vscode.apply-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-vscode.apply :as apply]
            [hive-vscode.host :as host]
            [hive-vscode.schema :as s]
            [malli.generator :as mg]))

;; SPDX-License-Identifier: MIT

(def fixtures
  (edn/read-string (slurp (io/file "test/fixtures/json_natives.edn"))))

(deftest every-fixture-reaches-the-host-exactly-once
  (let [h (host/recording-host)
        replies (mapv #(apply/apply-payload h (first (:natives %))) fixtures)]
    (is (every? #(s/validate s/Reply %) replies))
    (is (every? #(true? (get % "ok")) replies))
    (is (= [[:show-message "info" "Build finished"]
            [:show-message "warn" "Careful <script>alert(1)</script> & \"quotes\""]
            [:show-message "error" "Build failed"]
            [:upsert-panel "carto-flow" "Carto flow" 23]
            [:close-panel "carto-flow"]
            [:open-file "/tmp/a.clj" 12 3]
            [:open-file "/tmp/b.clj" nil nil]
            [:send-to-terminal "hive" "echo hi"]
            [:emit-event "carto-flow/selected" {"node" "hive.x/f" "depth" 2}]]
           (host/calls h)))
    (is (= {"ok" true "op" "ui/notify" "applied" ["show-message"]} (first replies)))))

(deftest failures-become-error-replies-and-never-reach-the-host
  (let [h (host/recording-host)]
    (is (= {"ok" false "op" "ui/teleport"
            "error" {"code" "vscode/unsupported" "message" "unsupported op ui/teleport"}}
           (apply/apply-payload h {"op" "ui/teleport"})))
    (is (= "vscode/invalid" (get-in (apply/apply-payload h {"op" "ui/open-file"}) ["error" "code"])))
    (is (= "vscode/invalid" (get-in (apply/apply-payload h "junk") ["error" "code"])))
    (is (empty? (host/calls h))))
  (testing "a host that throws yields vscode/failed carrying its message"
    (let [h (host/fail! (host/recording-host) :open-file)]
      (is (= {"ok" false "op" "ui/open-file"
              "error" {"code" "vscode/failed" "message" "no such file"}}
             (apply/apply-payload h {"op" "ui/open-file" "file" "/nope"}))))))

(defspec apply-never-throws-and-always-replies 300
  (prop/for-all [p (gen/one-of [(mg/generator s/Payload) gen/any-printable-equatable])]
    (s/validate s/Reply (apply/apply-payload (host/recording-host) p))))
