# Smart Home And True Refresh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the today-only Home timeline with a deterministic smart Home that shows owner work now, overdue, and across all future dates; exposes genuinely unassigned roots without enforcing them; and gives pull-to-refresh a real Todoist synchronization contract.

**Architecture:** The backend remains the authority for ownership and synchronized task scope. It adds a non-enforced `ROOT_UNASSIGNED` projection and an authenticated, coalesced `POST /tasks/sync` operation. Android keeps ordinary foreground refresh as mirror-only `GET /tasks/snapshot`, routes explicit pull/Retry through the forced-sync operation and `EnforcementCoordinator`, builds all Home sections in a pure clock/zone-injected state function, and renders them with one Material 3 `PullToRefreshBox` containing one `LazyColumn`. Mutations continue to use the existing action-journal and authoritative-snapshot protocol.

**Tech Stack:** Node.js/TypeScript, Fastify, SQLite, Vitest, Kotlin Multiplatform, Ktor, Android/Kotlin, Compose Material 3, JVM tests, Compose instrumentation tests, and the repository's real-backend E2E harness.

---

## Handoff instructions for the implementer

This plan is intentionally self-contained. Treat the accepted design and the decisions below as product truth; do not rely on the planning chat or invent adjacent Morning Review/readiness behavior.

### Required reading and repository checks

From the repository root, before editing:

```bash
cat AGENTS.md
cat docs/superpowers/specs/2026-04-30-todoist-commitment-companion-design.md
cat docs/superpowers/specs/2026-08-02-smart-home-morning-review-readiness-design.md
cat docs/notes/agent-workspaces.md
git worktree list
git status --short
ls -1t docs/handoffs | head -10
```

Read the recent handoffs returned by the final command. Create a dedicated implementation worktree per `AGENTS.md`; do not implement on the primary checkout or reuse this design worktree. Record the worktree/branch in its manifest. Preserve unrelated dirty files.

For framework/API uncertainty, run the repository-required Context7 flow before coding. The relevant documented APIs are AndroidX Compose Material 3 `PullToRefreshBox`, Compose `LazyColumn.stickyHeader`, Ktor client POST serialization, and Fastify async routes/injection:

```bash
npx ctx7@latest library "AndroidX Compose Material 3" "PullToRefreshBox custom indicator semantics and LazyColumn integration"
npx ctx7@latest docs "/websites/developer_android_reference_kotlin_androidx_compose_material3" "PullToRefreshBox state indicator and accessibility custom actions"
npx ctx7@latest library "Fastify" "authenticated async POST route inject testing and thrown error handling"
npx ctx7@latest docs "/fastify/fastify" "async POST route error propagation and app.inject tests"
```

In a git worktree, export the SDK locations rather than creating or committing `client/local.properties`:

```bash
export ANDROID_HOME=/Users/svehla/Library/Android/sdk
export ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk
```

Run Gradle from `client/`. Run backend npm commands from `backend/`. Run `scripts/e2e-real-backend.sh` from the repository root. Do not recreate or use `tools/fake-backend`; the historically named `BlockerActivityFakeBackendTest` runs against `backend/test/e2eServer.ts` through the real-backend script.

### Scope boundary

Implement only Change 1 from the accepted design:

- smart Home information architecture;
- unassigned-root snapshot projection;
- authoritative pull-to-refresh;
- Home actions already supported by the unified editor and completion coordinator.

Explicitly exclude:

- 08:00 automated Morning Review scheduling or a Morning Review flow redesign (Change 2);
- startup/foreground health-and-permission gating (Change 3);
- completed-today history;
- parent-task Undo/reopen;
- projected future recurring occurrences;
- calendar/date jump, search, overflow menu, Reschedule action, collaborator agendas, assignee filters, and tasks assigned to other people.

The product backlog already records Undo/reopen and accurate completed-occurrence history. Do not implement either here.

### Accepted task and ownership rules

The authoritative snapshot includes:

1. roots explicitly assigned to the device owner;
2. roots in personal/non-assignable projects implicitly owned by the owner through the existing companion-ownership rule;
3. genuinely unassigned roots in synchronized active projects where `canAssignTasks == true`;
4. eligible checklist children nested below each included root.

It excludes roots explicitly assigned to another person. `ROOT_UNASSIGNED` is presentation-only and non-enforced: it receives no strict scheduling validation, never enters Now/Overdue/Future, never schedules alarms, and never blocks Morning Review. When Edit assigns that task to the owner, existing owner schedule validation applies. When Edit assigns it to another person, the next authoritative snapshot omits it.

Do not identify unassigned work using title, creator, missing schedule, or task state. On Android, the backend contract makes this predicate sufficient and explicit:

```kotlin
val isUnassigned = !task.isAssignedToUser && task.assigneeId == null
val isOwnerTask = task.isAssignedToUser
```

### Exact Home behavior

Home contains one scroll surface in this order:

1. weekday/date title and `Updated HH:mm` derived from `snapshot.serverTime` in the injected device zone;
2. Morning Review/repair attention strip when owner invalid/pending tasks exist, followed by a separate refresh-failure Retry strip when applicable;
3. one owner Now hero;
4. compact overlapping owner Now rows;
5. owner Overdue section;
6. collapsed `Unassigned (N)` section;
7. every future owner commitment in sticky local-date groups.

The persistent FAB is Add Task only. Morning Review remains reachable only from its attention strip in this change. There is no calendar, date-jump, search, or overflow action. A transient `Back to top` pill appears once the third future-date header becomes visible (and never appears when fewer than three future groups exist), then calls `animateScrollToItem(0)` and disappears at the top.

