# Recurring Tasks, Checklists, And Action Correctness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Todoist-native recurrence and direct checklist children to Buzer while making task actions occurrence-safe, retry-safe across two Android phones, and correct for Todoist's explicit completion, move, assignment, and duration commands.

**Architecture:** Keep Todoist as the visible task and recurrence authority, mirror hierarchy and recurrence as typed fields, project only root commitments with nested checklist children, and use an occurrence-key retrofit plus a durable action journal for cross-device safety. All mutations flow through an idempotent root-task coordinator, use explicit Todoist commands, synchronize immediately, and return an authoritative snapshot so Android does not spend a second rate-limited request refreshing after each checklist action.

**Tech Stack:** Node.js 22, TypeScript 6, Fastify 5, SQLite/`better-sqlite3`, Todoist API v1 Sync commands, Kotlin Multiplatform, kotlinx.serialization, Ktor, Compose Multiplatform/Android, Android AlarmManager, Vitest, Kotlin/JUnit, Compose instrumentation, and the deterministic fake-backend emulator lane.

---

## Scope And Delivery Rules

This is one coherent cross-layer plan rather than separate backend and Android plans. The new snapshot and mutation contracts contain occurrence keys, revisions, recurrence, checklist children, and replay metadata; shipping either side alone would create unsafe alarms or unusable actions. The work is still divided into small commits with red/green verification at each boundary.

The MVP invariant is fixed:

- A Buzer commitment is a root Todoist task.
- It may contain at most 30 direct checklist children.
- Checklist children may have title, optional details, checked state, and Todoist order only.
- A child may not have due data, duration, recurrence, assignment, or descendants.
- Recurring completion always sends `reset_subtasks = true`; no reset preference exists in persistence, API, or UI.
- The mirror remains general enough to support scheduled child commitments later. Policy, not schema, imposes the MVP invariant.

Do not change the current deployment path model while implementing this plan:

- Fastify serves unprefixed routes such as `/tasks/snapshot`.
- The public base URL contains `/buzer` only because Tailscale Funnel mounts and strips that segment.
- Keep all application routes inside the existing `app.register(async (scope) => registerRoutes(scope, options))` plugin scope. Moving them to direct root registration breaks the rate-limit plugin and 404 behavior.
- The shared bearer token remains the authentication mechanism. `deviceId`, `actionId`, revisions, and occurrence keys are concurrency metadata, never authentication credentials.

## Fixed API Shapes

Use these names consistently in TypeScript, JSON, Kotlin, fixtures, tests, and `docs/notes/api-contract.md`.

```ts
type RecurrencePreset = "never" | "daily" | "weekdays" | "weekly" | "monthly" | "yearly";
type RecurrenceKind = "none" | "supported" | "custom";
type ReconciliationStatus = "confirmed" | "pending";

interface CreateActionMetadata {
  actionId: string;
  deviceId: string;
}

interface ExistingTaskActionMetadata extends CreateActionMetadata {
  expectedOccurrenceKey: string;
}

interface ChecklistDraft {
  clientMutationId: string;
  title: string;
  description: string | null;
}

interface CreateTaskRequest extends CreateActionMetadata {
  title: string;
  description: string | null;
  projectId: string;
  assigneeId: string | null;
  dueDatetime: string;
  durationMinutes: number;
  recurrencePreset: RecurrencePreset;
  timeZone: string;
  checklistDrafts: ChecklistDraft[];
}

interface RootEditPatch {
  expectedTaskRevision: string;
  title?: string;
  description?: string | null;
  projectId?: string;
  assigneeId?: string | null;
  dueDatetime?: string;
  durationMinutes?: number;
  recurrencePreset?: RecurrencePreset;
  timeZone?: string;
}

type ChildMutation =
  | { type: "add"; clientMutationId: string; title: string; description: string | null }
  | { type: "edit"; clientMutationId: string; childId: string; expectedChildRevision: string; title: string; description: string | null }
  | { type: "delete"; clientMutationId: string; childId: string; expectedChildRevision: string }
  | { type: "check"; clientMutationId: string; childId: string; expectedChildRevision: string }
  | { type: "uncheck"; clientMutationId: string; childId: string; expectedChildRevision: string };

interface UpdateTaskRequest extends ExistingTaskActionMetadata {
  rootPatch: RootEditPatch | null;
  childMutations: ChildMutation[];
}

interface FocusedChildRequest extends ExistingTaskActionMetadata {
  expectedChildRevision: string;
}

interface AddChecklistItemRequest extends ExistingTaskActionMetadata, ChecklistDraft {}

interface EditChecklistItemRequest extends FocusedChildRequest {
  title: string;
  description: string | null;
}

interface ExtendTaskRequest extends ExistingTaskActionMetadata {
  expectedTaskRevision: string;
}

interface RescheduleTaskRequest extends ExistingTaskActionMetadata {
  dueDatetime: string;
  durationMinutes: number;
  descriptionUpdate: "unchanged" | "clear" | "set";
  description: string | null;
}

interface ChecklistItemResponse {
  id: string;
  todoistTaskId: string;
  title: string;
  description: string | null;
  checked: boolean;
  order: number;
  childRevision: string;
}

interface RecurrenceResponse {
  kind: RecurrenceKind;
  preset: RecurrencePreset | null;
  displayLabel: string;
  rule: string | null;
  language: string | null;
  timeZone: string | null;
}

interface MutationResponse {
  actionId: string;
  outcome: "completed" | "partial" | "reconciliation_pending";
  snapshot: TasksSnapshotResponse;
  childOutcomes?: Array<{
    clientMutationId: string;
    childId: string | null;
    outcome: "completed" | "failed" | "stale_child";
    errorCode: string | null;
  }>;
}
```

Mutation routes use these exact paths:

```text
POST /tasks
POST /tasks/:id/update
POST /tasks/:id/start
POST /tasks/:id/postpone-start
POST /tasks/:id/done
POST /tasks/:id/extend
POST /tasks/:id/reschedule
POST /tasks/:id/checklist
POST /tasks/:id/checklist/:childId/update
POST /tasks/:id/checklist/:childId/delete
POST /tasks/:id/checklist/:childId/check
POST /tasks/:id/checklist/:childId/uncheck
```

A completed or partial mutation returns HTTP 200. An accepted mutation whose Todoist result cannot yet be confirmed returns HTTP 202 with `outcome = "reconciliation_pending"`. Stale task, child, and occurrence errors return HTTP 409 and include the current authoritative `snapshot`. Description overflow returns HTTP 422 `activity_log_full`. Rate limiting remains HTTP 429 with `Retry-After`.

Root and child revisions are serialized as `r:<mirror_revision>`, for example `r:17`. Clients treat the value as opaque; the backend parses and compares it to the integer mirror revision. An occurrence key is a UUID-shaped opaque string with no derivation contract.

## Existing Behavior Test Migration Map

The implementation must update existing assertions in the same task that changes their behavior:

