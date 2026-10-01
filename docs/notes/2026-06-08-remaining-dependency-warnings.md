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

## npm Audit: puppeteer Chain (2026-10-01)

After the 2026-10-01 upgrade, `npm audit` reports 5 high findings, all from
`whatsapp-web.js` 1.34.7 (latest), which pins `puppeteer` 24.38.0 exactly. The vulnerable
`extract-zip` sits in `@puppeteer/browsers`, the browser downloader. Production sets
`PUPPETEER_SKIP_DOWNLOAD` and uses Debian's Chromium (`Dockerfile`), so that code does not run.
npm's suggested "fix" is a downgrade to `whatsapp-web.js` 1.34.2; do not apply it. Revisit
when `whatsapp-web.js` moves to a newer puppeteer.

## npm 11 `allow-scripts` Warnings

npm 11.17+ warns that install scripts (`esbuild`, `fsevents`, `puppeteer`, `@sentry/cli`) are
"not yet covered by allowScripts". The scripts still run; the warning is informational.

## Android Native Strip Warning

AGP can report that `libandroidx.graphics.path.so` cannot be stripped and is packaged as-is. This appears to come from upgraded AndroidX/Compose transitive native artifacts and is non-fatal. Revisit only if release packaging, APK size, or Play upload validation becomes a problem.

## Android 16 Connected Harness

Android 16 / API 36 connected instrumentation passed on the `Buzer_API_36` emulator. Physical-device Android 16 hardware remains unverified; keep that as a hardware-confidence task, not a dependency-migration blocker.
