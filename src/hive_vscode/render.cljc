(ns hive-vscode.render
  "hive-vessel face-tagged lines -> a self-contained webview HTML document.
   Pure; every piece of payload text is HTML-escaped."
  (:require [clojure.string :as str]))

;; SPDX-License-Identifier: MIT

(def faces
  #{"title" "heading" "plain" "muted" "info" "success" "warn" "error"
    "added" "removed" "hunk" "code" "link"})

(defn face-class
  "CSS class for FACE; unknown faces render as plain."
  [face]
  (str "f-" (if (contains? faces face) face "plain")))

(defn escape
  "HTML-escape S (text and attribute safe)."
  [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&#39;")))

(defn line-html
  "One rendered line. Lines carrying a file become clickable links."
  [{:strs [text face file line]}]
  (let [attrs (cond-> [(str "class=\"l " (face-class face) "\"")]
                (string? file) (conj (str "data-file=\"" (escape file) "\"")
                                     "role=\"link\"" "tabindex=\"0\"")
                (integer? line) (conj (str "data-line=\"" line "\"")))]
    (str "<div " (str/join " " attrs) ">" (escape text) "</div>")))

(def ^:private stylesheet
  (str
   "body{font-family:var(--vscode-editor-font-family);font-size:var(--vscode-editor-font-size);"
   "color:var(--vscode-foreground);padding:0 12px}"
   ".l{white-space:pre-wrap;min-height:1.3em}"
   ".f-title{font-weight:bold;font-size:1.3em}"
   ".f-heading{font-weight:bold}"
   ".f-muted{color:var(--vscode-descriptionForeground)}"
   ".f-info{color:var(--vscode-editorInfo-foreground)}"
   ".f-success{color:var(--vscode-testing-iconPassed)}"
   ".f-warn{color:var(--vscode-editorWarning-foreground)}"
   ".f-error{color:var(--vscode-editorError-foreground)}"
   ".f-added{color:var(--vscode-gitDecoration-addedResourceForeground)}"
   ".f-removed{color:var(--vscode-gitDecoration-deletedResourceForeground)}"
   ".f-hunk{color:var(--vscode-charts-purple)}"
   ".f-code{background:var(--vscode-textCodeBlock-background)}"
   ".f-link{color:var(--vscode-textLink-foreground);cursor:pointer;text-decoration:underline}"))

(defn- script [nonce]
  (str "<script nonce=\"" (escape nonce) "\">"
       "const vscode=acquireVsCodeApi();"
       "document.addEventListener('click',e=>{const t=e.target.closest('[data-file]');"
       "if(t){vscode.postMessage({command:'open-file',file:t.dataset.file,"
       "line:t.dataset.line?Number(t.dataset.line):null});}});"
       "</script>"))

(defn panel-html
  "Full webview document for TITLE and LINES. NONCE authorises the one inline script."
  [title lines nonce]
  (str "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
       "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; "
       "style-src 'unsafe-inline'; script-src 'nonce-" (escape nonce) "';\">"
       "<title>" (escape title) "</title>"
       "<style>" stylesheet "</style></head><body>"
       (str/join (map line-html lines))
       (script nonce)
       "</body></html>"))
