# Atomic Mutation Reconciliation Design

## Status

Approved in conversation on 2026-07-16; implementation is pending this written
review.

## Problem

The original recurring-checklist implementation exposed `partial` mutation
responses. A root or some child effects could be reported as complete while a
different child was presented to Android as a fresh retry. That is unsafe after
an ambiguous Todoist command failure: Todoist may have applied a child command
after the client lost its response, so a new action can duplicate that child.

## Decision

Public mutations use two successful outcomes only:

- `completed`: authoritative synchronization proves the entire immutable user
  intent has taken effect.
- `reconciliation_pending` (`202`): the backend cannot yet prove the intended
  effects. It retains the same durable action and command UUIDs; the client
  must not create a fresh retry action.

`partial`, `createdRoot`, and per-child retry outcome handling are removed from
the public Android/backend/fake contract.

## Validation and failure rules

All deterministic local validation occurs before a Todoist command is sent:
required fields, valid recurrence, direct-child limit, expected occurrence and
revision preconditions, and supported hierarchy rules. Such invalid input is a
normal `4xx` error.

Once any Todoist command has been marked sent, any uncertainty—including a
timeout, process interruption, or unverified command result—returns
`reconciliation_pending`. The action journal retains the original immutable
intent, command UUIDs, temporary IDs, and any first-run derived values. A
replay with the same action ID resumes reconciliation; it never creates a new
Todoist command UUID or a duplicate child.

## Immutable action intent

An action journal fingerprint is computed solely from client-provided immutable
intent and preconditions. It must not include a fresh server timestamp or a
value recomputed from mutable mirror state. Server-derived values needed for
execution—such as a postpone deadline or an extend target duration—are stored
with the first durable action and reused on every replay.

## Client behavior

Android applies the authoritative snapshot returned by a completed mutation.
It does not immediately fetch a second snapshot. A pending response keeps the
same user action in progress and displays generic synchronization copy. Users
can revise fields only after an explicit terminal error or after reconciliation
has completed; there is no child-specific retry UI.

Snapshots expose mirror-local root and child IDs. Focused checklist routes
accept these snapshot IDs, then resolve Todoist IDs privately for Todoist
commands. The backend may accept Todoist child IDs only as a short-lived
compatibility fallback for already released clients.

## Consequences

- The UI has worse per-child failure feedback but substantially simpler state.
- No fresh retry can duplicate an ambiguous Todoist effect.
- Start, postpone, extend, root create, and checklist mutations share the same
  durable replay rule.
- Fake backend and API documentation expose the same two outcomes and the same
  field names/status codes as the real backend.

## Required proof

Tests must prove: post-send timeout yields `202` and same-command replay;
identical action replays for start/postpone/extend; local snapshot child IDs
work on focused routes; duplicate child drafts remain distinct on reconciliation;
and Android makes no immediate GET after an authoritative mutation response.
