# Home Static Blocker Route Cleanup Design

Date: 2026-06-07

## Goal

Remove obsolete static blocker demo navigation from the Android home screen so the app shell reflects the real MVP enforcement model.

The home screen should remain a real operational surface: it shows snapshot counts, refresh status, Add task, and Morning review. Start and Deadline blocker UI should only be reached through real Android blocker activities with task context, not through static in-app Compose routes.

## Background

The original KMP Android shell exposed `Home`, `Add`, `Review`, `Start`, and `Deadline` sections from `BuzerApp`. That was useful while the project only had first-pass Compose screens and no real Android enforcement wiring.

Later work added:

- `StartBlockerActivity`
- `DeadlineBlockerActivity`
- `MorningReviewActivity`
- backend-connected blocker actions
- live snapshot state on the home screen
- fake-backend managed-device tests

Morning review was already moved from static Compose navigation to `MorningReviewActivity`. Start and Deadline still have static home routes, and logs mark them as `mode=static_compose_route`. Those routes are now development scaffolding that no longer matches the product behavior.

## Scope

This cleanup removes static blocker/demo navigation from the app shell.

In scope:

- Remove Start blocker and Deadline blocker buttons from `HomeScreen`.
- Remove Start and Deadline callback parameters from `HomeScreen`.
- Remove `AppSection.Start` and `AppSection.Deadline` from `BuzerApp`.
- Remove dead static navigation branches to `StartBlockerScreen` and `DeadlineBlockerScreen` in `BuzerApp`.
- Remove any dead static `Review` route from `BuzerApp` if it is no longer reachable because Morning review uses `MorningReviewActivity`.
- Remove imports and log lines that only supported static home navigation.
- Update home-screen unit tests and fake-backend instrumentation tests so they assert the current user-facing home behavior.

Out of scope:

- Do not remove `StartBlockerScreen`, `DeadlineBlockerScreen`, or `MorningReviewScreen` composables. The real Android activities still use these screens.
- Do not change `StartBlockerActivity`, `DeadlineBlockerActivity`, or `MorningReviewActivity` behavior.
- Do not add task-backed blocker pickers in this slice.
- Do not change alarm scheduling, backend actions, Todoist behavior, WhatsApp behavior, or snapshot reconciliation.
- Do not redesign the home screen beyond removing obsolete demo entry points.

## Intended Home Behavior

The user-facing home screen includes:

- `Today` headline.
- Optional status message such as `Refresh failed`.
- Counts for invalid tasks, active blockers, tasks starting today, and health warnings.
- Primary `Add task` action.
- `Morning review` action that opens `MorningReviewActivity`.

The user-facing home screen does not include:

- `Start blocker` button.
- `Deadline blocker` button.
- Any static Compose route that presents blocker UI without a task ID.

## Architecture

`HomeScreen` becomes a smaller composable with only the callbacks it actually exposes:

- `onAddTask`
- `onMorningReview`

`BuzerApp` owns only app-shell navigation that is valid from home:

- `Home`
- `Add`

Morning review stays activity-backed. The home callback calls `context.startActivity(Intent(context, MorningReviewActivity::class.java))`.

The real blocker activity architecture remains unchanged:

- Alarms and explicit Android activity launches provide task IDs.
- `StartBlockerActivity` renders `StartBlockerScreen`.
- `DeadlineBlockerActivity` renders `DeadlineBlockerScreen`.
- `MorningReviewActivity` renders `MorningReviewScreen`.

## Testing

Update unit tests to verify `HomeScreenState` still derives snapshot counts and status correctly.

Update UI/instrumentation coverage where needed:

- Home screen still shows Add task and Morning review.
- Home screen no longer shows Start blocker or Deadline blocker buttons.
- Morning review still opens the real `MorningReviewActivity`.
- Existing blocker activity tests remain unchanged unless they imported app-shell routes indirectly.

Run:

```bash
cd client
./gradlew :shared:testDebugUnitTest :androidApp:testDebugUnitTest :androidApp:assembleDebug
```

If a fake backend is running or can be started for this slice, run the managed-device fake-backend suite that currently covers home behavior:

```bash
cd tools/fake-backend
PORT=18433 npm run dev
```

```bash
cd client
./gradlew pixel8Api34DebugAndroidTest --rerun-tasks \
  -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 \
  -PBUZER_BACKEND_TOKEN=test-token \
  -PBUZER_ASSIGNEE_ME_ID=user-me \
  -PBUZER_ASSIGNEE_WIFE_ID=user-wife \
  -PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433
```

## Success Criteria

- No user-facing Start blocker or Deadline blocker buttons remain on home.
- `BuzerApp` contains no static Start, Deadline, or dead Review route.
- Real blocker activities still compile and keep using their screen composables.
- Existing Add task and Morning review home actions still work.
- Relevant unit/build checks pass.
- Managed-device fake-backend home coverage passes or any skipped emulator check is documented in the handoff.
