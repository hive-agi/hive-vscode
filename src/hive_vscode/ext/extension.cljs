(ns hive-vscode.ext.extension
  "VS Code entry point: activate wires the host and client into the editor."
  (:require [hive-vscode.ext.client :as client]
            [hive-vscode.ext.vscode-host :as vh]))

;; SPDX-License-Identifier: MIT

(defonce ^:private runtime (atom nil))

(defn- status-text [{:keys [status applied]}]
  (case status
    :connected (str "$(plug) hive " applied)
    :connecting "$(sync~spin) hive"
    "$(debug-disconnect) hive"))

(defn- register-command! [^js vscode ^js ctx id f]
  (.push (.-subscriptions ctx) (.registerCommand (.-commands vscode) id f)))

(defn activate
  "Extension activation. Returns the public API object."
  [^js ctx]
  (let [^js vscode (js/require "vscode")
        config (.getConfiguration (.-workspace vscode) "hive")
        configured (.get config "discoveryPath")
        h (vh/vscode-host vscode)
        ^js item (.createStatusBarItem (.-window vscode) (.. vscode -StatusBarAlignment -Left) 0)
        c (client/client h {:discovery-path (when (seq configured) configured)
                            :on-state (fn [st] (set! (.-text item) (status-text st)))})]
    (set! (.-command item) "hive.status")
    (set! (.-text item) (status-text {:status :idle}))
    (.show item)
    (.push (.-subscriptions ctx) item)
    (register-command! vscode ctx "hive.connect" #(client/restart! c))
    (register-command! vscode ctx "hive.disconnect" #(client/disconnect! c))
    (register-command! vscode ctx "hive.status"
                       #(.showInformationMessage (.-window vscode)
                                                 (str "hive vessel: " (pr-str (client/status c)))))
    (reset! runtime {:client c :host h})
    (when (not= false (.get config "autoConnect"))
      (client/connect! c))
    #js {:status (fn [] (clj->js (client/status c)))
         :onEvent (fn [f] (vh/on-event! h (fn [event data] (f event (clj->js data)))))
         :openPanels (fn [] (clj->js (vh/open-panels h)))}))

(defn deactivate
  []
  (when-let [{:keys [client]} @runtime]
    (client/disconnect! client)
    (reset! runtime nil)))