- Backend persistence/sync/action behavior: `backend/test/db/migrations.test.ts`, `backend/test/repositories/taskRepository.test.ts`, `backend/test/services/todoistSyncService.test.ts`, `backend/test/services/taskActionService.test.ts`, `backend/test/services/escalationService.test.ts`, and `backend/test/todoist/httpTodoistAdapter.test.ts`.
- Backend HTTP/auth/rate-limit/snapshot behavior: `backend/test/http/routes.test.ts`, `backend/test/http/auth.test.ts`, `backend/test/http/rateLimit.test.ts`, and `backend/test/services/taskSnapshotService.test.ts`.
- Shared JSON/state/alarm/UI behavior: `client/shared/src/commonTest/kotlin/app/buzer/shared/api/BuzerApiClientJsonTest.kt`, `client/shared/src/commonTest/kotlin/app/buzer/shared/state/SnapshotReducerTest.kt`, `client/shared/src/commonTest/kotlin/app/buzer/shared/enforcement/AlarmPlanCalculatorTest.kt`, and the shared Home/blocker/editor model suites.
- Android coordinator/alarm/editor/Home/blocker behavior: existing tests under `client/androidApp/src/test/kotlin/app/buzer/android/enforcement`, `client/androidApp/src/test/kotlin/app/buzer/android/ui`, and `client/androidApp/src/test/kotlin/app/buzer/android/blockers` named in Tasks 13-16.
- Android instrumentation behavior: `client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/AddTaskFakeBackendTest.kt` and `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`. Both are migrated before the mandatory full `scripts/e2e-fake-backend.sh` run; this is not deferred to a follow-up.

## Task 0: Reconfirm The Clean Baseline And Protect Concurrent Work

**Files:**
- Read: `AGENTS.md`
- Read: `docs/superpowers/specs/2026-04-30-todoist-commitment-companion-design.md`
- Read: `docs/superpowers/specs/2026-07-14-recurring-tasks-and-checklist-subtasks-design.md`
- Read: `docs/decisions/2026-07-15-funnel-strips-path-prefix.md`
- Read: `docs/handoffs/2026-07-13-subtasks-recurring-tasks-research.md`
- Modify: `docs/superpowers/plans/2026-07-15-recurring-tasks-checklists-and-action-correctness.md` (checkboxes only)

- [x] Run `git status --short --branch` from the repository root. Record pre-existing changes in the session handoff and do not overwrite them. Expected clean baseline at planning time: `master` tracks `origin/master` with no short-status entries.
- [x] Run `git log -8 --oneline --decorate`. Confirm the Tailscale/Funnel security work is present, especially constant-time bearer auth, per-source rate limiting, trusted-proxy scoping, loopback/tailnet binding, production cleartext removal, and the Funnel prefix correction.
- [x] From `backend/`, run `npm test`, `npm run typecheck`, and `npm run build`. Expected: 18 Vitest files and 84 tests pass at the planning baseline; typecheck and build exit 0.
- [x] From `tools/fake-backend/`, run `npm test` and `npm run build`. Expected: all Node tests pass and TypeScript exits 0.
- [x] From `client/`, run `./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest --console=plain`. Expected: `BUILD SUCCESSFUL`.
- [x] If any baseline command fails before implementation, use `superpowers:systematic-debugging`, document whether it is pre-existing, and stop before mixing an unrelated fix into this feature.

## Task 1: Add Guarded Persistence For Typed Mirrors, Occurrences, And Journaling

**Files:**
- Modify: `backend/src/db/schema.sql`
- Modify: `backend/src/db/migrations.ts`
- Modify: `backend/src/todoist/todoistTypes.ts`
- Modify: `backend/src/repositories/taskRepository.ts`
- Create: `backend/src/repositories/actionJournalRepository.ts`
- Modify: `backend/test/db/migrations.test.ts`
- Modify: `backend/test/repositories/taskRepository.test.ts`
- Create: `backend/test/repositories/actionJournalRepository.test.ts`

- [x] Add failing migration tests that open a legacy schema, insert one active and one terminal commitment plus an existing WhatsApp row, run `migrate`, and assert typed columns, occurrence backfill, the new escalation uniqueness, journal tables, and forced full-sync state. Run from `backend/`: `npm test -- test/db/migrations.test.ts`. Expected: FAIL because migration version 2 is absent.
- [x] Extend `todoist_tasks` with these typed columns while keeping `raw_json`:

```sql
parent_id text,
child_order integer,
due_date text,
due_string text,
due_lang text,
due_timezone text,
is_recurring integer not null default 0,
duration_amount integer,
duration_unit text,
mirror_revision integer not null default 1
```

- [x] Extend `commitment_states` with:

```sql
occurrence_key text,
occurrence_due_datetime text,
occurrence_recurrence_fingerprint text,
reconciliation_status text not null default 'confirmed'
  check (reconciliation_status in ('confirmed', 'pending'))
```

- [x] Add `occurrence_key` to `whatsapp_escalations`, drop `whatsapp_escalations_one_per_task`, backfill active rows from `commitment_states.occurrence_key`, use a deterministic `legacy:<task_id>` key for terminal/history rows, and create `whatsapp_escalations_one_per_occurrence` on `(task_id, occurrence_key)`.
- [x] Add the durable journal tables. Persist parsed request fingerprints and compact response/error JSON only; do not add any header, bearer token, Todoist token, or raw HTTP-request column.

```sql
create table if not exists task_action_journal (
  action_id text primary key,
  device_id text not null,
  root_task_id text,
  expected_occurrence_key text,
  action_type text not null,
  request_fingerprint text not null,
  status text not null check (status in ('processing', 'completed', 'failed', 'reconciliation_pending')),
  response_json text,
  error_json text,
  created_at text not null,
  updated_at text not null
);

create table if not exists task_action_commands (
  action_id text not null references task_action_journal(action_id) on delete cascade,
  step_key text not null,
  command_uuid text not null unique,
  temp_id text,
  status text not null check (status in ('reserved', 'sent', 'confirmed', 'failed')),
  result_json text,
  updated_at text not null,
  primary key (action_id, step_key)
);
```

- [x] Implement migration version 2 as an idempotent transaction. Backfill every missing occurrence key with `randomUUID()`, copy the current due anchor and a normalized recurrence fingerprint, and delete the current `todoist_sync_state` row so the next synchronization uses `*`. Add a `mirror_contract_version` column and write version 2 only after that full sync succeeds.
- [x] Expand `TodoistTaskMirror`, `TodoistTaskUpsert`, and `TaskWithCommitment` with all typed fields and opaque revision/occurrence values. Store booleans as `0/1` in SQLite and expose booleans at repository boundaries.
- [x] Make mirror upsert update every typed field and increment `mirror_revision` only when authoritative Todoist fields changed. Test that an identical replay keeps the same revision and a changed child title advances only that child's revision.
- [x] Implement `ActionJournalRepository.reserve`, `get`, `reserveCommand`, `markCommandSent`, `markCommandConfirmed`, `markCompleted`, `markFailed`, and `markReconciliationPending`. `reserve` must return replay for the same fingerprint and throw `409 action_id_reused` for a different fingerprint.
- [x] Run `npm test -- test/db/migrations.test.ts test/repositories/taskRepository.test.ts test/repositories/actionJournalRepository.test.ts`. Expected: PASS.
- [x] Run `npm run typecheck`. Expected: PASS.
- [x] Commit only this task's files with `git commit -m "feat: add occurrence and action journal persistence"`.

## Task 2: Mirror Todoist Hierarchy And Recurrence Through Explicit Commands

**Files:**
- Modify: `backend/src/todoist/todoistAdapter.ts`
- Modify: `backend/src/todoist/httpTodoistAdapter.ts`
- Modify: `backend/src/todoist/todoistTypes.ts`
- Create: `backend/src/todoist/todoistMutationGateway.ts`
- Modify: `backend/test/fakes/fakeTodoistAdapter.ts`
- Modify: `backend/test/todoist/httpTodoistAdapter.test.ts`

