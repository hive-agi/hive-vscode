(ns hive-vscode.ext.vscode-host
  "IVsCodeHost over the VS Code extension API, which is injected (never required
   here) so the rest of the extension loads outside VS Code."
  (:require [hive-vscode.host :as host]
            [hive-vscode.render :as render]))

;; SPDX-License-Identifier: MIT

(defn- nonce []
  (.toString (.randomBytes (js/require "crypto") 16) "hex"))

(defn- position [^js vscode line column]
  (new (.-Position vscode) (max 0 (dec (or line 1))) (max 0 (dec (or column 1)))))

(defn- open! [^js vscode file line column]
  (-> (.openTextDocument (.-workspace vscode) (.file (.-Uri vscode) file))
      (.then (fn [doc]
               (let [p (position vscode line column)
                     sel (new (.-Selection vscode) p p)]
                 (.showTextDocument (.-window vscode) doc #js {:selection sel :preview false}))))
      (.then nil (fn [err]
                   (.showErrorMessage (.-window vscode) (str "hive: cannot open " file ": " (.-message err)))))))

(defrecord VsCodeHost [^js vscode panels listeners]
  host/IVsCodeHost
  (show-message! [_ level message]
    (let [w (.-window vscode)]
      (case level
        "error" (.showErrorMessage w message)
        "warn" (.showWarningMessage w message)
        (.showInformationMessage w message))))

  (open-file! [_ file line column]
    (open! vscode file line column))

  (upsert-panel! [_ panel-id title lines]
    (let [html (render/panel-html title lines (nonce))]
      (if-let [^js panel (get @panels panel-id)]
        (do (set! (.-title panel) title)
            (set! (.. panel -webview -html) html))
        (let [^js panel (.createWebviewPanel (.-window vscode) "hive.panel" title
                                             (.. vscode -ViewColumn -Beside)
                                             #js {:enableScripts true :retainContextWhenHidden true})]
          (swap! panels assoc panel-id panel)
          (.onDidDispose panel (fn [] (swap! panels dissoc panel-id)))
          (.onDidReceiveMessage (.-webview panel)
                                (fn [^js msg]
                                  (when (= "open-file" (.-command msg))
                                    (open! vscode (.-file msg) (.-line msg) nil))))
          (set! (.. panel -webview -html) html)))))

  (close-panel! [_ panel-id]
    (when-let [^js panel (get @panels panel-id)]
      (.dispose panel)))

  (send-to-terminal! [_ terminal text]
    (let [w (.-window vscode)
          ^js existing (some (fn [^js t] (when (= terminal (.-name t)) t)) (array-seq (.-terminals w)))
          ^js term (or existing (.createTerminal w terminal))]
      (.show term true)
      (.sendText term text)))

  (emit-event! [_ event data]
    (doseq [f @listeners] (f event data))))

(defn vscode-host
  "Host over the injected VSCODE module."
  [vscode]
  (->VsCodeHost vscode (atom {}) (atom [])))

(defn on-event!
  "Register (fn [event data]) for json/event deliveries. Returns a dispose fn."
  [h f]
  (swap! (:listeners h) conj f)
  (fn [] (swap! (:listeners h) (fn [fs] (vec (remove #(identical? f %) fs))))))

(defn open-panels [h] (set (keys @(:panels h))))
