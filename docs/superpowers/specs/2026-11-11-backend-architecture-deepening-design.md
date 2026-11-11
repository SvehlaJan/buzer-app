# Backend Architecture Deepening Design

Date: 2026-11-11
Status: Approved
Related decision: `docs/decisions/2026-11-11-retire-unused-checklist-and-review-routes.md`
Domain glossary: `CONTEXT.md`

## Overview

This design deepens two coupled areas of `backend/` without changing the Level 1 mutation protocol (`docs/decisions/2026-08-30-level-1-mutation-simplification.md`) or live HTTP wire payloads:

1. **Unified `CommitmentPolicy` & Server Composition (Candidate 04)** — Consolidates four drifted copies of root commitment and child-hierarchy rules into one pure domain module (`backend/src/domain/commitmentPolicy.ts`), retires four uncalled HTTP endpoints, folds remaining checklist child operations into `TaskActionService` with typed constructor options, and unifies service graph construction across `server.ts`, `routes.ts`, and `test/e2eServer.ts`.
2. **Deep `TodoistRuntime` Module (Candidate 02)** — Internalizes `TodoistOperationQueue`, `TodoistCommandExecutor`, `TodoistSyncService`, `TaskMutationRunner`, and operational-vs-defect failure classification behind a narrow `TodoistRuntime` interface (`executeMutation`, `forceRefresh`, `pollOnce`), and removes four unused `TodoistAdapter` methods.

## Goals & Non-Goals

### Goals
- Eliminate the 4-way drift in child-hierarchy validation between `TaskEnforcementPolicy`, `TaskActionService.directChildTree`, `TodoistChecklistService.assertProjectedTree`, and `TodoistSyncService.eligibleCheckedChildIds`.
- Eliminate the unqueued `synchronizer.syncOnce()` fallback and 85-line inline queue/telemetry block in `GET /tasks/snapshot?forceRefresh=true`.
- Give `TodoistAdapter` only the methods used by live Sync API reads and commands (`sync`, `itemAdd`, `itemUpdate`, `itemMove`, `itemClose`, `itemUncomplete`, `itemDelete`).
- Retire `POST /review/dismiss`, `POST /tasks/:id/checklist`, `POST /tasks/:id/checklist/:childId/update`, and `POST /tasks/:id/checklist/:childId/delete`.
- Construct repositories and services once per server instance (`createBackendCore`), fixing `e2eServer.ts` omitting `ownerTimeZone` on `EscalationService` and `routes.ts` instantiating `ProjectRepository`/`DeviceRepository` per request.

### Non-Goals
- Changing SQLite schema or migrations.
- Changing live HTTP request/response JSON shapes or status codes on remaining routes.
- Changing Level 1 queue deadlines (`MUTATION_DEADLINE_MS = 13_000`, `MUTATION_MINIMUM_EXECUTION_BUDGET_MS = 5_000`), retry rules, or structured observability event names/attributes.

---

## Module 1: Unified `CommitmentPolicy` (`backend/src/domain/commitmentPolicy.ts`)

### Problem Fixed
Previously, child-hierarchy eligibility was implemented in four separate places with subtle disagreements:
- `TaskEnforcementPolicy.childViolation` (`domain/taskEnforcementPolicy.ts`) checked `dueDatetime`, `dueDate`, `durationMinutes`, `durationAmount`, `isRecurring`, `assigneeId`, and `projectId`, **omitting** `dueString`, `dueLang`, `dueTimezone`, and `durationUnit`. A child task carrying `dueString`, `dueTimezone`, or `durationUnit` appeared `hierarchyStatus: "valid"` in `GET /tasks/snapshot`, yet threw `422 unsupported_hierarchy` when `TaskActionService.completeTask` or `TodoistChecklistService` ran.
- `TodoistSyncService.eligibleCheckedChildIds` (`services/todoistSyncService.ts`) checked `dueDatetime`, `dueDate`, `durationMinutes`, `durationAmount`, `!isRecurring`, and `assigneeId`, **omitting** `projectId === root.projectId` and `dueString`/`dueLang`/`dueTimezone`/`durationUnit`.
- `TaskSnapshotService` called `TaskEnforcementPolicy.classify(...)` and then re-ran `validateTaskStrictness(...)` separately to populate `validationErrors`.

