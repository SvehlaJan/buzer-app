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

## Android Native Strip Warning

AGP can report that `libandroidx.graphics.path.so` cannot be stripped and is packaged as-is. This appears to come from upgraded AndroidX/Compose transitive native artifacts and is non-fatal. Revisit only if release packaging, APK size, or Play upload validation becomes a problem.

## Android 16 Connected Harness

Android 16 / API 36 connected instrumentation passed on the `Buzer_API_36` emulator. Physical-device Android 16 hardware remains unverified; keep that as a hardware-confidence task, not a dependency-migration blocker.
