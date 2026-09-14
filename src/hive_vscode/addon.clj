(ns hive-vscode.addon
  "IAddon for the VS Code vessel. Construction is pure; initialize! starts a
   hive-vessel.executor.sse bridge on loopback (token-gated, every browser
   Origin refused), writes the discovery file the extension reads, builds the
   hive-vessel target and hands it to an injected :vessel/register-target-fn."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [hive-addon.protocol :as addon]
            [hive-vessel.executor.sse :as sse]
            [hive-spi.vessel :as render-port]
            [hive-vessel.renderer :as renderer])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file CopyOption Files LinkOption OpenOption Path StandardCopyOption)
           (java.nio.file.attribute FileAttribute PosixFilePermissions)
           (java.security SecureRandom)))

;; SPDX-License-Identifier: MIT

(def addon-id-value "hive.vscode")

(def vessel-id :vscode)

(def target-hook-key
  "IAddon hook key: a zero-arg fn returning the current hive-vessel target, or nil
   before initialize! and after shutdown!."
  :vessel/target)

(defn discovery-path
  "Discovery file for the VS Code vessel under XDG-RUNTIME-DIR (may be nil)."
  [xdg-runtime-dir]
  (str (or xdg-runtime-dir "/tmp") "/hive-vessel/vscode.json"))

(defn discovery-doc
  [{:keys [port token pid]}]
  {"vessel" "vscode" "dialect" "json" "url" (str "http://127.0.0.1:" port)
   "port" port "token" token "pid" pid})

(defn- perms [s]
  (into-array FileAttribute [(PosixFilePermissions/asFileAttribute (PosixFilePermissions/fromString s))]))

(defn write-private!
  "Atomically write CONTENT to PATH with mode 0600 inside a 0700 directory."
  [path content]
  (let [target (.toPath (io/file path))
        dir (.getParent target)]
    (when-not (Files/exists dir (make-array LinkOption 0))
      (Files/createDirectories dir (perms "rwx------")))
    (let [tmp (Files/createTempFile dir ".vscode" ".tmp" (perms "rw-------"))]
      (Files/writeString tmp content StandardCharsets/UTF_8 (make-array OpenOption 0))
      (Files/move tmp target (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                                     StandardCopyOption/REPLACE_EXISTING])))))

(defn new-token
  "32 hex chars from a SecureRandom."
  []
  (let [bytes (byte-array 16)]
    (.nextBytes (SecureRandom.) bytes)
    (apply str (map #(format "%02x" (bit-and % 0xff)) bytes))))

(defn parse-reply
  "A window's raw POST body as a string-keyed map; an unparseable body becomes a
   failed reply."
  [raw]
  (try (json/read-str raw)
       (catch Exception _ {"ok" false "error" {"code" "bridge/unparseable"}})))

(defn target
  "hive-vessel target executing :json natives through the sse BRIDGE."
  [b]
  {:vessel/id vessel-id
   :vessel/dialect :json
   :vessel/execute! (sse/executor b)})

(defn- flatten-config
  [seed runtime-config]
  (merge (:addon/config seed) seed (:addon/config runtime-config) runtime-config))

(defn- start-bridge!
  "sse bridge on loopback gated by TOKEN; refuses every Origin; :vscode/on-reply
   receives parsed replies."
  [config token]
  (let [on-reply (:vscode/on-reply config)]
    (sse/start! {:token token
                 :allowed-origin? (constantly false)
                 :on-message (when on-reply (comp on-reply parse-reply))
                 :on-connect (:vscode/on-connect config)})))

(defn- start!
  [config]
  (let [token (new-token)
        b (start-bridge! config token)
        path (or (:vscode/discovery-path config) (discovery-path (System/getenv "XDG_RUNTIME_DIR")))
        t (target b)
        register-fn (:vessel/register-target-fn config)]
    (try
      (write-private! path (json/write-str (discovery-doc {:port (:port b) :token token
                                                           :pid (.pid (java.lang.ProcessHandle/current))})))
      (when register-fn (register-fn t))
      {:lifecycle :active :bridge b :discovery path :target t
       :target-registered? (boolean register-fn)
       :unregister-fn (:vessel/unregister-target-fn config)}
      (catch Exception e
        (sse/stop! b)
        (throw e)))))

(defn- initialize-addon!
  [state seed runtime-config]
  (locking state
    (if (= :active (:lifecycle @state))
      {:success? true :already-initialized? true}
      (try
        (let [started (start! (flatten-config seed runtime-config))]
          (reset! state started)
          {:success? true :errors []
           :metadata {:port (get-in started [:bridge :port])
                      :discovery (:discovery started)
                      :target-registered? (:target-registered? started)}})
        (catch Exception e
          (reset! state {:lifecycle :error :errors [(ex-message e)]})
          {:success? false :errors [(ex-message e)]})))))

(defn- shutdown-addon!
  [state]
  (locking state
    (let [{:keys [lifecycle bridge discovery unregister-fn]} @state]
      (when (= :active lifecycle)
        (when unregister-fn (unregister-fn vessel-id))
        (sse/stop! bridge)
        (Files/deleteIfExists ^Path (.toPath (io/file discovery))))
      (reset! state {:lifecycle :stopped})))
  nil)

(defn- addon-health
  [state]
  (let [{:keys [lifecycle bridge discovery target-registered? errors]} @state]
    (if (= :active lifecycle)
      (let [n (sse/clients bridge)
            failures (count (remove #(true? (get % "ok")) (map parse-reply (sse/inbox bridge))))]
        {:status (if (pos? n) :ok :degraded)
         :details {:windows n
                   :panels (sse/retained-panels bridge)
                   :failed-replies failures
                   :port (:port bridge)
                   :discovery discovery
                   :target-registered? target-registered?}})
      {:status :down
       :details (cond-> {:lifecycle (or lifecycle :new)}
                  (seq errors) (assoc :errors errors))})))

(defrecord HiveVsCodeAddon [state seed]
  render-port/IRenderer
  (renderer-id [_] addon-id-value)
  (render! [_ ops] (renderer/deliver! (:target @state) ops))
  addon/IAddon
  (addon-id [_] addon-id-value)
  (addon-type [_] :native)
  (capabilities [_] #{:vessel :health-reporting})
  (initialize! [this runtime-config]
    (let [result (initialize-addon! state seed runtime-config)]
      (when (:success? result) (renderer/register! this))
      result))
  (shutdown! [this]
    (renderer/unregister! this)
    (shutdown-addon! state))
  (tools [_] [])
  (schema-extensions [_] [])
  (health [_] (addon-health state))
  (excluded-tools [_] #{})
  (hooks [_] {target-hook-key (fn [] (:target @state))}))

(defn make-addon
  "Uninitialized IAddon. No bridge, file or registry mutation occurs."
  ([] (make-addon {}))
  ([seed] (->HiveVsCodeAddon (atom {:lifecycle :new}) (or seed {}))))

(defn addon-ctor
  "hive-addon.mount constructor: config -> uninitialized IAddon."
  [config]
  (make-addon config))

(defn vessel-target
  "The hive-vessel target of an initialized ADDON, or nil."
  [a]
  (:target @(:state a)))

(defn bridge-of
  "The hive-vessel.executor.sse bridge of an initialized ADDON, or nil."
  [a]
  (:bridge @(:state a)))

(defn replies
  "Parsed replies retained by an initialized ADDON's bridge (latest 100), or nil."
  [a]
  (some->> (bridge-of a) sse/inbox (mapv parse-reply)))