### Design & Interface
Create `backend/src/domain/commitmentPolicy.ts` (re-exporting or replacing `TaskEnforcementPolicy` so callers have one pure domain authority; `domain/taskValidation.ts` delegates to or is absorbed by it):

```ts
export const MAX_CHECKLIST_CHILDREN = 30;

export type HierarchyViolationCode =
  | "nested_child"
  | "too_many_children"
  | "child_has_schedule"
  | "child_has_duration"
  | "child_has_recurrence"
  | "child_has_assignment"
  | "child_project_mismatch";

export interface PolicyProjectInfo {
  canAssignTasks: boolean;
}

export interface PolicyTaskRecord {
  id: string;
  todoistTaskId: string;
  title: string;
  parentId: string | null;
  childOrder?: number | null;
  projectId: string | null;
  assigneeId: string | null;
  creatorId: string | null;
  commitmentOwnerId: string | null;
  dueDate?: string | null;
  dueDatetime: string | null;
  dueString?: string | null;
  dueLang?: string | null;
  dueTimezone?: string | null;
  isRecurring: boolean;
  durationMinutes: number | null;
  durationAmount?: number | null;
  durationUnit?: "minute" | "day" | null;
  completedAt?: string | null;
  deletedAt?: string | null;
  mirrorRevision?: number;
}

export class CommitmentPolicy {
  constructor(
    private readonly options: {
      currentUserId: string;
      projectsById: ReadonlyMap<string, PolicyProjectInfo>;
    }
  ) {}

  isCompanionOwned(task: Pick<PolicyTaskRecord, "projectId" | "assigneeId" | "creatorId" | "commitmentOwnerId">): boolean;
  validateRoot(task: PolicyTaskRecord, isCompanionOwned: boolean): ValidationResult;
  findChildViolation(
    root: Pick<PolicyTaskRecord, "projectId">,
    child: PolicyTaskRecord
  ): Exclude<HierarchyViolationCode, "nested_child" | "too_many_children"> | null;
  inspectRootHierarchy(
    root: PolicyTaskRecord,
    allTasks: readonly PolicyTaskRecord[]
  ): {
    status: "valid" | "unsupported";
    violation: HierarchyViolationCode | null;
    directChildren: readonly PolicyTaskRecord[];
  };
  eligibleCheckedChildTodoistIds(
    root: PolicyTaskRecord,
    allTasks: readonly PolicyTaskRecord[]
  ): string[];
  classify(
    task: PolicyTaskRecord,
    allTasks: readonly PolicyTaskRecord[]
  ): TaskEnforcementResult;
}
```

### Invariants Pinning All Call Sites
1. **Preserved Root Ownership & Strictness (`isCompanionOwned` + `validateRoot`)**:
   - Exact parity with existing `TaskEnforcementPolicy.isCompanionOwned`: when `assigneeId === null`, returns `true` if `commitmentOwnerId === currentUserId` OR (`creatorId === currentUserId && projectId !== null && projectsById.get(projectId)?.canAssignTasks === false`).
   - Exact parity with `validateTaskStrictness` (`"title_required"`, `"assignee_required_for_created_task"`, `"missing_due_datetime"`, `"missing_duration"`).
   - When `classify` returns `UNSUPPORTED_HIERARCHY` on a non-unassigned root, it also populates `validationErrors` from `validateRoot(task, isCompanionOwned).errors` so roots that are *both* unsupported-hierarchy and missing schedule fields carry `validationErrors` in `GET /tasks/snapshot` without `TaskSnapshotService` calling `validateTaskStrictness` a second time.
2. **Single 9-field child check (`findChildViolation`)**:
   - Schedule violation (`"child_has_schedule"`): any non-null `dueDatetime`, `dueDate`, `dueString`, `dueLang`, or `dueTimezone`.
   - Duration violation (`"child_has_duration"`): `(durationMinutes != null && durationMinutes > 0)` or `(durationAmount != null && durationAmount > 0)` or `durationUnit != null` (note: also treats non-null zero/negative `durationMinutes !== null || durationAmount != null` as `"child_has_duration"` to stay at least as strict as existing `TaskEnforcementPolicy`).
   - Recurrence violation (`"child_has_recurrence"`): `isRecurring === true`.
   - Assignment violation (`"child_has_assignment"`): `assigneeId !== null`.
   - Project mismatch (`"child_project_mismatch"`): `child.projectId !== root.projectId`.