- [x] Use Context7 before editing the adapter:

```text
npx ctx7@latest library "Todoist API" "API v1 Sync commands item_add item_update item_move item_close item_uncomplete item_delete item_update_date_complete reset_subtasks parent_id due recurrence"
npx ctx7@latest docs "/websites/developer_todoist_api_v1" "exact command arguments for item_add item_update item_move item_close item_uncomplete item_delete and item_update_date_complete including reset_subtasks due timezone language and responsible_uid null"
```

Expected: the docs confirm dedicated move/completion commands and `item_update_date_complete` with `reset_subtasks`.
- [x] Add failing adapter tests for parsing `parent_id`, `child_order`, checked state, due date/datetime/string/lang/timezone/recurring flag, duration amount/unit, and for emitting each dedicated command with a caller-supplied UUID. Run `npm test -- test/todoist/httpTodoistAdapter.test.ts`. Expected: FAIL against the generic current adapter.
- [x] Map Todoist data into this typed shape without inferring product policy:

```ts
interface TodoistTaskMirror {
  todoistTaskId: string;
  title: string;
  description: string | null;
  projectId: string | null;
  parentId: string | null;
  childOrder: number | null;
  assigneeId: string | null;
  creatorId: string | null;
  dueDate: string | null;
  dueDatetime: string | null;
  dueString: string | null;
  dueLang: string | null;
  dueTimezone: string | null;
  isRecurring: boolean;
  durationAmount: number | null;
  durationUnit: string | null;
  durationMinutes: number | null;
  completedAt: string | null;
  deletedAt: string | null;
  rawJson: unknown;
  updatedAt: string;
}
```

- [x] Normalize minute and hour durations to `durationMinutes`, retain unsupported units in the typed amount/unit fields, and let policy reject unsupported scheduled roots. Do not erase raw values.
- [x] Replace random UUID generation inside `HttpTodoistAdapter` with caller-supplied command identities. Keep `sync(syncToken)` and expose explicit gateway methods for `item_add`, `item_update`, `item_move`, `item_close`, `item_uncomplete`, `item_delete`, and `item_update_date_complete`.
- [x] Model due mutations explicitly so field updates can preserve or replace recurrence without accidental flattening:

```ts
type TodoistDueMutation =
  | { kind: "preserve" }
  | { kind: "clear" }
  | {
      kind: "replace";
      datetime: string;
      string: string | null;
      lang: string | null;
      timezone: string | null;
    };
```

- [x] Ensure `item_update` omits due for metadata-only edits and one-time extension, emits a recurrence-aware due object for supported reschedule/edit, and sends `responsible_uid: null` when unassigning. Ensure project changes use `item_move`, never `project_id` inside `item_update`.
- [x] Implement recurring completion with `item_update_date_complete`, the mirrored due facts required by the official command, and hard-coded `reset_subtasks: true`. There must be no Boolean parameter that permits `false`.
- [x] Implement root and child creation through `item_add` with journal-provided UUID/temp ID. A child command must contain `parent_id`, the root project, content, and optional description, and must omit due, duration, recurrence, and assignment.
- [x] Assert command failures include command UUID, type, and Todoist status but never the Authorization header or API token.
- [x] Run `npm test -- test/todoist/httpTodoistAdapter.test.ts`. Expected: PASS.
- [x] Run `npm run typecheck`. Expected: PASS.
- [x] Commit with `git commit -m "refactor: use explicit Todoist task commands"`.

## Task 3: Add Recurrence Presets And Managed Description Encoding

**Files:**
- Create: `backend/src/domain/taskRecurrence.ts`
- Create: `backend/src/domain/taskDescriptionCodec.ts`
- Create: `backend/test/domain/taskRecurrence.test.ts`
- Create: `backend/test/domain/taskDescriptionCodec.test.ts`

- [x] Write failing table-driven recurrence tests for Never, Daily, Weekdays, Weekly, Monthly, and Yearly; DST-zone preservation; exact recognition of Buzer-generated rules; and custom Todoist recurrence preservation. Run `npm test -- test/domain/taskRecurrence.test.ts`. Expected: FAIL because the module does not exist.
- [x] Define `RecurrencePreset` as the six fixed values in the API section. Translate supported presets on the backend using the selected start interpreted in the supplied IANA time zone, `due_lang = "en"`, and these canonical rule forms:

```text
daily     -> every day at HH:mm
weekdays  -> every weekday at HH:mm
weekly    -> every <weekday> at HH:mm
monthly   -> every month on the <ordinal-day> at HH:mm
yearly    -> every year on <month> <ordinal-day> at HH:mm
```

Use English weekday/month names and a tested ordinal formatter (`1st`, `2nd`, `3rd`, `4th`, `11th`, `12th`, `13th`, `21st`). Never calculate future occurrences in Buzer.
- [x] Return `{kind: "custom", preset: null}` when a Todoist rule cannot be recognized exactly. Metadata/checklist edits preserve its full rule/lang/time zone; schedule or recurrence edits reject it until the request selects a supported preset or Never.
- [x] Write failing codec tests for no marker, one valid marker, malformed activity suffix, duplicate/corrupt markers, whitespace preservation, reconstruction, append, Unicode arrow text, and description overflow. Run `npm test -- test/domain/taskDescriptionCodec.test.ts`. Expected: FAIL.
- [x] Implement the exact delimiter and event model. The 16,384-character constant is grounded in Todoist's current official [task-description documentation](https://www.todoist.com/help/articles/add-a-task-description-in-todoist-rOryWIHn), not inferred from an error string:

```ts
export const BUZER_ACTIVITY_DELIMITER = "--- Buzer activity (managed) ---";
export const TODOIST_DESCRIPTION_MAX_CHARS = 16_384;

interface ActivityLogEntry {
  timestamp: string;
  timeZone: string;
  message: string;
}
```

- [x] Treat the suffix as managed only when the delimiter is at a line boundary and every non-empty following line parses as an activity entry. Otherwise preserve the complete description as user text. On the next extension, append a fresh delimiter and entry rather than deleting or guessing at malformed content.
- [x] Implement `appendExtension` to produce `YYYY-MM-DD HH:mm <IANA-zone> — Extended +30 min (<old> → <new> min)`, using the task's Todoist zone and UTC fallback. Reject before calling Todoist if the reconstructed full description exceeds 16,384 characters.
- [x] Run both focused tests and `npm run typecheck`. Expected: PASS.
- [x] Commit with `git commit -m "feat: add recurrence and task description codecs"`.

## Task 4: Classify And Project Root Commitments With Checklist Children

**Files:**
- Create: `backend/src/domain/taskEnforcementPolicy.ts`
- Create: `backend/src/services/taskHierarchyProjector.ts`
- Modify: `backend/src/domain/taskValidation.ts`
- Create: `backend/test/domain/taskEnforcementPolicy.test.ts`
- Create: `backend/test/services/taskHierarchyProjector.test.ts`

- [x] Write failing policy tests for an eligible root, a repairable root candidate, an eligible child, not-owned tasks, scheduled/recurring/assigned/duration-bearing children, a grandchild, 31 direct children, and a valid 30-child tree. Expected classification names: `ROOT_COMMITMENT`, `ROOT_CANDIDATE_INVALID`, `CHECKLIST_CHILD`, `UNSUPPORTED_HIERARCHY`, and `NOT_OWNED`.
- [x] Move personal-project ownership normalization out of `TaskSnapshotService` into `TaskEnforcementPolicy`. Preserve the current rule that a task created by the configured user in a non-assignable personal project can be companion-owned through `commitment_owner_id`.
- [x] Give hierarchy violations stable codes suitable for API/UI tests:

```ts
type HierarchyViolation =
  | "too_many_children"
  | "nested_child"
  | "child_has_schedule"
  | "child_has_duration"
  | "child_has_recurrence"
  | "child_has_assignment"
  | "child_project_mismatch";
```

- [x] Write failing projector tests proving only roots appear at top level, direct children are ordered by `child_order` then stable ID, child progress is derived, grandchildren are detected rather than flattened, and changing one child's checked state does not change the root revision.
- [x] Implement a pure projector returning root, children, checked/total counts, policy classification, and violation. Do not place alarm, recurrence mutation, or SQLite writes in this component.
- [x] Keep the underlying mirror uniform for every task. Do not create a checklist-only table or schema branch; future scheduled-child support must require a new policy classification, not a mirror migration.
- [x] Run `npm test -- test/domain/taskEnforcementPolicy.test.ts test/services/taskHierarchyProjector.test.ts test/domain/taskValidation.test.ts`. Expected: PASS.
- [x] Run `npm run typecheck`. Expected: PASS.
- [x] Commit with `git commit -m "feat: project checklist task hierarchies"`.

## Task 5: Reconcile Occurrence Identity And Per-Occurrence Accountability

**Files:**
- Create: `backend/src/services/occurrenceReconciler.ts`
- Modify: `backend/src/services/todoistSyncService.ts`
- Modify: `backend/src/services/todoistPoller.ts`
- Modify: `backend/src/repositories/taskRepository.ts`
- Modify: `backend/src/services/escalationService.ts`
- Modify: `backend/test/services/todoistSyncService.test.ts`
- Create: `backend/test/services/occurrenceReconciler.test.ts`
- Modify: `backend/test/services/escalationService.test.ts`

- [x] Write failing occurrence tests for first backfill, unchanged sync, external one-time schedule edit, recurring due advance, recurrence-rule change, title-only edit, explicit Buzer schedule replan, Extend 30 exception, leaving ownership, and returning to ownership.
- [x] Compute a stable recurrence fingerprint from `isRecurring`, due rule, language, and time zone. Treat `occurrence_key` as opaque random identity; do not derive it from due datetime.
- [x] Implement these reconciler decisions:

```ts
type OccurrenceDecision =
  | { kind: "unchanged" }
  | { kind: "rotate"; reason: "initialized" | "external_schedule" | "recurring_advance" | "editor_replan" | "ownership_returned" }
  | { kind: "reset_children_then_rotate"; checkedChildIds: string[] }
  | { kind: "leave_ownership" };
```

- [x] On rotation, generate a new key, copy current due/fingerprint anchors, set state to `planned`, clear started/dismiss/extension/snooze/escalation fields, increment schedule version, and mark reconciliation confirmed only after the tree is authoritative.
- [x] For an externally advanced recurring root with checked children, mark the root pending, reserve a system action and per-child `item_uncomplete` commands through the same journal/gateway, synchronize again, and rotate only after every direct eligible child is confirmed unchecked. Do not schedule the next occurrence while pending.
- [x] Serialize `TodoistSyncService.syncOnce()` so two action coordinators cannot race the shared sync token. Read the token inside the serialized section and update it only after the corresponding mirror transaction commits.
- [x] After migration, require a successful `sync("*")` before writing `mirror_contract_version = 2`. Until then, snapshot and mutation services return `503 sync_initializing`; `/health` remains available. This prevents typed policy from reading partially backfilled raw JSON.
- [x] Change escalation lookup and insert to `(task_id, occurrence_key)`. Verify an existing migrated occurrence cannot resend, while the next rotated occurrence can send once.
- [x] Run `npm test -- test/services/occurrenceReconciler.test.ts test/services/todoistSyncService.test.ts test/services/escalationService.test.ts`. Expected: PASS.
- [x] Run `npm run typecheck`. Expected: PASS.
- [x] Commit with `git commit -m "feat: reconcile recurring task occurrences"`.

## Task 6: Add Durable Root Serialization And Replay Coordination

**Files:**
- Create: `backend/src/services/rootTaskMutex.ts`
- Create: `backend/src/services/taskActionCoordinator.ts`
- Modify: `backend/src/domain/errors.ts`
- Create: `backend/test/services/rootTaskMutex.test.ts`
- Create: `backend/test/services/taskActionCoordinator.test.ts`

- [x] Write failing mutex tests proving same-root operations execute in submission order, different-root operations may overlap, rejection releases the queue, and completed roots are removed from the lock map.
- [x] Implement an in-process per-root promise queue. Keep SQLite as the durable journal; the mutex is only the single-process Todoist race guard.
- [x] Write failing coordinator tests for first reservation, same-action replay, different-payload reuse rejection, same-action retry after process-like reconstruction, ambiguous Todoist timeout, sync failure after Todoist success, completed response replay, and two-phone distinct postponements.
- [x] Fingerprint a canonical JSON object containing action type and parsed domain payload with SHA-256. Do not fingerprint HTTP headers. Do not log or persist descriptions outside the existing mirrored task content and compact durable result required by the product.
- [x] Implement `TaskActionCoordinator.execute` in this order: reserve/replay journal, lock root, synchronize, validate readiness/revisions/occurrence, reserve command IDs before sending, execute domain action, synchronize and verify, store compact response, then return. A replayed completed action returns its stored response without incrementing counters or sending Todoist commands.
- [x] On successful Todoist response followed by sync failure, mark journal and root `reconciliation_pending` and return the HTTP-layer pending result. On retry, synchronize first; if the effect is present, confirm without resending. If the earlier send timed out ambiguously, reuse the persisted command UUID only after synchronization proves the effect absent.
- [x] Add `DomainError` factories for `stale_task`, `stale_child`, `stale_occurrence`, `action_id_reused`, `reconciliation_pending`, `unsupported_hierarchy`, `children_incomplete`, and `activity_log_full`, including current snapshot context where the contract requires it.
- [x] Run `npm test -- test/services/rootTaskMutex.test.ts test/services/taskActionCoordinator.test.ts`. Expected: PASS.
- [x] Run `npm run typecheck`. Expected: PASS.
- [x] Commit with `git commit -m "feat: coordinate idempotent task actions"`.

## Task 7: Implement Checklist Creation, Editing, And Focused Check Actions

**Files:**
- Create: `backend/src/services/todoistChecklistService.ts`
- Modify: `backend/src/services/taskActionService.ts`
- Modify: `backend/src/repositories/taskRepository.ts`
- Modify: `backend/test/services/taskActionService.test.ts`
- Create: `backend/test/services/todoistChecklistService.test.ts`

- [x] Write failing checklist-service tests for add, edit title/details, delete, check, uncheck, already-in-requested-state idempotency, stale child revision, child not under root, forbidden child fields, 30-child limit, partial batch failure, and different-child concurrent mutations.
- [x] Define explicit child intents rather than replacing a submitted array:

```ts
type ChildMutation =
  | { type: "add"; clientMutationId: string; title: string; description: string | null }
  | { type: "edit"; clientMutationId: string; childId: string; expectedChildRevision: string; title: string; description: string | null }
  | { type: "delete"; clientMutationId: string; childId: string; expectedChildRevision: string }
  | { type: "check"; clientMutationId: string; childId: string; expectedChildRevision: string }
  | { type: "uncheck"; clientMutationId: string; childId: string; expectedChildRevision: string };
```