Use a half-open interval `[start, end)`: a task is current when `now >= start && now < end`; it is overdue at `now >= end`. If multiple tasks are current, the one ending soonest is the hero and task ID breaks ties. The hero displays `+N overlapping`; every other current task follows as a compact row. The hero ID must not appear in another section.

Overdue tasks sort by end instant descending, then ID. Initially expose the three most recently missed rows, show the total count, and offer `Show all N` / `Show less`. Unassigned rows sort by `updatedAt` descending, then ID and remain hidden until expanded. Future rows sort by start instant, end instant, then ID and group by local start date. A cross-midnight task belongs to its start-date group and displays an explicit next-day end marker such as `23:30–00:30 (+1d)`.

Use these header rules so screenshots and tests are deterministic:

- today: `Today · Sunday, August 2`;
- tomorrow: `Tomorrow · Monday, August 3`;
- later in the same year: `Tuesday, August 4`;
- another year: `Saturday, January 2, 2027`.

The client shows only Todoist's one mirrored active/next recurring occurrence. It must not synthesize recurrence rows.

Actions are exact:

- hero: inline checklist toggles, `Done`, and `Edit`;
- overlapping Now and Overdue rows: `Done` and `Edit`;
- future owner rows: `Edit`;
- unassigned rows: tap/`Assign` opens the existing unified Edit screen;
- owner invalid/pending work: excluded from timed sections and surfaced through the existing repair attention path.

Disable parent `Done` whenever checklist children are incomplete. Completion remains one tap with no confirmation and no Undo. Never remove a task optimistically; render the snapshot returned/applied by `EnforcementCoordinator`.

### Pure presentation contract

Move Home state construction out of the oversized composable file into:

`client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreenState.kt`

Use a signature equivalent to:

```kotlin
fun buildHomeScreenState(
    snapshot: SnapshotViewState?,
    refreshInFlight: Boolean = false,
    refreshError: String? = null,
    actionStatusMessage: String? = null,
    now: Instant,
    zoneId: ZoneId,
    overdueExpanded: Boolean = false,
    unassignedExpanded: Boolean = false
): HomeScreenState
```

The function must be pure: no `now()`, `systemDefault()`, mutable Compose state, navigation, or callbacks inside it. It receives the complete `SnapshotViewState.tasks`, not only `startingToday`.

Use explicit section models, for example:

```kotlin
data class HomeDateGroup(val date: LocalDate, val heading: String, val rows: List<HomeAgendaRow>)

data class HomeExpandableSection(
    val totalCount: Int,
    val rows: List<HomeAgendaRow>,
    val expanded: Boolean
)

data class HomeScreenState(
    val weekdayLabel: String,
    val dateLabel: String,
    val updatedLabel: String?,
    val contentState: HomeContentState,
    val refreshInFlight: Boolean,
    val actionStatusMessage: String?,
    val reviewAttention: HomeAttention?,
    val refreshAttention: HomeAttention?,
    val currentBlock: HomeCurrentBlock?,
    val overlappingCurrentRows: List<HomeAgendaRow>,
    val overdue: HomeExpandableSection,
    val unassigned: HomeExpandableSection,
    val futureGroups: List<HomeDateGroup>,
    val allClear: Boolean
)
```

Names may be adjusted to existing conventions, but do not collapse these sections back into one stateful composable classifier.

Classification order is owner invalid/pending, overdue, current, future, hidden. First remove deleted/completed records and de-duplicate by stable task ID. Build a set from owner entries in `snapshot.invalidTasks`; exclude those IDs from all timed sections. Parse scheduled instants safely, rejecting missing/invalid due datetime or duration into the attention/repair path rather than guessing. Build Unassigned independently before owner classification. Assert through tests that all visible root IDs are unique. Keep review and refresh attention as separate nullable models so a failed pull cannot hide the only Morning Review entry point.

### Refresh contract and concurrency

Keep two refresh intents:

- automatic foreground/open: `GET /tasks/snapshot` through `refreshAndReconcile()`;
- pull gesture, accessibility action, and visible Retry: `POST /tasks/sync` through a new `forceSyncAndReconcile()`.

`POST /tasks/sync` is bearer-authenticated, accepts `{}`, awaits Todoist synchronization, and returns the newly built snapshot. A sync failure uses the existing normal error envelope and returns no partial snapshot/stale metadata.

Do not change the existing queued semantics of `TodoistSyncService.syncOnce()`: task mutations depend on it. Add a separate coalesced manual entry point:

```ts
private manualSyncInFlight: Promise<void> | null = null;

syncNow(): Promise<void> {
  if (this.manualSyncInFlight !== null) return this.manualSyncInFlight;
  const run = this.syncOnce().finally(() => {
    if (this.manualSyncInFlight === run) this.manualSyncInFlight = null;
  });
  this.manualSyncInFlight = run;
  return run;
}
```

Use the same `TodoistSyncService` instance for route actions, manual sync, and the production `TodoistPoller`. Add an optional injected synchronizer to `BuildServerOptions`; production constructs one and supplies it to both `buildServer` and `TodoistPoller`. This is required: two individually serialized service instances are not one synchronization boundary.

On Android, refresh failure must leave the already displayed `SnapshotViewState` untouched. Keep refresh errors separate from action-status text. `Refreshing` is the `PullToRefreshBox` indicator state, not a list row or status component; it must overlay the content and not shift it.

## File map

