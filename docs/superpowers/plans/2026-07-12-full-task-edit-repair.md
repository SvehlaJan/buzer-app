# Full Task Edit Repair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Morning Review's schedule-only repair form with a full task edit flow for title, details, project, assignee, exact time, and duration.

**Architecture:** Add a full task update request and backend route instead of overloading reschedule. Reuse the existing add-task project and assignee selector patterns in Morning Review, and keep deadline blocker reschedule unchanged.

**Tech Stack:** Kotlin Multiplatform models, Android Jetpack Compose Material 3, Ktor client serialization, Node.js/TypeScript Fastify backend, Todoist Sync `item_update`, fake backend tests.

---

## Task 1: Add Full Update Contract

**Files:**
- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/model/TaskModels.kt`
- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/api/BuzerApiClient.kt`
- Modify: `client/shared/src/commonTest/kotlin/app/buzer/shared/api/BuzerApiClientJsonTest.kt`
- Modify: `tools/fake-backend/src/server.ts`
- Modify: `tools/fake-backend/src/server.test.ts`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementApi.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementCoordinator.kt`

- [x] Add a failing shared JSON test asserting `UpdateTaskRequest` serializes title, description, projectId, assigneeId, dueDatetime, and durationMinutes.
- [x] Add `UpdateTaskRequest` to shared task models.
- [x] Add `BuzerApiClient.updateTask(id, request)` posting to `/tasks/:id/update`.
- [x] Add fake-backend route support and a failing/passing server test that records `/tasks/task-invalid/update`.
- [x] Add `updateTask` to Android enforcement API and coordinator.

## Task 2: Add Backend Update Support

**Files:**
- Modify: `backend/src/http/routes.ts`
- Modify: `backend/src/services/taskActionService.ts`
- Modify: `backend/src/todoist/todoistAdapter.ts`
- Modify: `backend/src/todoist/httpTodoistAdapter.ts`
- Modify: backend tests under `backend/test/**`

- [x] Add a failing backend test proving full update sends title, description, project, assignee, due datetime, and duration to Todoist.
- [x] Add `TodoistUpdateTaskInput` and `TodoistAdapter.updateTask`.
- [x] Implement `HttpTodoistAdapter.updateTask` with Todoist `item_update` args.
- [x] Add `TaskActionService.update`.
- [x] Add `POST /tasks/:id/update` route and validation.

## Task 3: Convert Morning Review Repair To Full Edit

**Files:**
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreen.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/blockers/MorningReviewActivity.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/MorningReviewScreenStateTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/blockers/BlockerActionStateTest.kt`

- [x] Add a failing Morning Review state test that resolves `project-test` to `Test Shared` and `user-me` to `Me`.
- [x] Add a failing request-construction test that edited title/project/assignee/schedule/details become `UpdateTaskRequest`.
- [x] Expose or move the existing project and assignee selector composables for reuse.
- [x] Load projects in Morning Review and initialize selected project/assignee from the task snapshot.
- [x] Submit `UpdateTaskRequest` through the coordinator.

## Task 4: Focused Verification And Device Install

**Files:**
- Modify: `docs/handoffs/2026-07-12-device-crash-theme-loading.md` or create a new handoff if needed.

- [x] Run shared/client JVM tests relevant to models and Morning Review.
- [x] Run backend and fake-backend tests.
- [x] Skip `scripts/e2e-fake-backend.sh` and document that manual testing was requested.
- [ ] Install debug build to connected device. Blocked: `adb devices` showed no attached devices.
- [ ] Write handoff, commit, push.
