# Native Android Failure Path Testing Plan

Date: 2026-06-01

Goal: Extend the native Android emulator workflow to cover backend action failures for blocker screens without using live Todoist or WhatsApp.

## Tasks

- [x] Create reusable Codex skill `android-emulator-testing` under `~/.codex/skills`.
- [x] Add a fake-backend unit test for a runtime action-failure toggle.
- [x] Implement host-only fake-backend helper endpoint `POST /__fail-actions`.
- [x] Add Android instrumentation helper support for toggling fake action failures.
- [x] Add start blocker failure test: force backend failure, tap `I'm starting`, assert activity hides and target app error log records `START_TASK`.
- [x] Add deadline blocker failure test: force backend failure, tap `Done`, assert activity hides and target app error log records `COMPLETE_TASK`.
- [x] Run fake-backend tests/build, Android unit tests, and managed-device instrumentation tests.

## Notes

- The fake backend toggle is used instead of starting the whole suite with `FAIL_ACTIONS=1`, because the same instrumentation class now contains both happy-path and failure-path tests.
- `FAIL_ACTIONS=1` remains useful for manual one-mode runs.
- The failure tests assert error-log evidence rather than notification UI; notification shade/system UI coverage should be a later UI Automator slice.