- [x] Make `TodoistChecklistService` the only product service that mutates children. It validates the current projected tree, inherits the root project on add, and rejects parent ID, due, duration, recurrence, assignment, or descendants from clients.
- [x] Append zero to 30 children only after a root exists. Persist one command step per child before sending, return the root plus each child result, and do not roll back successful external Todoist child commands after a later child fails. Root creation itself remains the Task 8 root-create contract.
- [x] For mixed edit batches, validate child revisions independently, continue safe different-child operations after a per-child stale/error result, and preserve failed local drafts in outcomes by `clientMutationId`. Root-field validation remains Task 8 because Task 7 accepts no root patch.
- [x] Focused check/uncheck synchronizes before validation and after the command. Check uses `item_close`; uncheck uses `item_uncomplete`; delete uses `item_delete`. A request for the already-observed state avoids another command while completing its journal action and returning the authoritative snapshot. Checking the last child does not auto-complete the root.
- [x] Ensure successful child mutation responses include the refreshed snapshot so Android does not immediately call `GET /tasks/snapshot`. This keeps rapid child actions within the shared source-IP limit.
- [x] Run `npm test -- test/services/todoistChecklistService.test.ts test/services/taskActionService.test.ts`. Expected: PASS.
- [x] Run `npm run typecheck`. Expected: PASS.
- [x] Commit with `git commit -m "feat: add Todoist checklist mutations"`.

## Task 8: Correct Root Create, Edit, Done, Reschedule, And Extend Semantics

**Files:**
- Modify: `backend/src/services/taskActionService.ts`
- Modify: `backend/src/services/taskActionCoordinator.ts`
- Modify: `backend/src/todoist/todoistMutationGateway.ts`
- Modify: `backend/test/services/taskActionService.test.ts`
- Modify: `backend/test/services/taskActionCoordinator.test.ts`
- Modify: `backend/test/todoist/httpTodoistAdapter.test.ts`

- [ ] Add failing tests proving root create maps recurrence on the backend; metadata-only edit preserves lifecycle/occurrence; schedule edit rotates; custom recurrence schedule edit is rejected; project move and field update use separate journal steps; explicit unassignment sends null; leaving ownership cancels enforcement; returning creates a fresh occurrence; and partial move/update recovery executes only the unfinished step.
- [x] Update create input to include `recurrencePreset`, `timeZone`, child drafts, `actionId`, and `deviceId`. Validate a real IANA time zone, a supported preset, and 0-30 children. Keep recurrence rule construction server-side.
- [x] Update edit input to include expected task revision, expected occurrence key, root-field changes, supported/custom recurrence replacement intent, and explicit child mutations. Reconstruct the full description through `TaskDescriptionCodec` so ordinary edits cannot overwrite managed activity.
- [x] Classify root edits before mutation. Title, description, project, assignee, and checklist-only changes preserve lifecycle while ownership remains. Start, duration, or recurrence changes replan and rotate. `item_move` and `item_update` use distinct durable step keys, then sync verifies root and direct children share the authoritative project.
- [ ] Add failing Done tests for incomplete children, stale external child state corrected by pre-action sync, unsupported hierarchy, one-time `item_close`, recurring `item_update_date_complete` with hard-coded reset, terminal old occurrence, immediate next-occurrence sync, and pending recurring completion.
- [x] Implement Done exactly in the approved order: reserve/lock, sync tree, validate expected occurrence/hierarchy/all checked, issue one-time or recurring command, prevent further old-occurrence actions, sync, rotate recurring state from Todoist authority, and return the snapshot.
- [x] Quality follow-up: require durable metadata/coordinator for every root mutation, recover a create journal onto a sync-reconstructed local root ID, and leave create/reschedule/non-move-edit mirrors unchanged until authoritative confirmation.
- [x] Quality follow-up: coordinate Start and Postpone with occurrence metadata, finalize confirmed Extend lifecycle/alarm state exactly once, and return canonical create identity after root reassociation.
- [x] Quality follow-up: after create recovery reassociates a journal from its provisional ID, hand off its active mutex tail to the canonical root ID so existing and newly queued canonical actions cannot overlap the remaining recovery work.
- [x] Quality follow-up: derive Postpone dismiss/escalation state inside the root mutex, replay a durable escalation command after a post-mutation crash, and atomically persist Extend finalization with journal completion.
- [x] Quality follow-up: a restored confirmed command remains effect-bearing during replay, so a second authoritative sync must verify the effect before completion.
- [ ] Add failing reschedule tests proving supported recurrence/time zone is preserved, Never remains one-time, custom recurrence rejects schedule mutation, and a schedule generation change rotates the occurrence.
- [ ] Add failing extension tests proving recurring tasks reject, one-time due remains byte-for-byte unchanged, duration increases by 30, one audit line is appended once, retry does not duplicate, new intentional action appends a second line, state becomes/remains started, counters/schedule version advance only after sync confirmation, and description overflow sends no Todoist command.
- [x] Implement Extend as one `item_update` with duration and reconstructed description while omitting due. Use task timezone or UTC for the audit timestamp and keep the occurrence key unchanged.
- [x] Assert create, edit, check, uncheck, start, postpone, reschedule, Done, project move, and assignment changes do not append managed activity entries. The MVP activity log records extension events only.
- [x] Run `npm test -- test/services/taskActionService.test.ts test/services/taskActionCoordinator.test.ts test/todoist/httpTodoistAdapter.test.ts`. Expected: PASS.
- [x] Run `npm run typecheck`. Expected: PASS.
- [x] Commit with `git commit -m "fix: make task actions occurrence safe"`.

## Task 9: Publish The Nested Snapshot And Authenticated Mutation Routes

**Files:**
- Modify: `backend/src/services/taskSnapshotService.ts`
- Modify: `backend/src/http/routes.ts`
- Modify: `backend/src/server.ts`
- Modify: `backend/test/services/taskSnapshotService.test.ts`
- Modify: `backend/test/http/routes.test.ts`
- Modify: `backend/test/http/auth.test.ts`
- Modify: `backend/test/http/rateLimit.test.ts`

- [x] Write failing snapshot tests for root-only projection, nested ordered children/revisions, progress, split user description/activity log, supported/custom recurrence, hierarchy violation, occurrence key, root revision, and reconciliation-pending state.
- [x] Replace raw-description and raw-JSON date extraction in `TaskSnapshotService` with typed mirror fields, `TaskDescriptionCodec`, `TaskEnforcementPolicy`, and `TaskHierarchyProjector`. Return invalid/unsupported owned roots for Morning Review; never return checklist children as top-level tasks.
- [x] Keep existing scheduling fields for client compatibility and add these root fields. Encode both root and child revisions as `r:<mirror_revision>` and reject malformed or mismatched values through the stale-domain error path:

```ts
occurrenceKey: string;
taskRevision: string;
activityLogEntries: ActivityLogEntry[];
recurrence: RecurrenceResponse;
checklistItems: ChecklistItemResponse[];
checkedCount: number;
totalCount: number;
hierarchyStatus: "valid" | "unsupported";
hierarchyViolation: HierarchyViolation | null;
reconciliationStatus: "confirmed" | "pending";
```

