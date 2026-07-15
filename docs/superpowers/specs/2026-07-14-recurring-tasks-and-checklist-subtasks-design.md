# Recurring Tasks And Checklist Sub-Tasks Design

Date: 2026-07-14 (updated 2026-07-15)
Status: approved design, pending implementation plan

## Goal

Add Todoist-native recurrence and direct checklist children to Buzer commitments while preserving
correct enforcement across recurring occurrences and multiple Android phones.

The MVP invariant is:

> A Buzer commitment is always a root Todoist task. It may have up to 30 direct, schedule-free
> checklist children. Children cannot have due dates, durations, recurrence, assignments, or
> descendants.

The persistence and synchronization layers must still mirror the relevant task, hierarchy,
schedule, duration, and recurrence facts for every Todoist task. The invariant belongs in an
enforcement policy, not in the task schema. A later feature may therefore promote eligible child
tasks to independently scheduled commitments without rewriting the Todoist mirror.

This design amends the 2026-04-30 product spec for hierarchy and recurring completion. It retains
the Todoist-native scheduling decision from
`docs/decisions/2026-07-05-todoist-native-due-semantics.md`: `due.datetime` is the start of a block
and the enforcement deadline is `due + duration`.

## Product Decisions

### Commitment and checklist model

- Only a root Todoist task can be a Buzer commitment in the MVP.
- A root can have zero to 30 direct checklist children.
- A checklist child has an editable title and optional details, plus checked/unchecked state.
- Buzer can add, edit, delete, check, and uncheck direct checklist children.
- The editor has no parent-task selector and cannot create or edit grandchildren.
- New children are appended. Manual child reordering is not part of the MVP.
- Buzer displays the current `child_order` returned by Todoist. Todoist does not guarantee that
  reset children retain their previous order, so the MVP does not promise stable order after a
  recurring rollover.
- Checking the last child does not complete the parent automatically. Parent completion remains an
  explicit action.
- `Done` is disabled until every direct child is checked. A parent with no children can be
  completed normally.

The 30-child limit is imposed by Todoist's recurring `reset_subtasks` behavior. It applies to all
Buzer commitments, including one-time commitments, so a task can become recurring without a
surprising checklist migration.

### Recurrence choices

The Add Task and Edit Task forms offer these choices:

1. Never
2. Daily
3. Weekdays
4. Weekly
5. Monthly
6. Yearly

All supported recurrence is fixed-calendar recurrence anchored to the selected start's local date,
time, and IANA time zone. It is not completion-relative recurrence. The presets repeat indefinitely;
the MVP has no end date, occurrence count, or custom recurrence text entry.

The backend, rather than Android, translates a preset into the Todoist recurrence string and sends
it with `due_lang = en` and the task's time zone. The mappings are conceptually:

- Daily: every day at the selected local time
- Weekdays: every weekday at the selected local time
- Weekly: every week on the selected weekday and local time
- Monthly: every month on the selected day-of-month and local time
- Yearly: every year on the selected month, day, and local time

Todoist remains responsible for daylight-saving transitions and calendar edge cases such as a
monthly task anchored near the end of a month.

A new recurring commitment uses the creating device's IANA time zone. Editing an existing recurring
commitment preserves the Todoist due time zone and interprets the displayed start in that zone. The
MVP does not include a time-zone chooser.

If Todoist contains a recurrence Buzer cannot map exactly to a preset, the editor displays
`Custom Todoist recurrence` with Todoist's human-readable rule. The custom rule is read-only and is
preserved during title, details, project, assignee, duration, or checklist-only changes. Changing
the start or recurrence requires replacing it with one of the supported presets or `Never`.
Selecting `Never` removes recurrence but keeps the selected current start as a one-time schedule.

### Completion and child reset

Recurring commitments always reset their direct checklist children for the next occurrence. There
is no `reset_children_on_recurrence` setting in the database, API, or UI.

When `Done` is accepted:

- A one-time root is completed through Todoist's normal close operation.
- A recurring root is completed through Todoist's recurring-completion command with
  `reset_subtasks = true`.
- The backend then synchronizes the affected Todoist task tree immediately and returns the refreshed
  snapshot.
- Todoist is authoritative for the next due instance and the identities and checked state of reset
  children. Buzer does not calculate or pre-create the next occurrence.

