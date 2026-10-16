# Remaining Dependency Warnings

Date: 2026-06-08

## Node Engine Warnings

`npm install` and `npm ci` can warn with `EBADENGINE` on local Node versions other than 22 because both Node projects intentionally declare:

```json
"engines": {
  "node": "22.x"
}
```

The local assessment on 2026-06-09 used Node `v26.0.0`, which is outside that declared engine range. Use Node 22 for backend and fake-backend development when reproducing dependency-install behavior or CI-like runs.

## Native Addon Rebuilds

Update 2026-10-01: `better-sqlite3` 13.x runs on N-API and ships its prebuilt binaries inside
the package (no `prebuild-install`), so a local Node version change no longer needs a rebuild.
The section below applies to `better-sqlite3` 12.x and older.

`better-sqlite3` is a native dependency. If local Node changes, backend tests can fail with a `NODE_MODULE_VERSION` mismatch until the ignored `backend/node_modules` artifact is rebuilt.

The observed 2026-06-09 mismatch was:

```text
better-sqlite3.node was compiled for NODE_MODULE_VERSION 141
current Node v26.0.0 requires NODE_MODULE_VERSION 147
```

Running `npm rebuild better-sqlite3` in `backend/` fixed the local artifact and restored the backend test suite.

## npm Transitive Deprecations

Backend install may report transitive deprecations for packages such as:

- `prebuild-install`
- `glob`
- `fluent-ffmpeg`

These are not direct dependencies. Revisit when the direct package that brings them updates or if audit/build output starts failing.

## npm Audit: puppeteer Chain (2026-10-01, resolved 2026-10-16)

`whatsapp-web.js` 1.34.7 (latest) pins `puppeteer` 24.38.0 exactly. Every puppeteer up to
24.43.1 pulls `@puppeteer/browsers` 2.x, which depends on `extract-zip` (symlink path
traversal, no patched release exists) and `proxy-agent` -> `basic-ftp` (CPU DoS).

Resolved on 2026-10-16 with an npm override in `backend/package.json`:

```json
"overrides": { "whatsapp-web.js": { "puppeteer": "25.14.0" } }
```

puppeteer 25 uses `@puppeteer/browsers` 3.x (no `extract-zip`, no `proxy-agent`); `npm audit`
reports 0 vulnerabilities. puppeteer 25 is ESM-only and needs Node >= 22.12; whatsapp-web.js's
CommonJS `require('puppeteer')` works through Node's `require(esm)`. It still reads
`PUPPETEER_EXECUTABLE_PATH` and `PUPPETEER_SKIP_DOWNLOAD`, which the `Dockerfile` sets. Verified
by launching a headless whatsapp-web.js `Client` to the `qr` event on Node 24 and 26.

Remove the override once `whatsapp-web.js` itself depends on puppeteer 25+. npm's own suggested
"fix" (downgrade to `whatsapp-web.js` 1.34.2) is wrong; do not apply it.

## npm 11 `allow-scripts` Warnings

npm 11.17+ warns that install scripts (`esbuild`, `fsevents`, `puppeteer`, `@sentry/cli`) are
"not yet covered by allowScripts". The scripts still run; the warning is informational.

## Android Native Strip Warning

AGP can report that `libandroidx.graphics.path.so` cannot be stripped and is packaged as-is. This appears to come from upgraded AndroidX/Compose transitive native artifacts and is non-fatal. Revisit only if release packaging, APK size, or Play upload validation becomes a problem.

## Android 16 Connected Harness

Android 16 / API 36 connected instrumentation passed on the `Buzer_API_36` emulator. Physical-device Android 16 hardware remains unverified; keep that as a hardware-confidence task, not a dependency-migration blocker.
