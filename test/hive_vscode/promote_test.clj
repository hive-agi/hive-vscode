(ns hive-vscode.promote-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-vscode.effects :as fx]
            [hive-vscode.render :as render]
            [hive-vscode.schema :as s]
            [hive-vscode.sse :as sse]
            [malli.generator :as mg]))

;; SPDX-License-Identifier: MIT

(def fixtures
  (edn/read-string (slurp (io/file "test/fixtures/json_natives.edn"))))

(deftest every-hive-vessel-native-is-a-known-payload-with-effects
  (doseq [{:keys [input natives]} fixtures
          payload natives]
    (testing (pr-str (:op input))
      (is (s/validate s/Payload payload))
      (let [effects (fx/payload->effects payload)]
        (is (s/validate s/Effects effects))
        (is (not-any? fx/failure? effects))))))

(deftest fixture-effects
  (let [by-op (group-by #(get-in % [:input :op]) fixtures)
        effects-of (fn [op i] (fx/payload->effects (first (:natives (nth (by-op op) i)))))]
    (is (= [[:show-message "error" "Build failed"]] (effects-of :ui/notify 2)))
    (is (= [[:open-file "/tmp/a.clj" 12 3]] (effects-of :ui/open-file 0)))
    (is (= [[:open-file "/tmp/b.clj" nil nil]] (effects-of :ui/open-file 1)))
    (is (= [[:close-panel "carto-flow"]] (effects-of :ui/close-panel 0)))
    (is (= [[:send-to-terminal "hive" "echo hi"]] (effects-of :ui/send-to-terminal 0)))
    (is (= [[:emit-event "carto-flow/selected" {"node" "hive.x/f" "depth" 2}]]
           (effects-of :json/event 0)))
    (let [[[k id title lines]] (effects-of :ui/show-panel 0)]
      (is (= [:upsert-panel "carto-flow" "Carto flow"] [k id title]))
      (is (= (get (first (:natives (first (by-op :ui/show-panel)))) "lines") lines)))))

(defspec generated-payloads-never-fail 300
  (prop/for-all [p (mg/generator s/Payload)]
    (let [effects (fx/payload->effects p)]
      (and (s/validate s/Effects effects)
           (every? #(contains? #{:show-message :upsert-panel :close-panel :open-file
                                 :send-to-terminal :emit-event :invalid}
                               (first %))
                   effects)))))

(defspec anything-else-is-unsupported-or-invalid-never-thrown 300
  (prop/for-all [x (gen/one-of [gen/any-printable-equatable
                                (gen/map gen/string-alphanumeric gen/any-printable-equatable)
                                (gen/fmap #(assoc % "op" "ui/nope") (gen/map gen/string-alphanumeric gen/small-integer))])]
    (let [effects (fx/payload->effects x)]
      (and (s/validate s/Effects effects)
           (or (every? fx/failure? effects)
               (s/validate s/Payload x))))))

(deftest missing-required-fields-are-invalid
  (is (= [[:invalid "ui/notify" "message is required"]] (fx/payload->effects {"op" "ui/notify"})))
  (is (= [[:invalid "ui/show-panel" "panel/id is required"]] (fx/payload->effects {"op" "ui/show-panel" "lines" []})))
  (is (= [[:unsupported "ui/teleport"]] (fx/payload->effects {"op" "ui/teleport"})))
  (is (= [[:invalid nil "payload must be an object"]] (fx/payload->effects [1 2]))))

(defspec rendered-text-never-escapes-its-element 300
  (prop/for-all [text gen/string
                 face (gen/elements (conj (vec render/faces) "evil\" onload=\"x"))
                 file (gen/one-of [(gen/return nil) gen/string])]
    (let [html (render/line-html (cond-> {"text" text "face" face} file (assoc "file" file)))
          inner (subs html (inc (str/index-of html ">")) (str/last-index-of html "</div>"))]
      (and (str/starts-with? html "<div class=\"l f-")
           (not (str/includes? inner "<"))
           (= 1 (count (re-seq #"<div" html)))
           (re-matches #"<div class=\"l f-[a-z]+\"[^<>]*>[^<>]*</div>" html)))))

(deftest panel-html-carries-a-nonce-bound-csp
  (let [html (render/panel-html "T <x>" [{"text" "a" "face" "title"}] "n0nce")]
    (is (str/includes? html "script-src 'nonce-n0nce'"))
    (is (str/includes? html "<script nonce=\"n0nce\">"))
    (is (str/includes? html "<title>T &lt;x&gt;</title>"))
    (is (str/includes? html "default-src 'none'"))))

(def gen-event
  (gen/hash-map :id (gen/one-of [(gen/return nil) gen/string-alphanumeric])
                :event (gen/elements ["vessel" "message" "x-y"])
                :data (gen/fmap #(str/join "\n" %) (gen/vector (gen/fmap #(str/replace % #"[\r\n]" "") gen/string) 1 3))))

(defn- wire [events crlf?]
  (let [nl (if crlf? "\r\n" "\n")]
    (str "retry: 2000" nl nl ": ping" nl nl
         (str/join (for [{:keys [id event data]} events]
                     (str (when id (str "id: " id nl))
                          (when (not= "message" event) (str "event: " event nl))
                          (str/join (map #(str "data: " % nl) (str/split data #"\n" -1)))
                          nl))))))

(defn- feed-all [chunks]
  (reduce (fn [{:keys [buffer] :as acc} chunk]
            (let [r (sse/feed buffer chunk)]
              (-> acc (update :events into (:events r)) (assoc :buffer (:buffer r)))))
          {:events [] :buffer ""}
          chunks))

(defspec sse-chunking-never-changes-the-events 300
  (prop/for-all [events (gen/vector gen-event 0 5)
                 crlf? gen/boolean
                 cuts (gen/vector gen/nat 0 12)]
    (let [text (wire events crlf?)
          points (sort (distinct (map #(mod % (inc (count text))) cuts)))
          chunks (map (fn [[a b]] (subs text a b)) (partition 2 1 (concat [0] points [(count text)])))
          expected (mapv #(update % :event identity) events)]
      (and (= expected (:events (feed-all [text])))
           (= expected (:events (feed-all chunks)))
           (= "" (:buffer (feed-all chunks)))))))

(deftest sse-feed-shape
  (let [r (sse/feed "" "retry: 1500\n\nid: 7\nevent: vessel\ndata: {}\n\ndata: part")]
    (is (s/validate s/FeedResult r))
    (is (= 1500 (:retry r)))
    (is (= [{:id "7" :event "vessel" :data "{}"}] (:events r)))
    (is (= "data: part" (:buffer r)))))
