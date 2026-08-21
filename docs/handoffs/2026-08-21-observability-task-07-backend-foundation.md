# Handoff: Observability Task 7 - Add Backend Observability Foundation

**Date:** 2026-08-21  
**Plan:** `docs/superpowers/plans/2026-08-21-observability-and-bug-reporting.md`  
**Task:** Task 7: Add Backend Observability Foundation  
**Status:** DONE  

---

## 1. Summary of Changes

Implemented backend Sentry observability boundary, secret redaction parity engine matching the canonical redaction corpus, in-memory bounded ring buffers with safe multi-byte UTF-8 truncation, Fastify telemetry/tracing hooks, and centralized configuration:

1. **Dependencies & Configuration** (`backend/package.json`, `backend/package-lock.json`, `backend/src/config.ts`):
   - Added `@sentry/node` pinned to `8.53.0`.
   - Created `backend/src/config.ts` exporting server environment variables and defaults (`SENTRY_BACKEND_DSN`, `SENTRY_ENVIRONMENT`, `SENTRY_RELEASE`, `DATABASE_PATH`, `TODOIST_USER_ID`, `ANDROID_API_TOKEN`, `TODOIST_API_TOKEN`, `WHATSAPP_RECIPIENT_ID`, `PORT`, `WHATSAPP_DRY_RUN`, `WHATSAPP_SESSION_PATH`, `WHATSAPP_CLIENT_ID`).

2. **Redaction Parity Engine** (`backend/src/diagnostics/redaction.ts`):
   - Implemented TypeScript redaction engine with 100% parity to `test-fixtures/observability/redaction-corpus.json` and Kotlin `DiagnosticRedactor`.
   - Case-insensitive credential key matching (`authorization`, `cookie`, `set-cookie`, `token`, `api_token`, `access_token`, `refresh_token`, `password`, `secret`, `private_key`, `todoist_token`, `sentry_auth_token`, `whatsapp_session`, `qr_code`, `session_token`, etc.) while protecting non-credential IDs (`reportSessionId`, `occurrenceKey`, `deviceId`, `taskId`, `actionId`, `requestId`, `traceId`).
   - Regular expressions for:
     - PEM private key blocks (`/-----BEGIN [A-Z0-9_ -]*PRIVATE KEY-----[\s\S]*?(?:-----END [A-Z0-9_ -]*PRIVATE KEY-----|$)/g` -> `[REDACTED]`)
     - Bearer and Basic authorization schemes (`/\b(bearer|basic)\s+(?:(["'])(?!\[REDACTED\]\2)([^\r\n]*?)\2|(?!\[REDACTED\])[^\s,;"'\r\n]+)/gi`)
     - Credentials in URLs (`/([a-zA-Z][a-zA-Z0-9+.-]*:\/\/)([^@\/\s]+)@/g`)
     - Cookie and Set-Cookie headers (`/\b(cookie|set-cookie|set_cookie)\s*:\s*(?!\[REDACTED\])[^\r\n]+/gi`)
     - QR strings and WhatsApp session strings (`/\b(qr_code|qr_string|qr_content|whatsapp_session)\s*([:=])\s*(?!\[REDACTED\])[^\r\n]+/gi`)
     - In-text key-value assignments (`KEY_VALUE_PATTERN`)
   - Functions exported: `redactString(input: string): string`, `redactAny(value: unknown): unknown`, `redactObject(input: Record<string, unknown>): Record<string, unknown>`, `isCredentialKey(rawKey: string): boolean`.

3. **In-Memory Ring Buffers & UTF-8 Truncation** (`backend/src/diagnostics/ringBuffer.ts`):
   - `RingBuffer<T>`:
     - Capacity bounds (1,000 for logs, 100 for diagnostics/network/polling).
     - 15-minute maximum age expiration (900,000 ms).
     - `add(item: T, timestampMillis?: number): void`
     - `snapshot(nowMillis?: number): T[]` (newest-first, filtered by max age, bounded to capacity).
   - `truncateUtf8(input: string, maxBytes: number): string`: Safe code point traversal that never splits multi-byte UTF-8 sequences or UTF-16 surrogate pairs (such as emojis).
   - Exported singletons: `backendLogRingBuffer`, `backendDiagnosticRingBuffer`.

