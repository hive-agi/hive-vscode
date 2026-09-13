(ns hive-vscode.apply
  "Interpreter: a :json native payload -> effects -> IVsCodeHost calls -> reply.
   Never throws; the reply is what the extension acknowledges to hive."
  (:require [hive-vscode.effects :as fx]
            [hive-vscode.host :as host]))

;; SPDX-License-Identifier: MIT

(defn- run-effect!
  [h [kind a b c]]
  (case kind
    :show-message (host/show-message! h a b)
    :upsert-panel (host/upsert-panel! h a b c)
    :close-panel (host/close-panel! h a)
    :open-file (host/open-file! h a b c)
    :send-to-terminal (host/send-to-terminal! h a b)
    :emit-event (host/emit-event! h a b)))

(defn- error-reply [op code message]
  {"ok" false "op" op "error" {"code" code "message" message}})

(defn apply-payload
  "Execute PAYLOAD on host H. Returns a reply map (schema hive-vscode.schema/Reply)."
  [h payload]
  (let [op (when (map? payload) (let [o (get payload "op")] (when (string? o) o)))
        effects (fx/payload->effects payload)
        failure (first (filter fx/failure? effects))]
    (cond
      (= :unsupported (first failure))
      (error-reply op "vscode/unsupported" (str "unsupported op " (second failure)))

      failure
      (error-reply op "vscode/invalid" (nth failure 2))

      :else
      (try
        (doseq [e effects] (run-effect! h e))
        {"ok" true "op" op "applied" (mapv (comp name first) effects)}
        (catch #?(:clj Exception :cljs :default) e
          (error-reply op "vscode/failed" (or (ex-message e) (str e))))))))
