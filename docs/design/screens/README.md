# UI Screenshot Inventory

Captured for the upcoming UX redesign. All screens run against the local
fake backend (`tools/fake-backend`) seeded with deterministic fixture data —
no production or real user data is shown anywhere in this set. Captured on
`master` after the start-semantics change, on 2026-07-06, from an
`emulator-5584` (Buzer_API_36) AVD at native resolution (no downscaling).

1. **01-home.png** — Home screen with fixture data loaded (task list showing
   "Invalid: 1"). Reached by launching `MainActivity` and waiting for the
   initial snapshot to load.
2. **02-home-refresh-error.png** — Home screen in the snapshot-refresh error
   state, with a Retry action. Reached by enabling the fake backend's
   `/__fail-snapshot` toggle, force-stopping the app, relaunching
   `MainActivity`, and waiting ~8s for the failed refresh to surface.
3. **03-add-task-empty.png** — Add Task form in its empty/default state.
   Reached by tapping "Add task" from the home screen.
4. **04-add-task-project-dropdown.png** — Add Task form with the project
   picker dropdown open, showing "Test Shared" and "Test Personal
   (personal)". Reached by tapping the "Project picker" element; dismissed
   afterward with the BACK key.
5. **05-add-task-start-picker.png** — Add Task form with the start-time
   wheel dialog open (date + hour + minute wheels). Reached by tapping the
   "Start time picker" element; cancelled afterward.
6. **06-add-task-duration-picker.png** — Add Task form with the duration
   wheel dialog open (hours + minutes wheels). Reached by tapping the
   "Duration minutes input" element; cancelled afterward.
7. **07-add-task-filled.png** — Add Task form fully filled out: title
   "Write weekly review", project "Test Personal", a selected start time,
   and the computed "Ends ..." readout. Reached by typing the title, picking
   the project from the dropdown, and confirming a start time via the wheel
   dialog (OK), with the keyboard dismissed via BACK before capture.
8. **08-add-task-submit-error.png** — Add Task form with the save-failed
   error dialog ("Task was not saved") over the filled form. Reached by
   enabling the fake backend's `/__fail-actions` toggle, tapping "Save
   task", and waiting for the error dialog; the toggle was disabled again
   afterward.
9. **09-start-blocker.png** — Start blocker full-screen activity for the
   `task-start` fixture (planned task). Reached via the debug launcher:
   `DebugBlockerLauncherActivity` with `BLOCKER_KIND=start`,
   `TASK_ID=task-start`.
10. **10-deadline-blocker.png** — Deadline blocker full-screen activity for
    the `task-deadline` fixture (started, deadline passed), showing "Done",
    "Extend 30 min", and "Reschedule". Reached via the debug launcher with
    `BLOCKER_KIND=deadline`, `TASK_ID=task-deadline`.
11. **11-deadline-reschedule.png** — Reschedule form launched from the
    deadline blocker. Reached by tapping "Reschedule" on screen 10.
12. **12-morning-review.png** — Morning review screen showing an invalid
    fixture task with repair reasons ("missing_due_datetime,
    missing_duration"). Reached via the debug launcher with
    `BLOCKER_KIND=morning`.
13. **13-morning-review-repair.png** — Repair form launched from the morning
    review screen. Reached by tapping "Repair" on screen 12.
14. **14-overlay-over-home.png** — Start-blocker overlay window rendered on
    top of the Home screen (via `SYSTEM_ALERT_WINDOW`, confirmed as a
    distinct window over the still-resumed `MainActivity`, not a full
    activity replacement). Reached by granting the overlay app-op
    (`cmd appops set app.buzer.android SYSTEM_ALERT_WINDOW allow`), opening
    `MainActivity`, then launching `DebugOverlayLauncherActivity` with
    `kind=START_BLOCKER`, `task_id=task-start`.

All fixture task ids (`task-start`, `task-deadline`, `task-invalid`) and
project data (`Test Shared`, `Test Personal`) come from
`tools/fake-backend/src/fixtures.ts` and are reset between error-toggle
scenarios via `POST /__reset`.
