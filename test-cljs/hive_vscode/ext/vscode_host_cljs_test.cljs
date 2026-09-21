(ns hive-vscode.ext.vscode-host-cljs-test
  "VsCodeHost panel painting over a fake injected vscode module."
  (:require [cljs.test :refer [deftest is testing]]
            [hive-vscode.ext.vscode-host :as vh]
            [hive-vscode.host :as host]))

;; SPDX-License-Identifier: MIT

(defn- fake-vscode
  "A vscode module whose webview panels count html writes in WRITES and throw
   on a write while (:fail-writes @STATE) is positive."
  [state]
  #js {:ViewColumn #js {:Beside 2}
       :window
       #js {:createWebviewPanel
            (fn [_ title _ _]
              (let [dispose-fns (atom [])
                    wv #js {:onDidReceiveMessage (fn [_] nil)}
                    panel #js {:title title :webview wv
                               :onDidDispose (fn [f] (swap! dispose-fns conj f))}]
                (set! (.-dispose panel) (fn [] (doseq [f @dispose-fns] (f))))
                (js/Object.defineProperty
                 wv "html"
                 #js {:get (fn [] (:html @state))
                      :set (fn [v]
                             (when (pos? (:fail-writes @state 0))
                               (swap! state update :fail-writes dec)
                               (throw (js/Error. "Webview is disposed")))
                             (swap! state #(-> % (assoc :html v) (update :writes inc))))})
                (swap! state update :created inc)
                panel))}})

(def lines-a [{"text" "alpha" "face" "plain"}])
(def lines-b [{"text" "beta" "face" "plain"}])

(defn- fresh [] (atom {:writes 0 :created 0 :fail-writes 0}))

(deftest an-unchanged-panel-is-not-repainted
  (let [st (fresh) h (vh/vscode-host (fake-vscode st))]
    (host/upsert-panel! h "p" "T" lines-a)
    (host/upsert-panel! h "p" "T" lines-a)
    (host/upsert-panel! h "p" "T" lines-a)
    (is (= 1 (:writes @st)) "equal title and lines leave the webview document alone")
    (host/upsert-panel! h "p" "T" lines-b)
    (is (= 2 (:writes @st)) "changed lines repaint")
    (host/upsert-panel! h "p" "T2" lines-b)
    (is (= 3 (:writes @st)) "a changed title repaints")))

(deftest a-paint-that-threw-is-not-remembered-as-painted
  (let [st (fresh) h (vh/vscode-host (fake-vscode st))]
    (host/upsert-panel! h "p" "T" lines-a)
    (swap! st assoc :fail-writes 1)
    (is (thrown? js/Error (host/upsert-panel! h "p" "T" lines-b)))
    (host/upsert-panel! h "p" "T" lines-b)
    (is (= 2 (:writes @st)) "the retry of the failed paint lands")
    (is (re-find #"beta" (:html @st)))))

(deftest a-disposed-panel-is-recreated-and-painted
  (let [st (fresh) h (vh/vscode-host (fake-vscode st))]
    (host/upsert-panel! h "p" "T" lines-a)
    (host/close-panel! h "p")
    (is (= #{} (vh/open-panels h)))
    (host/upsert-panel! h "p" "T" lines-a)
    (is (= 2 (:created @st)))
    (is (= 2 (:writes @st)) "the same content paints into the new panel")))