3. **Active subtree rules (`inspectRootHierarchy`)**:
   - Ignores deleted tasks (`deletedAt != null`).
   - Checks count (`directChildren.length > MAX_CHECKLIST_CHILDREN` → `"too_many_children"`), then grandchildren (`"nested_child"` if any active direct child itself has active children), then `findChildViolation` in deterministic `(childOrder ?? MAX_SAFE_INTEGER, todoistTaskId)` order.
4. **All consumers delegate to `CommitmentPolicy`**:
   - `TaskHierarchyProjector` and `TaskSnapshotService` use `CommitmentPolicy` (`TaskSnapshotService` reads `hierarchy.validationErrors ?? []` directly).
   - `TodoistSyncService` uses `policy.classify(...)` and `policy.eligibleCheckedChildTodoistIds(...)`.
   - `TaskActionService` uses `CommitmentPolicy` for root ownership/schedule validation and `inspectRootHierarchy` for checklist/completion gating.

---

## Module 2: Deep `TodoistRuntime` (`backend/src/services/todoistRuntime.ts`)

### Problem Fixed
- `server.ts` manually wired `TodoistOperationQueue`, `TodoistCommandExecutor`, `TodoistSyncService`, `TaskMutationRunner`, and `TodoistPoller`.
- `routes.ts` (`GET /tasks/snapshot?forceRefresh=true`) reached directly into `todoistQueue`, `synchronizer`, `isOperationalFailure`, and `observability` across 85 lines — and fell back to an unqueued `await services.synchronizer.syncOnce()` when `todoistQueue` was omitted in route tests.
- `TodoistAdapter` still carried four pre-Level-1 methods (`createTask`, `completeTask`, `updateTask`, `itemUpdateDateComplete`) and unused `TodoistSyncService.syncNow`.

### Design & Interface

1. **Slim `TodoistAdapter` (`backend/src/todoist/todoistAdapter.ts`)**:
   - Keep `sync(syncToken, options?)`, `itemAdd`, `itemUpdate`, `itemMove`, `itemClose`, `itemUncomplete`, `itemDelete`, and the 6 corresponding variants on `TodoistCommand`.
   - Delete `createTask`, `completeTask`, `updateTask`, `itemUpdateDateComplete`, `TodoistCreateTaskInput`, `TodoistCreateTaskResult`, `TodoistUpdateTaskInput`, `TodoistLegacyUpdateCommandIdentities`, `TodoistRecurringCompletionInput`, and `{ type: "item_update_date_complete" }` across `todoistAdapter.ts`, `httpTodoistAdapter.ts`, `fakeTodoistAdapter.ts`, and `e2eTodoistAdapter.ts`.

2. **Deep `TodoistRuntime` (`backend/src/services/todoistRuntime.ts`)**:
   ```ts
   export type ForceRefreshResult =
     | { kind: "success"; snapshot: TaskSnapshot }
     | { kind: "busy"; message: string; snapshot?: TaskSnapshot }
     | { kind: "failed"; message: string; snapshot?: TaskSnapshot };

   export interface ForceRefreshOptions {
     requestId?: string;
     deadlineMs?: number;
     minimumExecutionBudgetMs?: number;
     signal?: AbortSignal;
   }

   export interface PollOnceOptions {
     deadlineMs?: number;
     signal?: AbortSignal;
   }

   export interface TodoistRuntime {
     executeMutation(options: MutationRunOptions): Promise<MutationRunResult>;
     forceRefresh(options?: ForceRefreshOptions): Promise<ForceRefreshResult>;
     pollOnce(options?: PollOnceOptions): Promise<TodoistSyncSummary | void>;
   }

   export interface CreateTodoistRuntimeOptions {
     db: Database.Database;
     tasks: TaskRepository;
     projects: ProjectRepository;
     collaborators?: CollaboratorRepository;
     todoist: TodoistAdapter;
     snapshots: SnapshotProvider;
     currentUserId?: string | null;
     observability?: BackendObservability;
     uuidFactory?: () => string;
     now?: () => number;
   }

   export function createTodoistRuntime(options: CreateTodoistRuntimeOptions): TodoistRuntime;
   ```