If Todoist polling discovers that a recurring root was advanced outside Buzer and any direct child
remained checked, the occurrence reconciler unchecks those direct children through Todoist before
activating the new occurrence. The next occurrence remains reconciliation-pending, with no new
alarms, until Todoist confirms the reset. This makes the always-reset rule independent of the reset
choice used in another Todoist client.

Completion must refresh the root and children from Todoist before mutation, confirm that the
client's occurrence is still current, confirm that every direct child is checked, and reject an
unsupported hierarchy. A child completed in another Todoist client therefore satisfies the gate
after synchronization.

Todoist's `Complete forever` operation is out of scope. A user can remove recurrence in Edit Task
and then complete the resulting one-time commitment.

### Extend and reschedule

`Extend 30` is unavailable for recurring commitments in the MVP. For a one-time commitment it keeps
Todoist `due.datetime` unchanged and increases Todoist duration by exactly 30 minutes. Because due
is the block start, this extends the block's end instead of postponing the entire block.

The same Todoist update also appends an audit entry to a backend-managed section at the bottom of
the task description:

```text
--- Buzer activity (managed) ---
2026-07-15 14:05 Europe/Bratislava — Extended +30 min (60 → 90 min)
```

The delimiter is exact and the managed section runs to the end of the description. Buzer exposes
the text above it as the editable user description and renders the activity section read-only.
Existing descriptions without the delimiter are entirely user-owned. If another Todoist client
removes or corrupts the delimiter, Buzer preserves all existing text and starts a fresh managed
section on the next extension rather than deleting or guessing at content. Audit timestamps use the
task's Todoist time zone and fall back to UTC. The MVP records extension events only; other task
actions do not add activity entries.

An accepted extension remains in the same occurrence, becomes or remains `started`, increments the
extension count, and replaces the deadline alarm using the longer duration. Durable action and
Todoist command IDs ensure that retries cannot add 60 minutes or duplicate the audit entry.

Recurring commitments still support `Reschedule`, but the operation must preserve their recurrence
preset and time zone. A custom Todoist recurrence cannot be rescheduled until the user replaces it
with a supported preset or `Never` in Edit Task.

## Architecture

The feature uses an occurrence-key retrofit rather than a full occurrence-history ledger. The
current `commitment_states` row remains the live enforcement state for a task, but it gains an
opaque occurrence identity and a due-instance anchor. A durable action journal supplies retry and
cross-device idempotency.

### `TodoistTaskMirror`

The mirror records Todoist facts uniformly for roots and descendants, including:

- stable Todoist task ID
- title and description
- project, assignee, and creator
- `parent_id` and `child_order`
- completion and deletion state
- due date/datetime, recurrence string, language, time zone, and `is_recurring`
- duration amount and unit, normalized to minutes for enforcement where supported
- raw JSON for forward compatibility and diagnostics
- a monotonically changing mirror revision

Hierarchy and recurrence must no longer be inferred from raw JSON at product boundaries. Typed
fields are the source for policy, projection, and API responses; raw JSON is retained as evidence.

### `TaskDescriptionCodec`

The Todoist mirror stores the complete description exactly as synchronized. A focused codec splits
it into the editable user description and ordered, read-only Buzer activity entries using the exact
managed delimiter. It reconstructs the full Todoist description for mutations without rewriting
user text. A missing or malformed delimiter is handled conservatively by treating all existing text
as user-owned and appending a fresh managed section only when a new audit event is required.

### `TaskEnforcementPolicy`

This component classifies mirrored tasks without changing their stored shape:

- `ROOT_COMMITMENT`: a root task that satisfies normal Buzer assignment/schedule/duration rules
  and the checklist invariant
- `ROOT_CANDIDATE_INVALID`: a root owned by the configured user but missing required root fields,
  preserving the existing Morning Review repair behavior
- `CHECKLIST_CHILD`: an eligible direct, schedule-free child of a root commitment
- `UNSUPPORTED_HIERARCHY`: a root tree with scheduled, recurring, assigned, or duration-bearing
  children; grandchildren; or more than 30 direct children
- `NOT_OWNED`: existing tasks outside the configured commitment ownership rules

Only `ROOT_COMMITMENT` participates in normal task-specific alarms, blockers, and WhatsApp
accountability. Invalid and unsupported roots appear in Morning Review. Checklist children never
appear as independent Morning Review violations or independent alarms. Hierarchy violations are
attached to the root and disable normal enforcement and `Done` until repaired in Todoist or Buzer.

