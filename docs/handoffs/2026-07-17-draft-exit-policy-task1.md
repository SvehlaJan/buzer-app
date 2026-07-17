# Handoff: Android Smoke-Hardening Task 1 Draft Exit Policy

Date: 2026-07-17
Plan: `docs/superpowers/plans/2026-07-17-android-smoke-hardening.md`

## Scope

- Attempted Task 1 only: introduce pure Add/Edit draft fingerprints and the clean/confirm/ignore exit decision.
- Plan checkboxes were intentionally not edited; the parent task owner tracks plan progress.
- No VPS, Todoist, WhatsApp, alarms, screens, or navigation behavior was changed.

## Completed

- Added `DraftExitPolicy.kt` with generic `decideDraftExit`, `DraftExitDecision`, and immutable add/edit/checklist fingerprints.
- Added explicit add/edit factories that accept only fingerprint data; no request objects, validation state, or Compose visibility state enters the comparison.
- Added focused JVM coverage for clean drafts; every tracked root field; existing/added/deleted/reordered checklist data; restoring the baseline; restored project selection; in-flight submits; and the factory mappings.

## Files Changed

- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/DraftExitPolicy.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/screens/DraftExitPolicyTest.kt`
- This handoff file (left uncommitted because Task 1 requires a two-file feature commit).

## TDD Evidence

Verification:
- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest --tests app.buzer.android.ui.screens.DraftExitPolicyTest`
- Result: FAIL (expected RED)
- Evidence: `compileDebugUnitTestKotlin` reported unresolved `DraftExitDecision`, `decideDraftExit`, and fingerprint types before `DraftExitPolicy.kt` existed.

Verification:
- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest --tests app.buzer.android.ui.screens.DraftExitPolicyTest`
- Result: PASS
- Evidence: final run completed `BUILD SUCCESSFUL`; `:androidApp:testDebugUnitTest` passed.

## Verification

Verification:
- Command: `git diff --check -- client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/DraftExitPolicy.kt client/androidApp/src/test/kotlin/app/buzer/android/ui/screens/DraftExitPolicyTest.kt`
- Result: PASS
- Evidence: no whitespace diagnostics.

## Manual Verification

- Not applicable: this task adds no screen integration or device behavior. Task 2/3 will wire and verify the policy through deterministic Android instrumentation.

## Decisions And Assumptions

- Ordered checklist lists deliberately use `List`, so ordering is part of structural equality; deleted IDs use `Set`, so only membership matters.
- Factories accept already-normalized fingerprint lists to keep this two-file commit independent from concurrent untracked screen/editor types and exclude UI-only validation fields by type.
- Kotlin data-class structural equality was confirmed with Context7 before implementation.

## Open Issues

- The policy is intentionally not wired into Add or Edit yet; that is Task 2/3 scope.
- A requested reviewer subagent could not be created because all agent slots were occupied; a local requirements/self-review was completed instead.

## Quality Review Follow-up

- Review identified that data-class collection properties retained caller-owned mutable `List` and `Set` instances. Mutating a shared source after capturing both baseline and current drafts could make a real edit appear clean.
- Replaced the two collection-bearing data classes with equivalent value objects: constructors and custom `copy` functions snapshot every list/set with `toList()`/`toSet()`, while explicit `equals`/`hashCode` retain structural comparison semantics.
- Strengthened factory coverage to compare complete expected Add/Edit fingerprints, not only their child lists.

Verification:
- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest --tests app.buzer.android.ui.screens.DraftExitPolicyTest`
- Result: FAIL (expected RED)
- Evidence: `fingerprintsSnapshotMutableCollectionInputs` failed after mutation of its source collections (11 tests, 1 failure), proving the prior fingerprints retained those references.

Verification:
- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest --tests app.buzer.android.ui.screens.DraftExitPolicyTest`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL` after defensive snapshotting; the focused class completed with zero failures.

## Next Step

- Complete Task 2: wire the Add Task baseline and exit handler to this pure policy, then exercise the Add fake-backend instrumentation class.
