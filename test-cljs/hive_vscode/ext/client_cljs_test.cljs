(ns hive-vscode.ext.client-cljs-test
  "Runs the shared pure strata under ClojureScript, plus client helpers."
  (:require [cljs.test :refer [deftest is testing]]
            [hive-vscode.apply :as apply]
            [hive-vscode.effects :as fx]
            [hive-vscode.ext.client :as client]
            [hive-vscode.host :as host]
            [hive-vscode.render :as render]
            [hive-vscode.sse :as sse]))

;; SPDX-License-Identifier: MIT

(deftest pure-strata-agree-under-cljs
  (is (= [[:open-file "/a" 3 nil]] (fx/payload->effects {"op" "ui/open-file" "file" "/a" "line" 3})))
  (is (= [[:unsupported "x"]] (fx/payload->effects {"op" "x"})))
  (is (= "<div class=\"l f-plain\">&lt;&amp;&gt;</div>" (render/line-html {"text" "<&>" "face" "bogus"})))
  (let [{:keys [events buffer retry]} (sse/feed "retry: 9\r\n\r\ndata: a" "\r\ndata: b\r\n\r\nid: 2")]
    (is (= [{:id nil :event "message" :data "a\nb"}] events))
    (is (= 9 retry))
    (is (= "id: 2" buffer))))

(deftest apply-catches-host-failures-under-cljs
  (let [h (host/fail! (host/recording-host) :show-message)]
    (is (= "vscode/failed" (get-in (apply/apply-payload h {"op" "ui/notify" "message" "m"}) ["error" "code"])))))

(deftest client-helpers
  (is (= [500 1000 2000 4000 8000 15000 15000] (mapv client/backoff-ms [0 1 2 3 4 5 9])))
  (is (nil? (client/read-discovery "/nonexistent/hive/vscode.json")))
  (testing "status excludes live handles"
    (is (= #{:status :attempts :applied :stopped?}
           (set (keys (client/status (client/client (host/recording-host) {:discovery-path "/x"})))))))
  (is (re-find #"/hive-vessel/vscode\.json$" (client/default-discovery-path))))
