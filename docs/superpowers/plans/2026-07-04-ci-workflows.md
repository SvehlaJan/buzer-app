# CI Workflows And Token-Note Update Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add two GitHub Actions workflows — a fast no-device CI job on every push and a nightly/manual headless-emulator E2E job — plus update the token-efficiency note with the `android` CLI navigation workflow.

**Architecture:** Two separate workflow files split by trigger: `ci.yml` (push, ~5 min, JVM tests + build + backend tests) and `emulator-e2e.yml` (nightly cron + manual dispatch, runs the existing `scripts/e2e-fake-backend.sh` lane under KVM with a skip guard for idle days). The e2e script gains an optional GPU-mode env hook so CI can force `swiftshader_indirect` without changing local behavior. No secrets are used anywhere.

**Tech Stack:** GitHub Actions (`ubuntu-latest` hosted runners), Temurin JDK 17, Node 22, Gradle Managed Devices (`pixel8Api34`, already configured), bash.

**Source spec:** `docs/superpowers/specs/2026-07-04-ci-workflows-design.md`

---

## Execution Notes (read before Task 1)

- **Never modify `client/local.properties`.** It contains the developer's real VPS URL and token and is gitignored.
- **Do not change the pinned action versions** (`actions/checkout@v4`, `actions/setup-java@v4`, `actions/setup-node@v4`, `gradle/actions/setup-gradle@v4`, `actions/upload-artifact@v4`). Do not "upgrade" them.
- **`gh` may be unauthenticated on this machine.** Task 5 tells you exactly what to do in that case: stop and ask the user. Do not attempt `gh auth login` yourself and do not paste tokens.
- **`workflow_dispatch` and `schedule` triggers only work after the workflow file exists on the default branch (`master`).** That is why Task 5 merges to master before dispatching the emulator workflow. The `push` trigger of `ci.yml` works on any branch that contains the file.
- Expected timings: `ci.yml` ≈ 3–7 min per job. `emulator-e2e.yml` ≈ 10–20 min on first runs (system image download, cold Gradle cache).

---

## File Structure

- Create: `.github/workflows/ci.yml` — tier-1 workflow (client JVM tests + assemble; backend + fake-backend tests).
- Create: `.github/workflows/emulator-e2e.yml` — tier-2 workflow (nightly + manual emulator E2E).
- Modify: `scripts/e2e-fake-backend.sh` — optional `BUZER_GMD_GPU` env hook for CI GPU mode.
- Modify: `docs/notes/2026-06-12-agent-token-efficient-android-testing.md` — `android` CLI navigation workflow.
- Create: `docs/handoffs/2026-07-04-ci-workflows.md` — session handoff (Task 5).

Out of scope (per spec): picker-flow Compose tests, `manual-verify.sh`, Maestro, Roborazzi/screenshot testing, branch protection rules, deploy workflows, any `pull_request` trigger.

---

## Task 1: Create branch and `ci.yml`

**Files:**
- Create: `.github/workflows/ci.yml`

- [x] **Step 1: Create the working branch**

From the repo root (`/Users/svehla/playground/buzer-app`), with a clean tree on `master`:

```bash
git checkout master
git pull
git checkout -b feature/ci-workflows
```

Expected: `Switched to a new branch 'feature/ci-workflows'`.

- [x] **Step 2: Create the workflow file**

Create `.github/workflows/ci.yml` (this also creates the `.github/workflows/` directories) with exactly this content:

```yaml
name: CI

on:
  push:

concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: true

jobs:
  client:
    runs-on: ubuntu-latest
    timeout-minutes: 30
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - uses: gradle/actions/setup-gradle@v4

      - name: Run client unit tests and assemble
        working-directory: client
        run: ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain

      - name: Upload test reports
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: client-test-reports
          path: client/**/build/reports/tests/
          if-no-files-found: ignore

  backend:
    runs-on: ubuntu-latest
    timeout-minutes: 15
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-node@v4
        with:
          node-version: '22'
          cache: npm
          cache-dependency-path: |
            backend/package-lock.json
            tools/fake-backend/package-lock.json

      - name: Backend install
        working-directory: backend
        run: npm ci

      - name: Backend tests
        working-directory: backend
        run: npm test

      - name: Backend typecheck
        working-directory: backend
        run: npm run typecheck

      - name: Backend build
        working-directory: backend
        run: npm run build

      - name: Fake backend install
        working-directory: tools/fake-backend
        run: npm ci

      - name: Fake backend tests
        working-directory: tools/fake-backend
        run: npm test
```

