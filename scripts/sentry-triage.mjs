#!/usr/bin/env node

/**
 * Sentry-to-GitHub Triage CLI Wrapper
 * Delegates to tools/sentry-github-sync
 */

import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

const rootDir = resolve(__dirname, '..');
const toolDir = resolve(rootDir, 'tools/sentry-github-sync');
const distEntry = resolve(toolDir, 'dist/src/index.js');
const tsEntry = resolve(toolDir, 'src/index.ts');
const localTsx = resolve(toolDir, 'node_modules/.bin/tsx');

const args = process.argv.slice(2);

let result;
if (existsSync(distEntry)) {
  result = spawnSync(process.execPath, [distEntry, ...args], {
    stdio: 'inherit',
    env: process.env
  });
} else if (existsSync(localTsx)) {
  result = spawnSync(localTsx, [tsEntry, ...args], {
    stdio: 'inherit',
    env: process.env
  });
} else {
  result = spawnSync('npx', ['--prefix', toolDir, 'tsx', tsEntry, ...args], {
    stdio: 'inherit',
    env: process.env
  });
}

if (result.error) {
  console.error(`Failed to run sentry-github-sync: ${result.error.message}`);
  process.exit(1);
}

process.exit(result.status ?? 0);