- [x] Add Zod request schemas for all fixed routes. Validate action/device IDs as non-empty bounded strings, occurrence/revision values as opaque bounded strings, at most 30 child drafts/mutations, no unknown child scheduling fields, and IANA time zone through domain validation.
- [x] Make every mutation route call `TaskActionCoordinator` and return its `MutationResponse`; remove route-local construction of the old service that bypasses journaling. Reuse a server-scoped coordinator/mutex so separate requests share root serialization.
- [x] Keep `/health` as the only unauthenticated route and keep all route registration inside the existing Fastify plugin scope. Do not introduce an application `/buzer` prefix and do not change the trusted proxy ranges.
- [x] Add an unauthenticated table test covering every new route path and representative required params/body. Expected: 401 before domain validation. Also keep the unmatched-path test at 404, proving the auth hook did not leak to the not-found handler.
- [x] Add rate-limit tests proving child mutation responses carry rate-limit headers, 61 requests from one effective source are limited under the default config, spoofed forwarding from an untrusted peer is ignored, and the successful mutation response contains a snapshot so no follow-up GET is required.
- [x] Add a log-safety test or logger spy proving route errors and action journal output never include the bearer token, Todoist token, full description, or managed audit payload. Keep production Fastify `logger: false` and Ktor Authorization sanitization unchanged.
- [x] Run `npm test -- test/services/taskSnapshotService.test.ts test/http/routes.test.ts test/http/auth.test.ts test/http/rateLimit.test.ts`. Expected: PASS.
- [x] Run the full backend gate: `npm test`, `npm run typecheck`, and `npm run build`. Expected: all PASS.
- [x] Commit with `git commit -m "feat: expose recurring checklist task contracts"`.

## Task 10: Update The Stateful Fake Backend Before Android Code

**Files:**
- Modify: `tools/fake-backend/src/fixtures.ts`
- Modify: `tools/fake-backend/src/server.ts`
- Modify: `tools/fake-backend/src/server.test.ts`

- [x] Write failing fake-backend tests for nested fixtures, supported recurring and custom recurring roots, focused child check/uncheck, incomplete-parent Done rejection, recurring Done rollover/reset, action replay, stale occurrence/child responses, partial create/edit outcomes, and returned snapshots.
- [x] Change fixtures from immutable flat JSON to deterministic in-memory root/checklist state. Include these named scenarios: one-time no children, one-time partially checked, recurring all checked, recurring with Extend disabled, custom recurrence, unsupported hierarchy, and reconciliation pending.
- [x] Record action body metadata (`actionId`, `deviceId`, occurrence/revisions) without recording Authorization. Reusing an action ID with an identical body returns the same stored response; changing its body returns `409 action_id_reused`.
- [x] Implement all fixed routes and return the authoritative updated snapshot from mutations. Checking a child must advance that child revision only. Recurring Done must advance to a deterministic next due, create a new occurrence key, and reset all children unchecked.
- [x] Keep test controls for forced 500 snapshot/action failures and add deterministic 202 pending, 409 stale, 422 activity-log-full, and 429-with-`Retry-After` controls for Android and shared-client tests.
- [x] Run from `tools/fake-backend/`: `npm test` and `npm run build`. Expected: PASS.
- [x] Commit with `git commit -m "test: model recurring checklists in fake backend"`.

## Task 11: Add Shared Kotlin Contracts, Error Context, And Retry Semantics

**Files:**
- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/model/TaskModels.kt`
- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/api/BuzerApiClient.kt`
- Modify: `client/shared/src/commonTest/kotlin/app/buzer/shared/api/BuzerApiClientJsonTest.kt`
- Modify: `client/shared/src/commonTest/kotlin/app/buzer/shared/api/BuzerApiClientTimeoutTest.kt`
- Create: `client/shared/src/commonTest/kotlin/app/buzer/shared/api/BuzerApiClientErrorTest.kt`

- [x] Add failing serialization tests for the fixed snapshot fields, recurrence enums, checklist items, activity entries, action metadata, create child drafts, explicit child mutations, focused check/uncheck, mutation response, partial outcomes, and nullable assignee clearing.
- [x] Mirror the fixed API types with `@Serializable` Kotlin data classes. Create requests contain `actionId` and `deviceId`; existing-task requests additionally contain `expectedOccurrenceKey`; root/child mutations add their relevant expected revision. Keep these fields explicit so a captured request object can be reused unchanged on retry.
- [x] Replace `Unit` mutation API methods with `MutationResponse` return values and add methods for all checklist paths. Continue using the configured base URL verbatim; do not add `/buzer` inside client paths.
- [x] Add a typed `BuzerApiException(status, code, retryAfterSeconds, snapshot)` that parses error JSON and the `Retry-After` header. Preserve 409 snapshot context so reducers can refresh without discarding an editor draft.
- [x] On 429, surface retry timing and do not automatically fabricate a new request/action ID. The UI/coordinator may retry the exact captured request after the indicated time. The shared client itself must not issue hidden duplicate mutations.
- [x] Keep HTTP logging at headers only and keep `sanitizeHeader { it == HttpHeaders.Authorization }`. Remove the current error log that includes arbitrary response bodies if it could contain snapshot descriptions; log status/code only.
- [x] Run from `client/`: `./gradlew :shared:testAndroidHostTest --tests 'app.buzer.shared.api.*' --console=plain`. Expected: PASS.
- [x] Commit with `git commit -m "feat: add recurring checklist client contracts"`.

## Task 12: Make Shared State And Alarm Plans Occurrence-Aware

**Files:**
- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/state/SnapshotReducer.kt`
- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/state/SnapshotDiagnostics.kt`
- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/enforcement/AlarmPlanCalculator.kt`
- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/ui/commitment/CommitmentEditorModels.kt`
- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/ui/home/HomeTimelineModels.kt`
- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/ui/blockers/BlockerUiModels.kt`
- Modify: `client/shared/src/commonTest/kotlin/app/buzer/shared/state/SnapshotReducerTest.kt`
- Modify: `client/shared/src/commonTest/kotlin/app/buzer/shared/enforcement/AlarmPlanCalculatorTest.kt`
- Modify: `client/shared/src/commonTest/kotlin/app/buzer/shared/ui/blockers/BlockerUiModelsTest.kt`
- Create: `client/shared/src/commonTest/kotlin/app/buzer/shared/ui/commitment/ChecklistEditorModelsTest.kt`

- [x] Add failing reducer tests proving nested children are not independent invalid/active tasks, unsupported and pending roots appear in Morning Review, a recurring next occurrence is distinct from the old key, and stale mutation snapshots replace cached authority without losing a caller-owned draft object.
- [x] Add `occurrenceKey` to `DesiredAlarm` and define alarm identity as `(taskId, occurrenceKey, kind)`. Exclude invalid, unsupported, completed/deleted, and reconciliation-pending roots from alarm creation.
- [x] Add alarm-plan tests for key rotation at the same due timestamp, recurring rollover, pending cancellation, checklist-only mutation with unchanged key, and Extend 30 with unchanged key but changed deadline.
- [x] Add pure editor models for recurrence choices in numbered order and explicit checklist draft/mutation state:

```kotlin
val recurrenceChoices = listOf(
    RecurrenceChoice(1, RecurrencePreset.never, "Never"),
    RecurrenceChoice(2, RecurrencePreset.daily, "Daily"),
    RecurrenceChoice(3, RecurrencePreset.weekdays, "Weekdays"),
    RecurrenceChoice(4, RecurrencePreset.weekly, "Weekly"),
    RecurrenceChoice(5, RecurrencePreset.monthly, "Monthly"),
    RecurrenceChoice(6, RecurrencePreset.yearly, "Yearly")
)
```

