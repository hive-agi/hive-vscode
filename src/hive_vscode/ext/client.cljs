(ns hive-vscode.ext.client
  "Node boundary: read the discovery file, subscribe to the bridge's SSE stream,
   apply each :json native on the host, and POST the reply. Reconnects with
   capped backoff, re-reading discovery each attempt."
  (:require [hive-vscode.apply :as apply]
            [hive-vscode.sse :as sse]))

;; SPDX-License-Identifier: MIT

(def ^:private http (js/require "http"))
(def ^:private fs (js/require "fs"))

(defn default-discovery-path
  []
  (str (or (.. js/process -env -XDG_RUNTIME_DIR) "/tmp") "/hive-vessel/vscode.json"))

(defn read-discovery
  "Discovery map with string keys, or nil when absent or unreadable."
  [path]
  (try
    (let [doc (js->clj (js/JSON.parse (.readFileSync fs path "utf8")))]
      (when (and (map? doc) (integer? (get doc "port")) (string? (get doc "token")))
        doc))
    (catch :default _ nil)))

(defn backoff-ms
  "Delay before reconnect attempt N (0-based): 500ms doubling, capped at 15s."
  [n]
  (min 15000 (* 500 (js/Math.pow 2 (min n 5)))))

(defn- set-state! [client k & kvs]
  (swap! (:state client) #(apply assoc % :status k kvs))
  (when-let [f (:on-state client)] (f @(:state client))))

(defn- post-reply!
  [client doc reply]
  (let [body (js/JSON.stringify (clj->js reply))
        req (.request http #js {:host "127.0.0.1"
                                :port (get doc "port")
                                :method "POST"
                                :path (str "/vessel/reply?token=" (js/encodeURIComponent (get doc "token")))
                                :headers #js {"Content-Type" "application/json"
                                              "Content-Length" (.byteLength js/Buffer body)}})]
    (.on req "error" (fn [_] nil))
    (.on req "response" (fn [res] (.resume res)))
    (.end req body)))

(defn- on-event!
  [client doc {:keys [event data]}]
  (when (= "vessel" event)
    (let [payload (try (js->clj (js/JSON.parse data)) (catch :default _ ::unparseable))
          reply (if (= ::unparseable payload)
                  {"ok" false "op" nil "error" {"code" "vscode/unparseable" "message" "payload is not JSON"}}
                  (apply/apply-payload (:host client) payload))]
      (swap! (:state client) update :applied inc)
      (when-let [f (:on-reply client)] (f payload reply))
      (post-reply! client doc reply))))

(declare connect!)

(defn- schedule-reconnect!
  [client]
  (when (and (:auto-reconnect? client) (not (:stopped? @(:state client))))
    (let [n (:attempts @(:state client))
          t (js/setTimeout #(connect! client) (backoff-ms n))]
      (swap! (:state client) assoc :timer t :attempts (inc n)))))

(defn connect!
  "Open (or reopen) the stream for CLIENT. Returns CLIENT."
  [client]
  (let [doc (read-discovery (:discovery-path client))]
    (if-not doc
      (do (set-state! client :no-discovery) (schedule-reconnect! client))
      (let [buffer (atom "")
            req (.request http #js {:host "127.0.0.1"
                                    :port (get doc "port")
                                    :method "GET"
                                    :path (str "/vessel/events?token=" (js/encodeURIComponent (get doc "token")))
                                    :headers #js {"Accept" "text/event-stream"}})]
        (swap! (:state client) assoc :request req)
        (set-state! client :connecting)
        (.on req "response"
             (fn [^js res]
               (if (not= 200 (.-statusCode res))
                 (do (.resume res)
                     (set-state! client :refused :http-status (.-statusCode res))
                     (schedule-reconnect! client))
                 (do (.setEncoding res "utf8")
                     (swap! (:state client) assoc :attempts 0)
                     (set-state! client :connected :port (get doc "port"))
                     (.on res "data"
                          (fn [chunk]
                            (let [fed (sse/feed @buffer chunk)]
                              (reset! buffer (:buffer fed))
                              (doseq [e (:events fed)] (on-event! client doc e)))))
                     (.on res "end"
                          (fn []
                            (set-state! client :disconnected)
                            (schedule-reconnect! client)))))))
        (.on req "error"
             (fn [^js err]
               (set-state! client :disconnected :error (.-message err))
               (schedule-reconnect! client)))
        (.end req))))
  client)

(defn client
  "Client over HOST. opts: :discovery-path, :on-state (fn [state]), :on-reply
   (fn [payload reply]), :auto-reconnect? (default true)."
  [host {:keys [discovery-path on-state on-reply auto-reconnect?] :or {auto-reconnect? true}}]
  {:host host
   :discovery-path (or discovery-path (default-discovery-path))
   :on-state on-state
   :on-reply on-reply
   :auto-reconnect? auto-reconnect?
   :state (atom {:status :idle :attempts 0 :applied 0 :stopped? false})})

(defn disconnect!
  "Stop CLIENT: cancel any pending reconnect and close the stream."
  [client]
  (let [{:keys [timer request]} @(:state client)]
    (swap! (:state client) assoc :stopped? true)
    (when timer (js/clearTimeout timer))
    (when request (.destroy request))
    (set-state! client :stopped)
    client))

(defn restart!
  "Reconnect a stopped or disconnected CLIENT now."
  [client]
  (disconnect! client)
  (swap! (:state client) assoc :stopped? false :attempts 0)
  (connect! client))

(defn status [client] (dissoc @(:state client) :request :timer))
