# Android Smoke-Hardening Design

**Date:** 2026-07-17  
**Status:** proposed  
**Scope:** Android client navigation and debug-only blocker launch support. The VPS snapshot deployment mismatch is explicitly out of scope for this design.

## Problem

The connected-device smoke pass found two Android defects unrelated to the live VPS response:

1. Pressing Android Back from Add Task lets `MainActivity` finish, taking the user to the launcher. It neither returns to Home nor protects edited data.
2. `DebugBlockerLauncherActivity` forwards a task ID but not its occurrence key. Start and Deadline blocker activities correctly reject that incomplete identity, so the debug launcher cannot open them.

Edit Task currently has a visible Cancel button but no Android-Back handling. It should obey the same draft-protection rule as Add Task.

## Goals

- Normal app screens use Android Back to return to Home rather than finish the app.
- Back or Cancel never silently discards a changed Add or Edit draft.
- Untouched Add/Edit forms return Home immediately, without a dialog.
- Existing full-screen blocker behavior remains unchanged: production Start and Deadline blockers keep intercepting Android Back.
- The debug-only launcher carries all identity needed to open a Start or Deadline blocker.
- The behavior is covered by deterministic unit and connected-device fake-backend tests.

## Non-Goals

- Persisting or restoring drafts after process death.
- Changing production alarm identity, alarm scheduling, or blocker action semantics.
- Changing the deployed VPS, Funnel, Todoist, or WhatsApp configuration.
- Adding a full navigation framework or a global app back stack.

## Design

### 1. Screen exit contract

`AddTaskScreen` and `EditTaskScreen` receive a single `requestExit` path shared by their visible Cancel button and an Android `BackHandler`.

- If the form is clean, `requestExit` immediately invokes the existing `onBack`, which sets `BuzerApp`'s section to Home.
- If the form is dirty, `requestExit` opens a Material confirmation dialog:
  - title: `Discard changes?`
  - message: `Your unsaved changes will be lost.`
  - secondary action: `Keep editing` (dismisses the dialog)
  - destructive action: `Discard` (invokes `onBack`)
- While an Add create or Edit update is in flight, both the visible Cancel action and Android Back are ignored. This prevents the activity from closing while the request outcome is unresolved.
- Modal pickers and menus continue to consume Back before the screen-level handler. The screen-level confirmation only applies when no child modal is handling the event.

The dialog is screen-local; `BuzerApp` remains the owner of the Home/Add/Edit section state. No new navigation framework is introduced.

### 2. Dirty-draft definitions

Dirty state is a pure, testable comparison between the editable data and a baseline captured for the current screen instance. Presentation-only state does not count.

**Add Task baseline:**

- title and description
- selected project and selected assignee
- start date/time, duration, recurrence preset
- checklist draft IDs, titles, and descriptions

The baseline is established after the initial project load and any persisted-project restoration have settled. Loading a persisted project must not itself make the new form dirty. Choosing a different project, even though the preferred-project setting is retained for future new forms, does make the current draft dirty.

**Edit Task baseline:**

- editable root fields (title, description, start, duration, recurrence)
- existing checklist item data and checked state
- deleted child IDs and newly added checklist drafts

The baseline resets when the authoritative task ID changes. A stale update retains the current changed draft and therefore remains dirty. A successful update navigates Home directly; it never presents a discard dialog.

`descriptionExpanded`, picker/menu visibility, validation messages, loading state, and cached pending request objects are UI state, not draft data.

### 3. Debug blocker launcher

`DebugBlockerLauncherActivity` copies the full incoming alarm identity onto its target intent:

- `AlarmIntentFactory.EXTRA_TASK_ID`
- `AlarmIntentFactory.EXTRA_OCCURRENCE_KEY`
- `AlarmIntentFactory.EXTRA_ALARM_KIND` when supplied

For `start` and `deadline`, both task ID and occurrence key are required. The launcher should fail closed—launch no target and finish—when either is absent, matching the production activities' identity requirements. Morning review remains task-independent.

This change is debug-source-set only. It does not relax the validation in `StartBlockerActivity` or `DeadlineBlockerActivity` and does not change production manifests or alarm behavior.

### 4. Test strategy

Add focused pure tests for the dirty comparators:

- untouched Add and Edit drafts are clean;
- one change in each tracked field category is dirty;
- returning a field to its baseline is clean;
- restored project selection is clean;
- presentation-only state does not become dirty;
- an Edit stale-conflict draft remains dirty.

Extend the fake-backend Android instrumentation lane on the connected device:

- Add Task: Android Back returns Home when untouched; changed title opens the dialog; Keep editing preserves input; Discard returns Home.
- Edit Task: identical Back/Cancel behaviors using an existing fixture task; update-in-flight cannot navigate away.
- Debug launcher: Start and Deadline launch with known fixture task/occurrence identities and render their blockers instead of logging `missing_alarm_identity`.
- Existing blocker Back tests remain unchanged and must pass, proving this navigation rule does not make production blockers dismissible.

Run the existing `scripts/e2e-connected-device.sh` lane, then reinstall the real-backend debug build so the phone is not left pointed at a test service.

## Acceptance Criteria

1. Pressing Android Back from an untouched Add or Edit form displays Home; it does not exit Buzer.
2. Pressing Back or Cancel from a changed Add or Edit form offers Keep editing and Discard; no unsaved changes are lost without choosing Discard.
3. Add/Edit cannot be exited while create/update is in progress.
4. Production Start and Deadline blocker Back interception is unchanged.
5. Debug Start/Deadline launches include the occurrence key and render their target blocker for a valid fake-backend fixture.
6. Focused unit tests and the physical-device fake-backend instrumentation suite pass.

## Risks and Mitigations

- **False dirty prompt after async project restoration:** capture Add's baseline only once project initialization completes; cover this with a unit test.
- **BackHandler masks a picker/dialog dismissal:** retain child-modal behavior and test screen-level Back only when the form itself has focus.
- **Debug-only behavior leaks into release:** keep changes in `src/debug`; production activities retain their existing identity checks.
- **A live endpoint issue is mistaken for a UI regression:** keep VPS deployment verification outside this patch and record it separately.