- [x] Model custom recurrence as read-only and require an explicit supported replacement before a start/recurrence edit. Validate 30 children, nonblank child titles, stable `clientMutationId`, and retention of failed drafts.
- [x] Extend Home/blocker UI models with checked/total progress, next unchecked child, Done enabled only when counts match, and Extend enabled only for non-recurring roots.
- [x] Run `./gradlew :shared:testAndroidHostTest --console=plain`. Expected: PASS.
- [x] Commit with `git commit -m "feat: make client state occurrence aware"`.
- [x] Spec-review follow-up: require a nonblank occurrence key, expose the stable `(taskId, occurrenceKey, kind)` identity separately from trigger time, and exclude invalid-state roots even without validation errors.
- [x] Code-quality follow-up: clear custom recurrence state on replacement and add typed checklist editor mutations with failure retention for add, edit, delete, check, and uncheck intents.
- [x] Final P1 follow-up: reject duplicate `clientMutationId` values across every typed checklist mutation before failed-intent retention/batch processing.

## Task 13: Persist Device Identity And Occurrence-Keyed Android Alarms

**Files:**
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/sync/DeviceIdentityStore.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/ScheduledAlarmRegistry.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/AlarmScheduler.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/AndroidAlarmScheduler.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/AlarmIntentFactory.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/AlarmReceiver.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/AndroidEnforcementMapper.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/blockers/BlockerTaskSnapshotGate.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementDependencies.kt`
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/sync/DeviceIdentityStoreTest.kt`
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/ScheduledAlarmRegistryTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/AlarmIntentFactoryTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/blockers/BlockerTaskSnapshotGateTest.kt`

- [ ] Add failing device tests proving one UUID is generated once and survives store reconstruction, while each explicit user intent gets a fresh action UUID. Retry code must hold the same request/action ID; `DeviceIdentityStore` must not silently reuse an action ID for a second intent.
- [ ] Add `occurrenceKey` to `ScheduledAlarm`, PendingIntent extras, request-code input, blocker launch intents, logs, and snapshot gate. Keep Morning Review on a fixed synthetic key such as `morning-review`.
- [ ] Add a persistent registry of scheduled alarm identities. During reconciliation, cancel registry entries not in the new desired set, including an old occurrence with the same task ID/due. Then schedule desired identities and atomically replace the registry.
- [ ] Add failing tests proving old/new occurrence PendingIntents differ, cancellation targets the exact old occurrence, boot reconciliation restores the registry, and an alarm for a stale occurrence refreshes then dismisses without showing a blocker.
- [ ] Keep Android release networking unchanged: production HTTPS only and no release cleartext opt-in. The debug network security config may continue allowing only `10.0.2.2` for the emulator fake backend.
- [ ] Run `./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.enforcement.*' --tests 'app.buzer.android.sync.*' --tests 'app.buzer.android.blockers.BlockerTaskSnapshotGateTest' --console=plain`. Expected: PASS.
- [ ] Commit with `git commit -m "feat: key Android alarms by occurrence"`.

## Task 14: Consume Authoritative Mutation Responses In The Android Coordinator

**Files:**
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementApi.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementCoordinator.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/BlockerAction.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/HomeDoneActionHandler.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/EnforcementCoordinatorTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/HomeDoneActionHandlerTest.kt`

- [ ] Add failing coordinator tests proving a successful mutation saves/reconciles the returned snapshot without a follow-up `getSnapshot`, a 202 pending response cancels speculative alarms and exposes pending UI, a 409 applies current snapshot while retaining a caller draft, 429 exposes retry timing, and a retry sends the exact same action ID.
- [ ] Change coordinator action methods to accept immutable serialized request objects that already contain action metadata. UI handlers create the request once and capture it in the retry closure.
- [ ] Replace `runBackendAction`'s unconditional `refreshAndReconcile()` with `applyMutationResponse(response)`: save the returned snapshot, reconcile alarms, clear obsolete notifications, and return typed success/partial/pending/stale state.
- [ ] Do not cancel all task alarms optimistically before a confirmed Done response. The returned authoritative snapshot determines cancellation or next-recurring-occurrence scheduling.
- [ ] Add focused `checkChild` and `uncheckChild` coordinator actions. Prevent multiple simultaneous taps on one child while its action is in flight, but allow the next intent after the response with a new action ID/revision.
- [ ] Keep offline behavior honest: cached UI may display, but mutation failure never changes checked/done/recurrence state locally. Alarm-triggered blockers show retry/offline state if refresh fails.
- [ ] Run `./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.enforcement.EnforcementCoordinatorTest' --tests 'app.buzer.android.ui.HomeDoneActionHandlerTest' --console=plain`. Expected: PASS.
- [ ] Commit with `git commit -m "feat: reconcile authoritative action responses"`.

## Task 15: Add Recurrence And Checklist Editing To Add, Edit, And Repair