Why these choices (context, not instructions): the Android SDK is preinstalled on `ubuntu-latest` with `ANDROID_HOME` set, so no `local.properties` is needed on CI; the `BUZER_BACKEND_*` build-config values fall back to `""` via `backendConfigValue()` in `client/androidApp/build.gradle.kts`, which compiles fine. The script names `test`, `typecheck`, `build` all exist in `backend/package.json`; `test` exists in `tools/fake-backend/package.json`.

- [x] **Step 3: Lint the workflow if actionlint is available**

```bash
command -v actionlint >/dev/null && actionlint .github/workflows/ci.yml || echo "actionlint not installed - skipping (GitHub validates on push)"
```

Expected: either no output from actionlint (clean), or the skip message. If actionlint reports errors, fix them before continuing; do not proceed with reported syntax errors.

- [x] **Step 4: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: add push-triggered unit test and build workflow

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 2: GPU-mode hook in the e2e script

**Files:**
- Modify: `scripts/e2e-fake-backend.sh`

Gradle Managed Devices sometimes fails to boot the emulator on GPU-less CI machines unless the GPU mode is forced to `swiftshader_indirect`. This hook lets CI set it via env without changing local behavior (locally the variable is unset, so the flag is omitted entirely).

- [x] **Step 1: Apply the edit**

In `scripts/e2e-fake-backend.sh`, replace exactly this block (currently the last lines of the file):

```bash
cd "${ROOT_DIR}/client"
./gradlew pixel8Api34DebugAndroidTest --rerun-tasks \
  -PBUZER_BACKEND_BASE_URL="${EMULATOR_BACKEND_URL}" \
```

with:

```bash
# CI sets BUZER_GMD_GPU (e.g. swiftshader_indirect) to force the managed-device
# emulator GPU mode on GPU-less runners; unset locally, the flag is omitted.
GMD_GPU_ARG="${BUZER_GMD_GPU:+-Pandroid.testoptions.manageddevices.emulator.gpu=${BUZER_GMD_GPU}}"

cd "${ROOT_DIR}/client"
./gradlew pixel8Api34DebugAndroidTest --rerun-tasks \
  ${GMD_GPU_ARG:+"${GMD_GPU_ARG}"} \
  -PBUZER_BACKEND_BASE_URL="${EMULATOR_BACKEND_URL}" \
```

The remaining `-P` lines of the gradlew invocation are unchanged. Note the `${GMD_GPU_ARG:+"${GMD_GPU_ARG}"}` form is deliberate: it expands to nothing when the variable is empty and stays safe under `set -u` on macOS's bash 3.2. Do not "simplify" it to `"${GMD_GPU_ARG}"` (which would pass an empty argument to gradle and fail) or to an array (bash 3.2 + `set -u` breaks on empty arrays).

- [x] **Step 2: Syntax-check the script**

```bash
bash -n scripts/e2e-fake-backend.sh && echo SYNTAX_OK
```

Expected: `SYNTAX_OK`.

- [x] **Step 3: Verify the expansion logic in isolation**

```bash
BUZER_GMD_GPU=swiftshader_indirect bash -c 'set -u; GMD_GPU_ARG="${BUZER_GMD_GPU:+-Pandroid.testoptions.manageddevices.emulator.gpu=${BUZER_GMD_GPU}}"; echo "[${GMD_GPU_ARG}]"'
bash -c 'set -u; BUZER_GMD_GPU=""; GMD_GPU_ARG="${BUZER_GMD_GPU:+-Pandroid.testoptions.manageddevices.emulator.gpu=${BUZER_GMD_GPU}}"; echo "[${GMD_GPU_ARG}]"'
```

Expected output, exactly two lines across the two commands:

```
[-Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect]
[]
```

- [x] **Step 4: Commit**

```bash
git add scripts/e2e-fake-backend.sh
git commit -m "test: allow forcing managed-device GPU mode via env

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 3: Create `emulator-e2e.yml`

**Files:**
- Create: `.github/workflows/emulator-e2e.yml`

- [x] **Step 1: Create the workflow file**

Create `.github/workflows/emulator-e2e.yml` with exactly this content:

```yaml
name: Emulator E2E

on:
  schedule:
    - cron: '30 2 * * *'
  workflow_dispatch:

jobs:
  emulator-e2e:
    runs-on: ubuntu-latest
    timeout-minutes: 45
    steps:
      - uses: actions/checkout@v4

      - name: Decide whether to run
        id: guard
        run: |
          if [ "${{ github.event_name }}" = "schedule" ]; then
            head_ts=$(git log -1 --format=%ct)
            now=$(date +%s)
            age=$(( now - head_ts ))
            if [ "$age" -gt 90000 ]; then
              echo "No commits in the last 25 hours (age ${age}s); skipping."
              echo "run=false" >> "$GITHUB_OUTPUT"
              exit 0
            fi
          fi
          echo "run=true" >> "$GITHUB_OUTPUT"

      - name: Enable KVM
        if: steps.guard.outputs.run == 'true'
        run: |
          echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' | sudo tee /etc/udev/rules.d/99-kvm4all.rules
          sudo udevadm control --reload-rules
          sudo udevadm trigger --name-match=kvm

      - uses: actions/setup-java@v4
        if: steps.guard.outputs.run == 'true'
        with:
          distribution: temurin
          java-version: '17'

      - uses: gradle/actions/setup-gradle@v4
        if: steps.guard.outputs.run == 'true'

      - uses: actions/setup-node@v4
        if: steps.guard.outputs.run == 'true'
        with:
          node-version: '22'
          cache: npm
          cache-dependency-path: tools/fake-backend/package-lock.json

      - name: Install fake backend dependencies
        if: steps.guard.outputs.run == 'true'
        working-directory: tools/fake-backend
        run: npm ci

      - name: Run fake-backend emulator E2E
        if: steps.guard.outputs.run == 'true'
        env:
          BUZER_GMD_GPU: swiftshader_indirect
        run: scripts/e2e-fake-backend.sh

      - name: Upload managed-device reports
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: emulator-e2e-reports
          path: |
            client/androidApp/build/reports/
            client/androidApp/build/outputs/androidTest-results/
          if-no-files-found: ignore
```

Notes for the reviewer (not instructions): the guard's 25-hour window (90000 s) gives the 24-hour cron slack for runner start jitter. Dispatch runs always set `run=true`. A skipped scheduled run ends green having consumed under a minute. The `BUZER_GMD_GPU` env feeds the hook added in Task 2.

- [x] **Step 2: Lint the workflow if actionlint is available**

```bash
command -v actionlint >/dev/null && actionlint .github/workflows/emulator-e2e.yml || echo "actionlint not installed - skipping (GitHub validates on push)"
```

Expected: clean or the skip message. Fix any reported errors before continuing.

- [x] **Step 3: Commit**

```bash
git add .github/workflows/emulator-e2e.yml
git commit -m "ci: add nightly and manual emulator e2e workflow

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 4: Token-efficiency note update

**Files:**
- Modify: `docs/notes/2026-06-12-agent-token-efficient-android-testing.md`

Use the Edit tool (not sed/awk) for both edits.

- [x] **Step 1: Replace the UI-hierarchy bullet in section 2**

Replace exactly this text:

```markdown
- **UI hierarchy**: `uiautomator dump` XML is 10-50x more verbose than you need. Prefer a
  targeted log/dumpsys assertion. If a tree is unavoidable, grep the dumped XML file for the
  one node you care about rather than reading the whole thing.
```

with:

```markdown
- **UI hierarchy / on-screen navigation**: prefer the `android` CLI
  (`/opt/homebrew/bin/android`, `android-cli` skill) over raw dumps:
  ```bash
  android layout --device <serial> -p        # flat JSON: text, center, interactions, state
  android layout --device <serial> -p --diff # only what changed since the last call
  android screen capture -a -o /tmp/shot.png # annotated screenshot when layout misses elements
  android screen resolve --screenshot /tmp/shot.png --string "tap #3"  # label -> coordinates
  ```
  A `layout` JSON dump costs a fraction of a screenshot and is machine-checkable. This
  workflow carried the 2026-07-04 wheel-picker device verification end to end.
  `uiautomator dump` XML is 10-50x more verbose; use it only if the `android` CLI is
  unavailable, and grep the dumped XML file for the one node you care about rather than
  reading the whole thing.
```

- [x] **Step 2: Extend the android-cli bullet in section 5**

Replace exactly this text:

```markdown
- `android-cli` skill — project creation, deployment, SDK management, env diagnostics via the
  `android` CLI tool.
```

with:

```markdown
- `android-cli` skill — project creation, deployment, SDK management, env diagnostics via the
  `android` CLI tool. Also the preferred on-screen navigation workflow (`android layout` /
  `screen capture -a` / `screen resolve`, see section 2); keep `scripts/adb-verify.sh` for
  log/dumpsys/alarm checks.
```

- [x] **Step 3: Sanity-check the result**

```bash
rg -c "android layout" docs/notes/2026-06-12-agent-token-efficient-android-testing.md
```

Expected: `2` (one occurrence in each edited section, counting the `--diff` line as part of the same fenced block — a count of 2 or 3 is acceptable; 0 means an edit did not land).

- [x] **Step 4: Commit**

```bash
git add docs/notes/2026-06-12-agent-token-efficient-android-testing.md
git commit -m "docs: prefer android-cli layout workflow for UI navigation

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 5: Push, verify on GitHub, merge, dispatch, handoff

**Files:**
- Create: `docs/handoffs/2026-07-04-ci-workflows.md`

- [x] **Step 1: Push the branch**

```bash
git push -u origin feature/ci-workflows
```

Expected: new branch on origin. This push triggers `ci.yml` (its `push` trigger works on any branch containing the file). The emulator workflow does NOT trigger and CANNOT be dispatched yet — it is not on `master`.

- [x] **Step 2: Check gh availability**

```bash
gh auth status
```

If this reports you are logged in: continue to Step 3 using the `gh` commands shown.

If it errors (not logged in / not installed): **STOP and ask the user** to either (a) run `gh auth login` themselves in a terminal, or (b) open https://github.com/SvehlaJan/buzer-app/actions in a browser and report the run results back to you. Do not run `gh auth login` yourself; do not ask for or handle tokens. Resume once you have one of the two.

- [x] **Step 3: Verify the CI run is green**

```bash
gh run list --workflow=ci.yml --branch=feature/ci-workflows --limit 3
gh run watch $(gh run list --workflow=ci.yml --branch=feature/ci-workflows --limit 1 --json databaseId -q '.[0].databaseId')
```

Expected: the run completes with conclusion `success` for both jobs (`client`, `backend`).

If it fails: read the failing job log (`gh run view <run-id> --log-failed`), fix the workflow or the underlying issue on this branch, commit, push, and re-verify. Do not merge a red workflow to master. If the failure is not understandable, stop and report the exact log excerpt to the user.

- [x] **Step 4: Merge to master and push**

Only after Step 3 is green:

```bash
git checkout master
git pull
git merge feature/ci-workflows
git push
```

Expected: fast-forward merge, push succeeds. This push triggers `ci.yml` again on master (expected green — same code) and makes `emulator-e2e.yml` dispatchable.

- [x] **Step 5: Dispatch the emulator workflow and watch it**

```bash
gh workflow run emulator-e2e.yml
sleep 20
gh run list --workflow=emulator-e2e.yml --limit 1
gh run watch $(gh run list --workflow=emulator-e2e.yml --limit 1 --json databaseId -q '.[0].databaseId')
```

Expected: conclusion `success` within ~10–20 minutes. The `Decide whether to run` step logs `run=true` (dispatch always runs); the E2E step ends with `BUILD SUCCESSFUL` and 21 tests on `pixel8Api34`.

Known failure modes and what to do:
- Emulator fails to boot / `Timed out` in the GMD task: the GPU flag is already forced via `BUZER_GMD_GPU`; capture the log (`gh run view <run-id> --log-failed`), stop, and report to the user — do not iterate blindly on emulator flags.
- Fake backend fails to start: check the `Install fake backend dependencies` step ran (`npm ci`) and that `scripts/e2e-fake-backend.sh` was invoked from the repo root (the workflow's default working directory is the repo root; do not add a `working-directory` to that step).
- Disk-space errors: stop and report; do not add third-party "free disk space" actions without asking.

- [x] **Step 6: Delete the feature branch**

```bash
git branch -d feature/ci-workflows
git push origin --delete feature/ci-workflows
```

- [x] **Step 7: Write the handoff**

Create `docs/handoffs/2026-07-04-ci-workflows.md` following the template in `docs/handoffs/README.md` (`# Handoff: <name>`, Date, Agent, Plan, then sections: Scope, Completed, Verification, Decisions And Assumptions, Open Issues, Next Step). Record with real values from this session:

- Scope: this plan (two workflows + script hook + token-note update).
- Completed: the four commits from Tasks 1–4 (list their hashes from `git log --oneline -6`), the merge, and both verified workflow runs.
- Verification: one entry per verification actually run — the actionlint/skip results, the `bash -n` and expansion checks from Task 2, the `ci.yml` run id + conclusion, and the `emulator-e2e.yml` dispatch run id + conclusion. Use the exact Result: PASS/FAIL wording from the template.
- Decisions And Assumptions: `BUZER_GMD_GPU=swiftshader_indirect` forced on CI from the start (spec contingency adopted proactively); no `pull_request` trigger by design; nightly guard window 25 h.
- Open Issues: the schedule-skip guard has not been observed on a real scheduled run yet (verifiable after one idle day); system image re-downloads each emulator run (~1–2 min, acceptable per spec).
- Next Step: observe the first scheduled nightly run; then consider the parked follow-ups (picker-flow Compose tests, `manual-verify.sh`).

- [x] **Step 8: Tick this plan's checkboxes, commit, push**

Update the checkboxes in `docs/superpowers/plans/2026-07-04-ci-workflows.md` for all completed steps (use the Edit tool), then:

```bash
git add docs/handoffs/2026-07-04-ci-workflows.md docs/superpowers/plans/2026-07-04-ci-workflows.md
git commit -m "docs: record CI workflows verification

Co-Authored-By: Claude <noreply@anthropic.com>"
git push
```

Expected: push succeeds; `git status --short --branch` shows `## master...origin/master` with no divergence.

---

## Plan Self-Review

Spec coverage:

- `ci.yml` triggers/concurrency/jobs/steps/timeouts/artifact-on-failure: Task 1, matching the spec section "Workflow 1" line by line (push all branches, per-ref cancel-in-progress, JDK 17, Node 22 with dual-lockfile cache, no secrets).
- `emulator-e2e.yml` triggers/guard/KVM/GMD lane/artifacts/timeout: Task 3, matching spec section "Workflow 2"; the GMD output-path uncertainty flagged in the spec is resolved with two-directory globs plus `if-no-files-found: ignore`.
- Spec GPU-flag contingency: adopted proactively via Task 2's env hook plus the workflow env var, so the small-model executor never has to debug emulator boot interactively.
- Token-note update: Task 4, matching spec section "Documentation Update".
- Spec "Verification" section: Task 5 (green `ci.yml` on branch, merge, dispatched emulator run, guard verified by review + first real nightly, noted as an open issue).

Placeholder scan: no TBDs; all code/YAML blocks are complete file contents or exact old/new edit pairs; commands include expected output.

Consistency: `BUZER_GMD_GPU` is spelled identically in Task 2 (script), Task 3 (workflow env), and Task 5 (handoff notes). Action versions are identical across both workflows. The gradle task name `pixel8Api34DebugAndroidTest` matches `client/androidApp/build.gradle.kts` and the existing script.
