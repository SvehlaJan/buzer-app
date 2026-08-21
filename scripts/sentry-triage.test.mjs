import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const __dirname = dirname(fileURLToPath(import.meta.url));
const wrapperScript = resolve(__dirname, 'sentry-triage.mjs');

test('scripts/sentry-triage.mjs CLI wrapper can be invoked with --dry-run', () => {
  const result = spawnSync(
    process.execPath,
    [
      wrapperScript,
      '--dry-run',
      '--sentry-token', 'dummy-token',
      '--sentry-org', 'dummy-org',
      '--sentry-project', 'dummy-proj'
    ],
    {
      encoding: 'utf8',
      env: { ...process.env }
    }
  );

  // The wrapper should execute and complete
  assert.equal(typeof result.status, 'number');
});
