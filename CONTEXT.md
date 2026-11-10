# Buzer Commitment Companion

Buzer turns scheduled Todoist tasks into time-bounded personal commitments with active Android accountability and partner escalation.

## Language

### Commitments & Hierarchy

**Commitment**:
A root Todoist task that carries an explicit start date-time and positive duration so Buzer can enforce its time block.
_Avoid_: Todo, ticket, reminder, event

**Companion-Owned Commitment**:
A root task either explicitly assigned to the owner or left unassigned inside a personal (non-shared) project.
_Avoid_: User task, my task, personal item

**Unassigned Task**:
An active root task in a shared project with no assignee; visible in a separate triage section on Home, never enforced or blocking Morning Review.
_Avoid_: Orphan task, backlog item, unowned commitment

**Occurrence**:
A single scheduled instance of a commitment identified by its normalized start timestamp (or an invalid-state sentinel while unscheduled); advancing a recurring task creates a new occurrence.
_Avoid_: Instance, cycle, repetition, revision

**Checklist Item**:
A direct Todoist child task of a root commitment that carries no schedule, duration, recurrence, project mismatch, or independent assignment of its own, and must be checked before the root commitment can be completed.
_Avoid_: Subtask, sub-commitment, nested task, child commitment

**Needs Schedule Repair**:
A companion-owned root task missing a valid start date-time or positive duration; blocks Morning Review until repaired in-place or in Todoist.
_Avoid_: Broken task, malformed todo, unscheduled commitment

**Unsupported Hierarchy**:
A companion-owned root task whose Todoist subtree violates Buzer's flat checklist rules (for example, grandchildren, more than 30 children, or a child with its own schedule, duration, recurrence, assignment, or project mismatch); excluded from timed enforcement and surfaced in Morning Review for cleanup in Todoist.
_Avoid_: Corrupt tree, invalid checklist, broken parent

### Synchronization & Authority

**Snapshot**:
The complete, authoritative projection of current commitments, checklist items, revisions, and health warnings served from the backend's local Todoist mirror.
_Avoid_: Task list, feed, cache dump, view model

**Snapshot Generation**:
A monotonic counter incremented on every backend mirror write so clients never overwrite newer state with an older response.
_Avoid_: Sync cursor, version tag, ETag

**Session Warning**:
An in-memory, non-persistent banner shown when a Todoist mutation or forced refresh has uncertain, rejected, busy, or stale delivery, prompting the owner to refresh or inspect Todoist before retrying.
_Avoid_: Pending state, reconciliation banner, modal error, retry queue

**Owner Timezone**:
The IANA timezone of the owner's Todoist profile (falling back to UTC until synced), used by backend escalation quiet hours and day-boundary calculations.
_Avoid_: Server timezone, system locale

### Enforcement & Accountability

**Pre-Start Warning**:
A non-blocking heads-up notification delivered 10 minutes before a commitment's scheduled start.
_Avoid_: Early alarm, reminder ping

**Start Blocker**:
The enforcement prompt triggered at a commitment's scheduled start time (via native overlay when permitted or full-screen blocker screen) requiring the owner to either start the commitment or postpone it by 15 minutes.
_Avoid_: Start modal, alarm popup, snooze dialog

**Deadline Blocker**:
The full-screen enforcement prompt triggered at a commitment's scheduled end time requiring the owner to mark the commitment done, extend it by 30 minutes, or edit its schedule.
_Avoid_: End alarm, overdue modal, completion dialog

**Morning Review**:
The daily gate at the owner's morning boundary that requires clearing blocking companion-owned tasks before reviewing overdue and today's commitments to start the day.
_Avoid_: Daily standup, morning digest, inbox zero

**Escalation**:
A WhatsApp accountability message sent to the configured partner after a second start postponement on the same occurrence, respecting quiet hours.
_Avoid_: Alert, penalty notification, webhook
