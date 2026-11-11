# Backend Architecture Deepening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Consolidate backend commitment/hierarchy policy, internalize Todoist FIFO queue + sync + command execution behind `TodoistRuntime`, retire four unused HTTP routes and four dead `TodoistAdapter` methods, and unify server composition across `server.ts`, `routes.ts`, and `e2eServer.ts`.

**Architecture:** Three sequential tasks on `feature/deepen-backend`: (1) extract a single pure `CommitmentPolicy` in `backend/src/domain/` and wire all four classification/hierarchy call sites to it; (2) slim `TodoistAdapter` to live Sync API commands and wrap `TodoistOperationQueue`, `TodoistCommandExecutor`, `TodoistSyncService`, and `TaskMutationRunner` inside `TodoistRuntime` (`executeMutation`, `forceRefresh`, `pollOnce`); (3) fold checklist `check`/`uncheck` + child batch helpers into `TaskActionService`, retire dead routes, and extract `createBackendCore` shared by `server.ts` and `e2eServer.ts`.

**Tech Stack:** Node.js, TypeScript (ESM), Fastify, `better-sqlite3`, Zod, Vitest.

**Spec:** `docs/superpowers/specs/2026-11-11-backend-architecture-deepening-design.md` (and `docs/decisions/2026-11-11-retire-unused-checklist-and-review-routes.md`).

## Global Constraints

- Preserve all Level 1 mutation deadlines (`MUTATION_DEADLINE_MS = 13_000`, `MUTATION_MINIMUM_EXECUTION_BUDGET_MS = 5_000`, `POLL_DEADLINE_MS = 13_000`), command retry rules, and structured observability event markers (`"todoist_operation_started"`, `"todoist_operation_succeeded"`, `"backend_busy"`, `"forced_refresh_failed"`, `"forced_refresh_internal_defect"`, `"forced_refresh_snapshot_internal_defect"`, `"todoist_poll_*"`).
- Non-operational internal defects inside `forceRefresh` MUST log `captureException` (`marker: "forced_refresh_internal_defect"`) and still resolve `{ kind: "failed", message: "Failed to refresh snapshot from Todoist.", snapshot }` so `GET /tasks/snapshot?forceRefresh=true` responds HTTP `503 { code: "forced_refresh_failed" }` rather than HTTP `500` (preventing duplicate Android Sentry Issues).
- Keep Fastify plugin scoping (`app.register(async (scope) => { registerRoutes(...); })` and `app.after(() => app.setNotFoundHandler({ preHandler: app.rateLimit() }, ...))`) intact per `docs/decisions/2026-07-15-funnel-strips-path-prefix.md`.
- Do not change SQLite schema/migrations or live request/response JSON shapes on active routes (`GET /tasks/snapshot`, `GET /projects`, `POST /device/check-in`, `POST /tasks`, `POST /tasks/:id/{start,postpone,done,extend,update}`, `POST /tasks/:id/checklist/:childId/{check,uncheck}`).
- Note on test execution in Antigravity sandbox: Vitest opens a loopback socket in `test/http/routes.test.ts`, and `git commit` writes `.git/` locks; run `npm test` and `git commit` with `BypassSandbox: true`.

## Review Focus

