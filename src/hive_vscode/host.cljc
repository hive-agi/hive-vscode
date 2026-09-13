(ns hive-vscode.host
  "Port: what the VS Code vessel needs from its editor, plus a recording fake.")

;; SPDX-License-Identifier: MIT

(defprotocol IVsCodeHost
  (show-message! [this level message] "Show MESSAGE at LEVEL (info|warn|error).")
  (open-file! [this file line column] "Open FILE, revealing LINE and COLUMN (1-based, may be nil).")
  (upsert-panel! [this panel-id title lines] "Create or refresh the panel PANEL-ID with rendered LINES.")
  (close-panel! [this panel-id] "Dispose the panel PANEL-ID if present.")
  (send-to-terminal! [this terminal text] "Send TEXT to the terminal named TERMINAL, creating it if needed.")
  (emit-event! [this event data] "Deliver a custom json/event to extension listeners."))

(defrecord RecordingHost [calls fail-on]
  IVsCodeHost
  (show-message! [_ level message]
    (when (contains? @fail-on :show-message) (throw (ex-info "boom" {})))
    (swap! calls conj [:show-message level message]))
  (open-file! [_ file line column]
    (when (contains? @fail-on :open-file) (throw (ex-info "no such file" {:file file})))
    (swap! calls conj [:open-file file line column]))
  (upsert-panel! [_ panel-id title lines]
    (swap! calls conj [:upsert-panel panel-id title (count lines)]))
  (close-panel! [_ panel-id]
    (swap! calls conj [:close-panel panel-id]))
  (send-to-terminal! [_ terminal text]
    (swap! calls conj [:send-to-terminal terminal text]))
  (emit-event! [_ event data]
    (swap! calls conj [:emit-event event data])))

(defn recording-host
  "Host that records every call in :calls. (fail! host :open-file) makes that method throw."
  []
  (->RecordingHost (atom []) (atom #{})))

(defn fail! [host method] (swap! (:fail-on host) conj method) host)

(defn calls [host] @(:calls host))
