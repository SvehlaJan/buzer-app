# Android UI Redesign

Date: 2026-07-07

## Goal

Redesign the Android client around Buzer's actual job: enforcing committed time blocks with minimal ambiguity and minimal interaction cost. The redesign keeps Todoist as the task source, keeps Material 3 as the UI foundation, and improves the current screens without adding unrelated product features.

The app should feel like a calm referee: direct, firm, compact, readable, and free of motivational or decorative UI.

## Scope

In scope:

- Home / today's blocks.
- Start blocker.
- Deadline blocker.
- Overlay blocker.
- Morning Review.
- Add Task.
- Morning Review repair flow.
- Deadline reschedule flow.
- Loading, stale, error, validation, and lightweight health-warning states.
- Shared visual theme and shared UI patterns required by those screens.

Out of scope for this redesign:

- Widget implementation. The widget is deferred to a later roadmap phase and should eventually mirror Home's current/next block language.
- Dedicated health dashboard. This is a personal app; deeper diagnosis remains log-based.
- Backend product behavior changes unrelated to UI needs.
- New accountability rules.

## Design System

Use Material 3 with light customization:

- Standard Material 3 components wherever practical.
- Roboto / platform Material typography, with standard Material text fields and buttons.
- Dynamic-tonal dark-first palette based on the selected soft blue-grey direction.
- Light theme must remain first-class, but the first implementation can establish dark tokens before completing light-token polish.
- Rounded Material surfaces, restrained tonal contrast, and one primary accent for committed action.
- No ornamental gradients, illustrations, gamification, mascots, or decorative animations.
- Text and state must remain legible without color alone.

Healthy screens do not show permanent freshness text such as "now" or "last updated." Freshness appears only when refreshing, stale, failed, or otherwise relevant.

## Shared Patterns

### Commitment Editor

Create one shared commitment editor pattern used by:

- Add Task.
- Morning Review repair.
- Deadline blocker reschedule.

The editor includes:

- Standard single-line Material title text field where title editing is relevant.
- Optional description collapsed by default. When opened, it becomes a text field for details, links, or acceptance notes.
- Receipt summary with project, assignee, start, duration, and computed end.
- Separate Start wheel and Duration wheel.
- Computed submit label using the selected block range, for example `Save 14:30-15:00`.
- Inline validation for required fields.

Changing start or duration immediately updates the receipt and submit label.

### Blocker Surface

Create one reusable `BlockerSurface` family used by:

- Start blocker.
- Deadline blocker.
- Overlay blocker.

The shared structure is:

- Status/inset-safe shell.
- Eyebrow stating the blocker moment.
- Task title.
- Block window and duration.
- Optional task details, clamped when long and expandable if needed.
- Action panel.

Only the action set changes per blocker kind.

### Attention Strip

Home uses one compact attention strip near the top for issues requiring action. It can represent:

- Invalid tasks.
- Exact alarm or notification permission issues.
- Backend unreachable / stale data.
- WhatsApp disconnected.

If multiple issues exist, show the highest priority issue or a compact count such as `3 issues need attention`. Tapping routes to the relevant place when possible:

- Invalid tasks open Morning Review.
- Health issues may open a lightweight detail sheet or remain informational if no dedicated flow exists.

Morning Review may show health warnings in its header, but invalid task repair remains the main content.

## Home

Home becomes a compact "today's blocks" surface rather than a counter dashboard.

Layout:

- Date-first header:
  - Weekday label, for example `Tuesday`.
  - Date headline, for example `July 7`.
- Attention strip below the header when issues exist.
- Current block hero when a block is in progress.
- Timeline agenda list for overdue, upcoming, and done tasks.
- Icon-only vertical FAB stack for Add Task and Morning Review.

Current block hero:

- Shows remaining time prominently.
- Shows task title and block window.
- Includes a `Done` action to complete the current task early.
- `Done` uses the same completion path as the deadline blocker: complete Todoist, update local state, cancel related enforcement.

Agenda rows:

- Use timeline rail styling with time, task title, and state subline.
- Overdue rows use warning/error color plus text such as `Overdue - ended 59m ago`.
- Done rows are muted and can be visually de-emphasized.
- State is not placed in trailing chips because the lower-right FAB stack may cover trailing row content.

FAB stack:

- Primary icon FAB opens Add Task.
- Secondary icon FAB opens Morning Review.
- Must reserve bottom padding so agenda content is not hidden behind the FABs.

## Start Blocker

Start blocker uses `BlockerSurface`.

Content:

- Eyebrow: `Start now`.
- Task title as the dominant fact.
- Block window, for example `10:05-10:35 - 30 min block`.
- Optional task details in a secondary details block.

Actions:

- Primary: `I'm starting`.
- Secondary: `Postpone 5 min`.
- Consequence copy: `Second postpone escalates via WhatsApp.`

No back/dismiss affordance in production blocker mode.

## Deadline Blocker

Deadline blocker uses `BlockerSurface`.

Content:

- Eyebrow: `Block ended`.
- Task title.
- Block window and duration.
- Optional task details.