1. **Child task carrying `dueString`, `dueLang`, `dueTimezone`, or `durationUnit` with null `dueDatetime`/`dueDate`/`durationMinutes`**: `GET /tasks/snapshot`, `TodoistSyncService`, and `TaskActionService` must all classify the root as `UNSUPPORTED_HIERARCHY` (`"child_has_schedule"` or `"child_has_duration"`) instead of showing `hierarchyStatus: "valid"` in snapshot and failing on `Done`/`Edit` (pinned in Task 1).
2. **Unsupported-hierarchy root that is *also* missing root `dueDatetime` or `durationMinutes`**: `CommitmentPolicy.classify` and `GET /tasks/snapshot` must populate `validationErrors` (`["due_datetime_required", "duration_required"]`) alongside `hierarchyStatus: "unsupported"` so Morning Review knows root schedule fields need repair (pinned in Task 1).
3. **`forceRefresh` non-operational parse defect when fallback `snapshots.getSnapshot` also throws (`sync_initializing` or SQLite error)**: `TodoistRuntime.forceRefresh` must capture both `forced_refresh_internal_defect` and `forced_refresh_snapshot_internal_defect` and return `{ kind: "failed", message: "Failed to refresh snapshot from Todoist." }` without throwing an unhandled 500 (pinned in Task 2).
4. **`e2eServer` `/__block-queue` hold duration across slow Android emulator launches**: Holding the FIFO via `todoistRuntime.pollOnce({ deadlineMs: 60_000 })` + `adapter.holdNextSync()` must wait until `adapter.sync` is actually entered before `/__block-queue` returns `{ blocked: true }` and must release cleanly on `{ enabled: false }` or `/__reset` without leaking dangling promises (pinned in Task 3).
5. **Escalation quiet-hours evaluation in `e2eServer`**: `createBackendCore` must wire `ownerTimeZone: () => resolveOwnerTimeZone(collaborators, currentUserId)` into `EscalationService` in both `server.ts` and `e2eServer.ts` so owner timezone from synced collaborator state governs quiet hours everywhere (pinned in Task 3).

---

### Task 1: Unified `CommitmentPolicy` Eliminating 4-Way Hierarchy & Validation Drift

**Files:**
- Create: `backend/src/domain/commitmentPolicy.ts`
- Modify: `backend/src/domain/taskEnforcementPolicy.ts` (re-export `CommitmentPolicy` aliases or replace in-place and update imports)
- Modify: `backend/src/domain/taskValidation.ts`
- Modify: `backend/src/services/taskHierarchyProjector.ts`
- Modify: `backend/src/services/taskSnapshotService.ts`
- Modify: `backend/src/services/todoistSyncService.ts`
- Modify: `backend/src/services/taskActionService.ts` (child/hierarchy check delegation)
- Modify: `backend/src/services/todoistChecklistService.ts` (child/hierarchy check delegation prior to Task 3 fold-in)
- Test: `backend/test/domain/taskEnforcementPolicy.test.ts`
- Test: `backend/test/services/taskSnapshotService.test.ts`
- Test: `backend/test/services/todoistSyncService.test.ts`

**Interfaces:**
- Consumes: existing `TaskRecord` / `MirrorTaskRecord` from `backend/src/repositories/taskRepository.ts`.
- Produces (`backend/src/domain/commitmentPolicy.ts`):
  - `MAX_CHECKLIST_CHILDREN = 30`
  - `type HierarchyViolation = "too_many_children" | "nested_child" | "child_has_schedule" | "child_has_duration" | "child_has_recurrence" | "child_has_assignment" | "child_project_mismatch"`
  - `interface PolicyTaskRecord` (covering `id`, `todoistTaskId`, `title`, `projectId`, `parentId`, `childOrder?`, `assigneeId`, `creatorId`, `commitmentOwnerId`, `dueDate?`, `dueDatetime`, `dueString?`, `dueLang?`, `dueTimezone?`, `isRecurring`, `durationAmount?`, `durationUnit?`, `durationMinutes`, `completedAt?`, `deletedAt?`, `mirrorRevision?`)
  - `class CommitmentPolicy`:
    - `constructor(options: { currentUserId: string; projectsById: ReadonlyMap<string, { canAssignTasks: boolean }> })`
    - `isCompanionOwned(task: Pick<PolicyTaskRecord, "projectId" | "assigneeId" | "creatorId" | "commitmentOwnerId">): boolean`
    - `validateRoot(task: PolicyTaskRecord, isCompanionOwned: boolean): ValidationResult`
    - `findChildViolation(root: Pick<PolicyTaskRecord, "projectId">, child: PolicyTaskRecord): Exclude<HierarchyViolation, "too_many_children" | "nested_child"> | null`
    - `inspectRootHierarchy(root: PolicyTaskRecord, allTasks: readonly PolicyTaskRecord[]): { status: "valid" | "unsupported"; violation: HierarchyViolation | null; directChildren: PolicyTaskRecord[] }`
    - `eligibleCheckedChildTodoistIds(root: PolicyTaskRecord, allTasks: readonly PolicyTaskRecord[]): string[]`
    - `classify(task: PolicyTaskRecord, allTasks: readonly PolicyTaskRecord[]): TaskEnforcementResult` (populates `validationErrors` on both `ROOT_CANDIDATE_INVALID` and non-unassigned `UNSUPPORTED_HIERARCHY` roots).