| File | Planned change |
| --- | --- |
| `backend/src/domain/taskEnforcementPolicy.ts` | Add `ROOT_UNASSIGNED` and preserve assigned-other exclusion. |
| `backend/src/services/taskHierarchyProjector.ts` | Inspect only; its existing root/child projection should work once policy admits unassigned roots. Change it only if a failing focused test proves otherwise. |
| `backend/src/services/taskSnapshotService.ts` | Project unassigned roots as valid, non-owner snapshot items. |
| Backend policy/snapshot tests | Lock explicit owner, implicit owner, unassigned, children, and assigned-other behavior. |
| `backend/src/services/todoistSyncService.ts` | Add manual-sync single-flight while preserving queued `syncOnce`. |
| `backend/src/server.ts` | Inject/share one synchronizer across HTTP and polling. |
| `backend/src/http/routes.ts` | Add authenticated `POST /tasks/sync`. |
| `backend/test/services/todoistSyncService.test.ts`, `backend/test/http/routes.test.ts` | Lock coalescing, auth, success snapshot, and failure envelope. |
| `client/shared/src/commonMain/kotlin/app/buzer/shared/api/BuzerApiClient.kt` | Add `forceSync()`. |
| `client/shared/src/commonTest/kotlin/app/buzer/shared/api/BuzerApiClientContractTest.kt` | Lock POST path/body/result. |
| `client/shared/src/commonMain/kotlin/app/buzer/shared/ui/home/HomeTimelineModels.kt` | Represent current/overdue/future/unassigned rows and Done eligibility; remove completed-row presentation. |
| `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementApi.kt` | Expose forced sync. |
| `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementCoordinator.kt` | Apply forced-sync snapshot through normal persistence/reconciliation. |
| `client/androidApp/src/main/kotlin/app/buzer/android/ui/HomeDoneActionHandler.kt` | Distinguish automatic versus forced refresh and preserve cached content on failure. |
| `client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt` | Wire foreground GET separately from pull/Retry POST and separate status/error state. |
| `client/androidApp/src/main/kotlin/app/buzer/android/ui/App.kt` | Own saveable expansion flags and route all Home row actions. |
| `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreenState.kt` | New pure state builder and formatting/classification helpers. |
| `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreen.kt` | Render one pull-to-refresh LazyColumn and Add-only FAB. |
| `client/androidApp/src/test/kotlin/app/buzer/android/ui/HomeScreenStateTest.kt` | Replace today-only tests with complete pure-state matrix. |
| `client/androidApp/src/test/kotlin/app/buzer/android/ui/HomeDoneActionHandlerTest.kt` | Cover refresh intent, serialization, cached failure, and generic completion. |
| `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/EnforcementCoordinatorTest.kt` | Cover forced-sync application/reconciliation. |
| `backend/test/fakes/e2eFixtures.ts` and test | Add multiple future dates, unassigned root, child, and assigned-other exclusion. |
| `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt` | Migrate existing Home tests and add full Change 1 instrumentation coverage. |
| `docs/handoffs/2026-08-02-smart-home-and-true-refresh.md` | Record implementation, exact verification, skips, and follow-up. |

## Task 1: Project genuinely unassigned roots without weakening owner enforcement

**Files:**
- Modify: `backend/src/domain/taskEnforcementPolicy.ts`
- Inspect: `backend/src/services/taskHierarchyProjector.ts`
- Modify: `backend/src/services/taskSnapshotService.ts`
- Modify: `backend/test/domain/taskEnforcementPolicy.test.ts`
- Modify: `backend/test/services/taskSnapshotService.test.ts`

- [x] **Step 1: Add RED policy tests.**

  Add separate cases proving:

  - an unassigned root in an active `canAssignTasks: true` project is `ROOT_UNASSIGNED` with no validation errors even without schedule/duration;
  - an unassigned personal/non-assignable project task created by the owner remains `ROOT_COMMITMENT` through existing implicit ownership and therefore remains strictly validated;
  - a root assigned to another user remains `NOT_OWNED`;
  - an eligible child under a `ROOT_UNASSIGNED` parent is `CHECKLIST_CHILD`;
  - unsupported nested/scheduled/assigned children are not admitted merely because the root is unassigned.

- [x] **Step 2: Run the policy suite and confirm RED.**

  ```bash
  npm test -- test/domain/taskEnforcementPolicy.test.ts
  ```

  Expected: new `ROOT_UNASSIGNED` expectations fail because the union and classifier do not exist.

- [x] **Step 3: Add the minimal classification.**

  Extend `TaskEnforcementClassification` with `ROOT_UNASSIGNED`. In `classifyRoot`, calculate existing `isCompanionOwned` first, then classify as unassigned only when:

  ```ts
  task.assigneeId === null &&
  !isCompanionOwned &&
  task.projectId !== null &&
  this.options.projectsById.get(task.projectId)?.canAssignTasks === true
  ```

  Restructure `classifyRoot` in this order: calculate `isCompanionOwned` and `isGenuinelyUnassigned`; return `NOT_OWNED` unless explicit owner, companion owner, or genuinely unassigned; run `findHierarchyViolation` and return `UNSUPPORTED_HIERARCHY` when needed; return `ROOT_UNASSIGNED` for the genuine-unassigned case; finally run existing strict validation for owner tasks. In `classifyChild`, accept parent classification `ROOT_COMMITMENT` or `ROOT_UNASSIGNED` before applying the existing child rules. Preserve every assigned-other and unsupported-hierarchy branch.

- [x] **Step 4: Make policy tests GREEN.**

  ```bash
  npm test -- test/domain/taskEnforcementPolicy.test.ts
  ```

