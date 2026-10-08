(ns hive-vscode.effects-test
  "Both wire vocabularies reach the same VS Code effects: the legacy ui/*
   names and the neutral names hive-vessel sends a client that advertised
   features (Lens C5)."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [hive-vscode.effects :as effects]))

;; SPDX-License-Identifier: MIT

(deftest neutral-and-legacy-payloads-give-the-same-effects
  (is (= (effects/payload->effects {"op" "ui/show-panel" "panel/id" "p"
                                    "doc" {"doc/title" "T"} "lines" []})
         (effects/payload->effects {"op" "show" "id" "p" "doc" {"title" "T"} "lines" []})))
  (is (= [[:close-panel "p"]] (effects/payload->effects {"op" "close" "id" "p"})))
  (is (= [[:show-message "error" "x"]]
         (effects/payload->effects {"op" "notify" "message" "x" "level" "error"})))
  (is (= [[:open-file "/a" 2 nil]] (effects/payload->effects {"op" "open-file" "file" "/a" "line" 2})))
  (is (= [[:unsupported "teleport"]] (effects/payload->effects {"op" "teleport"}))))

(deftrifecta legacy-names-contract
  hive-vscode.effects/->legacy
  {:golden-path "test/golden/effects/legacy.edn"
   :cases {:neutral-show {"op" "show" "id" "p" "doc" {"title" "T"}}
           :neutral-close {"op" "close" "id" "p"}
           :neutral-notify {"op" "notify" "message" "m"}
           :legacy-show {"op" "ui/show-panel" "panel/id" "p" "doc" {"doc/title" "T"}}
           :json-event {"op" "json/event" "event" "e" "id" "kept"}}
   :gen (gen/hash-map "op" (gen/elements ["show" "close" "notify" "open-file" "ui/notify" "json/event"])
                      "id" gen/string-alphanumeric)
   :property-type :totality
   :mutations [["identity" (fn [payload] payload)]
               ["op-only" (fn [payload]
                            (if-let [op (get effects/legacy-op-names (get payload "op"))]
                              (assoc payload "op" op)
                              payload))]]})