This is the future extension point. Supporting scheduled child commitments later means adding a
new policy classification and projection behavior; it does not require a task-table migration.

### `TaskHierarchyProjector`

This component converts flat mirrored tasks into the bounded MVP view:

- root commitment
- ordered list of direct checklist children
- completed and total counts
- hierarchy validity and a user-facing reason when unsupported

It must use stable IDs and must detect, rather than silently discard, grandchildren and excess or
scheduled children. It contains no alarm or recurrence mutation logic.

### `OccurrenceReconciler`

An occurrence key is an opaque backend-generated ID for the currently enforceable schedule
generation. It is not derived solely from the due datetime, because the same datetime can be reused
after edits or conflicts.

The commitment state also stores the Todoist due-instance anchor and the normalized recurrence
facts used to establish that key. The reconciler rotates the key and resets per-occurrence state
when:

- Todoist advances a recurring root after completion;
- Todoist polling observes an externally changed due instance or recurrence rule;
- an editor-driven Buzer edit or reschedule changes start, duration, or recurrence in a way that
  replans the commitment; or
- an existing root first receives occurrence-aware state during migration.

For an externally advanced recurring root, rotation is not considered complete until all eligible
direct children are confirmed unchecked. Automated child-reset commands use the same durable
journal and reconciliation rules as user actions.

Title, details, project metadata, and checklist-only mutations change task or child revisions but
do not rotate the occurrence key. A schedule-generation change resets lifecycle state to planned,
clears start dismissals, extensions, snooze data, and escalation state, and increments the existing
schedule version.

`Extend 30` is the deliberate exception to editor-driven duration changes: it keeps the occurrence
key and lifecycle history, increments the deadline-extension count, and increments the schedule
version so Android replaces the deadline alarm. It does not clear start dismissals, snooze data, or
accountability state.

Per-occurrence data includes:

- lifecycle state and `started_at`
- start-dismiss count
- deadline-extension count
- last snooze
- escalation-sent state
- schedule version

WhatsApp uniqueness changes from one escalation per task to one escalation per
`(task_id, occurrence_key)`.

### `TodoistChecklistService`

This service is the only product-level component that mutates checklist children. It translates
intent into Todoist commands:

- add: create a task with the root's project and `parent_id`, without assignment, due, duration, or
  recurrence
- edit: update title and optional details only
- delete: delete the child
- check: close the child
- uncheck: uncomplete the child

It validates that both root and child still belong to the expected current tree. It does not accept
arbitrary parent IDs, schedules, assignments, or recursive task payloads.

### `TodoistMutationGateway`

All product actions use explicit Todoist commands rather than a generic update that happens to
contain every field:

- regular completion uses `item_close`;
- recurring completion uses `item_update_date_complete` with `reset_subtasks = true`;
- project changes use `item_move`;
- ordinary field, duration, recurrence, and description changes use `item_update`;
- clearing an assignment sends an explicit null/unassigned value; and
- child close, uncomplete, delete, and creation use their dedicated commands.

When a root edit changes project and other fields, the coordinator persists separate command UUIDs,
runs `item_move` and `item_update` as explicit steps, then synchronizes the root tree. The refreshed
tree must confirm that direct children remain under the root and share its project. This gateway is
an adapter boundary, not a general backend refactor.

### `TaskActionCoordinator`

Task and checklist mutations pass through a coordinator that serializes actions for a root task,
checks idempotency, validates revisions and occurrence identity, invokes Todoist, and reconciles the
mirror. SQLite transactions protect local transitions; a per-root in-process lock prevents two
Todoist commands for the same tree from racing in the single backend process.

The durable action journal records:

- globally unique `action_id`
- stable `device_id`
- root task ID and expected occurrence key
- action type and request fingerprint
- processing, completed, failed, or reconciliation-pending status
- durable response or error payload
- timestamps

It also persists the Todoist Sync command UUIDs generated for the action, including one UUID per
step of a multi-command create or edit. Retrying after a timeout or process restart reuses those
UUIDs and reconciles Todoist state before deciding whether more work is needed; it never generates
fresh command UUIDs for the same recorded action.

Reusing an `action_id` with the same request returns the stored result. Reusing it with a different
request is rejected. This prevents an HTTP retry or process restart from applying the same user
intent twice.