**Files:**
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/components/CommitmentEditor.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/components/ChecklistEditor.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/EditTaskScreen.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreen.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/App.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/addtask/AddTaskCreateFlow.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/components/CommitmentEditorStateTest.kt`
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/ui/components/ChecklistEditorStateTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskCreateFlowTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/MorningReviewScreenStateTest.kt`
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/AddTaskFakeBackendTest.kt`

- [ ] Add failing JVM state tests for numbered recurrence choices 1-6, creating-device timezone, custom recurrence display/preservation, custom replacement requirement, child add/edit/delete/check intent generation, 30-child validation, activity log read-only rendering, and failed child draft retention.
- [ ] Extend `CommitmentEditor` with a recurrence selector and a `ChecklistEditor` section. Show the choice number with each label (`1. Never` through `6. Yearly`). Do not add a parent-task selector, child schedule fields, drag reorder, or grandchildren.
- [ ] Add Task sends recurrence preset, `TimeZone.currentSystemDefault().id`, zero to 30 child drafts, stable device ID, and one action ID captured across retry. Render per-child partial failures without claiming the entire create failed.
- [ ] Add an Edit route reached by tapping a Home agenda row. Initialize from the authoritative root snapshot; submit expected root/occurrence revisions plus explicit child mutations. Preserve unsaved draft values when a 409 snapshot refresh arrives and show a reapply/conflict message.
- [ ] Reuse the same recurrence/checklist components in Morning Review repair. Unsupported hierarchy shows its reason and allows ordinary direct-child repair where safe; normal Done/enforcement remains disabled until the tree is valid.
- [ ] Split editable `description` from read-only `activityLogEntries`. The editor may change only user description; show the managed activity section below it with no text-field semantics.
- [ ] Migrate `AddTaskFakeBackendTest` to assert recurrence choice, two child drafts, action/device IDs, and partial child failure retention. Keep all existing add-task tests for project/assignee/schedule/description behavior passing.
- [ ] Run `./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest --console=plain`. Expected: PASS.
- [ ] Run the focused instrumentation class with the fake backend via the standard lane filter if supported; otherwise run the full lane in Task 17. Expected focused Add Task cases: PASS.
- [ ] Commit with `git commit -m "feat: edit recurring tasks and checklists"`.

## Task 16: Render Checklist Progress And Actions On Home, Blockers, And Overlay

**Files:**
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreen.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/BlockerScreens.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/components/BlockerSurface.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/components/ChecklistProgress.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/blockers/StartBlockerActivity.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/blockers/DeadlineBlockerActivity.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/overlay/OverlayBlockerRequest.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/overlay/BlockerOverlayController.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/overlay/OverlayComposeHost.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/HomeScreenStateTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/blockers/BlockerActionStateTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/overlay/OverlayBlockerRequestTest.kt`
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`

- [ ] Add failing state/UI tests for Home row progress-only display and tap-to-edit; current-block interactive checkboxes; Done disabled until all checked; start blocker collapsed progress; deadline blocker expanded interactive checklist; overlay progress/next unchecked/open-full-app; recurring Extend visible but disabled; and pending/offline messaging.
- [ ] Home agenda rows show `checkedCount/totalCount` without inline child controls and open Edit on tap. The current-block card shows direct child controls and derives Done enabled from the authoritative counts.
- [ ] Start blocker shows a compact/collapsed checklist summary. Deadline blocker expands all children and supports focused check/uncheck. Both create one immutable request per tap and apply returned snapshots without an extra GET.
- [ ] Keep Extend 30 visible on recurring blockers but disabled with explanatory copy. For one-time tasks, keep it enabled and show pending state as `Extended; syncing deadline` without scheduling an unconfirmed deadline.
- [ ] Overlay shows progress and the next unchecked child only. Its action opens the full blocker/activity for interaction; do not make the overlay a second full checklist editor.
- [ ] On every alarm entry, refresh before display and compare the alarm's `(taskId, occurrenceKey)` to the snapshot. Dismiss the surface if another phone completed, started, rescheduled, or advanced the occurrence.
- [ ] Migrate every existing `BlockerActivityFakeBackendTest` assertion that builds or invokes an action to include action/device/occurrence metadata. Add checks for child progress, Done gate, recurring Extend disabled, returned-snapshot UI update, and stale occurrence dismissal.
- [ ] Run `./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest --console=plain`. Expected: PASS.
- [ ] Commit with `git commit -m "feat: show checklist progress in enforcement UI"`.

## Task 17: Run Full Contract, Security, Emulator, And Two-Phone Verification

**Files:**
- Modify: `docs/notes/api-contract.md`
- Create: `docs/decisions/2026-07-15-occurrence-keyed-recurring-checklists.md`
- Create: `docs/handoffs/2026-07-15-recurring-tasks-checklists-implementation.md`
- Modify: `docs/superpowers/plans/2026-07-15-recurring-tasks-checklists-and-action-correctness.md` (final checkboxes)

- [ ] Update `docs/notes/api-contract.md` with the fixed paths, request/response JSON examples, nested roots, recurrence, descriptions/activity, action/device IDs, revisions, 200/202/409/422/429 handling, and deployment addressing. State explicitly: public `https://oc-vps.taila50381.ts.net/buzer` is a Funnel mount; backend/local/fake/tailnet routes remain unprefixed.
- [ ] Record the lasting architecture decision: typed general mirror, policy-bound checklist invariant, occurrence-key retrofit, always-reset recurrence, explicit Todoist commands, returned authoritative mutation snapshots, and durable action replay.
- [ ] Run from `backend/`: `npm test`, `npm run typecheck`, and `npm run build`. Expected: PASS.
- [ ] Run from `tools/fake-backend/`: `npm test` and `npm run build`. Expected: PASS.
- [ ] Run from `client/`: `./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain`. Expected: `BUILD SUCCESSFUL`.
- [ ] Run from repository root: `scripts/e2e-fake-backend.sh`. This is mandatory for the UI-affecting change. Expected: every `client/androidApp/src/androidTest` test passes on `pixel8Api34`, with zero failed tests.
- [ ] Run local unauthenticated security probes against the built server: `/health` returns the trimmed public shape; `/tasks/snapshot` and every new checklist route return 401 without a token; authenticated requests work; unmatched paths remain 404; rate-limit headers are present. Never print the real token in the handoff.
- [ ] If deployment is explicitly authorized, deploy through the existing runbook and recheck the real Funnel path, unauthenticated 401, trusted forwarding behavior, and direct public-IP closure. If deployment is not authorized, mark only these live checks SKIPPED; do not alter Funnel, ACL, bind, proxy, or Android cleartext settings as part of this feature.
- [ ] Perform a real Todoist test-project smoke test: create one-time and recurring commitments with children; edit metadata and schedule; check/uncheck; verify parent Done gate; complete recurring and confirm next Todoist due plus all children reset; complete externally and confirm repair reset; preserve a custom recurrence; extend one-time and verify unchanged start, +30 duration, and one audit line; move project; clear assignee.
- [ ] Perform the two-phone scenario on two Android installations when both devices are available: both schedule the same occurrence; different-child checks both survive; same-child stale edit is rejected/refreshed; two deliberate postponements count twice; a retransmitted identical action counts once; completion on one phone makes the other's next alarm refresh and dismiss or advance safely.
- [ ] Inspect the SQLite database with read-only queries: no `reset_children_on_recurrence` column; distinct occurrence keys across rollover; one WhatsApp row per occurrence; one journal row per action; stable command UUIDs on retry; no Authorization header or API token in journal JSON.
- [ ] Run `git diff --check` and scan code for unresolved markers with `rg -n 'TODO|TBD|FIXME|not implemented' backend client tools/fake-backend`. Expected: no unresolved markers in changed files.
- [ ] Use `superpowers:requesting-code-review` for the complete diff. Address findings with `superpowers:receiving-code-review`, rerun affected tests, then use `superpowers:verification-before-completion` before claiming success.
- [ ] Write the required handoff with exact commands, PASS/FAIL/SKIPPED evidence, manual checks, assumptions, known failures, plan checkbox status, and next step.
- [ ] Commit documentation and final fixes with `git commit -m "docs: record recurring checklist implementation"`.
- [ ] If the branch is pushed, run `gh run list --workflow=ci.yml --limit 1` and wait for the push-triggered CI result. Do not hand off on a red run without documenting the failure.

## Final Acceptance Checklist

- [ ] Add/Edit offers exactly the six numbered recurrence choices and no parent selector.
- [ ] Direct children support add, edit, delete, check, and uncheck, with a hard 30-child limit.
- [ ] There is no `reset_children_on_recurrence` or equivalent option; recurring completion always resets children.
- [ ] Parent Done is unavailable until all direct children are checked.
- [ ] Checking the last child does not auto-complete the parent, and Complete Forever remains out of scope.
- [ ] Child order follows current Todoist `child_order`; the product does not promise stable order after recurring reset.
- [ ] Root and child revisions allow different-child changes but reject same-object conflicts.
- [ ] Every action is scoped by stable device ID, unique/reused action ID, and expected occurrence key.
- [ ] Retried actions and Todoist commands are idempotent across process restart and two phones.
- [ ] Recurring completion, one-time close, project move, unassignment, reschedule, and extension use the correct explicit Todoist semantics.
- [ ] One-time extension preserves start, adds 30 minutes to duration, appends exactly one managed audit entry, and rejects overflow atomically.
- [ ] Recurring Extend is visibly disabled.
- [ ] Snapshot roots contain nested children; children never get independent alarms or Morning Review entries.
- [ ] Alarm identity includes occurrence key and stale alarms refresh before display.
- [ ] Mutations return authoritative snapshots and Android avoids redundant immediate GETs.
- [ ] New routes retain bearer auth, existing rate limiting, trusted-proxy behavior, unprefixed backend routing, and release HTTPS-only networking.
- [ ] Backend, fake-backend, shared JVM, Android JVM, assemble, and deterministic emulator suites pass.
