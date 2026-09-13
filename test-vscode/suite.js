// Runs inside the VS Code extension host. Waits for the hive bridge connection,
// signals readiness, waits for the expected payloads, then records real editor state.
// SPDX-License-Identifier: MIT
const fs = require('fs');
const vscode = require('vscode');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function waitFor(pred, what, ms) {
  const deadline = Date.now() + ms;
  while (!(await pred())) {
    if (Date.now() > deadline) throw new Error('timed out waiting for ' + what);
    await sleep(200);
  }
}

exports.run = async function run() {
  const ext = vscode.extensions.getExtension('hive-agi.hive-vscode');
  if (!ext) throw new Error('hive-agi.hive-vscode extension not found');
  const api = await ext.activate();
  const events = [];
  api.onEvent((event, data) => events.push({ event, data }));

  await waitFor(() => api.status().status === 'connected', 'bridge connection', 60000);
  fs.writeFileSync(process.env.HIVE_VSCODE_READY, 'ready');

  const expected = Number(process.env.HIVE_VSCODE_EXPECT);
  await waitFor(() => api.status().applied >= expected, expected + ' applied payloads', 60000);
  const file = process.env.HIVE_VSCODE_FILE;
  await waitFor(() => {
    const ed = vscode.window.activeTextEditor;
    return ed && ed.document.uri.fsPath === file;
  }, 'active editor on ' + file, 30000);
  await waitFor(() => vscode.window.terminals.some((t) => t.name === 'hive'), 'hive terminal', 30000);

  const ed = vscode.window.activeTextEditor;
  const result = {
    status: api.status(),
    panels: api.openPanels(),
    terminals: vscode.window.terminals.map((t) => t.name),
    activeFile: ed.document.uri.fsPath,
    activeLine: ed.selection.active.line,
    activeColumn: ed.selection.active.character,
    events,
    vscodeVersion: vscode.version,
  };
  fs.writeFileSync(process.env.HIVE_VSCODE_RESULT, JSON.stringify(result));
};