- [x] **Step 5: Add RED snapshot tests.**

  Replace the existing snapshot expectation that an unassigned user-created shared-project task produces `[]`. Assert instead that it produces one root with:

  ```ts
  expect(item).toMatchObject({
    assigneeId: null,
    isAssignedToUser: false,
    validationErrors: [],
    hierarchyStatus: "valid"
  });
  ```

  Also assert its checklist children are nested and not emitted as independent roots. Retain/add a distinct assigned-other test expecting omission. Retain owner-invalid tests unchanged.

- [x] **Step 6: Run the snapshot suite and confirm RED.**

  ```bash
  npm test -- test/services/taskSnapshotService.test.ts
  ```

- [x] **Step 7: Project `ROOT_UNASSIGNED` explicitly.**

  Update the snapshot classification union. The projector already carries the policy union and nests immediate children; do not edit it unless the RED test exposes a real gap. Exclude only `NOT_OWNED` and independent `CHECKLIST_CHILD` rows. For `ROOT_UNASSIGNED` set `isAssignedToUser: false`, keep `assigneeId: null`, use empty `validationErrors`, keep `hierarchyStatus: "valid"`, preserve its mirrored nonterminal state, and nest eligible children. Do not mark a valid unassigned root invalid to bypass schedule validation; that would incorrectly place it in `SnapshotReducer.invalidTasks`.

- [x] **Step 8: Run focused backend tests GREEN.**

  ```bash
  npm test -- test/domain/taskEnforcementPolicy.test.ts test/services/taskSnapshotService.test.ts
  npm run typecheck
  ```

- [x] **Step 9: Commit the backend ownership slice.**

  ```bash
  git add backend/src/domain/taskEnforcementPolicy.ts backend/src/services/taskSnapshotService.ts backend/test/domain/taskEnforcementPolicy.test.ts backend/test/services/taskSnapshotService.test.ts
  git commit -m "feat: project unassigned task roots"
  ```

## Task 2: Add a coalesced authoritative sync endpoint

**Files:**
- Modify: `backend/src/services/todoistSyncService.ts`
- Modify: `backend/src/server.ts`
- Modify: `backend/src/http/routes.ts`
- Modify: `backend/test/services/todoistSyncService.test.ts`
- Modify: `backend/test/http/routes.test.ts`

- [x] **Step 1: Add a RED manual-sync coalescing test.**

  Keep the existing `serializes sync token reads across concurrent callers` test unchanged. Add a new test that blocks the adapter's first sync, calls `service.syncNow()` twice before releasing it, asserts both promises share one adapter invocation, releases it, awaits both, then calls `syncNow()` again and asserts a second adapter invocation occurs. This proves only overlapping manual requests coalesce and later refreshes remain possible.

- [x] **Step 2: Confirm RED.**

  ```bash
  npm test -- test/services/todoistSyncService.test.ts
  ```

  Expected: compile/test failure because `syncNow` does not exist.

- [x] **Step 3: Implement manual single-flight without changing queued sync behavior.**

  Add `manualSyncInFlight` and `syncNow()` using the exact contract in the Refresh section above. Leave `syncTail`, `syncOnce()`, and the current concurrency test intact. Use `finally` and identity checking so a failed run clears the slot and a later Retry can start.

- [x] **Step 4: Verify both concurrency contracts GREEN.**

  ```bash
  npm test -- test/services/todoistSyncService.test.ts
  ```

- [x] **Step 5: Add RED HTTP contract tests.**

  In `routes.test.ts`:

  - add `POST /tasks/sync` to the fixed protected-route/auth matrix;
  - inject `{ method: "POST", url: "/tasks/sync", headers: auth, payload: {} }` and assert 200, a snapshot body, and the adapter sync token call;
  - make `FakeTodoistAdapter.sync` throw and assert the route returns the existing 500 envelope (`code`, `message`) with no `snapshot` field;
  - issue two overlapping injections against a blocked adapter and assert one manual sync invocation if practical at route level; service-level coverage remains authoritative for the race.

- [x] **Step 6: Confirm the route suite is RED.**

  ```bash
  npm test -- test/http/routes.test.ts
  ```

- [x] **Step 7: Register the endpoint and share the synchronizer.**

  Extend `RouteServices` with `synchronizer: TodoistSyncService`. Register:

  ```ts
  app.post("/tasks/sync", async (request) => {
    emptyBody.parse(request.body ?? {});
    await services.synchronizer.syncNow();
    return services.snapshots.getSnapshot(new Date().toISOString());
  });
  ```

  Do not special-case auth; the existing non-health bearer pre-handler must protect it. Do not catch errors in the route; allow the standard error handler to emit the normal error envelope.

  Add `synchronizer?: TodoistSyncService` to `BuildServerOptions` and use it when present. In the production entry point, construct one synchronizer from the already-created repositories, pass it to `buildServer`, and pass that exact same object to `TodoistPoller`. Avoid a second `new TodoistSyncService(...)` in production.

- [x] **Step 8: Verify endpoint and type safety.**

  ```bash
  npm test -- test/http/routes.test.ts test/services/todoistSyncService.test.ts
  npm run typecheck
  npm run build
  ```

- [x] **Step 9: Commit the sync contract.**

  ```bash
  git add backend/src/services/todoistSyncService.ts backend/src/server.ts backend/src/http/routes.ts backend/test/services/todoistSyncService.test.ts backend/test/http/routes.test.ts
  git commit -m "feat: add authoritative task sync"
  ```

## Task 3: Carry forced refresh through the Android authority pipeline

