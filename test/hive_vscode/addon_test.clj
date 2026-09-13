(ns hive-vscode.addon-test
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-addon.protocol :as addon]
            [hive-vscode.addon :as vscode]
            [hive-vscode.bridge :as bridge]
            [hive-vscode.sse :as sse])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.nio.file Files LinkOption)
           (java.nio.file.attribute FileAttribute PosixFilePermissions)
           (java.util.concurrent TimeUnit)
[java.net.http HttpClient$Version]))

;; SPDX-License-Identifier: MIT

(def fixtures
  (edn/read-string (slurp (io/file "test/fixtures/json_natives.edn"))))

(def natives (mapv (comp first :natives) fixtures))

(defn- temp-discovery []
  (str (Files/createTempDirectory "hive-vscode-test" (make-array FileAttribute 0))
       "/run/hive-vessel/vscode.json"))

(def ^HttpClient client
  (-> (HttpClient/newBuilder) (.version HttpClient$Version/HTTP_1_1) (.build)))

(defn- url [doc path & [query]]
  (str (get doc "url") path "?token=" (get doc "token") (when query (str "&" query))))

(defn- get-status [u & headers]
  (let [req (reduce (fn [b [k v]] (.header b k v)) (HttpRequest/newBuilder (URI. u)) (partition 2 headers))]
    (.statusCode (.send client (.build req) (HttpResponse$BodyHandlers/discarding)))))