- [x] **Step 1: Write failing unit & snapshot/sync tests for the 4-way drift cases and combined `UNSUPPORTED_HIERARCHY` + `validationErrors`**

Add tests in `backend/test/domain/taskEnforcementPolicy.test.ts`, `backend/test/services/taskSnapshotService.test.ts`, and `backend/test/services/todoistSyncService.test.ts`:
```ts
// taskEnforcementPolicy.test.ts
it("flags child dueString, dueLang, dueTimezone, and durationUnit as unsupported hierarchy", () => {
  const policy = createPolicy();
  const root = makeRoot();
  expect(policy.classify(root, [root, makeChild({ dueString: "tomorrow" })])).toMatchObject({
    classification: "UNSUPPORTED_HIERARCHY",
    violation: "child_has_schedule"
  });
  expect(policy.classify(root, [root, makeChild({ dueTimezone: "Europe/Prague" })])).toMatchObject({
    classification: "UNSUPPORTED_HIERARCHY",
    violation: "child_has_schedule"
  });
  expect(policy.classify(root, [root, makeChild({ durationUnit: "minute" })])).toMatchObject({
    classification: "UNSUPPORTED_HIERARCHY",
    violation: "child_has_duration"
  });
});

it("includes root validationErrors on companion-owned UNSUPPORTED_HIERARCHY roots missing schedule", () => {
  const policy = createPolicy();
  const root = makeRoot({ dueDatetime: null, durationMinutes: null });
  const child = makeChild({ dueDate: "2026-08-03" });
  expect(policy.classify(root, [root, child])).toEqual({
    classification: "UNSUPPORTED_HIERARCHY",
    violation: "child_has_schedule",
    isCompanionOwned: true,
    validationErrors: ["due_datetime_required", "duration_required"]
  });
});

it("excludes mismatched-project or dueString children from eligibleCheckedChildTodoistIds", () => {
  const policy = createPolicy();
  const root = makeRoot({ projectId: "proj-1" });
  const okChecked = makeChild({ todoistTaskId: "c-ok", projectId: "proj-1", completedAt: "2026-08-02T09:00:00Z" });
  const wrongProj = makeChild({ todoistTaskId: "c-proj", projectId: "proj-2", completedAt: "2026-08-02T09:00:00Z" });
  const dueStr = makeChild({ todoistTaskId: "c-due", projectId: "proj-1", dueString: "daily", completedAt: "2026-08-02T09:00:00Z" });
  expect(policy.eligibleCheckedChildTodoistIds(root, [root, okChecked, wrongProj, dueStr])).toEqual(["c-ok"]);
});
```
Also add a snapshot test in `taskSnapshotService.test.ts` verifying an unsupported-hierarchy owner root with `dueDatetime: null, durationMinutes: null` projects `hierarchyStatus: "unsupported"`, `hierarchyViolation: "child_has_schedule"`, and `validationErrors: ["due_datetime_required", "duration_required"]`.

- [x] **Step 2: Run Vitest on the modified domain/snapshot/sync test files to verify they fail**

Run: `npm --prefix /Users/svehla/playground/buzer-app/.worktrees/deepen-backend/backend test -- test/domain/taskEnforcementPolicy.test.ts test/services/taskSnapshotService.test.ts test/services/todoistSyncService.test.ts`
Expected: FAIL on `dueString`/`dueTimezone`/`durationUnit` classification, missing `eligibleCheckedChildTodoistIds`, and `validationErrors` being `[]` on `UNSUPPORTED_HIERARCHY`.

