// Launch VS Code with the hive-vscode extension under development and run suite.js.
// Driven by test-e2e/hive_vscode/e2e_vscode_test.clj, which supplies the HIVE_VSCODE_* env.
// SPDX-License-Identifier: MIT
const path = require('path');
const { runTests } = require('@vscode/test-electron');

async function main() {
  const env = {
    XDG_RUNTIME_DIR: process.env.XDG_RUNTIME_DIR,
    HIVE_VSCODE_READY: process.env.HIVE_VSCODE_READY,
    HIVE_VSCODE_RESULT: process.env.HIVE_VSCODE_RESULT,
    HIVE_VSCODE_EXPECT: process.env.HIVE_VSCODE_EXPECT,
    HIVE_VSCODE_FILE: process.env.HIVE_VSCODE_FILE,
  };
  try {
    await runTests({
      extensionDevelopmentPath: path.resolve(__dirname, '..'),
      extensionTestsPath: path.resolve(__dirname, 'suite.js'),
      extensionTestsEnv: env,
      launchArgs: [
        process.env.HIVE_VSCODE_WORKSPACE,
        '--user-data-dir', process.env.HIVE_VSCODE_USER_DIR,
        '--disable-extensions', '--disable-workspace-trust',
        '--skip-welcome', '--skip-release-notes', '--disable-gpu',
      ],
    });
    process.exit(0);
  } catch (err) {
    console.error('vscode e2e failed:', err);
    process.exit(1);
  }
}

main();
