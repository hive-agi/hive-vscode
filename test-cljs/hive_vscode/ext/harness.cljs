(ns hive-vscode.ext.harness
  "Node harness for cross-runtime e2e: the real client over a recording host.
   Prints one JSON line per applied payload: {payload, reply, calls}.
   Usage: node out/harness.js <discovery-path> <expected-count> [timeout-ms]"
  (:require [hive-vscode.ext.client :as client]
            [hive-vscode.host :as host]))

;; SPDX-License-Identifier: MIT

(defn- emit! [m]
  (.write js/process.stdout (str (js/JSON.stringify (clj->js m)) "\n")))

(defn main [& [discovery-path expected timeout-ms]]
  (let [expected (js/parseInt (or expected "1") 10)
        h (host/recording-host)
        seen (atom 0)
        c (atom nil)]
    (reset! c (client/client h {:discovery-path discovery-path
                                :on-state (fn [st] (emit! {:state (name (:status st))}))
                                :on-reply (fn [payload reply]
                                            (emit! {:payload payload :reply reply
                                                    :calls (mapv #(update % 0 name) (host/calls h))})
                                            (when (>= (swap! seen inc) expected)
                                              (client/disconnect! @c)
                                              (js/setTimeout #(js/process.exit 0) 50)))}))
    (client/connect! @c)
    (js/setTimeout (fn []
                     (emit! {:timeout true :seen @seen})
                     (js/process.exit 2))
                   (js/parseInt (or timeout-ms "20000") 10))))
