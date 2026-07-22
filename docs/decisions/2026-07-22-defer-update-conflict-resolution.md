# Defer Update Conflict Resolution Until Push Invalidation

Date: 2026-07-22

## Decision

Do not add stale-update rebase, merge, reload-choice, or proactive conflict UI in
the unified task-editor MVP. The user will operate one client at a time. A stale
response continues through the existing generic error presentation.

## Rationale

Correctly merging root fields and checklist child revisions needs a conflict policy,
user-facing review surface, and reliable change awareness. That adds branching to a
task-editor unification whose purpose is to reduce it.

## Follow-Up

Revisit this when FCM or another push-invalidation mechanism is introduced. That
design should define how a remote change reaches an open editor, whether a local
draft is preserved, and how checklist child identity conflicts are surfaced.
