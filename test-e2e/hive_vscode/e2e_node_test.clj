(ns hive-vscode.e2e-node-test
  "Cross-runtime e2e. hive-vessel dispatch! on the JVM -> :json SSE bridge ->
   the release-compiled extension client under node (recording host) -> POST
   replies. Needs node, out/harness.js (npm run build) and hive-vessel via
   local.deps.edn:  clojure -Sdeps \"$(cat local.deps.edn)\" -M:e2e"
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [hive-addon.protocol :as addon]
            [hive-vessel.core :as v]
            [hive-vessel.executor.sse :as sse]
            [hive-vscode.addon :as vscode])
  (:import (java.io BufferedReader InputStreamReader)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.util.concurrent TimeUnit)))

;; SPDX-License-Identifier: MIT

(def fixtures
  (edn/read-string (slurp (io/file "test/fixtures/json_natives.edn"))))

(def expected-calls
  [["show-message" "info" "Build finished"]
   ["show-message" "warn" "Careful <script>alert(1)</script> & \"quotes\""]
   ["show-message" "error" "Build failed"]
   ["upsert-panel" "carto-flow" "Carto flow" 23]
   ["close-panel" "carto-flow"]
   ["open-file" "/tmp/a.clj" 12 3]
   ["open-file" "/tmp/b.clj" nil nil]
   ["send-to-terminal" "hive" "echo hi"]
   ["emit-event" "carto-flow/selected" {"node" "hive.x/f" "depth" 2}]])

(defn- temp-discovery []
  (str (Files/createTempDirectory "hive-vscode-e2e" (make-array FileAttribute 0))
       "/hive-vessel/vscode.json"))

(defn- runnable? []
  (and (.exists (io/file "out/harness.js"))
       (zero? (:exit (try (clojure.java.shell/sh "node" "--version") (catch Exception _ {:exit 1}))))))

(defn- spawn-harness
  "Start node out/harness.js; returns {:process p :lines (future vector-of-parsed-lines)}."
  [discovery expected]
  (let [p (-> (ProcessBuilder. ["node" "out/harness.js" discovery (str expected) "20000"])
              (.redirectErrorStream true)
              (.start))]
    {:process p
     :lines (future
              (with-open [r (BufferedReader. (InputStreamReader. (.getInputStream p) "UTF-8"))]
                (vec (keep #(try (json/read-str %) (catch Exception _ {"raw" %}))
                           (line-seq r)))))}))

(defn- wait-until [pred]
  (loop [i 0]
    (cond (pred) true
          (> i 250) false
          :else (do (Thread/sleep 20) (recur (inc i))))))

(defn- drive!
  "Dispatch every fixture input through hive-vessel to TARGET, after the harness connects."
  [target connected?]
  (is (wait-until connected?) "node client connected to the bridge")
  (let [reg (v/standard-registry)]
    (mapv #(v/dispatch! reg target (:input %)) fixtures)))

(defn- check-harness-output [lines]
  (let [replies (filter #(contains? % "reply") lines)]
    (is (= (mapv (comp first :natives) fixtures) (mapv #(get % "payload") replies))
        "the client received every native, in order, as hive-vessel produced it")
    (is (every? #(true? (get-in % ["reply" "ok"])) replies))
    (is (= expected-calls (get (last replies) "calls"))
        "the recording host saw exactly the expected VS Code effects")
    (is (some #(= "connected" (get % "state")) lines))))

(deftest hive-vessel-to-node-extension-through-hive-vscode-addon
  (if-not (runnable?)
    (println "SKIP e2e: node or out/harness.js missing (run npm install && npm run build)")
    (let [path (temp-discovery)
          a (vscode/make-addon {:vscode/discovery-path path})]
      (try
        (addon/initialize! a {})
        (let [bridge (vscode/bridge-of a)
              target ((get (addon/hooks a) vscode/target-hook-key))
              {:keys [process lines]} (spawn-harness path (count fixtures))
              dispatched (drive! target #(= 1 (hive-vscode.bridge/clients bridge)))]
          (is (every? :ok dispatched) (pr-str (remove :ok dispatched)))
          (is (.waitFor ^Process process 25 TimeUnit/SECONDS))
          (is (zero? (.exitValue ^Process process)))
          (check-harness-output @lines)
          (is (wait-until #(= (count fixtures) (count (hive-vscode.bridge/inbox bridge)))))
          (is (every? #(true? (get % "ok")) (hive-vscode.bridge/inbox bridge))
              "every POSTed acknowledgement reached the addon"))
        (finally (addon/shutdown! a))))))

(deftest the-extension-client-speaks-hive-vessel-executor-sse
  (if-not (runnable?)
    (println "SKIP e2e: node or out/harness.js missing")
    (let [path (temp-discovery)
          token "e2e0e2e0e2e0e2e0e2e0e2e0e2e0e2e0"
          bridge (sse/start! {:token token})]
      (try
        (vscode/write-private! path (json/write-str (vscode/discovery-doc {:port (:port bridge) :token token :pid 0})))
        (let [target {:vessel/id :vscode :vessel/dialect :json :vessel/execute! (sse/executor bridge)}
              {:keys [process lines]} (spawn-harness path (count fixtures))
              dispatched (drive! target #(= 1 (sse/clients bridge)))]
          (is (every? :ok dispatched))
          (is (.waitFor ^Process process 25 TimeUnit/SECONDS))
          (is (zero? (.exitValue ^Process process)))
          (check-harness-output @lines)
          (is (wait-until #(= (count fixtures) (count (sse/inbox bridge)))))
          (is (every? #(true? (get (json/read-str %) "ok")) (sse/inbox bridge))))
        (finally (sse/stop! bridge))))))