**Files:**
- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/api/BuzerApiClient.kt`
- Modify: `client/shared/src/commonTest/kotlin/app/buzer/shared/api/BuzerApiClientContractTest.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementApi.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementCoordinator.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/EnforcementCoordinatorTest.kt`
- Modify: every `FakeEnforcementApi` implementation found by `rg -n 'class .*EnforcementApi|object .*EnforcementApi|: EnforcementApi' client`

- [x] **Step 1: Add a RED Ktor request-contract test.**

  In `BuzerApiClientContractTest`, use the existing MockEngine pattern to call `forceSync()`. Assert method `POST`, path `/tasks/sync`, bearer auth retained, JSON content type, body `{}`, and decoded `TasksSnapshotResponse` returned.

- [x] **Step 2: Confirm shared test RED.**

  ```bash
  ./gradlew :shared:testAndroidHostTest --tests '*BuzerApiClientContractTest'
  ```

- [x] **Step 3: Implement `BuzerApiClient.forceSync()`.**

  Post an empty `buildJsonObject {}` to `/tasks/sync`, set JSON content type, and decode the response as `TasksSnapshotResponse` with the existing `decodeBody()` path. Do not return `MutationResponse`; this operation has no action journal ID.

- [x] **Step 4: Add RED coordinator tests.**

  Extend the fake API with separate counters/results for `getSnapshot()` and `forceSync()`. Add tests proving `forceSyncAndReconcile()`:

  - calls only `forceSync`;
  - persists/applies the returned authoritative snapshot;
  - reconciles alarms through the same `applyFreshSnapshot` path as ordinary refresh;
  - propagates network failure without replacing the stored snapshot.

- [x] **Step 5: Confirm coordinator tests RED.**

  ```bash
  ./gradlew :androidApp:testDebugUnitTest --tests '*EnforcementCoordinatorTest'
  ```

- [x] **Step 6: Add the force path.**

  Extend `EnforcementApi`/`BuzerEnforcementApi` with `forceSync(): TasksSnapshotResponse`. Add `EnforcementCoordinator.forceSyncAndReconcile()` mirroring `refreshAndReconcile()` but fetching with `api.forceSync()`. Both methods must call the existing `applyFreshSnapshot`; do not duplicate storage, stale-generation, alarm, or notification logic.

- [x] **Step 7: Compile all fakes and run focused tests GREEN.**

  ```bash
  ./gradlew :shared:testAndroidHostTest --tests '*BuzerApiClientContractTest'
  ./gradlew :androidApp:testDebugUnitTest --tests '*EnforcementCoordinatorTest'
  ```

- [x] **Step 8: Commit the client authority path.**

  ```bash
  git add client/shared/src/commonMain/kotlin/app/buzer/shared/api/BuzerApiClient.kt client/shared/src/commonTest/kotlin/app/buzer/shared/api/BuzerApiClientContractTest.kt client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementApi.kt client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementCoordinator.kt client/androidApp/src/test/kotlin/app/buzer/android/enforcement/EnforcementCoordinatorTest.kt
  git commit -m "feat: reconcile forced task sync"
  ```

## Task 4: Build the complete Home model as a pure function

**Files:**
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreenState.kt`
- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/ui/home/HomeTimelineModels.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/HomeScreenStateTest.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreen.kt` only to remove the state-builder declarations moved to the new file; do not redesign rendering yet.

- [x] **Step 1: Replace today-only tests with a fixture matrix.**

  Build helpers that create `TaskSnapshotItem` fixtures with explicit ID, assignee/ownership, due instant, duration, update instant, checklist, state, recurrence, and reconciliation status. Use fixed:

  ```kotlin
  val now = Instant.parse("2026-08-02T10:00:00Z")
  val zone = ZoneId.of("Europe/Bratislava")
  ```

  Add RED tests for every item required by the accepted design:

  1. owner future tasks across today, tomorrow, later in year, and next year group/sort deterministically;
  2. hero is the current task ending soonest, ID breaks ties, overlap count is correct, and no visible ID occurs twice;
  3. current boundary uses `[start, end)` and becomes overdue exactly at end;
  4. overdue sorts newest missed first, reports total, exposes three by default, and all when expanded;
  5. explicit and implicit owner tasks enter owner sections;
  6. unassigned roots never enter owner sections, are counted while collapsed, and sort by `updatedAt` descending when expanded;
  7. assigned-other defensive fixture is not shown even if accidentally present client-side;
  8. cross-midnight task uses start-date group and `(+1d)` row time;
  9. recurring input contributes exactly one row and creates no projection;
  10. owner invalid and reconciliation-pending tasks are excluded from timed sections and produce repair attention;
  11. completed/deleted tasks and checklist children as roots are absent;
  12. hero/row Done is disabled for incomplete checklist and enabled when complete;
  13. null snapshot loading, uncached error, cached refresh error, refreshing-with-content, action status, and true all-clear states;
  14. invalid timestamp/duration input fails closed by staying out of timed sections without crashing (the backend normally includes such owner tasks in `snapshot.invalidTasks`, which supplies repair attention);
  15. header/updated labels honor the injected zone, including a date that differs from UTC.

- [x] **Step 2: Run the state suite and confirm RED.**

  ```bash
  ./gradlew :androidApp:testDebugUnitTest --tests '*HomeScreenStateTest'
  ```

- [x] **Step 3: Update shared Home presentation models.**

  Remove `Done` from `HomeAgendaRowState`; completed tasks are not a Home section. Use states `Overdue`, `Current`, `Upcoming`, and `Unassigned`. Add row data needed by rendering rather than re-reading the task in composables: `doneEnabled`, time/subline, checklist progress, pending label if applicable, and explicit action availability if that keeps rendering exhaustive. Keep `homeTaskProgress()` because blocker UI/tests also use it. Remove `ShowHealthDetail` from `HomeAttentionAction`; permission/readiness UI belongs to Change 3, not Home.

- [x] **Step 4: Implement the pure builder in the new file.**

  Follow the Pure presentation contract above. Convert due datetimes to `Instant`, then to the injected `ZoneId` for grouping/formatting. Use stable comparator chains rather than relying on snapshot order. `refreshError` with cached content creates separate `refreshAttention` with `RetryRefresh` and leaves content Ready; without cached content it creates blocking Error. Owner invalid/pending work independently creates `reviewAttention`, so both actions remain visible when both conditions exist.

  Produce `reviewAttention` from owner invalid/pending work and `refreshAttention` from cached refresh failure independently. Derive `allClear` only when Now, overlaps, Overdue, Unassigned, Future, and owner review attention are all absent. Use true all-clear copy later; never emit `No tasks for today`.

- [x] **Step 5: Make the state suite GREEN and run shared Home tests.**

  ```bash
  ./gradlew :androidApp:testDebugUnitTest --tests '*HomeScreenStateTest'
  ./gradlew :shared:testAndroidHostTest --tests '*Home*'
  ```

- [x] **Step 6: Commit the presentation model.**

  ```bash
  git add client/shared/src/commonMain/kotlin/app/buzer/shared/ui/home/HomeTimelineModels.kt client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreenState.kt client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreen.kt client/androidApp/src/test/kotlin/app/buzer/android/ui/HomeScreenStateTest.kt
  git commit -m "feat: model complete Home agenda"
  ```

## Task 5: Wire automatic versus forced refresh and generic Home completion

**Files:**
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/HomeDoneActionHandler.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/HomeDoneActionHandlerTest.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/App.kt`

