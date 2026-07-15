# Handoff: Backend Internet Exposure Implementation

Date: 2026-07-14

> **Correction (2026-07-15):** this handoff's `/buzer/health`-prefixed references below reflect the design as it stood at merge time, before live deployment revealed Funnel strips its mount path (the backend's `/buzer` prefix was reverted). See `docs/decisions/2026-07-15-funnel-strips-path-prefix.md`. Left otherwise unedited as a historical record of what was verified at the time.
Plan: `docs/superpowers/plans/2026-07-13-backend-internet-exposure.md`
Spec: `docs/superpowers/specs/2026-07-13-backend-internet-exposure-design.md`
Task: Execute the full implementation plan (Tasks 0-9) via `superpowers:subagent-driven-development` — close the accidental public backend exposure and replace it with an intentional one via Tailscale Funnel under a `/buzer` path prefix.

## Status

All of Tasks 0-9 are complete, plus one plan amendment (informally "Task 7b") discovered mid-review and resolved with the user's sign-off. Branch `feature/backend-internet-exposure` is 10 commits ahead of `master`. Every task was implemented by a fresh implementer subagent and passed both a spec-compliance review subagent and a code-quality review subagent before being marked done, per the `subagent-driven-development` process. Two review rounds required a fix-and-re-verify loop before approval (noted below).

**Not done, by design:** actual deployment/rollout to the VPS, and the merge-to-master decision. Both are explicitly out of scope for Tasks 0-9 per the plan's own boundaries; the merge decision is being handed off next via `superpowers:finishing-a-development-branch`.

## Task-by-task checkbox status

- [x] Task 0: Branch setup — `885ca90`
- [x] Task 1: Constant-time bearer-token comparison — `2c613a0`
- [x] Task 2: Serve all routes under `/buzer` prefix — `860d54d`
- [x] Task 3: Trim unauthenticated `/health` responses — `a01d7ea`
- [x] Task 4: Proxy-aware per-IP rate limiting — `f7c7c92`
- [x] Task 5: docker-compose.yml hardening — `eb26874`
- [x] Task 6: deploy-vps.sh prefixed health wait — `1eea7e1`
- [x] Task 7: Android debug cleartext override removal — `1978534`
- [x] Task 7b *(plan amendment, not in the written plan doc — see Assumptions)*: debug-only `network_security_config.xml` — `89da597`
- [x] Task 8: Documentation updates — `603fda1` (see Known Issues re: an earlier `646f33a`/`21b1180`)
- [x] Task 9: Final verification and this handoff

## Files Changed (full branch diff vs. `master`, 18 files, +1469/-89)

```
backend/package-lock.json
backend/package.json
backend/src/http/auth.ts
backend/src/http/prefix.ts                                          (new)
backend/src/http/routes.ts
backend/src/server.ts
backend/test/http/auth.test.ts                                      (new)
backend/test/http/rateLimit.test.ts                                 (new)
backend/test/http/routes.test.ts
client/androidApp/src/debug/AndroidManifest.xml
client/androidApp/src/debug/res/xml/network_security_config.xml     (new)
docker-compose.yml
docs/notes/api-contract.md
docs/notes/github-actions-tailscale-deploy.md
docs/notes/vps-deployment.md
docs/superpowers/plans/2026-07-13-backend-internet-exposure.md      (new)
docs/superpowers/specs/2026-07-13-backend-internet-exposure-design.md
scripts/deploy-vps.sh
```

## Verification

- Command: `npm --prefix backend test`
- Result: PASS
- Evidence: 18 test files, 85 tests, all passed.

- Command: `npm --prefix backend run typecheck`
- Result: PASS
- Evidence: `tsc -p tsconfig.json --noEmit` completed clean.

- Command: `npm --prefix backend run build`
- Result: PASS
- Evidence: production build completed clean.

