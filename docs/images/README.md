# README screenshots

Captured on 2026-11-11 from application source revision `2df6cbb`, on the existing
`Buzer_API_36` emulator (Android 16 / API 36). These are app captures, not design
mockups. Native screenshots were downscaled to 648 × 1440 with
`scripts/adb-verify.sh`; the UI was not retouched.

| File | Screen |
| --- | --- |
| `home.png` | Home with a started work block and upcoming tasks |
| `task-editor.png` | Edit task with its schedule, project, assignee, recurrence, notes, and checklist sections |
| `start-blocker.png` | Native start-blocker overlay displayed over the task editor |

## Data and configuration

Only the local `backend/test/e2eServer.ts` fixture backend was used. It constructs
the real application server and an in-memory SQLite database, with a simulated
Todoist adapter and dry-run accountability sender. Sentry DSN and upload token were
empty in the APK build. No real tasks, contacts, credentials, or messages appear.

The backend was reset with `POST /__reset` and `morningReviewScenario: "clean"`.
Fictional sample tasks were added through the ordinary `POST /tasks` endpoint:

- Write weekly review: 45-minute block starting ten minutes before seeding; started
  through the app before the Home capture.
- Review pull requests: 30-minute block starting seventy minutes after seeding.
- Read twenty pages: 30-minute block starting 160 minutes after seeding.
- Prepare tomorrow's priorities: a separate 30-minute block starting one minute
  before creation, used for the blocker capture.

All use the fixture project `project-test` and assignee `user-me`. Times are relative
to capture, so rerunning the same setup will produce different labels. Android's
notification, overlay, full-screen-intent, and battery-exemption setup was completed
inside a temporary read-only emulator session.

## Capture commands

The [fixture-app setup](../notes/local-development.md#explore-the-fixture-app-without-live-credentials)
documents how to build and install an APK without live credentials. The screenshot
build used those same settings plus `-PSENTRY_ENVIRONMENT=documentation`.

After navigating to each screen:

```bash
BUZER_DEVICE_SERIAL=emulator-5584 scripts/adb-verify.sh screenshot docs/images/home.png 1440
BUZER_DEVICE_SERIAL=emulator-5584 scripts/adb-verify.sh screenshot docs/images/task-editor.png 1440
BUZER_DEVICE_SERIAL=emulator-5584 scripts/adb-verify.sh screenshot docs/images/start-blocker.png 1440
```

The existing `DebugBlockerLauncherActivity` was used to reach the active sample
task's blocker. The normal overlay presentation appeared above the editor. Each
PNG was visually inspected after capture.