- [x] **Step 3: Implement `CommitmentPolicy` and wire `TaskHierarchyProjector`, `TaskSnapshotService`, `TodoistSyncService`, `TaskActionService`, and `TodoistChecklistService` to it**

Create `backend/src/domain/commitmentPolicy.ts` (re-exporting `CommitmentPolicy` as `TaskEnforcementPolicy` in `taskEnforcementPolicy.ts` for clean incremental imports, or updating imports directly), remove the duplicate `validateTaskStrictness` call in `TaskSnapshotService.ts` (reading `hierarchy.validationErrors ?? []`), replace `eligibleCheckedChildIds` in `todoistSyncService.ts` with `policy.eligibleCheckedChildTodoistIds(...)`, and replace custom child-violation checks in `TaskActionService.directChildTree` and `TodoistChecklistService.assertProjectedTree` with `CommitmentPolicy`'s `findChildViolation` / `inspectRootHierarchy`.

- [x] **Step 4: Run full backend unit tests and typecheck to verify Task 1 passes**

Run: `npm --prefix /Users/svehla/playground/buzer-app/.worktrees/deepen-backend/backend test` and `npm --prefix /Users/svehla/playground/buzer-app/.worktrees/deepen-backend/backend run typecheck`
Expected: PASS (all test files green, zero TypeScript errors).

- [x] **Step 5: Commit Task 1**

```bash
git -C /Users/svehla/playground/buzer-app/.worktrees/deepen-backend add backend/src/domain backend/src/services backend/test
git -C /Users/svehla/playground/buzer-app/.worktrees/deepen-backend commit -m "refactor(backend): unify commitment and hierarchy policy across sync, snapshot, and actions"
```

---

### Task 2: Slim `TodoistAdapter` & Internalize Queue/Executor/Sync Behind `TodoistRuntime`

**Files:**
- Create: `backend/src/services/todoistRuntime.ts`
- Modify: `backend/src/todoist/todoistAdapter.ts`
- Modify: `backend/src/todoist/httpTodoistAdapter.ts`
- Modify: `backend/src/services/todoistCommandExecutor.ts`
- Modify: `backend/src/services/todoistSyncService.ts` (delete unused `syncNow`)
- Modify: `backend/src/services/todoistPoller.ts`
- Modify: `backend/test/fakes/fakeTodoistAdapter.ts`
- Modify: `backend/test/fakes/e2eTodoistAdapter.ts` (remove dead methods + add `holdNextSync(): { entered: Promise<void>; release: () => void }`)
- Modify: `backend/test/todoist/httpTodoistAdapter.test.ts`
- Modify: `backend/test/fakes/e2eTodoistAdapter.test.ts`
- Modify: `backend/test/services/todoistCommandExecutor.test.ts`
- Modify: `backend/test/services/taskMutationRunner.test.ts`
- Modify: `backend/test/services/todoistPoller.test.ts`
- Modify: `backend/test/services/todoistPollerDiagnostics.test.ts`
- Create: `backend/test/services/todoistRuntime.test.ts`

**Interfaces:**
- Consumes: `CommitmentPolicy` from Task 1; internal `TodoistOperationQueue`, `TodoistCommandExecutor`, `TodoistSyncService`, `TaskMutationRunner`, `isOperationalFailure`.
- Produces:
  - Slimmed `TodoistAdapter` (`sync`, `itemAdd`, `itemUpdate`, `itemMove`, `itemClose`, `itemUncomplete`, `itemDelete` only — `createTask`, `completeTask`, `updateTask`, `itemUpdateDateComplete` and their dead types deleted).
  - `createTodoistRuntime(options: CreateTodoistRuntimeOptions): TodoistRuntime` in `backend/src/services/todoistRuntime.ts`:
    - `executeMutation(options: MutationRunOptions): Promise<MutationRunResult>`
    - `forceRefresh(options?: ForceRefreshOptions): Promise<ForceRefreshResult>` (`{ kind: "success", snapshot } | { kind: "busy", message, snapshot? } | { kind: "failed", message, snapshot? }`)
    - `pollOnce(options?: PollOnceOptions): Promise<TodoistSyncSummary | void>`
  - Updated `TodoistPoller` constructor accepting `PollableTodoistRuntime` (`{ pollOnce(options?: PollOnceOptions): Promise<TodoistSyncSummary | void> }`) while keeping optional legacy compatibility only if needed until Task 3, or migrating `todoistPoller*.test.ts` directly to `{ pollOnce }`.
  - `E2ETodoistAdapter.holdNextSync(): { entered: Promise<void>; release: () => void }` (resolving `entered` as soon as `adapter.sync` is called and suspending `adapter.sync` until `release()` is invoked).