4. **Backend Observability Boundary** (`backend/src/diagnostics/backendObservability.ts`):
   - Defined `BackendObservability`, `DiagnosticAttachment`, and `DiagnosticCorrelation` interfaces.
   - Implemented `NoOpBackendObservability`: returned when Sentry DSN is empty or whitespace.
   - Implemented `SentryBackendObservability`: integrates with `@sentry/node`, applies `beforeSend` and `beforeBreadcrumb` redactions, produces 32-character lowercase hex event IDs without hyphens, captures handled/fatal errors with correlation tags/contexts, attaches binary artifacts via Sentry scope attachments, and supports flushing.
   - Exported `backendObservabilityRuntime` and global `backendObservability` proxy.

5. **Fastify Telemetry Hooks** (`backend/src/server.ts`):
   - `BuildServerOptions`: supports optional `observability?: BackendObservability` injection (defaults to `backendObservability`).
   - Fastify configuration: configured with `requestIdHeader: "x-request-id"`, generating UUID v4 when missing.
   - `onRequest` hook: sets `x-request-id` response header, extracts `sentry-trace` / `baggage` headers, correlates request context, and writes redacted request line to `backendLogRingBuffer`.
   - `preHandler` hook: sanitizes and truncates request bodies to 64 KiB (65,536 bytes), extracts `taskId`, `actionId`, `deviceId`, and `occurrenceKey`.
   - `onResponse` hook: records redacted and truncated exchange to `backendDiagnosticRingBuffer` (status, duration, sanitized URL, body, correlation) and logs response to `backendLogRingBuffer`.
   - `onError` hook: captures unhandled 5xx internal errors to `observability.captureHandled("fastify_unhandled_error", error, correlation)` while logging 4xx client/validation errors as warnings without Sentry exceptions.
   - Graceful shutdown: flushes observability queue on server close.

6. **Comprehensive Test Suites** (`backend/test/redaction.test.ts`, `backend/test/backendObservability.test.ts`):
   - Verified 100% parity across all string cases and JSON cases in `test-fixtures/observability/redaction-corpus.json` and guaranteed complete purge of all 12/13 sentinels.
   - Verified RingBuffer capacity eviction, newest-first ordering, 15-minute expiration filtering.
   - Verified UTF-8 truncation safety across ASCII, Czech accented characters, and 4-byte emoji surrogate pairs.
   - Verified NoOp vs Sentry initialization with blank/present DSN.
   - Verified Fastify request ID propagation, generated request IDs, header extraction, body truncation/redaction, and 5xx error capture vs 4xx validation skipping.

---

## 2. Verification and Test Evidence

All backend and cross-runtime test suites passed:

- **Targeted Redaction Parity Suite**:
  - `backend/test/redaction.test.ts`: 4 passed
- **Targeted Backend Observability Suite**:
  - `backend/test/backendObservability.test.ts`: 12 passed
- **Full Backend Test Suite**:
  - `npm test` in `backend/`: 34 test files passed, 422 tests passed (0 failures)
- **TypeScript Typecheck & Build**:
  - `npm run typecheck`: 0 errors
  - `npm run build`: built successfully to `backend/dist`
- **Kotlin Redaction Corpus Parity Check**:
  - `./gradlew :shared:testAndroidHostTest --tests '*RedactionCorpusParityTest*'`: BUILD SUCCESSFUL

---

## 3. Files Created / Modified

- `backend/package.json` (modified)
- `backend/package-lock.json` (modified)
- `backend/src/config.ts` (created)
- `backend/src/diagnostics/redaction.ts` (created)
- `backend/src/diagnostics/ringBuffer.ts` (created)
- `backend/src/diagnostics/backendObservability.ts` (created)
- `backend/src/server.ts` (modified)
- `backend/test/redaction.test.ts` (created)
- `backend/test/backendObservability.test.ts` (created)
- `docs/superpowers/plans/2026-08-21-observability-and-bug-reporting.md` (modified)
- `docs/handoffs/2026-08-21-observability-task-07-backend-foundation.md` (created)

---

## 4. Next Steps

- Proceed to **Task 8: Implement The Read-Only Backend Diagnostic Endpoint** (`POST /diagnostics/reports`).