(defn- open-stream
  "Subscribe to /vessel/events over a raw HTTP/1.1 socket, decoding the chunked
   body as it arrives (what a node http client sees). Returns {:events atom :socket s}."
  [doc]
  (let [events (atom [])
        port (get doc "port")
        s (java.net.Socket. "127.0.0.1" (int port))
        in (java.io.DataInputStream. (java.io.BufferedInputStream. (.getInputStream s)))
        out (.getOutputStream s)
        read-line! (fn []
                     (let [sb (StringBuilder.)]
                       (loop []
                         (let [c (.read in)]
                           (cond (neg? c) nil
                                 (= c 10) (str/replace (str sb) #"\r$" "")
                                 :else (do (.append sb (char c)) (recur)))))))]
    (.write out (.getBytes (str "GET /vessel/events?token=" (get doc "token") " HTTP/1.1\r\n"
                                "Host: 127.0.0.1\r\n\r\n")
                           "UTF-8"))
    (.flush out)
    (future
      (try
        (loop [] (let [l (read-line!)] (when (and l (not= "" l)) (recur))))
        (loop [buffer ""]
          (when-let [size-line (read-line!)]
            (let [size (Long/parseLong (first (str/split size-line #";")) 16)]
              (when (pos? size)
                (let [bytes (byte-array size)]
                  (.readFully in bytes)
                  (read-line!)
                  (let [{evs :events rest :buffer} (sse/feed buffer (String. bytes "UTF-8"))]
                    (swap! events into evs)
                    (recur rest)))))))
        (catch java.io.IOException _ nil)))
    {:events events :socket s}))

(defn- wait-until [pred]
  (loop [i 0]
    (cond (pred) true
          (> i 150) false
          :else (do (Thread/sleep 20) (recur (inc i))))))

(defmacro with-addon [[sym config] & body]
  `(let [~sym (vscode/make-addon ~config)]
     (try ~@body (finally (addon/shutdown! ~sym)))))

(deftest construction-is-pure-and-health-is-down
  (let [a (vscode/make-addon)]
    (is (= "hive.vscode" (addon/addon-id a)))
    (is (= :down (:status (addon/health a))))
    (is (nil? (vscode/vessel-target a)))))

(deftest the-target-hook-resolves-per-call
  (let [a (vscode/make-addon {:vscode/discovery-path (temp-discovery)})
        resolve-target (get (addon/hooks a) vscode/target-hook-key)]
    (is (nil? (resolve-target)))
    (try
      (addon/initialize! a {})
      (is (= {:vessel/id :vscode :vessel/dialect :json}
             (select-keys (resolve-target) [:vessel/id :vessel/dialect])))
      (is (fn? (:vessel/execute! (resolve-target))))
      (finally (addon/shutdown! a)))
    (is (nil? (resolve-target)))))

(deftest initialize-writes-a-private-discovery-file-and-registers-the-target
  (let [path (temp-discovery)
        registered (atom nil)]
    (with-addon [a {:vscode/discovery-path path
                    :vessel/register-target-fn #(reset! registered %)
                    :vessel/unregister-target-fn #(reset! registered [:gone %])}]
      (let [result (addon/initialize! a {})
            doc (json/read-str (slurp path))
            p (.toPath (io/file path))]
        (is (:success? result))
        (is (= {"vessel" "vscode" "dialect" "json"} (select-keys doc ["vessel" "dialect"])))
        (is (re-matches #"[0-9a-f]{32}" (get doc "token")))
        (is (= "rw-------" (PosixFilePermissions/toString (Files/getPosixFilePermissions p (make-array LinkOption 0)))))
        (is (= "rwx------" (PosixFilePermissions/toString (Files/getPosixFilePermissions (.getParent p) (make-array LinkOption 0)))))
        (is (= {:vessel/id :vscode :vessel/dialect :json}
               (select-keys @registered [:vessel/id :vessel/dialect])))
        (is (= :degraded (:status (addon/health a))) "no window connected yet")
        (is (:already-initialized? (addon/initialize! a {})))
        (addon/shutdown! a)
        (is (= [:gone :vscode] @registered))
        (is (not (.exists (io/file path))))))))

(deftest the-bridge-refuses-strangers
  (let [path (temp-discovery)]
    (with-addon [a {:vscode/discovery-path path}]
      (addon/initialize! a {})
      (let [doc (json/read-str (slurp path))]
        (is (= 200 (get-status (url doc "/vessel/health"))))
        (is (= 401 (get-status (str (get doc "url") "/vessel/health?token=" (str/reverse (get doc "token"))))))
        (is (= 401 (get-status (str (get doc "url") "/vessel/health"))))
        (is (= 403 (get-status (url doc "/vessel/health") "Origin" "https://evil.example")))))))

(deftest every-native-reaches-a-connected-window-in-order
  (let [path (temp-discovery)
        replies (atom [])]
    (with-addon [a {:vscode/discovery-path path :vscode/on-reply #(swap! replies conj %)}]
      (addon/initialize! a {})
      (let [doc (json/read-str (slurp path))
            b (vscode/bridge-of a)
            execute! (:vessel/execute! (vscode/vessel-target a))
            stream (open-stream doc)]
        (is (wait-until #(= 1 (bridge/clients b))))
        (doseq [n natives] (execute! {:native/dialect :json :native/payload n}))
        (is (wait-until #(= (count natives) (count @(:events stream)))))
        (is (= natives (mapv #(json/read-str (:data %)) @(:events stream))))
        (is (every? #(= "vessel" (:event %)) @(:events stream)))
        (is (= :ok (:status (addon/health a))))
        (testing "a window replies with POST"
          (let [req (-> (HttpRequest/newBuilder (URI. (url doc "/vessel/reply")))
                        (.POST (HttpRequest$BodyPublishers/ofString (json/write-str {"ok" true "op" "ui/notify"})))
                        (.build))]
            (is (= 204 (.statusCode (.send client req (HttpResponse$BodyHandlers/discarding)))))
            (is (= [{"ok" true "op" "ui/notify"}] @replies))))
        (testing "only :json natives are accepted"
          (is (thrown? clojure.lang.ExceptionInfo
                       (execute! {:native/dialect :elisp :native/payload "(message 1)"}))))))))

(deftest a-late-window-sees-the-open-panels-only
  (let [path (temp-discovery)]
    (with-addon [a {:vscode/discovery-path path}]
      (addon/initialize! a {})
      (let [doc (json/read-str (slurp path))
            b (vscode/bridge-of a)
            execute! #((:vessel/execute! (vscode/vessel-target a)) {:native/dialect :json :native/payload %})]
        (execute! {"op" "ui/show-panel" "panel/id" "a" "lines" []})
        (execute! {"op" "ui/show-panel" "panel/id" "b" "lines" []})
        (execute! {"op" "ui/close-panel" "panel/id" "a"})
        (execute! {"op" "ui/notify" "message" "missed"})
        (let [stream (open-stream doc)]
          (is (wait-until #(= 1 (bridge/clients b))))
          (is (wait-until #(= 1 (count @(:events stream)))))
          (Thread/sleep 100)
          (is (= [{"op" "ui/show-panel" "panel/id" "b" "lines" []}]
                 (mapv #(json/read-str (:data %)) @(:events stream)))))))))