- [x] **Step 1: Write failing `todoistRuntime.test.ts` and `e2eTodoistAdapter` hold tests**

Create `backend/test/services/todoistRuntime.test.ts` covering:
1. `executeMutation`, `forceRefresh`, and `pollOnce` serialize strictly in FIFO order through one shared internal queue (`pollOnce` held inside `adapter.sync` causes a concurrent `forceRefresh` with short `deadlineMs` to return `{ kind: "busy", message, snapshot }` and emit `warn("backend_busy", { operation: "force_refresh", request_id })`).
2. `forceRefresh` success syncs Todoist into SQLite, emits `info("todoist_operation_started")` + `info("todoist_operation_succeeded")`, and returns `{ kind: "success", snapshot }`.
3. `forceRefresh` operational failure (`new TodoistOperationError("timeout", "Timed out")`) emits `warn("forced_refresh_failed")` without `captureException` and returns `{ kind: "failed", message: "Timed out", snapshot }`.
4. `forceRefresh` non-operational defect (`new TodoistOperationError("parse", "Bad payload")`) when `snapshots.getSnapshot` also throws captures **both** `forced_refresh_internal_defect` and `forced_refresh_snapshot_internal_defect` and returns `{ kind: "failed", message: "Failed to refresh snapshot from Todoist." }` (`snapshot` omitted, never rethrowing).
5. `pollOnce({ deadlineMs: 60_000 })` forwards `AbortSignal` to `adapter.sync` and returns `TodoistSyncSummary`.

- [x] **Step 2: Run `todoistRuntime.test.ts` to verify failure**

Run: `npm --prefix /Users/svehla/playground/buzer-app/.worktrees/deepen-backend/backend test -- test/services/todoistRuntime.test.ts`
Expected: FAIL (`Cannot find module '../../src/services/todoistRuntime.js'`).

- [x] **Step 3: Slim `TodoistAdapter`, implement `createTodoistRuntime`, update `TodoistPoller`, and add `holdNextSync` on `E2ETodoistAdapter`**

1. Delete `createTask`, `completeTask`, `updateTask`, `itemUpdateDateComplete` and unused types (`TodoistCreateTaskInput`, `TodoistCreateTaskResult`, `TodoistUpdateTaskInput`, `TodoistLegacyUpdateCommandIdentities`, `TodoistRecurringCompletionInput`, `{ type: "item_update_date_complete" }`) from `todoistAdapter.ts`, `httpTodoistAdapter.ts`, `todoistCommandExecutor.ts`, `fakeTodoistAdapter.ts`, `e2eTodoistAdapter.ts`, and remove their obsolete mock properties/tests in `httpTodoistAdapter.test.ts`, `e2eTodoistAdapter.test.ts`, `todoistCommandExecutor.test.ts`, and `taskMutationRunner.test.ts`.
2. Delete unused `TodoistSyncService.syncNow()`.
3. Implement `createTodoistRuntime` in `backend/src/services/todoistRuntime.ts` owning one private `TodoistOperationQueue`, `TodoistCommandExecutor`, `TodoistSyncService`, and `TaskMutationRunner`.
4. Update `TodoistPoller` (`backend/src/services/todoistPoller.ts`) to take `{ pollOnce(options?: PollOnceOptions): Promise<TodoistSyncSummary | void> }` (removing its own `TodoistOperationQueue` field) and update `todoistPoller.test.ts` + `todoistPollerDiagnostics.test.ts` + `server.ts` poller call site.
5. Add `holdNextSync()` on `E2ETodoistAdapter` and test it in `e2eTodoistAdapter.test.ts`.