## Persistence Changes

Guarded SQLite migrations extend the current schema rather than replacing it.

### Todoist mirror

`todoist_tasks` gains typed hierarchy and recurrence columns, including `parent_id`,
`child_order`, due recurrence string/language/time zone/flag, and a mirror revision. Duration facts
remain mirrored for every task even though policy rejects them on checklist children.

After migration, the backend performs a full Todoist synchronization before serving the new
snapshot contract so classifications do not depend on partially backfilled raw JSON.

### Commitment state

`commitment_states` gains:

- `occurrence_key`
- `occurrence_due_datetime`
- `occurrence_recurrence_fingerprint`, derived from Todoist's recurring flag, rule, language, and
  time zone
- reconciliation status for an uncertain Todoist mutation

Existing active commitments receive a fresh key during migration. Existing escalation rows for an
active task are backfilled to that same key so migration cannot resend an escalation for the
current one-time occurrence. Historical or already-terminal rows receive a legacy occurrence key.

### Accountability and action journal

`whatsapp_escalations` gains `occurrence_key`; its unique index becomes
`(task_id, occurrence_key)`. A new durable action-journal table enforces unique `action_id` and
stores replayable results.

No reset preference column is added anywhere.

## API And Data Flow

### Snapshot contract

The main task array contains root projections only: valid commitments plus invalid or unsupported
root candidates that require Morning Review. Direct children are nested rather than duplicated at
the top level. Each root contains the existing scheduling and enforcement fields plus:

- opaque `occurrenceKey`
- opaque `taskRevision`
- editable `description` containing only the user-owned portion
- ordered, read-only `activityLogEntries` parsed from the managed Todoist description section
- recurrence kind, supported preset when recognized, display label, and time zone
- nested `checklistItems`
- `checkedCount` and `totalCount`
- hierarchy validity and violation reason
- reconciliation-pending state when the next occurrence is not yet confirmed

Each checklist item includes stable ID, title, optional details, checked state, current order, and
an opaque child revision. Children are not duplicated as top-level tasks.

`taskRevision` is the mirror revision of the root task's own Todoist fields. Checklist progress is
derived and does not change that root revision; each child has its own revision. This separation is
what allows two phones to change different children without manufacturing a root-field conflict.

The backend snapshot remains authoritative. Android may cache it for alarm delivery and offline
display, but it does not invent a new occurrence or child state.

### Create

Add Task sends the existing root fields plus:

- one supported recurrence preset or `Never`
- the creating device's IANA time zone
- zero to 30 child drafts containing title and optional details
- `actionId` and `deviceId`

The backend creates the root first, then creates children under its returned Todoist ID. The
response reports root creation and each child result explicitly. A partial child failure does not
pretend the entire checklist succeeded: the root and successful children are synchronized, failed
drafts remain visible for retry, and retrying with the same action ID replays the recorded result.
A retry of an individual failed draft uses a new action ID.

### Edit

Edit Task sends an expected task revision for any changed root fields, an expected occurrence key,
and explicit child mutations. It does not replace the entire checklist from a submitted array.

Explicit add/edit/delete/check/uncheck intents prevent a stale phone from deleting a child that was
added by another phone. Root-field conflicts return `409 stale_task`. Child mutations carry the
child's expected revision, so changes to different children can be serialized and accepted while
conflicting changes to the same child return a stale-child result.

The backend returns per-child outcomes for a mixed batch. Android refreshes afterward, retains any
failed local draft, and shows which changes need retry.

Root edits are classified before mutation. Metadata-only changes preserve lifecycle and occurrence
state. Editor-driven start, duration, or recurrence changes replan the commitment and rotate its
occurrence key. When the project changes, `item_move` is a separate durable step from `item_update`;
the follow-up synchronization verifies the root and direct children in their authoritative Todoist
location. The description codec prevents ordinary editor saves from overwriting the managed
activity section.

Metadata preservation applies while the task remains owned by the configured user. If a project or
assignee edit changes ownership classification, `TaskEnforcementPolicy` wins: leaving ownership
cancels Buzer enforcement, while returning to ownership creates a fresh planned occurrence.

### Focused checklist actions

Current-block and blocker surfaces use focused child check/uncheck endpoints rather than submitting
the full editor payload. Each request includes:

- unique `actionId`
- stable `deviceId`
- `expectedOccurrenceKey`
- child ID and expected child revision
- requested checked state

Checking an already-checked child or unchecking an already-unchecked child succeeds idempotently.

### Enforcement actions

Start, postpone, done, reschedule, and applicable one-time extension actions include `actionId`,
`deviceId`, and `expectedOccurrenceKey`. Extend and edit also include the current task revision;
child mutations include the relevant child revision.

Postponements with distinct action IDs are real distinct user actions and increment the one global
occurrence counter, regardless of which phone sent them. A retransmission of one action ID does not
increment again.

### Done flow

The backend handles `Done` in this order:

1. Reserve or replay the durable action-journal entry and serialize the root.
2. Synchronize the relevant Todoist root and children.
3. Validate the expected occurrence key, hierarchy invariant, and all-children-checked gate.
4. Issue normal close for a one-time root, or recurring completion with
   `reset_subtasks = true` for a recurring root.
5. Mark the old occurrence terminal locally so no further old-occurrence action can run.
6. Perform an immediate Todoist synchronization.
7. For recurrence, create the next occurrence key from Todoist's returned next due instance and
   reset per-occurrence enforcement state.
8. Return the refreshed snapshot and durable action result.

The fixed `reset_subtasks = true` rule applies even when the root currently has no children.

### Extend flow

The backend handles one-time `Extend 30` in this order:

1. Reserve or replay the durable action-journal entry and serialize the root.
2. Synchronize the root and validate its task revision, expected occurrence key, non-recurring
   state, current due, duration, and description.
3. Compute `duration + 30` and append one timestamped activity entry through the description codec.
4. Issue one `item_update` containing the new duration and full reconstructed description while
   omitting due, so the start cannot move.
5. Synchronize immediately and verify the unchanged due, new duration, and audit entry.
6. Keep the occurrence key, set or retain `started`, increment the extension counter and schedule
   version, and return the refreshed snapshot.

The new schedule version causes Android to replace the same occurrence's deadline alarm. Local
success and counters are not finalized until Todoist's authoritative state is confirmed.

### Contract documentation

The implementation updates `docs/notes/api-contract.md` to cover the current description and full
edit behavior plus recurrence, nested checklist items, occurrence keys, task and child revisions,
action and device IDs, stale responses, activity-log fields, reconciliation-pending states, and the
current deployed base-path behavior.

## Android Behavior

### Commitment editor

The shared Add/Edit/Repair editor gains:

- `Repeats` selector with the six supported product choices
- recurrence summary and next-occurrence context
- `Checklist` section with add, edit, delete, check, and uncheck controls
- compact child fields for title and optional details
- progress and the 30-item limit
- editable user-description content plus a separate read-only Buzer activity section

For unsupported custom Todoist recurrence, the selector shows a locked custom state. Non-schedule
and checklist edits preserve it. Choosing a supported replacement unlocks schedule editing.

Morning Review continues to validate root projections only. An invalid hierarchy appears as a root
repair issue; children never produce separate repair cards.

### Home

- An agenda row shows recurrence and checklist progress, for example `Daily · 2/4`.
- It does not expand into interactive children.
- Tapping the agenda row opens Edit Task.
- The current-block card exposes the interactive checklist.
- The current-block `Done` action is disabled until all children are checked.

### Blockers and overlay

- The start blocker shows checklist progress in a collapsed section that the user can expand and
  check inline.
- The deadline blocker shows the checklist expanded and keeps `Done` disabled until complete.
- The compact overlay shows checklist progress and the next unchecked item, with an action to open
  the full blocker for checklist interaction.
- `Extend 30` remains visible but disabled for recurring commitments, with concise explanatory
  text.

Every Android alarm and blocker intent is identified by
`(taskId, occurrenceKey, alarmKind)`. Before presenting, Android refreshes the backend and confirms
that the occurrence still exists and the alarm is still applicable. A stale alarm is cancelled
without displaying a blocker.

## Cross-Device Synchronization

Every registered Android phone schedules enforcement alarms. There is no elected primary phone in
the MVP.

The backend is the single authority for occurrences, counters, checklist state, and Todoist
mutations. Each installation has a stable `deviceId`; each user intent has a unique `actionId` that
is reused only while retrying that same intent.

Consequences for two-phone use:

- Repeating Start, Done, check, or uncheck from another phone is idempotent or returns a clear stale
  result rather than duplicating Todoist mutations.
- Two postponements intentionally performed on different phones count as two postponements for the
  same occurrence and can trigger accountability.
- A conflicting root edit receives `409 stale_task`; Android refreshes and preserves the local
  draft so the user can reapply it.
- Independent mutations to different children may both succeed. Conflicting mutations to the same
  child use its revision to detect staleness.
- An action for a previous occurrence receives `409 stale_occurrence` and the current snapshot.
- Retrying one extension action cannot increase duration or append its audit entry twice; a second
  intentional extension with a new action ID adds another 30 minutes and another entry.
- A visible blocker refreshes and dismisses when another phone completes, reschedules, starts, or
  otherwise makes it obsolete.

FCM remains out of scope. An idle phone may retain stale UI until its next normal refresh, but an
alarm always refreshes before display. This makes cross-device enforcement safe even though it is
eventually synchronized rather than instant while idle.

## Failure Handling

### Stale clients and retries

- `409 stale_task`, `stale_child`, and `stale_occurrence` responses include enough current snapshot
  context to refresh without discarding an unsaved editor draft.
- Network retries reuse the same action ID and receive the durable recorded response.
- A request that cannot reach Todoist never reports the Todoist mutation as successful.
- Cached alarms may still fire offline, but the blocker shows a retry/offline state and does not
  fabricate completion, checklist, or recurrence progress.
- If the reconstructed description plus a new audit entry would exceed Todoist's accepted
  description limit, extension returns `422 activity_log_full` without changing duration. Android
  asks the user to shorten the description; Buzer never extends without the required audit entry.

### Todoist success followed by synchronization failure

A Todoist mutation cannot participate in the SQLite transaction. Every action therefore persists
its Todoist command UUIDs before sending them. If a command succeeds or times out ambiguously but
the follow-up synchronization fails:

- the action journal and affected commitment become `reconciliation_pending`;
- Android reports the known pending state instead of claiming unconfirmed success;
- the backend synchronizes Todoist before deciding whether work remains; and
- retrying the user action reuses the recorded action and command UUIDs.

For recurring completion specifically:

- the old occurrence is terminal and rejects further actions;
- Android shows `Completed; syncing next occurrence` and schedules no speculative next alarms;
- the backend retries Todoist synchronization until it observes either the advanced occurrence or
  an authoritative terminal state;
- the reconciler then creates the next occurrence key and returns to normal scheduling.

The same action ID never sends the recurring-completion command twice.

For one-time extension, Android shows `Extended; syncing deadline` and schedules no new deadline
until the synchronized duration and audit entry are confirmed. The same action cannot append a
second entry or extend again.

For a project edit, `item_move` and `item_update` are separately journaled steps. If move succeeds
and field update fails, synchronization exposes the actual moved project and retry executes only
the unfinished update command. The refreshed hierarchy must confirm that the root and direct
children still form one same-project tree; otherwise the root remains pending or unsupported rather
than being overwritten locally. Revision validation rejects a concurrent external edit.

### Partial checklist mutation

Batch editor operations return per-child success or failure. Successful Todoist mutations are
synchronized; failed drafts remain retryable. The backend does not roll back a successful external
Todoist child mutation by issuing compensating commands.

### Unsupported Todoist hierarchy

If synchronization encounters a scheduled, recurring, assigned, duration-bearing, or nested child,
or more than 30 direct children, the root is marked unsupported. Normal `Done` and enforcement are
disabled, the reason is exposed in Morning Review and Edit Task, and Buzer does not silently flatten
or mutate the unexpected structure.

The owner expects Todoist tasks to follow the MVP invariant, so recovery tooling beyond clear
diagnosis and ordinary child editing is out of scope.

## Testing Strategy

The implementation plan must migrate existing tests that assert task shape, edit behavior,
completion, blocker actions, or alarm identity. This explicitly includes
`client/androidApp/src/androidTest`; instrumentation migration is part of the feature, not a later
follow-up.

### Backend

- guarded migrations and typed mirror round trips for hierarchy and all recurrence due fields
- policy classification for valid roots, checklist children, and every unsupported invariant
- hierarchy projection, ordering, progress, and stable revisions
- preset-to-Todoist mapping, custom recurrence recognition, preservation, and replacement
- exact Todoist command selection for `item_close`, `item_update_date_complete` with
  `reset_subtasks = true`, `item_move`, `item_update`, explicit unassignment, and child commands
