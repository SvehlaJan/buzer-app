# Occurrence-keyed recurring checklists

Date: 2026-07-16

## Decision

The backend exposes a typed mirror of the Todoist root task and its direct
children. A root snapshot owns its checklist; children are never independent
tasks, alarms, or Morning Review entries.

Checklist completion is a policy-bound invariant: parent Done is unavailable
until every direct child is checked, but checking the final child never
implicitly completes the root. Recurring Done always advances to a new
occurrence and resets every direct child unchecked. There is intentionally no
`reset_children_on_recurrence` option and no Complete Forever behavior.

Every mutating request contains a stable device ID and a user-intent action ID.
An existing-root action also contains its expected occurrence key; root and
child edits carry their respective opaque revision preconditions. A retry
reuses the complete captured request/action ID. Different children can change
independently; the server rejects conflicting edits to the same object.

Alarm identity is `(taskId, occurrenceKey, kind)`, rather than task ID alone.
This makes an old recurring alarm distinguishable after rollover. Android
refreshes before a blocker renders and dismisses a surface whose occurrence is
no longer current.

## Todoist and backend behavior

Todoist remains the source of user-visible task data. The backend performs
explicit Todoist commands for root completion, rescheduling, project movement,
assignee changes, and one-time extension. One-time extension preserves start,
adds 30 minutes to duration, and appends one managed audit entry; recurring
Extend remains visible in Android but is disabled.

Every mutation returns an authoritative complete snapshot. Android persists and
reconciles that response rather than issuing an immediate follow-up GET. The
global durable `snapshotGeneration` orders whole snapshots across installations;
client root/child revisions are write preconditions, not a client merge format.

Action replay is durable: the same action ID and body return the stored result;
the same ID with a changed body conflicts. This ensures retries and two-phone
delivery do not repeat Todoist commands or accountability side effects.

## Consequences

- Editors permit only zero to thirty direct children—no parent picker,
  child schedules, drag reorder, or grandchildren.
- Custom recurrence is displayed read-only until the user explicitly selects a
  supported numbered replacement.
- A partial child response retains failed drafts and their error codes; it does
  not present the entire root mutation as a complete failure.
- Activity log entries are managed read-only history and remain separate from
  the editable user description.
