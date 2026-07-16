# Recurring Checklists Task 11: Shared Client Contracts

Plan/task attempted:

- Completed Task 11 in `docs/superpowers/plans/2026-07-15-recurring-tasks-checklists-and-action-correctness.md` only. No Task 12 state, alarm, or UI files were changed.

Changes made:

- Added serializable recurring/checklist snapshot models, action metadata requests, root/child revisions, mutation outcomes, and authoritative mutation responses.
- Added fixed create/update/checklist client methods that return `MutationResponse`, using the configured base URL unchanged.
- Added `BuzerApiException` carrying HTTP status, error code, retry timing, and any authoritative conflict snapshot. Error logs now include only status/code; request logging remains header-only with Authorization sanitized.
- Added contract coverage for fixed JSON serialization/decoding and API error coverage for 409 snapshots, 429 retry timing with no hidden retry, and malformed error bodies.
- Migrated the legacy flat update JSON test to the fixed `rootPatch`/`childMutations` shape. The existing timeout test remains valid and passes unchanged.

Files changed:

- `client/shared/src/commonMain/kotlin/app/buzer/shared/model/TaskModels.kt`
- `client/shared/src/commonMain/kotlin/app/buzer/shared/api/BuzerApiClient.kt`
- `client/shared/src/commonTest/kotlin/app/buzer/shared/api/BuzerApiClientContractTest.kt`
- `client/shared/src/commonTest/kotlin/app/buzer/shared/api/BuzerApiClientErrorTest.kt`
- `client/shared/src/commonTest/kotlin/app/buzer/shared/api/BuzerApiClientJsonTest.kt`
- `docs/superpowers/plans/2026-07-15-recurring-tasks-checklists-and-action-correctness.md`
- this handoff

TDD evidence:

- RED: `./gradlew :shared:testAndroidHostTest --tests '*BuzerApiClientContractTest' --console=plain`
  - Result: FAIL
  - Evidence: the new contract test failed at compilation because recurring/checklist models, action metadata fields, root/child mutations, and mutation response types were absent.
- GREEN: `./gradlew :shared:testAndroidHostTest --tests 'app.buzer.shared.api.*' --console=plain`
  - Result: PASS
  - Evidence: 13 API tests passed after fixed models, client envelopes, and typed error parsing were implemented.

Verification:

- Command: `./gradlew :shared:testAndroidHostTest --tests 'app.buzer.shared.api.BuzerApiClientErrorTest' --tests 'app.buzer.shared.api.BuzerApiClientTimeoutTest' --console=plain`
  - Result: PASS
  - Evidence: 409 snapshot context, 429 `Retry-After`, malformed error handling, and the existing timeout behavior passed.
- Command: `./gradlew :shared:testAndroidHostTest --console=plain`
  - Result: PASS
  - Evidence: complete shared Android-host suite passed.

## Patch and focused-checklist quality follow-up

Plan/task attempted:

- Recovered the final Task 11 patch-serialization and focused-checklist quality findings only. No Task 12 files were edited.

Changes made:

- Replaced lossy nullable root-patch fields with `NullablePatch`, preserving absent, non-null set, and explicit-null clear semantics for description and assignee.
- Made bulk checklist edit and focused checklist update titles required nonblank strings.
- Removed the `checked` property from `FocusedChecklistMutationRequest`; check/uncheck is represented solely by the endpoint path, which now sends the original action metadata body unchanged.

TDD evidence:

- RED: `./gradlew :shared:testAndroidHostTest --tests 'app.buzer.shared.api.BuzerApiClientContractTest' --console=plain`
  - Result: FAIL
  - Evidence: `NullablePatch` and the checked-free focused request did not exist, so the new fixed-wire contract did not compile.
- GREEN: `./gradlew :shared:testAndroidHostTest --tests 'app.buzer.shared.api.BuzerApiClientContractTest' --tests 'app.buzer.shared.api.BuzerApiClientErrorTest' --console=plain`
  - Result: PASS
  - Evidence: tri-state root patch wire tests, strict edit-title validation, and MockEngine focused-path/body checks passed.