- [x] **Step 4: Run full backend test suite and typecheck**

Run: `npm --prefix /Users/svehla/playground/buzer-app/.worktrees/deepen-backend/backend test` and `npm --prefix /Users/svehla/playground/buzer-app/.worktrees/deepen-backend/backend run typecheck`
Expected: PASS across all test files and `tsc --noEmit`.

- [x] **Step 5: Commit Task 2**

```bash
git -C /Users/svehla/playground/buzer-app/.worktrees/deepen-backend add backend/src backend/test
git -C /Users/svehla/playground/buzer-app/.worktrees/deepen-backend commit -m "refactor(backend): encapsulate Todoist queue, sync, and command execution in TodoistRuntime"
```

---

### Task 3: Retire Unused Routes, Fold Checklist Toggle into `TaskActionService`, and Unify Server Composition

**Files:**
- Delete: `backend/src/services/todoistChecklistService.ts`
- Delete: `backend/test/services/todoistChecklistService.test.ts`
- Modify: `backend/src/services/taskActionService.ts`
- Modify: `backend/src/http/routes.ts`
- Modify: `backend/src/server.ts`
- Modify: `backend/test/e2eServer.ts`
- Modify: `backend/test/services/taskActionService.test.ts`
- Modify: `backend/test/http/routes.test.ts`
- Modify: `backend/test/e2eServer.test.ts`

**Interfaces:**
- Consumes: `CommitmentPolicy` (Task 1) and `TodoistRuntime` / `createTodoistRuntime` (Task 2).
- Produces:
  - `TaskActionService` (`new TaskActionService(deps: TaskActionServiceDependencies)`):
    - `deps`: `{ tasks: TaskRepository; projects: ProjectRepository; collaborators?: CollaboratorRepository; snapshots: SnapshotProvider; runtime: Pick<TodoistRuntime, "executeMutation">; escalationService?: EscalationService | null; currentUserId?: string }`
    - Canonical methods only: `createTask`, `startTask`, `postponeTask`, `completeTask`, `extendTask`, `editTask`, `checkChecklistItem(rootId, childTodoistId, options?)`, `uncheckChecklistItem(rootId, childTodoistId, options?)`.
  - `RouteServices` in `backend/src/http/routes.ts`:
    - `{ snapshots: TaskSnapshotService; todoistRuntime: Pick<TodoistRuntime, "forceRefresh">; actions: TaskActionService; projects: ProjectRepository; collaborators: CollaboratorRepository; devices: DeviceRepository }` (no `db`-based per-request repository creation, no `synchronizer`, no `todoistQueue`, no `checklist`).
  - `createBackendCore(options)` in `backend/src/server.ts` shared by `buildServer`, CLI `start()`, and `buildE2EServer` — wiring `ownerTimeZone: () => resolveOwnerTimeZone(collaborators, currentUserId)` into `EscalationService` everywhere.

- [ ] **Step 1: Write failing tests for retired routes, `TaskActionService` checklist `check`/`uncheck`, and `e2eServer` `ownerTimeZone` + `/__block-queue`**

1. In `backend/test/http/routes.test.ts`:
   - Assert `POST /review/dismiss`, `POST /tasks/task-1/checklist`, `POST /tasks/task-1/checklist/c-1/update`, and `POST /tasks/task-1/checklist/c-1/delete` return HTTP `404` even with a valid bearer token (removing them from the authenticated-routes table while keeping `/tasks/task-1/checklist/c-1/check` and `.../uncheck`).
   - Update `GET /tasks/snapshot?forceRefresh=true` route tests to pass `todoistRuntime` (`createTodoistRuntime(...)` or mock `forceRefresh`).
