# Handoff: Recurring Tasks And Checklist Sub-Tasks Design

Date: 2026-07-13 to 2026-07-15
Plan/task: research, approved design, recent security-change verification, and implementation planning
Attempted steps: project research, Todoist API verification, product clarification, architecture
comparison, section-by-section design approval, adjacent task-action gap review, written spec, and
spec self-review, Tailscale/Funnel commit review, current production security probes, source/test
inventory refresh, test-first implementation plan, and plan self-review

## Status

- Research, brainstorming, and implementation planning are complete.
- No code or API behavior changed.
- The occurrence-key retrofit with checklist-only children and broader task-action correctness
  cleanup is approved.
- Research note: `docs/notes/2026-07-13-subtasks-recurring-tasks-research.md`
- Design spec:
  `docs/superpowers/specs/2026-07-14-recurring-tasks-and-checklist-subtasks-design.md`
- Implementation plan:
  `docs/superpowers/plans/2026-07-15-recurring-tasks-checklists-and-action-correctness.md`
- The owner approved the written design. The next gate is choosing the implementation execution
  workflow; runtime implementation has not started.

## Project Context Read

- `AGENTS.md`
- approved 2026-04-30 product spec
- implementation plan index
- backend Sync/API plan
- alarm reliability/Todoist semantics plan
- full task edit plan
- Android redesign plan
- recent handoffs for the redesign, full edit flow, personal-project assignment, and Todoist update
- accepted Todoist-native due and task-details decisions
- Funnel path-stripping decision and backend internet-exposure rollout handoff

## Files Changed

- `docs/handoffs/2026-07-13-subtasks-recurring-tasks-research.md`
- `docs/superpowers/plans/2026-07-15-recurring-tasks-checklists-and-action-correctness.md`

The research note and approved design spec were committed earlier. At the start of implementation
planning, `master` matched `origin/master` and the worktree was clean. This planning pass changes
documentation only and leaves the recent backend-exposure implementation untouched.

## Commands And Results

Verification:
- Command: `git status --short --branch`
- Result: PASS
- Evidence: implementation planning started on clean `master` tracking `origin/master`; after the
  planning edits, only this handoff and the new implementation plan are changed.

Verification:
- Command: `npx ctx7@latest library "Todoist API" "Current API v1 Sync API subtasks parent_id child_order recurring tasks due is_recurring recurrence completion next occurrence item_complete item_update item_add"`
- Result: PASS
- Evidence: resolved `/websites/developer_todoist_api_v1`.

Verification:
- Command: `npx ctx7@latest docs "/websites/developer_todoist_api_v1" "Sync API items subtasks parent_id child_order create subtask parent_id move completion recurring tasks due is_recurring string lang timezone item_complete advancing next occurrence item_update recurrence"`
- Result: PASS
- Evidence: confirmed parent/move fields and recurring completion/reset-subtask commands.

Verification:
- Command: `npx ctx7@latest docs /websites/developer_todoist_api_v1 "Sync API item_update can it change project_id; correct item_move command to move task to another project; implications for subtasks"`
- Result: PASS
- Evidence: confirmed that moving requires the dedicated `item_move` command rather than an
  ordinary `item_update`.

Verification:
- Command: official-site `bx` research queries scoped to `developer.todoist.com` and
  `todoist.com`
- Result: PASS
- Evidence: confirmed Todoist task hierarchy, due recurrence representation, `item_close`,
  `item_update_date_complete`, reset-subtask behavior, hierarchy depth, and ordering limitations.

Verification:
- Command: `gh run list --workflow=ci.yml --limit 5 --json ...` during research
- Result: PASS
- Evidence: research-baseline commit `31d1953` passed run `29248106439`.

Verification:
- Command: `rg` placeholder/ambiguity scan, `git diff --check`, and staged diff review
- Result: PASS
- Evidence: no unresolved placeholders or whitespace errors; review fixes made reset behavior
  explicit for external Todoist rollover, separated root and child revisions, and made the
  recurring extension UI unambiguously disabled. The amendment review also made project-move
  recovery and the one-time extension audit contract explicit.

Verification:
- Command: recent `git log`, security commit diffs, Funnel decision, rollout handoff, backend auth,
  rate-limit, server registration, Android manifest/network-security, and deployment configuration
  review