- Command (Task 9 Step 2, 4 consistency greps from the plan):
  - `grep -rn "BUZER_BIND_HOST\|BUZER_HOST_PORT\|BUZER_CONTAINER_PORT" docker-compose.yml scripts/ backend/src/` → PASS, only the expected `scripts/deploy-vps.sh:9` hit.
  - `grep -n "buzer/health" docker-compose.yml scripts/deploy-vps.sh` → PASS, exactly one hit in each file.
  - `grep -rn "usesCleartextTraffic" client/` → one hit, in `client/androidApp/build/intermediates/packaged_manifests/debugUnitTest/processDebugUnitTestManifest/AndroidManifest.xml` — confirmed via `git check-ignore -v` this is inside the gitignored, generated `build/` tree (matched by `.gitignore:41 **/build/`), a stale artifact from a build that predates this fix. Not source, not tracked, regenerates clean on next build. Treated as PASS.
  - `grep -rn '"/health"' backend/src/` → PASS, exactly one hit at `backend/src/http/routes.ts:44` (the route registration inside the `/buzer` prefix scope, as expected).

- Command: `client/gradlew -p client :androidApp:assembleDebug`
- Result: PASS (run multiple times across Task 7 and Task 7b)
- Evidence: `BUILD SUCCESSFUL`; merged-manifest output inspected directly each time to confirm the actual attribute changes landed.

