(ns hive-vscode.effects
  "hive-vessel :json native payload -> VS Code effects as data. Pure.

   Effects:
     [:show-message level message]
     [:upsert-panel panel-id title lines]
     [:close-panel panel-id]
     [:open-file file line column]
     [:send-to-terminal terminal text]
     [:emit-event event data]
     [:invalid op reason]
     [:unsupported op]")

;; SPDX-License-Identifier: MIT

(def levels #{"info" "warn" "error"})

(defn- non-blank? [s] (and (string? s) (pos? (count s))))

(defn- pos-int-or-nil [x] (when (and (integer? x) (pos? x)) x))

(defn- notify [p]
  (if (non-blank? (get p "message"))
    [[:show-message (if (contains? levels (get p "level")) (get p "level") "info")
      (get p "message")]]
    [[:invalid "ui/notify" "message is required"]]))

(defn- show-panel [p]
  (let [id (get p "panel/id")]
    (if (non-blank? id)
      [[:upsert-panel id
        (let [t (get-in p ["doc" "doc/title"])] (if (non-blank? t) t id))
        (vec (filter map? (get p "lines")))]]
      [[:invalid "ui/show-panel" "panel/id is required"]])))

(defn- close-panel [p]
  (if (non-blank? (get p "panel/id"))
    [[:close-panel (get p "panel/id")]]
    [[:invalid "ui/close-panel" "panel/id is required"]]))

(defn- open-file [p]
  (if (non-blank? (get p "file"))
    [[:open-file (get p "file") (pos-int-or-nil (get p "line")) (pos-int-or-nil (get p "column"))]]
    [[:invalid "ui/open-file" "file is required"]]))

(defn- send-to-terminal [p]
  (if (string? (get p "text"))
    [[:send-to-terminal (if (non-blank? (get p "terminal")) (get p "terminal") "hive")
      (get p "text")]]
    [[:invalid "ui/send-to-terminal" "text is required"]]))

(defn- emit-event [p]
  (if (non-blank? (get p "event"))
    [[:emit-event (get p "event") (let [d (get p "data")] (if (map? d) d {}))]]
    [[:invalid "json/event" "event is required"]]))

(def legacy-op-names
  "Neutral wire op -> legacy ui/* op. hive-vessel's :json dialect sends the
   neutral names to a client that advertised features (Lens C5)."
  {"show" "ui/show-panel" "close" "ui/close-panel" "focus" "ui/focus-tab"
   "append" "ui/append-tab" "notify" "ui/notify" "open-file" "ui/open-file"})

(defn ->legacy
  "PAYLOAD in the legacy vocabulary, whichever one it arrived in: the op name,
   \"id\" -> \"panel/id\" and the doc's \"title\" -> \"doc/title\"."
  [payload]
  (if-let [op (legacy-op-names (get payload "op"))]
    (cond-> (assoc payload "op" op)
      (and (contains? payload "id") (not (contains? payload "panel/id")))
      (assoc "panel/id" (get payload "id"))
      (and (map? (get payload "doc")) (contains? (get payload "doc") "title")
           (not (contains? (get payload "doc") "doc/title")))
      (assoc-in ["doc" "doc/title"] (get-in payload ["doc" "title"])))
    payload))

(defn payload->effects
  "Effects for one :json native PAYLOAD (string-keyed map), in either wire
   vocabulary."
  [payload]
  (if-not (map? payload)
    [[:invalid nil "payload must be an object"]]
    (let [payload (->legacy payload)
          op (get payload "op")]
      (case op
        "ui/notify" (notify payload)
        "ui/show-panel" (show-panel payload)
        "ui/close-panel" (close-panel payload)
        "ui/open-file" (open-file payload)
        "ui/send-to-terminal" (send-to-terminal payload)
        "json/event" (emit-event payload)
        [[:unsupported (str op)]]))))

(defn failure?
  "True for an effect that reports the payload could not be executed."
  [effect]
  (contains? #{:invalid :unsupported} (first effect)))