3. **Behavior Guarantees**:
   - **Single internal FIFO**: `executeMutation`, `forceRefresh`, and `pollOnce` all serialize through one internal `TodoistOperationQueue` instance. No caller can run an unqueued Todoist sync.
   - **`forceRefresh` lifecycle (preserves exact HTTP 503 + observability markers)**:
     - Emits `info("todoist_operation_started", { operation: "force_refresh", request_id })`.
     - Enforces `deadlineMs ?? MUTATION_DEADLINE_MS` and `minimumExecutionBudgetMs ?? MUTATION_MINIMUM_EXECUTION_BUDGET_MS` in the FIFO queue.
     - Helper `safeReadSnapshot()` wraps `snapshots.getSnapshot(...)` in `try/catch`, calling `observability.captureException(snapshotError, { marker: "forced_refresh_snapshot_internal_defect", operation: "force_refresh", request_id })` and returning `undefined` if reading the fallback snapshot fails.
     - On queue saturation (`error_code === "backend_busy"`): emits `warn("backend_busy", { operation: "force_refresh", request_id })` and returns `{ kind: "busy", message: "Buzer is currently processing another Todoist operation. Nothing was sent. Please try again shortly.", snapshot: safeReadSnapshot() }`. (`routes.ts` sends `503 { code: "backend_busy", message, ...(snapshot ? { snapshot } : {}) }`.)
     - On `syncOnce` success: emits `info("todoist_operation_succeeded", { operation: "force_refresh", request_id })` and returns `{ kind: "success", snapshot: snapshots.getSnapshot(...) }`.
     - On any other error: emits `warn("forced_refresh_failed", { operation: "force_refresh", request_id })`. If `!isOperationalFailure(error)`, calls `observability.captureException(error, { marker: "forced_refresh_internal_defect", operation: "force_refresh", request_id })` and returns `{ kind: "failed", message: "Failed to refresh snapshot from Todoist.", snapshot: safeReadSnapshot() }` so `routes.ts` still responds `503 { code: "forced_refresh_failed", ... }` (preventing duplicate Android-side Sentry Issues per `2026-08-30-level-1-mutation-simplification.md`). If operational, returns `{ kind: "failed", message: error instanceof Error ? error.message : "Failed to refresh snapshot from Todoist.", snapshot: safeReadSnapshot() }`.
   - **`pollOnce` + `TodoistPoller` split**:
     - `TodoistRuntime.pollOnce(options)` is stateless: it runs `syncOnce({ signal })` inside the internal FIFO queue with `{ operation: "poll", deadlineMs: options?.deadlineMs ?? POLL_DEADLINE_MS, ...(options?.signal ? { signal: options.signal } : {}) }`, returning the `TodoistSyncSummary` and rejecting on failure.
     - `TodoistPoller` remains the stateful timer, consecutive-failure counter, diagnostics holder, and `todoist_poll_changed` / `todoist_poll_recovered` / `todoist_poll_failed` / `todoist_poll_internal_defect` telemetry emitter, taking `{ pollOnce: (options?: PollOnceOptions) => Promise<TodoistSyncSummary | void> }` instead of separate `SyncOnceService` + `TodoistOperationQueue`.
     - Remove unused `TodoistSyncService.syncNow()`.
   - **`e2eServer.ts` `/__block-queue` without leaking queue internals**:
     - Add `holdNextSync()` / `releaseHeldSync()` on `E2ETodoistAdapter`. When `POST /__block-queue` `{ enabled: true }` is called, `e2eServer.ts` arms `adapter.holdNextSync()`, kicks off `void todoistRuntime.pollOnce({ deadlineMs: 60_000 })`, and awaits `hold.entered` so the FIFO slot is guaranteed occupied before `POST /__block-queue` responds (`60_000ms` budget preserves headroom for Android emulator E2E tests). Releasing resolves the held `adapter.sync` cleanly.

---

## Module 3: Focused `TaskActionService`, Retired Routes & Unified Server Composition