2. In `backend/test/services/taskActionService.test.ts`:
   - Migrate focused `checkChecklistItem` / `uncheckChecklistItem` and batched `editTask` `childMutations` validation tests (stale `expectedOccurrenceKey`, stale `expectedChildRevision`, `duplicate_client_mutation_id`, `> 30` projected children, unsupported root hierarchy) from `todoistChecklistService.test.ts` onto `new TaskActionService({ ... })`.
3. In `backend/test/e2eServer.test.ts`:
   - Remove standalone checklist add/update/delete and `/review/dismiss` tests; add a test verifying `POST /__block-queue` `{ enabled: true }` causes `GET /tasks/snapshot?forceRefresh=true` (or a Todoist mutation) to return `503 { code: "backend_busy" }` until `{ enabled: false }` releases it, and a test verifying `e2eServer`'s `EscalationService` honors synced collaborator timezone during quiet hours.

- [ ] **Step 2: Run targeted tests to verify they fail**

Run: `npm --prefix /Users/svehla/playground/buzer-app/.worktrees/deepen-backend/backend test -- test/http/routes.test.ts test/e2eServer.test.ts test/services/taskActionService.test.ts`
Expected: FAIL (retired endpoints still return non-404, `TaskActionService.checkChecklistItem` missing, etc.).

- [ ] **Step 3: Fold checklist child helpers into `TaskActionService`, retire the 4 HTTP routes, and unify `server.ts` + `e2eServer.ts` via `createBackendCore`**

1. Move `ChildMutation` validation, `buildChildCommands`, `checkChecklistItem`, and `uncheckChecklistItem` into `TaskActionService` (deleting `todoistChecklistService.ts` and `todoistChecklistService.test.ts`), replace `TaskActionService`'s union constructor and duplicate method aliases (`create`, `start`, `postponeStart`, `done`, `extend`, `update`) with `TaskActionServiceDependencies` and canonical method names, and update `taskActionService.test.ts`.
2. Simplify `routes.ts`:
   - Replace the 85-line `forceRefresh === "true"` block with `const outcome = await services.todoistRuntime.forceRefresh({ requestId: request.id, signal: getRequestSignal(request) })`, mapping `success` → `200 outcome.snapshot`, `busy` → `503 { code: "backend_busy", message: outcome.message, ...(outcome.snapshot ? { snapshot: outcome.snapshot } : {}) }`, `failed` → `503 { code: "forced_refresh_failed", message: outcome.message, ...(outcome.snapshot ? { snapshot: outcome.snapshot } : {}) }`.
   - Use injected `services.projects` and `services.devices` instead of `new ProjectRepository(options.db)` / `new DeviceRepository(options.db)`.
   - Delete `POST /review/dismiss`, `POST /tasks/:id/checklist`, `POST /tasks/:id/checklist/:childId/update`, and `POST /tasks/:id/checklist/:childId/delete`. Route `check` and `uncheck` to `services.actions.checkChecklistItem` / `uncheckChecklistItem`.
3. Extract `createBackendCore` in `server.ts` (wiring `EscalationService` with `ownerTimeZone: () => resolveOwnerTimeZone(collaborators, currentUserId)`) and update `e2eServer.ts` to use `buildServer` + `todoistRuntime.pollOnce({ deadlineMs: 60_000 })` + `adapter.holdNextSync()` for `/__block-queue` and `todoistRuntime.pollOnce()` in `seed()`.

- [ ] **Step 4: Run full backend verification (`npm test`, `npm run typecheck`, `npm run build`)**

Run:
- `npm --prefix /Users/svehla/playground/buzer-app/.worktrees/deepen-backend/backend test`
- `npm --prefix /Users/svehla/playground/buzer-app/.worktrees/deepen-backend/backend run typecheck`
- `npm --prefix /Users/svehla/playground/buzer-app/.worktrees/deepen-backend/backend run build`
Expected: PASS on all 3 commands with 0 errors.

- [ ] **Step 5: Commit Task 3**

```bash
git -C /Users/svehla/playground/buzer-app/.worktrees/deepen-backend add -A backend
git -C /Users/svehla/playground/buzer-app/.worktrees/deepen-backend commit -m "refactor(backend): retire unused checklist/review routes and unify server composition"
```