- Result: PASS
- Evidence: the recurrence/checklist feature does not require changing the current exposure model.
  The plan keeps routes unprefixed inside Fastify's registered plugin scope, preserves bearer auth,
  trusted-proxy rate limiting, HTTPS-only production Android networking, and treats action/device
  IDs as metadata rather than authentication.

Verification:
- Command: public `/buzer/health`, unauthenticated `/buzer/tasks/snapshot`, and direct public-IP
  port probe performed 2026-07-15
- Result: PASS
- Evidence: public health returned the trimmed healthy shape; the protected route returned 401;
  direct `167.86.68.104:18432` timed out with HTTP 000, confirming the old raw exposure stayed
  closed.

Verification:
- Command: `wc`, code-fence count, design/security keyword coverage scan, and `git diff --check` for
  `docs/superpowers/plans/2026-07-15-recurring-tasks-checklists-and-action-correctness.md`
- Result: PASS
- Evidence: the plan has balanced code fences, names the existing backend/shared/Android/fake test
  suites, includes the mandatory deterministic emulator lane, and covers recurrence reset,
  revisions, occurrence identity, explicit Todoist commands, audit logging, rate limiting, Funnel
  routing, and two-phone verification.

Verification:
- Command: Context7 Todoist API description-limit query followed by official Todoist Help lookup
- Result: PASS
- Evidence: the API reference did not expose the field limit, while the current official
  task-description article explicitly documents a 16,384-character maximum. The plan links that
  source next to the codec constant.

## Tests

Verification:
- Command: `npm test`, `npm run typecheck`, and `npm run build` in `backend/` during the recent
  security verification
- Result: PASS
- Evidence: 18 Vitest files and 84 tests passed; typecheck and build exited 0.

Verification:
- Command: runtime client/fake-backend suites for this documentation-only planning change
- Result: SKIPPED
- Evidence: no runtime code changed. The implementation plan begins with explicit clean-baseline
  backend, fake-backend, shared JVM, and Android JVM gates before any feature edit.

Verification:
- Command: latest GitHub `ci.yml` check for deployed commit `b6b87e6`
- Result: PASS
- Evidence: GitHub Actions run `29397909962` completed successfully.

## Manual Verification

- Read the current schema, repositories, adapter, sync/action/snapshot services, shared DTOs,
  reducer, alarm planner, Home rendering, editor models, fixtures, and relevant test inventory.
- No live Todoist mutations were performed.

## Known Findings

- Parent and recurrence metadata exist only in stored raw Todoist JSON.
- Recurring completion and next-occurrence lifecycle reset are not supported correctly.
- Existing reschedule/edit/extend actions may destroy a recurrence by replacing it with an exact
  non-recurring due datetime.
- Full edit uses `item_update` for project movement and resets lifecycle even for metadata-only
  edits.
- Current one-time extension moves the Todoist start rather than extending the block end.
- Per-task enforcement state and the one-escalation-per-task index need occurrence scoping.
- Cross-device safety requires durable action IDs, task/child revisions, expected occurrence keys,
  and serialized mutations.

## Assumptions

- Todoist remains the source of truth for visible hierarchy and recurrence rules.
- Buzer should not calculate future recurrence occurrences independently when Todoist can advance
  them authoritatively.
- Todoist tasks are expected to follow the approved MVP hierarchy invariant; unexpected structures
  are diagnosed and blocked rather than silently flattened.
- The managed activity section is read-only in Buzer but remains editable through external Todoist
  clients; malformed markers are preserved and a fresh section is appended safely.
- Two phones behind one NAT share the current 60-request/minute source-IP budget. Focused child
  mutations therefore return an authoritative snapshot and Android must not issue an immediate
  redundant GET after each action.
- The action journal stores parsed fingerprints and compact results only; it must never store
  Authorization headers, API tokens, or log full task descriptions.

## Next Recommended Step

Choose the implementation workflow for
`docs/superpowers/plans/2026-07-15-recurring-tasks-checklists-and-action-correctness.md`, then execute
it task-by-task with test-driven development and the plan's review checkpoints. No runtime task is
complete merely because this planning handoff is complete.