- [x] **Step 1: Add RED handler tests.**

  Change the injected fetch dependency to `refreshSnapshot: suspend (forceTodoist: Boolean) -> SnapshotViewState`. Add tests proving:

  - automatic `refresh(forceTodoist = false)` forwards `false`;
  - pull/Retry `refresh(forceTodoist = true)` forwards `true`;
  - the existing mutex prevents a completion and refresh from applying snapshots out of order;
  - failed forced refresh does not call `applySnapshot`, reports refresh failure, clears in-flight state, and leaves action status independent;
  - successful Retry clears the refresh error;
  - completion works for any Home row, not only the hero, and still uses one retained `TaskActionRequest` per retry chain.

- [x] **Step 2: Confirm handler tests RED.**

  ```bash
  ./gradlew :androidApp:testDebugUnitTest --tests '*HomeDoneActionHandlerTest'
  ```

- [x] **Step 3: Refine the handler without bypassing authority.**

  Add the boolean refresh intent, a `clearRefreshFailure` callback, and keep `reportRefreshFailure`. Rename `completeCurrentTask` to `completeTask`; do not introduce a second completion implementation. On refresh start, clear only the prior refresh error; on success apply the returned view state; on failure report and retain the existing view state.

- [x] **Step 4: Wire MainActivity explicitly.**

  Use the handler fetch closure to choose:

  ```kotlin
  if (forceTodoist) coordinator.forceSyncAndReconcile()
  else coordinator.refreshAndReconcile()
  ```

  Keep `onCreate`/`onResume` on `forceTodoist = false`. Add a distinct `forceRefreshEnforcement()` used only by Home pull and Retry. Split `homeActionStatusMessage` from `homeRefreshError`; completion/reconciliation messages use the former, network refresh failures use the latter. Rename `completeCurrentTask` to `completeHomeTask` and route every eligible Now/Overdue row through it.

- [x] **Step 5: Own saveable UI expansion in `BuzerApp`.**

  Use `rememberSaveable` for `overdueExpanded` and `unassignedExpanded`. Pass them and their toggle callbacks to Home/state construction. Change the clock injection to an `Instant` provider and add an injectable/default `ZoneId` provider; do not call the clock/zone from leaf composables. Preserve Add/Edit navigation. Unassigned row selection must set `editingTaskId` and open the same `EditTaskScreen` used by owner tasks.

- [x] **Step 6: Run focused tests GREEN and compile.**

  ```bash
  ./gradlew :androidApp:testDebugUnitTest --tests '*HomeDoneActionHandlerTest' --tests '*HomeScreenStateTest'
  ./gradlew :androidApp:compileDebugKotlin
  ```

- [x] **Step 7: Commit the application wiring.**

  ```bash
  git add client/androidApp/src/main/kotlin/app/buzer/android/ui/HomeDoneActionHandler.kt client/androidApp/src/test/kotlin/app/buzer/android/ui/HomeDoneActionHandlerTest.kt client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt client/androidApp/src/main/kotlin/app/buzer/android/ui/App.kt
  git commit -m "feat: wire authoritative Home refresh"
  ```

## Task 6: Render the smart Home as one accessible continuous agenda