### 1. Fold Checklist Helpers into `TaskActionService` & Retire Dead Routes
- Delete `POST /review/dismiss`, `POST /tasks/:id/checklist`, `POST /tasks/:id/checklist/:childId/update`, and `POST /tasks/:id/checklist/:childId/delete` from `backend/src/http/routes.ts`.
- Fold child mutation validation (`validateChildMutations`), child command construction (`buildChildCommands`), and focused checkbox toggling (`checkChecklistItem(rootId, childTodoistId, options)` / `uncheckChecklistItem(rootId, childTodoistId, options)`) into `TaskActionService` (or a private helper module `backend/src/services/checklistMutations.ts` owned exclusively by `TaskActionService`), deleting standalone `TodoistChecklistService` from `RouteServices` and `server.ts`.
- Replace `TaskActionService`'s 9-parameter overloaded constructor and duplicate method aliases (`create`/`createTask`, `start`/`startTask`, `postponeStart`/`postponeTask`, `done`/`completeTask`, `extend`/`extendTask`, `update`/`editTask`) with:
  - One typed constructor `new TaskActionService(deps: TaskActionServiceDependencies)` where `snapshots: TaskSnapshotService` and `runtime: Pick<TodoistRuntime, "executeMutation">` are required (removing the unsafe `as unknown as TaskSnapshot` fallback).
  - Canonical method names only: `createTask`, `startTask`, `postponeTask`, `completeTask`, `extendTask`, `editTask`, `checkChecklistItem`, `uncheckChecklistItem`.

### 2. Unified Composition Helper (`createBackendCore`)
- Extract `createBackendCore(options)` in `backend/src/server.ts` (or `backend/src/bootstrap.ts`) constructing `tasks`, `projects`, `collaborators`, `devices`, `snapshots`, `escalationService` (with `ownerTimeZone: () => resolveOwnerTimeZone(collaborators, currentUserId)`!), `todoistRuntime`, and `taskActions` once.
- Both `buildServer(...)` (including `e2eServer.ts`) and the production `start()` entrypoint share `createBackendCore`, eliminating duplicate repository instantiation and fixing `e2eServer.ts`'s missing `ownerTimeZone` callback on `EscalationService`.
- Pass `projects: ProjectRepository` and `devices: DeviceRepository` via `RouteServices` so `routes.ts` never calls `new ProjectRepository(options.db)` or `new DeviceRepository(options.db)` per request.
- Preserve Fastify plugin scoping (`app.register(async (scope) => { registerRoutes(scope, ...); })` + `app.after(() => app.setNotFoundHandler(...))`) per `docs/decisions/2026-07-15-funnel-strips-path-prefix.md`.

---

## Test Migration Plan

| Existing Test File | Action |
|---|---|
| `backend/test/domain/taskEnforcementPolicy.test.ts`, `taskValidation.test.ts` | Consolidate/expand around `CommitmentPolicy`, adding explicit cases for child `dueString`, `dueLang`, `dueTimezone`, `durationUnit`, and child `projectId` mismatch in `eligibleCheckedChildTodoistIds`. |
| `backend/test/services/todoistRuntime.test.ts` (replaces/absorbs `forceRefresh` queue tests + `TodoistRuntime` contract tests) | Test `executeMutation`, `forceRefresh` (`success`, `busy`, operational `failed`, defect rethrow, `AbortSignal`), and `pollOnce` through `TodoistRuntime` with real `:memory:` SQLite + `FakeTodoistAdapter` / mock `TodoistAdapter`. Keep focused internal edge-case tests in `taskMutationRunner.test.ts` / `todoistCommandExecutor.test.ts` / `todoistSyncService.test.ts` updated to remove deleted `createTask`/`completeTask`/`updateTask`/`itemUpdateDateComplete` mocks and `syncNow`. |
| `backend/test/todoist/httpTodoistAdapter.test.ts`, `backend/test/fakes/e2eTodoistAdapter.test.ts` | Delete tests for removed `createTask`, `updateTask`, `completeTask`, and `itemUpdateDateComplete`; keep all `sync` and `item_*` Sync API command tests. |
| `backend/test/services/todoistChecklistService.test.ts`, `taskActionService.test.ts` | Migrate child validation/batching + `checkChecklistItem`/`uncheckChecklistItem` tests onto `TaskActionService` (`new TaskActionService({...})`); delete tests for retired standalone checklist `add`/`update`/`delete` methods. |
| `backend/test/http/routes.test.ts`, `backend/test/e2eServer.test.ts` | Remove auth/behavioral tests for retired `POST /review/dismiss` and standalone `POST /tasks/:id/checklist`, `.../update`, `.../delete` (asserting 404 or removing retired route table entries); keep `check`/`uncheck` and batched `POST /tasks/:id/update` `childMutations` coverage; update `routes.test.ts` `forceRefresh` tests to construct `TodoistRuntime`. |
