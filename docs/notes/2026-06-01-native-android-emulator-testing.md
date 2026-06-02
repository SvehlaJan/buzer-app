# Native Android Emulator Testing

Date: 2026-06-01

## Fake Backend

```bash
cd tools/fake-backend
PORT=18433 npm run dev
```

The fake backend is the integration boundary for blocker action tests. It records action POSTs in memory and avoids live Todoist or WhatsApp side effects.

Action failures can be forced per test without restarting the server:

```http
POST /__fail-actions
Content-Type: application/json

{"enabled":true}
```

Reset it to `{"enabled":false}` before or after each test that needs a healthy backend.

## Native Android Tests

```bash
cd client
./gradlew pixel8Api34DebugAndroidTest \
  -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 \
  -PBUZER_BACKEND_TOKEN=test-token \
  -PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433
```

`10.0.2.2` is the Android emulator route to the host machine, so the app under test and the instrumentation helper can both reach the host fake backend.

## Fallback Connected Emulator

```bash
cd client
./gradlew :androidApp:connectedDebugAndroidTest \
  -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 \
  -PBUZER_BACKEND_TOKEN=test-token \
  -PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433
```

Use this only when Gradle Managed Devices are blocked or too slow. The primary workflow is `pixel8Api34DebugAndroidTest`.

## Rules

- Use native Android instrumentation tests for blocker UI/action verification.
- Keep fake backend action tests off live Todoist and WhatsApp.
- Do not use host-side coordinate taps as the primary test workflow.
- Use UI Automator later only for system UI behavior.
- Keep the app-under-test backend configuration in Gradle properties (`BUZER_BACKEND_BASE_URL`, `BUZER_BACKEND_TOKEN`), not instrumentation-only arguments.

## Finding From Implementation

The shared Ktor client originally sent empty action POSTs without an explicit JSON body and did not throw on non-success HTTP responses. Fastify can reject unsupported empty POST content types before route handlers run, and Ktor defaults could let the activity treat that response as success. The client now sends `{}` with `Content-Type: application/json` for empty action endpoints and sets `expectSuccess = true`.

The forced-failure instrumentation tests verify that start and deadline blocker activities hide after backend action failure and that `blocker_action_errors.log` records the failed action. Tests read that file through `InstrumentationRegistry.getInstrumentation().targetContext`.