**Files:**
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreen.kt`
- Modify/Create: focused Home Compose instrumentation test if extracting one improves iteration; otherwise migrate the Home cases in `BlockerActivityFakeBackendTest.kt` in Task 7.

- [x] **Step 1: Add RED Compose assertions for the structural contract.**

  At minimum assert:

  - Add Task FAB exists and Morning Review/calendar/search/overflow actions do not;
  - Morning Review opens only from the `Review` attention action;
  - hero, overlap rows, Overdue controls, Unassigned controls, and dated groups render in state order;
  - `Show all N`, `Show less`, Unassigned expand/collapse, `Done`, `Edit`, and `Assign` callbacks receive the correct stable task ID;
  - incomplete parent Done is disabled;
  - true all-clear copy appears only for `allClear`;
  - pull container exposes custom accessibility action `Refresh tasks`;
  - refreshing indicator overlays existing content rather than inserting a list item;
  - `Back to top` visibility responds to `LazyListState` and scrolls to index zero.

- [x] **Step 2: Confirm the focused Compose test is RED.**

  ```bash
  ./gradlew :androidApp:compileDebugAndroidTestKotlin
  ```

  If a managed/connected device is already available, run the exact focused test class as well.

- [x] **Step 3: Replace the nested/static layout with one list.**

  In `HomeScreen`, place exactly one Material 3 `PullToRefreshBox` around exactly one weighted `LazyColumn`. The list owns title/update context, attention/status, hero, overlaps, section headers, section rows, sticky future headers, and all-clear state. Do not place a `LazyColumn` inside `BuzerScaffold` content that already scrolls independently.

  Use stable keys prefixed by section, for example `hero:<id>`, `overlap:<id>`, `overdue:<id>`, `unassigned:<id>`, `date:<ISO-date>`, `future:<id>`. State tests guarantee task uniqueness, while prefixes protect Compose keys across different item types.

- [x] **Step 4: Add pull and accessibility behavior.**

  Bind `isRefreshing = state.refreshInFlight`, `onRefresh = onRefreshTasks`, and an overlaid indicator. Add a semantics `CustomAccessibilityAction(label = "Refresh tasks")` returning `true` after invoking the same callback. Visible Retry also invokes `onRefreshTasks`. Opt in to the Material 3 experimental API if required by the pinned dependency.

- [x] **Step 5: Render exact actions and controls.**

  Preserve the hero checklist and toggle callback. Add Edit to the hero. Give overlap/overdue rows Done+Edit and respect `doneEnabled`. Future rows have Edit only. Unassigned rows expose Assign/Edit routing only and no Done. Remove the secondary Morning Review FAB from `IconFabStack`; pass `secondaryActions = emptyList()`.

  Record the rendered Lazy-list index of each future-date header. Render `Back to top` as a small transient pill above the Add FAB when `visibleItemsInfo` contains the third future-date header or any later item; do not render it if there are fewer than three future groups. Use `derivedStateOf` and a remembered coroutine scope; do not store scroll position in the pure state or expansion flags.

- [x] **Step 6: Render failures and empty state without false claims.**

  For uncached load failure use the existing blocking error with Retry. For cached failure keep every list item visible and show the concise Retry strip. Show action/reconciliation status separately. All-clear copy should be explicit, for example `All clear` and `No active or upcoming commitments.` Remove `No tasks for today`.

- [x] **Step 7: Run focused UI compilation/tests GREEN.**

  ```bash
  ./gradlew :androidApp:testDebugUnitTest --tests '*Home*'
  ./gradlew :androidApp:compileDebugAndroidTestKotlin
  ./gradlew :androidApp:assembleDebug
  ```

- [x] **Step 8: Commit the Home renderer.**

  ```bash
  git add client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreen.kt client/androidApp/src/androidTest
  git commit -m "feat: render continuous smart Home"
  ```

## Task 7: Migrate the real-backend E2E fixtures and Home instrumentation

**Files:**
- Modify: `backend/test/fakes/e2eFixtures.ts`
- Modify: `backend/test/fakes/e2eFixtures.test.ts`
- Modify if necessary: `backend/test/e2eServer.ts`
- Modify: `backend/test/e2eServer.test.ts`
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`

- [x] **Step 1: Add deterministic E2E fixture coverage.**

  Extend the dynamic Home fixture set with stable IDs/titles for:

  - two overlapping owner-current tasks with different end times;
  - four owner-overdue tasks with different end times;
  - owner-future tasks tomorrow, a later date, next year, and crossing midnight;
  - one genuinely unassigned root in a shared/assignable project with one checklist child;
  - one root assigned to another collaborator that must not appear;
  - one incomplete-checklist owner task whose Done control is disabled.

  Keep fixture times relative to the E2E server clock where tests need current/overdue semantics. Use stable Todoist IDs and unique visible titles. Update `e2eFixtures.test.ts` to prove time relationships and ownership fields before involving Android.

- [x] **Step 2: Confirm fixture tests RED then GREEN.**

  ```bash
  npm test -- test/fakes/e2eFixtures.test.ts test/e2eServer.test.ts
  ```

  `backend/test/e2eServer.ts` already records `/tasks...` POST paths and its `failActions` hook already covers `/tasks/sync`; change it only if the new endpoint is not observable through those existing facilities.

- [x] **Step 3: Migrate existing Home instrumentation; do not append contradictory tests.**

  In `BlockerActivityFakeBackendTest.kt`, update at least these existing cases:

  - `homeMorningReviewButtonOpensLiveMorningReview`: tap the attention-strip `Review` action and assert the former `Morning review` secondary FAB is absent;
  - `homeScreenShowsTimelineAgendaAndFabStack`: assert the new section order, future content, and Add-only FAB rather than the today-only agenda;
  - `homeCurrentBlockDoneCallsFakeBackend`: retain authoritative completion verification and add compact Overdue/Now completion if not covered elsewhere;
  - `homeScreenShowsRefreshFailureBanner`: force `/tasks/sync` failure, assert cached content remains, and Retry calls `/tasks/sync` again.

  Add/migrate instrumentation to cover every accepted visual interaction:

  - scroll through multiple sticky date headers and reach next-year content;
  - Back to top appears after deep scrolling and returns to Home title;
  - hero is not duplicated and overlap count/rows are visible;
  - Overdue shows three, expands all, and collapses;
  - Unassigned is initially collapsed, expands, and tapping Assign opens Edit for that task;
  - assigned-other title never appears;
  - hero checklist toggles still invoke the child action; parent Done stays disabled until complete;
  - future row Edit opens the expected task;
  - swipe-to-refresh records `POST /tasks/sync` and retains visible tasks until the response;
  - forced-sync failure retains cached tasks and exposes visible Retry;
  - the pull container semantics include custom action `Refresh tasks` and invoking it records `/tasks/sync`;
  - calendar/date-jump, search, overflow, and secondary Morning Review FAB are absent.

  Coverage completion: a focused Compose test proves the first future-date header remains pinned while scrolling within its group; stable task/action semantics prove Overdue Done/Edit routing; real-backend tests assert the exact editor title for Overdue, Unassigned, and future rows; and a one-shot test-server sync barrier proves cached Home content remains visible while the forced-sync response is held.

