# hive-vscode

[![Clojars Project](https://img.shields.io/clojars/v/io.github.hive-agi/hive-vscode.svg)](https://clojars.org/io.github.hive-agi/hive-vscode)
[![release](https://github.com/hive-agi/hive-vscode/actions/workflows/release.yml/badge.svg)](https://github.com/hive-agi/hive-vscode/actions/workflows/release.yml)

VS Code as a hive vessel. Addons describe UI once, as
[hive-vessel](https://github.com/hive-agi/hive-vessel) ops; `hive-vscode`
executes the `:json` dialect of those ops as VS Code effects: notifications,
panels, files and terminals.

```text
hive.vscode IAddon ── :json natives ── loopback SSE bridge ──▶ hive-vscode extension
                                                                      │
                                              notifications, webview panels, files, terminals
```

Two halves, one repository:

| half | path | runs in |
|------|------|---------|
| `hive.vscode` IAddon + SSE bridge | `src/hive_vscode/*.clj`, `resources/META-INF/hive-addons/` | the hive JVM |
| extension (ClojureScript) | `src/hive_vscode/ext/`, shared `.cljc` | VS Code extension host |

## Install

```clojure
io.github.hive-agi/hive-vscode {:mvn/version "RELEASE"}
```

Pin the version from the Clojars badge. The jar carries the addon manifest
`META-INF/hive-addons/hive-vscode.edn`, so hive discovers it on the classpath.

## The addon

`hive-vscode.addon/addon-ctor`, manifest id `hive.vscode`, capabilities
`:vessel` and `:health-reporting`.

On initialize it:

1. starts the loopback bridge with a fresh random token,
2. writes a private (0600) discovery file with port, token and pid, by default
   `$XDG_RUNTIME_DIR/hive-vessel/vscode.json`,
3. builds the hive-vessel target (`:vessel/id :vscode`, dialect `:json`) and
   hands it to `:vessel/register-target-fn` when one is injected.

Shutdown unregisters the target, stops the bridge and deletes the discovery file.

Config keys: `:vscode/discovery-path`, `:vscode/on-reply`, `:vscode/on-connect`,
`:vessel/register-target-fn`, `:vessel/unregister-target-fn`.

Hook: `:vessel/target`, a zero-arg fn returning the current target (nil when not
active). Health reports connected windows, retained panels and failed replies.

Bridge routes (every request carries `?token=`; requests with an `Origin`
header are refused):

| route | purpose |
|-------|---------|
| `GET /vessel/events` | SSE stream, replays retained panels |
| `POST /vessel/reply` | one JSON reply |
| `GET /vessel/health` | JSON status |

## The extension

Activates on startup, reads the discovery file and connects. Commands:
`hive: Connect to vessel bridge`, `hive: Disconnect`, `hive: Show vessel status`.
Settings: `hive.discoveryPath` (empty means the default path) and
`hive.autoConnect` (reconnect when the bridge restarts).

```sh
npm install
npm run build      # out/extension.js
npm run package    # .vsix via vsce
```

## Tests

```sh
clojure -M:test    # JVM: addon, bridge, rendering, generative properties
npm test           # ClojureScript client
clojure -M:e2e     # node and VS Code end-to-end
```

## Releases

Every push to `main` that changes `src/`, `resources/`, `test/`, `deps.edn` or
`version.edn` runs the suite, bumps the patch version, regenerates
`CHANGELOG.md`, tags `vX.Y.Z` and deploys to Clojars through
[hive-build](https://github.com/hive-agi/hive-build). Do not bump `VERSION` by hand.

## License

MIT
