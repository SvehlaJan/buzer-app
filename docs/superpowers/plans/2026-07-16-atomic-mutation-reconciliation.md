# Atomic Mutation Reconciliation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace public partial checklist mutation responses with safe, durable completed-or-pending reconciliation.

**Architecture:** Action fingerprints contain only immutable client intent and preconditions. The journal retains command UUIDs and first-execution derived values; any uncertainty after a command is sent returns `202 reconciliation_pending` until authoritative sync proves the whole requested effect. Android applies returned snapshots directly and has no child-specific retry state.

**Tech Stack:** TypeScript/Fastify/SQLite/Vitest, Kotlin Multiplatform/Ktor/Compose/Gradle, fake backend instrumentation.

---

### Task 1: Make child identity and fake wire contracts match the snapshot

**Files:**
- Modify: `backend/src/services/todoistChecklistService.ts`
- Modify: `backend/test/http/routes.test.ts`
- Modify: `tools/fake-backend/src/server.ts`
- Modify: `tools/fake-backend/src/server.test.ts`
- Modify: `docs/notes/api-contract.md`

- [x] Route coverage uses snapshot-local child IDs and asserts Todoist receives the resolved Todoist child ID.
- [x] Resolved focused child IDs local-first with direct-parent verification and legacy fallback.
- [x] Updated fake wire contract and API documentation; focused backend/fake tests pass.

### Task 2: Eliminate public partial outcomes and preserve ambiguous commands

**Files:**
- Modify: `backend/src/services/taskActionCoordinator.ts`
- Modify: `backend/src/services/taskActionService.ts`
- Modify: `backend/src/services/todoistChecklistService.ts`
- Modify: `backend/test/services/taskActionService.test.ts`
- Modify: `backend/test/services/todoistChecklistService.test.ts`
- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/model/TaskModels.kt`
- Modify: `client/shared/src/commonTest/kotlin/app/buzer/shared/api/BuzerApiClientContractTest.kt`

- [x] Covered sent child-command ambiguity: the action returns 202, preserves the command identity, and does not duplicate the child on replay.
- [x] Coordinator marks post-send uncertainty pending while retaining deterministic pre-send 4xx failures.
- [x] Removed public partial/create-root/child-retry transport and replaced duplicate-text replay matching with persisted command-result reconciliation.
- [x] Kotlin contract allows only completed or reconciliation_pending; backend and shared tests pass.

### Task 3: Make all root-action replays immutable

**Files:**
- Modify: `backend/src/http/routes.ts`
- Modify: `backend/src/services/taskActionService.ts`
- Modify: `backend/src/repositories/actionJournalRepository.ts`
- Modify: `backend/test/http/routes.test.ts`
- Modify: `backend/test/services/taskActionService.test.ts`
- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/model/TaskModels.kt`
- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/api/BuzerApiClient.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementApi.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementCoordinator.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/blockers/DeadlineBlockerActivity.kt`

- [x] Route-level same-action start/postpone/extend replays are covered.
- [x] Added typed ExtendTaskRequest with expectedTaskRevision through Android transport.
- [x] Journal reservation timestamps stabilize first-run start/postpone/extend values; fingerprints include immutable preconditions.
- [x] Extend validates the post-sync task revision and has stale-revision coverage.

### Task 4: Simplify Android mutation handling and verify end to end

**Files:**
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/addtask/AddTaskCreateFlow.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/EditTaskScreen.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreen.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/App.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt`
- Modify: Android unit/instrumentation tests above

- [x] Completed create hands its returned snapshot to the coordinator before navigation; pending uses generic sync state.
- [x] Removed partial child retry/navigation branches from Android.
- [x] MainActivity applies returned authority directly, including snapshot persistence and alarm reconciliation.
- [x] Kept ADB reverse as the only supported connected-device HTTP path.
- [x] Shared/Android JVM suites and assembleDebug pass; connected-device lane passes 25/25.

### Task 5: Final contract verification and handoff

**Files:**
- Modify: `docs/notes/api-contract.md`
- Modify: `docs/superpowers/plans/2026-07-15-recurring-tasks-checklists-and-action-correctness.md`
- Create: `docs/handoffs/2026-07-16-atomic-mutation-reconciliation.md`

- [x] Backend 329-test suite, typecheck, and build pass.
- [x] Fake backend 14-test suite and build pass.
- [x] Diff check and placeholder scan pass (only intentional TODOIST identifiers match).
- [x] Recorded exact evidence and no-live-deployment limitation in the atomic reconciliation handoff.
