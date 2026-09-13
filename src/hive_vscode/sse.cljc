(ns hive-vscode.sse
  "Incremental Server-Sent Events parser. Pure.
   (feed buffer chunk) -> {:events [{:id :event :data}] :retry n-or-nil :buffer rest}"
  (:require [clojure.string :as str]))

;; SPDX-License-Identifier: MIT

(defn- field [line]
  (let [i (str/index-of line ":")]
    (if (nil? i)
      [line ""]
      (let [v (subs line (inc i))]
        [(subs line 0 i) (if (str/starts-with? v " ") (subs v 1) v)]))))

(defn parse-block
  "One complete event block (LF-separated lines) -> {:event map-or-nil :retry n-or-nil}."
  [block]
  (let [acc (reduce
             (fn [acc line]
               (if (or (= "" line) (str/starts-with? line ":"))
                 acc
                 (let [[k v] (field line)]
                   (case k
                     "data" (update acc :data conj v)
                     "event" (assoc acc :event v)
                     "id" (assoc acc :id v)
                     "retry" (if (re-matches #"\d+" v)
                               (assoc acc :retry #?(:clj (Long/parseLong v) :cljs (js/parseInt v 10)))
                               acc)
                     acc))))
             {:data [] :event nil :id nil :retry nil}
             (str/split block #"\n"))]
    {:retry (:retry acc)
     :event (when (seq (:data acc))
              {:id (:id acc)
               :event (or (:event acc) "message")
               :data (str/join "\n" (:data acc))})}))

(defn feed
  "Append CHUNK to BUFFER and extract every complete event."
  [buffer chunk]
  (let [text (str/replace (str buffer chunk) "\r\n" "\n")
        idx (str/last-index-of text "\n\n")]
    (if (nil? idx)
      {:events [] :retry nil :buffer text}
      (let [parsed (map parse-block (str/split (subs text 0 idx) #"\n\n"))]
        {:events (vec (keep :event parsed))
         :retry (last (keep :retry parsed))
         :buffer (subs text (+ idx 2))}))))