Verification:

- Command: `./gradlew :shared:testAndroidHostTest --console=plain`
  - Result: PASS
  - Evidence: complete shared Android-host suite passed.
- Command: `git diff --check`
  - Result: PASS
  - Evidence: no whitespace errors.

Manual verification:

- Inspected the error boundary: response bodies are parsed only into the typed exception and are not included in log messages. The client does not issue a second request after a 429.

Known failures or skipped checks:

- No Android application or emulator lane was run: this task changes shared API contracts only and has no UI behavior. Task 14 will migrate Android coordinators to construct immutable action requests.

Assumptions:

- `RootTaskPatch` emits an explicit `assigneeId: null`, which is the fixed wire signal for clearing assignee ownership.
- Legacy no-metadata action overloads remain deprecated only to preserve the currently staged Android migration boundary; their callers must move to immutable request overloads in Task 14.

Next recommended step:

- Continue Task 12 occurrence-aware state and alarm plan changes, then migrate Android callers to explicit action metadata in Task 14.

## Contract-hardening follow-up

Plan/task attempted:

- Recovered Task 11 review findings only. No Task 12 files were edited.

Changes made:

- Removed the client’s deprecated overloads that created empty action metadata. Fixed request models now require nonblank `actionId`, `deviceId`, and, when applicable, `expectedOccurrenceKey` at construction.
- Added `stale_child` as a supported child mutation outcome so partial responses remain decodable.
- Error logging now emits status and code only; it no longer attaches the exception, avoiding response-message/stack logging through this path.

TDD evidence:

- RED: `./gradlew :shared:testAndroidHostTest --tests 'app.buzer.shared.api.BuzerApiClientContractTest' --console=plain`
  - Result: FAIL
  - Evidence: contract compilation failed because `MutationOutcome.stale_child` did not exist.
- GREEN: `./gradlew :shared:testAndroidHostTest --tests 'app.buzer.shared.api.BuzerApiClientContractTest' --console=plain`
  - Result: PASS
  - Evidence: nonblank metadata rejection and partial `stale_child` decoding passed.

Verification:

- Command: `./gradlew :shared:testAndroidHostTest --console=plain`
  - Result: PASS
  - Evidence: complete shared Android-host suite passed.
- Command: `git diff --check`
  - Result: PASS
  - Evidence: no whitespace errors.

Known failures or skipped checks:

- `./gradlew :androidApp:compileDebugKotlin --console=plain` is currently FAIL because the pre-Task 14 Android coordinator/editor code still constructs legacy flat requests and calls removed no-metadata action methods. This recovery deliberately does not fabricate metadata in shared client code; migrate those UI/coordinator callers through explicit user-intent request objects in Task 14 before Android application verification.

## Checklist mutation validation follow-up

Plan/task attempted:

- Recovered the final Task 11 checklist-mutation validation finding only. No Task 12 files were edited.

Changes made:

- Every `ChecklistMutation` subtype now rejects a blank `clientMutationId`; `Edit`, `Delete`, `Check`, and `Uncheck` also reject a blank `expectedChildRevision`.

TDD evidence:

- RED: `./gradlew :shared:testAndroidHostTest --tests 'app.buzer.shared.api.BuzerApiClientContractTest' --console=plain`
  - Result: FAIL
  - Evidence: the new mutation validation test constructed a blank-ID add mutation without throwing.
- GREEN: `./gradlew :shared:testAndroidHostTest --tests 'app.buzer.shared.api.BuzerApiClientContractTest' --console=plain`
  - Result: PASS
  - Evidence: all five mutation variants now reject their required blank metadata.

Verification:

- Command: `./gradlew :shared:testAndroidHostTest --console=plain`
  - Result: PASS
  - Evidence: complete shared Android-host suite passed.