Actions:

- Primary: `Done`.
- Secondary: `Extend 30m`.
- Secondary: `Reschedule`.

`Reschedule` opens the shared Commitment Editor in reschedule mode. It shows task context, receipt, Start wheel, Duration wheel, and a computed submit label such as `Save 16:00-16:45`.

## Overlay Blocker

The overlay is a compact blocker variant for already-unlocked contexts. It is not a separate product surface.

Use the same `BlockerSurface` content and action model as Start blocker, rendered as a compact bottom sheet over a scrimmed background.

Overlay is lower priority than Home, full-screen blockers, Add Task, and Morning Review in the roadmap. The core redesign must not depend on overlay implementation.

## Morning Review

Morning Review uses focused repair cards.

Header:

- Title such as `3 tasks need attention`.
- Short explanatory copy only when needed.
- Progress indicator in the header only.
- Optional lightweight health warning in the header.

Cards:

- One card per invalid task.
- Task title.
- Plain-language problem, for example `No start time or duration set`.
- Optional task details.
- Primary `Repair`.
- Quiet `Snooze`.

Avoid filler copy such as `1 of 3 left after this task is repaired` inside cards. Progress belongs in the header, not repeated per task.

Repair opens the shared Commitment Editor in repair mode.

All-clear state:

- Plain confirmation such as `All commitments are valid`.
- Single `Done` action.

## Add Task

Add Task uses the shared Commitment Editor as a task creation surface.

Layout:

- Simple header: `Add task`.
- Standard Material single-line title text field by default.
- Optional description collapsed by default.
- Receipt summary:
  - Project.
  - Assignee.
  - Start.
  - Duration.
  - Ends.
- Separate Start and Duration wheel controls.
- Primary button label computed from the selected block, for example `Save 14:30-15:00`.

Behavior:

- Default to the last selected project when available.
- Default assignee to the user when project assignment allows it.
- Default start and duration should remain optimized for fast entry.
- Project and assignee controls stay compact; they must not dominate the scheduling controls.

Validation:

- Missing assignee shows a concise banner such as `Assignee is required before saving`.
- The assignee field/chip group receives field-level error treatment.
- Focus moves to the missing field where practical.

System failures:

- Save failure uses an error dialog with retry.
- Project-load failure uses existing retryable error behavior but styled with the new theme.

## Repair And Reschedule

Morning Review repair and Deadline reschedule reuse the same Commitment Editor pattern.

Differences from Add Task:

- Title editing is not primary unless the task title itself is invalid or editable in that context.
- The task context card appears above the receipt.
- Submit labels are still computed by block range.
- Validation uses the same banner plus field-level error pattern.

This reuse is a core implementation goal. Do not build three separate scheduling form layouts.

## Loading, Error, And Stale States

When there is no cached data:

- Show blocking loading or blocking error state.
- Include retry for blocking error.

When cached data exists:

- Keep the screen usable.
- Show compact inline status:
  - `Refreshing...`
  - `Refresh failed. Showing last known tasks.`

Failed user actions:

- Add Task save failure: dialog with retry save.
- Blocker action failure: dialog with retry action.
- Morning Review repair/snooze failure: dialog with retry action.

Validation failures:

- Use field-level error.
- Add short banner only when it improves orientation.

## Accessibility And Interaction

- Minimum touch targets remain 48dp.
- Icon-only FABs require content descriptions.
- State cannot rely on color alone.
- Text must not overlap controls or be hidden behind FABs.
- Blocker primary actions must be substantially more prominent than escape hatches.
- Long task titles and details must be truncated or expanded intentionally, never overflow.

## Testing And Verification Expectations

The implementation roadmap must update tests in the same phase as UI changes.

Required test coverage:

- Home state builder tests for current, overdue, done, invalid, loading, stale, and empty states.
- Add Task validation and submit-state tests for assignee, title, start, duration, and failed save.
- Commitment Editor pure formatting tests for computed end and submit labels.
- Blocker action-state tests after shared `BlockerSurface` migration.
- Morning Review content-state tests after card migration.
- Instrumentation tests under `client/androidApp/src/androidTest` migrated alongside changed screens.

Required final verification for UI-affecting implementation:

- `cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain`
- `scripts/e2e-fake-backend.sh`

## Implementation Roadmap Shape

The roadmap should be written as a separate implementation plan after this spec is approved.

Recommended phases:

1. Theme tokens and shared primitives.
2. Commitment Editor core.
3. Home timeline agenda and current-block `Done`.
4. Shared BlockerSurface and blocker migration.
5. Morning Review cards and repair reuse.
6. Add Task final integration and validation polish.
7. Overlay compact blocker.
8. Loading/error/health state polish across screens.
9. Instrumentation migration and final e2e verification.
10. Deferred widget redesign.

Each phase should list the existing tests that assert changed behavior and migrate them within that phase.

## Open Decisions

None for the core app redesign.

Deferred:

- Widget redesign details.
- Dedicated health dashboard, intentionally not planned for the personal MVP.
