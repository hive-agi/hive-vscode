(ns hive-vscode.bridge
  "Loopback :json vessel bridge: hive pushes native payloads to every connected
   VS Code window as Server-Sent Events; windows acknowledge with POST.
   Wire-compatible copy of hive-deepseek.bridge (hive-81), pending its extraction
   into hive-vessel.

   Routes (every request carries ?token=):
     GET  /vessel/events   SSE stream (event: vessel); replays retained panels
     POST /vessel/reply    one JSON reply
     GET  /vessel/health   JSON status
   Requests carrying an Origin header are refused: the client is an extension
   host, never a web page."
  (:require [clojure.data.json :as json]
            [clojure.string :as str])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.io IOException OutputStream)
           (java.net InetAddress InetSocketAddress URLDecoder)
           (java.nio.charset StandardCharsets)
           (java.security MessageDigest SecureRandom)
           (java.util.concurrent ExecutorService Executors ScheduledExecutorService TimeUnit)))

;; SPDX-License-Identifier: MIT

(defn new-token
  "32 hex chars from a SecureRandom."
  []
  (let [bytes (byte-array 16)]
    (.nextBytes (SecureRandom.) bytes)
    (apply str (map #(format "%02x" (bit-and % 0xff)) bytes))))

(defn sse-frame
  "One SSE event carrying MESSAGE under sequence number SEQ."
  [seq message]
  (str "id: " seq "\n" "event: vessel\n" "data: " (json/write-str message :escape-slash false) "\n\n"))

(defn retain
  "RETAINED (panel id -> message) after MESSAGE passes."
  [retained message]
  (case (get message "op")
    "ui/show-panel" (assoc retained (get message "panel/id") message)
    "ui/close-panel" (dissoc retained (get message "panel/id"))
    retained))

(defn query-params
  [^String query]
  (if (str/blank? query)
    {}
    (into {}
          (keep (fn [pair]
                  (let [[k v] (str/split pair #"=" 2)]
                    (when-not (str/blank? k)
                      [(URLDecoder/decode k "UTF-8") (URLDecoder/decode (or v "") "UTF-8")]))))
          (str/split query #"&"))))

(defn token-ok?
  "Constant-time comparison of the presented token."
  [expected presented]
  (and (string? presented)
       (MessageDigest/isEqual (.getBytes ^String expected StandardCharsets/UTF_8)
                              (.getBytes ^String presented StandardCharsets/UTF_8))))

(defn- header [^HttpExchange ex name] (.getFirst (.getRequestHeaders ex) name))

(defn- respond! [^HttpExchange ex status ^String content-type ^String body]
  (let [bytes (.getBytes body StandardCharsets/UTF_8)]
    (.set (.getResponseHeaders ex) "Content-Type" content-type)
    (.sendResponseHeaders ex status (if (zero? (alength bytes)) -1 (alength bytes)))
    (when (pos? (alength bytes))
      (with-open [out (.getResponseBody ex)] (.write out bytes)))
    (.close ex)))

(defn- write! [{:keys [^OutputStream out lock]} ^String text]
  (locking lock
    (.write out (.getBytes text StandardCharsets/UTF_8))
    (.flush out)))

(defn- refusal
  "nil when EX may proceed, else [status message]."
  [{:keys [token]} ^HttpExchange ex]
  (cond
    (header ex "Origin") [403 "browser origins are not accepted"]
    (not (token-ok? token (get (query-params (.getRawQuery (.getRequestURI ex))) "token")))
    [401 "bad token"]
    :else nil))

(defn- drop-client! [state id]
  (when-let [{:keys [^HttpExchange exchange]} (get-in @state [:clients id])]
    (swap! state update :clients dissoc id)
    (try (.close exchange) (catch Throwable _ nil))))

(defn- handle-events [{:keys [state on-connect]} ^HttpExchange ex]
  (let [id (str (random-uuid))
        client {:id id :exchange ex :out (.getResponseBody ex) :lock (Object.)}]
    (doto (.getResponseHeaders ex)
      (.set "Content-Type" "text/event-stream; charset=utf-8")
      (.set "Cache-Control" "no-cache"))
    (.sendResponseHeaders ex 200 0)
    (locking state
      (try
        (write! client "retry: 2000\n\n")
        (doseq [message (vals (:retained @state))]
          (write! client (sse-frame (:seq @state) message)))
        (swap! state assoc-in [:clients id] client)
        (catch IOException _ (.close ex))))
    (when on-connect (on-connect {:client/id id}))))

(defn- handle-reply [{:keys [state on-reply]} ^HttpExchange ex]
  (let [body (slurp (.getRequestBody ex) :encoding "UTF-8")
        reply (try (json/read-str body) (catch Exception _ {"ok" false "error" {"code" "bridge/unparseable"}}))]
    (swap! state update :inbox (fn [inbox] (vec (take-last 100 (conj (or inbox []) reply)))))
    (when on-reply (on-reply reply))
    (respond! ex 204 "text/plain" "")))

(defn- handle-health [{:keys [state]} ^HttpExchange ex]
  (let [{:keys [clients retained seq]} @state]
    (respond! ex 200 "application/json"
              (json/write-str {"ok" true "clients" (count clients)
                               "panels" (vec (sort (keys retained))) "seq" seq}))))

(defn- handler [bridge method f]
  (reify HttpHandler
    (handle [_ ex]
      (try
        (if-let [[status message] (refusal bridge ex)]
          (respond! ex status "text/plain" message)
          (if (= method (.getRequestMethod ex))
            (f bridge ex)
            (respond! ex 405 "text/plain" "method not allowed")))
        (catch Throwable t
          (try (respond! ex 500 "text/plain" (str (ex-message t))) (catch Throwable _ nil)))))))

(defn start!
  "Start a bridge on loopback. opts: :token (required), :port (default 0),
   :on-reply (fn [reply-map]), :on-connect (fn [info]), :heartbeat-ms (default 15000)."
  [{:keys [token port on-reply on-connect heartbeat-ms] :or {port 0 heartbeat-ms 15000}}]
  (when-not (and (string? token) (<= 16 (count token)))
    (throw (ex-info "bridge token must be at least 16 chars" {})))
  (let [server (HttpServer/create (InetSocketAddress. (InetAddress/getLoopbackAddress) (int port)) 0)
        state (atom {:clients {} :retained {} :seq 0 :inbox []})
        bridge {:server server :state state :token token :on-reply on-reply :on-connect on-connect}
        pool (Executors/newCachedThreadPool)
        ^ScheduledExecutorService ticker (Executors/newSingleThreadScheduledExecutor)]
    (.createContext server "/vessel/events" (handler bridge "GET" handle-events))
    (.createContext server "/vessel/reply" (handler bridge "POST" handle-reply))
    (.createContext server "/vessel/health" (handler bridge "GET" handle-health))
    (.setExecutor server pool)
    (.start server)
    (.scheduleAtFixedRate ticker
                          (fn []
                            (doseq [[id client] (:clients @state)]
                              (try (write! client ": ping\n\n")
                                   (catch Throwable _ (drop-client! state id)))))
                          heartbeat-ms heartbeat-ms TimeUnit/MILLISECONDS)
    (assoc bridge :port (.getPort (.getAddress server)) :pool pool :ticker ticker)))

(defn broadcast!
  "Send MESSAGE (JSON-able data) to every connected window and update retention.
   Returns {:delivered n :seq s}."
  [{:keys [state]} message]
  (locking state
    (let [{:keys [seq clients]} (swap! state (fn [s] (-> s (update :seq inc) (update :retained retain message))))
          frame (sse-frame seq message)
          delivered (reduce (fn [n [id client]]
                              (try (write! client frame) (inc n)
                                   (catch Throwable _ (drop-client! state id) n)))
                            0 clients)]
      {:delivered delivered :seq seq})))

(defn clients [{:keys [state]}] (count (:clients @state)))

(defn retained-panels [{:keys [state]}] (set (keys (:retained @state))))

(defn inbox [{:keys [state]}] (:inbox @state))

(defn stop!
  [{:keys [^HttpServer server state pool ticker]}]
  (doseq [id (keys (:clients @state))] (drop-client! state id))
  (.shutdownNow ^ScheduledExecutorService ticker)
  (.stop server 0)
  (.shutdownNow ^ExecutorService pool)
  nil)