- Command: `scripts/e2e-fake-backend.sh` (the deterministic emulator E2E lane, run during Task 7b's verification, twice)
- Result: PARTIAL (12/24 passed both runs, deterministic) — see Known Failures below for why this is not treated as a regression.
- Evidence: report artifacts at `client/androidApp/build/reports/androidTests/managedDevice/debug/pixel8Api34/index.html` (build directory, not committed).

- Command: `bash -n scripts/deploy-vps.sh`
- Result: PASS

- Command: `docker compose -f docker-compose.yml config --quiet`
- Result: SKIPPED — Docker is not installed in this environment. Substituted a `js-yaml` structural parse during Task 5's code review, which passed. The real, stronger validation happens at actual deploy time (`docker compose ... up --build` fails loudly on misconfiguration).

## Manual Verification Performed

- Every task (including the 7b amendment) went through two independent subagent reviews — spec-compliance, then code-quality — both required to explicitly reach "ready to merge" before the task was marked done. Reviewers were instructed not to trust implementer self-reports and to independently re-run tests, re-read diffs, and re-derive claims.
- Two fix loops occurred:
  1. **Task 7 → 7b:** code-quality review of Task 7 discovered (not hypothesized — traced through actual script/workflow/test source) that removing the debug cleartext override would break the nightly `emulator-e2e.yml` workflow. Presented the finding and three remediation options to the user; they chose a scoped `network_security_config.xml`. That fix's own regression claim ("the 12 local test failures aren't caused by this change") was itself independently re-investigated by the spec reviewer via raw per-test logcat analysis (grepped all 24 captured logcat files for cleartext/SSL/network-security error signatures — zero matches; confirmed successful `10.0.2.2` HTTP round-trips completing immediately before each of the 12 failures; found a concrete unrelated root cause for one failure, a `kotlin.time.InstantFormatException` on a truncated ISO timestamp). The reviewer also caught that the implementer's own supporting evidence (a CI-history comparison) was invalid — it compared against `master`, which doesn't contain this commit — but confirmed the underlying conclusion held anyway via better evidence.
  2. **Task 8 doc fix:** code-quality review found one stale, self-contradicting doc line (`docs/notes/vps-deployment.md` describing the deploy script as waiting on unprefixed `/health`, five lines above a correctly-updated `/buzer/health` reference). Sent back to the original implementer subagent to fix; see Known Issues for what happened next.
- I (the orchestrating session) independently re-ran the full backend suite/typecheck/build myself at the end rather than relying solely on subagent reports, ran all four Task 9 consistency greps myself, and independently verified the git-history recovery described below (`git log`, `git status`, `git show --stat`) before accepting it.

## Known Failures Or Skipped Checks

- `scripts/e2e-fake-backend.sh` has not achieved a clean 24/24 pass in the sandbox environment used for this session (12/24 passed, deterministically, across two runs). The evidence trail (per-test logcat showing successful cleartext round-trips immediately preceding each failure, one identified unrelated date-parsing bug, failures concentrated in a UI date-picker helper) supports "sandbox-environment quirk, not a regression from this branch," but this has **not** been confirmed by a clean run in a standard local Android environment or by a real CI run of `.github/workflows/emulator-e2e.yml` against this branch's actual commits (that workflow only runs against `master`, which doesn't yet have this branch's changes). Treat the next real nightly/manual run after merge as the true confirming signal.
- `docker compose config` was never validated against a real Docker install locally (see Verification above).
- Deployment/rollout to the VPS has **not** been performed. The plan's "Appendix: Operator Rollout Runbook" (Contabo console check → Tailscale admin console config → VPS sysctl → subnet collision check → deploy → `tailscale funnel` → verification curls → reboot test → Android config update + real-device smoke test → doc re-verification) is entirely unexecuted.
- `client/local.properties` was intentionally not touched (gitignored, per-developer, explicitly out of scope) — whoever runs the rollout still needs to point a debug build at the new HTTPS URL.

## Assumptions Made / Deviations From The Written Plan

- **Task 7b is not written into the plan document.** It was authored ad hoc (by the orchestrating session) in response to a real regression the plan didn't anticipate, given the same rigor as a plan task (explicit spec, TDD-adjacent verification, both review stages) but its checkbox text doesn't exist in `docs/superpowers/plans/2026-07-13-backend-internet-exposure.md`. A future reader of that plan file comparing it against `git log` will find one extra commit (`89da597`) the plan doesn't mention. Recommend either appending this task retroactively to the plan file, or capturing it in a `docs/decisions/` entry, so the plan stays an accurate historical record.
- **A git history incident occurred during Task 8 and was resolved.** While fixing the one-line doc issue above, a subagent's `git commit --amend` swept three files staged by a *different, concurrent* Claude Code session into an unrelated commit (`21b1180`) — the user confirmed afterward that session was their own, working on an unrelated "recurring tasks / checklist subtasks" feature directly in this same non-worktree checkout. It was caught immediately, recovered via git plumbing (`read-tree`/`update-index`/`write-tree`/`commit-tree` against a scratch index — no working-tree or `reset --hard` operations), and left a `backup/pre-fix-21b1180` branch pointing at the polluted commit rather than deleting anything. **That backup branch still exists and has not been deleted** — left for you to remove at your discretion. Current branch tip `603fda1` was independently re-verified (by both a dedicated review subagent and by me directly) to contain exactly the intended 3-file diff.
- Mid-session, you granted permission for the executing agent to SSH into the VPS and run `scripts/deploy-vps.sh` directly during the eventual rollout, while you retain every sudo command and all Tailscale admin console configuration. This expands the plan's original "operator-only" boundary for those two specific actions. Not yet exercised — rollout hasn't started.

## Non-Blocking Follow-Up Items From Code Review

None of these block merge; none have been acted on:

- **Task 4:** the 429 rate-limit response body is shaped inconsistently between known routes (custom error handler labels it `"code":"internal_error"`, which could mislead log-based alerting into treating routine throttling as a server fault) and unknown routes (Fastify's own default 429 shape). Status codes and headers are correct either way.
- **Task 5:** the pinned `172.28.0.0/16` Docker subnet is a `/16` where a `/24` would reduce future collision risk with other Docker networks on the same host (this VPS also runs an unrelated `expense-bot` project sharing the Docker daemon). The plan already mitigates this operationally via a manual pre-deploy collision check in the rollout runbook (`docker network ls -q | xargs docker network inspect ...`), not structurally.
- **Task 7b:** no automated test enforces the `10.0.2.2`-only cleartext exception itself — e.g., an instrumented assertion on `NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(...)` for the allowed host vs. an arbitrary other host. Recommended as a fast-follow so a future accidental broadening of the exception wouldn't go unnoticed by any existing test.
- **Task 8:** `Date:` headers in the three edited `docs/notes/*.md` files weren't bumped despite substantive rewrites. Also, neither this branch nor the design doc documents a fast "turn Funnel off" rollback path for the exposure itself (the existing Rollback section only covers backend code rollback).

## Next Recommended Step

1. Use `superpowers:finishing-a-development-branch` to decide merge/PR handling with the user. Do **not** merge to `master` without explicit confirmation — `scripts/deploy-vps.sh` resets the VPS checkout to `origin/master`, so merging is effectively arming the next deploy.
2. Once merged, execute the plan's "Appendix: Operator Rollout Runbook" in order. Per the user's mid-session permission grant, the executing agent may handle the SSH/deploy-adjacent steps directly, but must stop and wait for the user to personally perform every sudo-marked step and every Tailscale admin console step.
3. Decide what to do with `backup/pre-fix-21b1180` (safe to delete once you're satisfied `603fda1` is correct).