- [x] **Step 4: Run the scoped real-backend E2E class.**

  From repository root:

  ```bash
  ANDROID_TEST_CLASS=app.buzer.android.blockers.BlockerActivityFakeBackendTest scripts/e2e-real-backend.sh
  ```

  Expected: PASS. This is the first runtime UI authority; JVM success is insufficient. The final exact-matcher correction run passed 28/28 with zero skipped and zero failed. Step 3 remains open for the explicitly enumerated coverage gaps above.

- [x] **Step 5: Commit fixtures and migrated instrumentation.**

  ```bash
  git add backend/test/fakes/e2eFixtures.ts backend/test/fakes/e2eFixtures.test.ts backend/test/e2eServer.ts backend/test/e2eServer.test.ts client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt
  git commit -m "test: verify smart Home end to end"
  ```

## Task 8: Full regression verification and handoff

**Files:**
- Create: `docs/handoffs/2026-08-02-smart-home-and-true-refresh.md`
- Modify: this plan's checkboxes as tasks complete

- [x] **Step 1: Run backend verification.**

  From `backend/`:

  ```bash
  npm test
  npm run typecheck
  npm run build
  ```

  Expected: all PASS.

- [x] **Step 2: Run complete JVM/build verification.**

  From `client/` with Android SDK variables exported:

  ```bash
  ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug
  ```

  Expected: all PASS.

- [ ] **Step 3: Run the full, unfiltered real-backend Android E2E suite.**

  From repository root:

  ```bash
  scripts/e2e-real-backend.sh
  ```

  Expected: PASS. Do not claim Change 1 complete based only on the scoped Home class.

  Deferred verification note (2026-08-02): one unfiltered run executed all 69 tests and
  reported 63 passed / 6 failed in legacy Add/Edit task helpers. The failures were traced
  to ambiguous nested scroll surfaces, off-screen Home rows, an ambiguous substring dialog
  matcher, and an unclosed IME. The corrected Add class subsequently passed 11/11; both Edit
  tests passed in the corrected combined class run. Per user direction, do not spend another
  five-minute unfiltered run in this session; this checkbox remains open until that rerun passes.

- [x] **Step 4: Run repository hygiene checks.**

  ```bash
  git diff --check
  git status --short
  git log --oneline --decorate -10
  ```

  Inspect the diff for accidental Change 2/3 implementation, fake-backend resurrection, generated build output, secrets, and unrelated files.

- [x] **Step 5: Write the required handoff.**

  Create `docs/handoffs/2026-08-02-smart-home-and-true-refresh.md` containing:

  - this plan name and final checkbox status;
  - worktree and branch;
  - files changed by task;
  - assumptions and scope exclusions;
  - every command run with PASS/FAIL/SKIPPED and concise evidence;
  - manual checks/screenshots performed;
  - known failures or skipped checks;
  - confirmation that automatic foreground refresh remains GET and explicit pull/Retry is POST sync;
  - confirmation that assigned-other roots remain excluded;
  - next recommended step: separately plan Change 2, not implement it in this branch.

  Use the repository-required evidence format:

  ```text
  Verification:
  - Command: <exact command>
  - Result: PASS/FAIL/SKIPPED
  - Evidence: <important output or reason>
  ```

- [x] **Step 6: Commit documentation/progress.**

  ```bash
  git add docs/superpowers/plans/2026-08-02-smart-home-and-true-refresh.md docs/handoffs/2026-08-02-smart-home-and-true-refresh.md
  git commit -m "docs: hand off smart Home delivery"
  ```

- [x] **Step 7: If the branch is pushed, check push-triggered CI before handoff.**

  ```bash
  gh run list --workflow=ci.yml --limit 1
  ```

  Do not report completion over a red run. Record the run URL/status in the handoff. If the branch is not pushed, state that CI was not triggered rather than implying it passed.

## Implementation completion criteria

Change 1 is complete only when all of the following are true:

- backend policy/snapshot tests prove owner, implicit-owner, unassigned, child, and assigned-other boundaries;
- concurrent manual sync calls coalesce and all production sync callers share one serialization boundary;
- pull/Retry force Todoist sync while open/resume stay mirror-only;
- forced-sync failure preserves cached Home content;
- the pure Home matrix covers every date, ordering, boundary, invalid, pending, checklist, and empty/error case listed in the accepted design;
- Home renders one pull-to-refresh LazyColumn with no duplicate task IDs and no obsolete global controls;
- migrated instrumentation passes against the real local backend;
- full backend, client JVM/build, and unfiltered real-backend E2E verification pass;
- the implementation handoff documents evidence and any skipped checks;
- no Change 2 or Change 3 behavior has been mixed into the branch.
