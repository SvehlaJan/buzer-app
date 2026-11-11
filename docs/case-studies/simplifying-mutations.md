# Choosing simpler guarantees for Todoist mutations

Buzer is a personal Android accountability app backed by Todoist. AI agents implement
the software; I own the product, author and review specifications, and decide the
architectural tradeoffs. This is an example of changing a design I had initially
approved when its recovery machinery became a liability.

## The original problem

A request can time out after Todoist has already applied a change. Retrying blindly
can duplicate an effect, while assuming failure can mislead the user. Editing a task
and its checklist can also involve multiple upstream commands with partial results.

The original design tried to make these operations appear atomic across network and
process failures. It persisted action intent and command UUIDs, replayed commands,
reconciled observed effects, and kept the Android UI pending until the result was
resolved. The motivation was legitimate; the maintenance cost grew beyond what this
single-user tool needed.

An investigation found an especially revealing failure: Todoist acknowledged a
command but Buzer could not verify the expected effect. The action remained pending,
replay reused the confirmed command, a new action was rejected, and Android removed
the normal Done button. The user's route out of uncertainty had become blocked by
the mechanism intended to resolve it.

The [original protocol design](../superpowers/specs/2026-07-16-atomic-mutation-reconciliation-design.md)
records the guarantees we were attempting. The accepted decision below explains why
we changed them.

## The decision I made

I asked for a simpler model. Keep the low-friction task UI, but stop promising
automatic recovery across every external failure. Make uncertainty visible and let
the user inspect Todoist when necessary.

| Concern | Previous design | Replacement |
| --- | --- | --- |
| Operation ordering | Durable coordinator and per-root locking | One process-local FIFO for Todoist operations |
| Recovery | Persistent journal, replay, and reconciliation | Bounded attempt with an explicit result |
| Retrying a command | Durable recovery protocol | At most one eligible retry using the same in-memory UUID |
| Declaring success | Verify the requested effect after synchronization | Acknowledged command batch plus one successful post-command sync |
| Android interaction | Persistent pending state | Temporary screen lock, then normal controls and a session warning if needed |
| User recovery | Wait for automatic reconciliation | Refresh or open Todoist and check the result |

This did not mean deleting safeguards. Operations retain occurrence and revision
preconditions. Queue wait counts toward the operation deadline. Acknowledged but
stale, rejected, and delivery-unknown outcomes remain distinct, and none is displayed
as success. Multi-command batches stop at the first failure.

The [accepted decision](../decisions/2026-08-30-level-1-mutation-simplification.md)
records both the behavior and the costs I accepted.

## What I accepted in exchange

- A process restart loses queued work and in-memory retry identity.
- A disconnected request can leave the upstream result unknown.
- A batch can partially apply; Buzer does not attempt compensating rollback.
- An acknowledged no-op followed by a successful sync can count as success because
  effect verification was removed.
- Recurring tasks completed outside Buzer may retain checked children into the next
  occurrence; automatic repair was removed.

These are explicit product limitations. The system has a narrower recovery contract,
and the UI communicates where human intervention may be needed.

## Implementation and verification evidence

The history records the removal of [action-journal persistence](https://github.com/SvehlaJan/buzer-app/commit/6c737ca1855f839ed9b8f0ff8c8b44509f651fe8)
and the [mutation coordinator, root mutex, and mutation gateway](https://github.com/SvehlaJan/buzer-app/commit/2df59ca0a14891a98d092ac11049b0bd16b09512).
The replacement has three useful implementation boundaries:

- [TodoistOperationQueue](../../backend/src/services/todoistOperationQueue.ts): ordering,
  admission, deadlines, and cancellation. [Tests](../../backend/test/services/todoistOperationQueue.test.ts).
- [TodoistCommandExecutor](../../backend/src/services/todoistCommandExecutor.ts): bounded
  same-UUID retry and rejection classification. [Tests](../../backend/test/services/todoistCommandExecutor.test.ts).
- [TaskMutationRunner](../../backend/src/services/taskMutationRunner.ts): command batches,
  post-command sync, and explicit outcomes. [Tests](../../backend/test/services/taskMutationRunner.test.ts).

The [September implementation handoff](../handoffs/2026-09-02-level1-review-fixes-continuation.md)
records backend/JVM/build checks and a full 115-test Android instrumentation run.
Those are historical verification results for that change, not a claim about a fresh
run of today's branch.

I describe this as reducing persistent state and recovery machinery. The complete
change also added outcome handling and tests, so it was not a net line-count reduction.

## Applying the same principle elsewhere

**Observability:** I asked agents to use as much native Sentry functionality as
possible. We replaced custom paired reports, collectors, buffers, an endpoint,
database storage, and a GitHub synchronization bot with SDK capture and direct triage.
The [design](../superpowers/specs/2026-08-22-lean-observability-design.md) explains why
repairing the bespoke platform would cost more than the diagnostic value it added.
The tradeoff was giving up custom paired snapshots and tracing in favor of existing
Sentry evidence and identifiers.

**Delivery scope:** after reviewing the proposed Home experience, I requested a
complexity-reduction pass. We removed secondary features and state handling, then
split delivery into Home, Morning Review, and permission readiness.
The [scope-review record](../handoffs/2026-08-02-smart-home-design.md#scope-review-revision)
documents the cuts and sequencing.

## What I learned about directing AI implementation

I approved the original designs and owned the decision to change course. Agents made
it easy to generate more recovery logic; my job was to ask which guarantees justified
their testing and maintenance cost for this product.

KISS became a requirement I apply during specification review: define the guarantee,
the failure behavior, and the accepted limitation before asking an agent to implement
another layer.
