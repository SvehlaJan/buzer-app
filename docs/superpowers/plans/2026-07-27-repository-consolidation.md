# Repository Consolidation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve the best verified real-backend E2E repair work on `master`, retain useful operational/editor documentation, and remove stale local worktrees and branches without attempting to repair the known remaining E2E failures.

**Architecture:** `feature/real-e2e-suite-repair` is the sole implementation source. The old `feature/fix-e2e-test-suite` worktree is intentionally discarded because it weakens occurrence-key validation and adds transient debug output. Historical plans and handoffs are retained; only active Git topology is pruned.

**Tech Stack:** Git worktrees and branches, Kotlin/Compose, Node.js test E2E server, repository documentation.

---

### Task 1: Select the integration candidate

**Files:**
- Review: `.worktrees/real-e2e-suite-repair/**`
- Review: `.worktrees/fix-e2e-test-suite/**`
- Review: `docs/handoffs/2026-07-26-real-e2e-suite-repair.md`

- [x] **Step 1: Compare the dirty worktree manifests and diffs.**

  Retain the repair worktree's focused editor/fake-adapter changes. Do not copy the older worktree's production fallbacks for a blank or stale occurrence key, and do not copy its `println` debugging output.

- [x] **Step 2: Record the selection rationale.**

  The repair worktree has a documented passing backend E2E-server test and focused Android tests. The full Android real-backend suite remains known-red and is explicitly out of scope to repair in this cleanup.

### Task 2: Consolidate the selected work on master

**Files:**
- Modify: selected paths from `.worktrees/real-e2e-suite-repair`
- Add: `docs/handoffs/2026-07-26-real-e2e-suite-repair.md`
- Add: `docs/superpowers/plans/2026-07-26-real-e2e-suite-repair.md`
- Add: `docs/handoffs/2026-07-21-connected-device-install.md`
- Add: `docs/handoffs/2026-07-27-add-repair-editor-audit.md`
- Add: `docs/superpowers/plans/2026-07-27-add-repair-editor-convergence.md`

- [x] **Step 1: Apply only the selected repair diff to `master`.**

  Preserve its typed one-shot child-add fault control, stable schedule/checklist test selectors, and shared update-draft correctness fixes. Keep the deprecated `tools/fake-backend` directory deleted.

- [x] **Step 2: Preserve the outstanding plans and handoffs.**

  Commit the untracked operational install handoff, the Add/Repair audit and its future convergence plan, plus the repair plan/handoff. Do not claim the convergence plan is implemented.

- [x] **Step 3: Run bounded verification without fixing the known remaining E2E suite.**

  ```bash
  npm test --prefix backend -- e2eServer.test.ts
  cd client
  ./gradlew :shared:testAndroidHostTest
  ./gradlew :androidApp:assembleDebug
  cd ..
  git diff --check
  ```

  Record any failure. Do not run retries or modify tests merely to make the unfiltered Android E2E suite green.

- [x] **Step 4: Commit coherent integration.**

  ```bash
  git add backend/test client/androidApp/src client/shared/src docs
  git commit -m "test: consolidate real backend E2E repairs"
  ```

### Task 3: Prune stale local Git topology

**Files:**
- Remove worktrees: `.worktrees/fix-e2e-test-suite`, `.worktrees/real-e2e-suite-repair`
- Delete all local branches merged into `master`, excluding `master`

- [x] **Step 1: Verify master is clean after the integration commit.**

  ```bash
  git status --short
  git worktree list
  ```

- [x] **Step 2: Remove the two explicitly stale worktrees.**

  ```bash
  git worktree remove .worktrees/fix-e2e-test-suite
  git worktree remove .worktrees/real-e2e-suite-repair
  ```

- [x] **Step 3: Delete the merged local feature and legacy branches.**

  Use `git branch -d` only after each branch is confirmed merged into `master`; preserve `master`. Do not delete remote branches or push as part of this task.

- [x] **Step 4: Write the final consolidation handoff.**

  Record the selected/discarded worktree rationale, exact test results, commits, removed worktrees/branches, and the known skipped full E2E suite.