- recurring rollover rotates occurrence key and resets every per-occurrence field
- editor-driven schedule edits rotate occurrence key; metadata and checklist-only edits do not
- one-time extension leaves due and occurrence unchanged, adds 30 minutes of duration, retains or
  enters `started`, increments the counter, and creates exactly one managed audit entry
- description codec round trips user text and activity entries and preserves malformed-marker text
- description-limit rejection leaves both duration and audit content unchanged
- WhatsApp uniqueness per `(task_id, occurrence_key)`
- idempotent action journal replay, request-fingerprint mismatch, and restart recovery
- same-root serialization and two-device race cases
- partial project-move recovery, unfinished-command retry, and post-move hierarchy verification
- partial child mutation results
- reconciliation-pending recovery after Todoist success and sync failure
- externally advanced recurrence resets any still-checked direct children before new alarms activate
- unsupported hierarchy disables normal completion and enforcement

### Shared Kotlin

- snapshot serialization with nested checklist and recurrence data
- reducer replacement across task and occurrence revisions
- alarm plans for one-time and recurring roots only
- stale occurrence removal and no alarms while reconciliation is pending
- progress, Done eligibility, and custom-recurrence UI models
- user-description and read-only activity-log projection
- existing task snapshot and diagnostics fixtures migrated to the new contract

### Android JVM and instrumentation

- Add Task preset selection and child draft CRUD
- Edit Task hydration, explicit child mutations, and stale-draft preservation
- custom Todoist recurrence preservation and required replacement before schedule editing
- editable user description with a separate read-only activity section
- Home row progress and tap-to-edit
- current-block and blocker inline checking
- Done disabled until all children are checked
- recurring blockers render `Extend 30` disabled with its explanation
- stale local alarm refresh/cancellation
- two-phone-equivalent API responses dismiss obsolete visible blockers
- Morning Review reports only the invalid root for hierarchy violations

The fake backend must model nested checklist snapshots, task and child revisions, action IDs,
activity entries, one-time extension, project-move cut points, occurrence rollover, stale conflicts,
and reconciliation-pending responses. The deterministic `scripts/e2e-fake-backend.sh` lane is
required because the feature changes user-visible client behavior.

### Manual integration

Use a disposable real Todoist project to verify:

- each supported preset appears correctly in Todoist and advances on its fixed calendar schedule
- a recurring parent with checked and unchecked children cannot be completed early
- completing it resets direct children and Buzer receives the new Todoist due instance
- a custom Todoist recurrence is preserved by unrelated edits
- Todoist-side child checks are reflected after refresh
- one-time extension keeps the Todoist start fixed, lengthens the Todoist calendar block, and
  appends one readable activity entry per intentional extension
- moving a root commitment keeps its direct checklist children attached in the destination project
- two Android phones schedule the same occurrence safely; action replay is idempotent; and a blocker
  on one phone disappears after the other phone completes or reschedules

## Implementation Boundaries

In scope:

- full typed Todoist hierarchy and recurrence mirroring
- root plus direct checklist projection
- supported recurrence preset creation and replacement
- occurrence-aware lifecycle, accountability, alarms, actions, and completion
- child CRUD and check/uncheck in the specified Android surfaces
- durable idempotency and revision conflicts for safe multi-phone use
- Todoist command-correct task mutation, including project moves and explicit unassignment
- corrected one-time extension with a managed description audit log
- synchronization recovery for partial and ambiguous Todoist mutations
- alignment of `docs/notes/api-contract.md` with the implemented contract

Out of scope:

- scheduled or recurring children as independent commitments
- grandchildren or recursive checklist editing
- parent selection, reparenting, or manual child reordering
- custom recurrence text entry, completion-relative recurrence, end dates, or occurrence counts
- Todoist `Complete forever`
- recurring `Extend 30`
- managed activity entries for actions other than one-time extension
- FCM or instant idle-device invalidation
- an occurrence-history/product analytics ledger

The future scheduled-child feature should extend `TaskEnforcementPolicy` and
`TaskHierarchyProjector`, reuse the uniform mirror and occurrence reconciler, and add the needed UI
and alarm rules. It should not require changing how Todoist task facts are persisted.
