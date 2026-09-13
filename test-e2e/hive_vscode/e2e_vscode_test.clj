(ns hive-vscode.e2e-vscode-test
  "Real VS Code e2e: hive-vessel dispatch! -> hive-vscode addon -> the packaged
   extension running in a downloaded VS Code under xvfb-run. Opt-in with
   HIVE_VSCODE_E2E=1 (downloads VS Code into .vscode-test/)."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [hive-addon.protocol :as addon]
            [hive-vessel.core :as v]
            [hive-vscode.addon :as vscode]
            [hive-vessel.executor.sse :as sse])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.util.concurrent TimeUnit)))

;; SPDX-License-Identifier: MIT

(defn- wait-until [pred ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (cond (pred) true
            (> (System/currentTimeMillis) deadline) false
            :else (do (Thread/sleep 250) (recur))))))

(defn- ops [file]
  [{:op :ui/notify :message "hive vessel e2e" :level :info}
   {:op :ui/show-panel :panel/id "carto-flow"
    :doc {:doc/title "Carto flow"
          :doc/blocks [{:block/type :heading :text "Frame 1"}
                       {:block/type :link :text "a.clj:3" :file file :line 3}]}}
   {:op :ui/open-file :file file :line 3 :column 2}
   {:op :ui/send-to-terminal :terminal "hive" :text "echo hive-vessel"}
   {:op :json/event :event "carto-flow/selected" :data {:node "hive.x/f"}}])

(deftest hive-vessel-drives-real-vscode
  (if-not (= "1" (System/getenv "HIVE_VSCODE_E2E"))
    (println "SKIP real VS Code e2e: set HIVE_VSCODE_E2E=1")
    (let [root (str (Files/createTempDirectory "hive-vscode-real" (make-array FileAttribute 0)))
          runtime (str root "/runtime")
          workspace (str root "/ws")
          file (str workspace "/a.clj")
          ready (str root "/ready")
          result (str root "/result.json")
          a (vscode/make-addon {:vscode/discovery-path (str runtime "/hive-vessel/vscode.json")})]
      (io/make-parents file)
      (spit file "(ns a)\n\n(defn f [x]\n  (inc x))\n")
      (try
        (addon/initialize! a {})
        (let [pb (doto (ProcessBuilder. ["xvfb-run" "-a" "node" "test-vscode/run.js"])
                   (.redirectErrorStream true)
                   (.redirectOutput (io/file (str root "/vscode.log"))))
              env (.environment pb)
              _ (doseq [[k val] {"XDG_RUNTIME_DIR" runtime "HIVE_VSCODE_READY" ready
                                 "HIVE_VSCODE_RESULT" result "HIVE_VSCODE_EXPECT" "5"
                                 "HIVE_VSCODE_FILE" file "HIVE_VSCODE_WORKSPACE" workspace
                                 "HIVE_VSCODE_USER_DIR" (str root "/user")}]
                  (.put env k val))
              p (.start pb)
              b (vscode/bridge-of a)
              target ((get (addon/hooks a) vscode/target-hook-key))]
          (is (wait-until #(.exists (io/file ready)) 300000)
              (str "extension connected; log " root "/vscode.log"))
          (is (= 1 (sse/clients b)))
          (let [reg (v/standard-registry)]
            (is (every? :ok (mapv #(v/dispatch! reg target %) (ops file)))))
          (is (.waitFor p 180 TimeUnit/SECONDS))
          (is (zero? (.exitValue p)) (str "see " root "/vscode.log"))
          (let [r (json/read-str (slurp result))]
            (println "real VS Code e2e result:" (pr-str r))
            (is (= "connected" (get-in r ["status" "status"])))
            (is (= 5 (get-in r ["status" "applied"])))
            (is (some #{"carto-flow"} (get r "panels")))
            (is (some #{"hive"} (get r "terminals")))
            (is (= file (get r "activeFile")))
            (is (= 2 (get r "activeLine")))
            (is (= 1 (get r "activeColumn")))
            (is (= [{"event" "carto-flow/selected" "data" {"node" "hive.x/f"}}] (get r "events"))))
          (is (wait-until #(= 5 (count (sse/inbox b))) 10000))
          (is (every? #(true? (get % "ok")) (vscode/replies a))))
        (finally (addon/shutdown! a))))))
