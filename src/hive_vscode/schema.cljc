(ns hive-vscode.schema
  "Malli value objects: hive-vessel :json payloads as VS Code receives them, and
   the effects they become."
  (:require [malli.core :as m]))

;; SPDX-License-Identifier: MIT

(def Line
  [:map
   ["text" :string]
   ["face" :string]
   ["file" {:optional true} :string]
   ["line" {:optional true} :int]])

(def Payload
  [:multi {:dispatch (fn [p] (get p "op"))}
   ["ui/notify" [:map ["op" [:= "ui/notify"]] ["message" :string]
                 ["level" {:optional true} [:enum "info" "warn" "error"]]]]
   ["ui/show-panel" [:map ["op" [:= "ui/show-panel"]] ["panel/id" :string]
                     ["doc" {:optional true} :map] ["lines" [:vector Line]]]]
   ["ui/close-panel" [:map ["op" [:= "ui/close-panel"]] ["panel/id" :string]]]
   ["ui/open-file" [:map ["op" [:= "ui/open-file"]] ["file" :string]
                    ["line" {:optional true} :int] ["column" {:optional true} :int]]]
   ["ui/send-to-terminal" [:map ["op" [:= "ui/send-to-terminal"]] ["text" :string]
                           ["terminal" {:optional true} :string]]]
   ["json/event" [:map ["op" [:= "json/event"]] ["event" :string]
                  ["data" {:optional true} :map]]]])

(def NonBlank [:string {:min 1}])

(def Effect
  [:multi {:dispatch first}
   [:show-message [:tuple [:= :show-message] [:enum "info" "warn" "error"] NonBlank]]
   [:upsert-panel [:tuple [:= :upsert-panel] NonBlank NonBlank [:vector :map]]]
   [:close-panel [:tuple [:= :close-panel] NonBlank]]
   [:open-file [:tuple [:= :open-file] NonBlank [:maybe pos-int?] [:maybe pos-int?]]]
   [:send-to-terminal [:tuple [:= :send-to-terminal] NonBlank :string]]
   [:emit-event [:tuple [:= :emit-event] NonBlank :map]]
   [:invalid [:tuple [:= :invalid] [:maybe :string] :string]]
   [:unsupported [:tuple [:= :unsupported] :string]]])

(def Effects [:vector {:min 1} Effect])

(def Reply
  [:map
   ["ok" :boolean]
   ["op" [:maybe :string]]
   ["applied" {:optional true} [:vector :string]]
   ["error" {:optional true} [:map ["code" :string] ["message" {:optional true} :string]]]])

(def SseEvent
  [:map {:closed true} [:id [:maybe :string]] [:event :string] [:data :string]])

(def FeedResult
  [:map {:closed true}
   [:events [:vector SseEvent]]
   [:retry [:maybe :int]]
   [:buffer :string]])

(defn validate [schema value] (m/validate schema value))
