# Agent Token-Efficient Android Dev & Testing

Date: 2026-06-12

How an AI agent (Claude Code) should develop and test the Buzer Android client on the
Pixel 5 (`11141FDD4002R1`) and the `Buzer_API_36` emulator while minimizing token usage.

Ordered by leverage: the first three sections save far more tokens than adopting an MCP.

## 1. The biggest sink is screenshots — avoid them

- A single screenshot costs ~1000-1600 tokens regardless of what it shows, and almost
  always a one-line structured log proves more than a picture does.
- This app already emits excellent structured logs. Assert on those instead of looking:
  - `reconcile complete cancelled=48 scheduled=0 preStart=0 start=0 deadline=0`
  - `refresh fetched scheduleVersion=4 ... tasks=48 visible=39 ...`
  - planned boot logs: `boot_reconcile received`, `boot_reconcile cached=true`
- Verify scheduled alarms with `dumpsys alarm` (text) instead of opening the UI.
- `scrcpy` live mirroring is for a human watching, not for the agent — it streams video
  the agent never consumes. Do not rely on it for agent verification. (It is also not
  installed locally; see `docs/handoffs/2026-06-08-scrcpy-wrapper-investigation.md`.)
- When visual proof is genuinely required: capture once, downscale hard, check exit code.
  ```bash
  adb -s 11141FDD4002R1 exec-out screencap -p > /tmp/shot.png   # check $? != 0
  # resize before reading into context, e.g. sips -Z 720 /tmp/shot.png
  ```
  The scrcpy investigation found `screencap` can silently produce a 0-byte PNG behind a
  `BiometricPrompt`, so always check the byte count / exit status before trusting it.

## 2. Filter at the source — never dump raw, then read into context

- **logcat**: clear, act, read only new lines, scoped to the app tag:
  ```bash
  adb -s 11141FDD4002R1 logcat -c
  # ...trigger the action...
  adb -s 11141FDD4002R1 logcat -d -s Buzer            # -d = dump and exit, -s = tag filter
  adb -s 11141FDD4002R1 logcat -d -s Buzer -t 200     # last 200 lines only
  ```
- **dumpsys** is huge — always pipe through `rg` with a narrow pattern and a match cap:
  ```bash
  adb -s 11141FDD4002R1 shell dumpsys alarm   | rg -m 20 'app.buzer'
  adb -s 11141FDD4002R1 shell dumpsys package app.buzer.android | rg 'BootReceiver|SCHEDULE_EXACT_ALARM'
  ```
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
- **Redirect-then-grep pattern**: the Bash tool auto-persists large output to a file, but be
  deliberate — send verbose output to `/tmp` and `rg` only what you need, so the full blob
  never enters context:
  ```bash
  adb -s 11141FDD4002R1 logcat -d -s Buzer > /tmp/buzer-boot.log
  rg 'boot_reconcile|BAL_BLOCK' /tmp/buzer-boot.log
  ```

## 3. Prefer fast JVM unit tests; keep Gradle quiet; scope everything

- **Logic belongs in host JVM tests**, not on a device. The overlay/boot plan already does
  this (TDD on `reconcileLatestSnapshot()` and `PermissionHealth.evaluate`). A host test run
  prints a few lines; an emulator instrumentation run plus logcat reading is far more tokens.
  Tasks: `:shared:testAndroidHostTest`, `:androidApp:testDebugUnitTest`.
- **Quiet, scoped Gradle**:
  ```bash
  ./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.enforcement.EnforcementCoordinatorTest' \
    --console=plain -q --warning-mode=summary
  ```
  - `--console=plain` (already standard here) kills ANSI redraw spam.
  - `-q` suppresses non-error chatter; `--warning-mode=summary` collapses warning walls.
  - `--tests '<FQN>'` runs one class, not the suite.
  - `--offline` once deps are cached avoids resolution noise.
- **Long builds in the background**: run `assembleDebug` / instrumentation with the Bash
  `run_in_background` flag and poll for the final status line instead of streaming the whole
  build log into context.
- Scope multi-device runs with `ANDROID_SERIAL=<serial>` so output isn't multiplied across
  the attached Pixel 5 + emulator.

## 4. MCP options (only worth it for UI-navigation loops)

The MCP registry has no Android connector. Community servers exist; the token-relevant ones
expose **accessibility-tree snapshots instead of screenshots**, which is the main saving.

- [mobile-next/mobile-mcp](https://github.com/mobile-next/mobile-mcp) — most mature;
  platform-agnostic (iOS/Android, emulator/real), structured accessibility snapshots with
  coordinate-tap fallback.
- [callstack/agent-device](https://github.com/callstack/agent-device) — CLI purpose-built
  for coding agents: token-efficient snapshots, semantic refs, "evidence captured only when
  needed." Closest fit to a Claude Code workflow.
- [CursorTouch/Android-MCP](https://github.com/CursorTouch/Android-MCP) — has a
  `SCREENSHOT_QUANTIZED` option to cut image input tokens.
- [danielealbano/android-remote-control-mcp](https://github.com/danielealbano/android-remote-control-mcp)
  — explicitly token-optimized; runs on the device, compact UI representation vs raw
  uiautomator XML.
- [minhalvp/android-mcp-server](https://github.com/minhalvp/android-mcp-server) — straightforward
  adb wrapper (screenshot, UI layout, package mgmt, raw adb).

**Recommendation for Buzer:** an Android MCP is *not* a clear win here. Our verification is
log- and alarm-assertion based (boot recovery, exact alarms, reconcile counts), where plain
filtered `adb` already beats a screenshot-driven MCP on tokens. An accessibility-snapshot MCP
(`mobile-mcp` / `agent-device`) only pays off if/when we start driving multi-step UI flows
(tap through Add Task → Morning Review → blocker actions) interactively — and even then, the
existing fake-backend instrumentation tests cover those flows more cheaply. Revisit if manual
UI walking becomes frequent.

## 5. Skills already available

- `android-cli` skill — project creation, deployment, SDK management, env diagnostics via the
  `android` CLI tool. Also the preferred on-screen navigation workflow (`android layout` /
  `screen capture -a` / `screen resolve`, see section 2); keep `scripts/adb-verify.sh` for
  log/dumpsys/alarm checks.
- `testing-setup` skill — testing strategy / harness setup for native Android (unit, UI,
  screenshot, e2e).
- `scripts/adb-verify.sh` (added 2026-06-12) canonicalizes the low-token verification commands
  above — cleared + tag-filtered logcat, app-scoped `dumpsys alarm`, overlay/notification/
  exact-alarm/full-screen app-op state, exit-checked downscaled screenshots — behind one call
  with pre-filtered output and consistent handoff evidence. It resolves the target device from
  `BUZER_DEVICE_SERIAL` → `ANDROID_SERIAL` → sole attached device and refuses to guess between
  several. Prefer it over raw `adb` for device verification.
  ```bash
  scripts/adb-verify.sh logcat-clear
  BUZER_DEVICE_SERIAL=11141FDD4002R1 scripts/adb-verify.sh alarms
  BUZER_DEVICE_SERIAL=11141FDD4002R1 scripts/adb-verify.sh health
  ```

## Quick checklist for any device/emulator task

1. Can a host JVM test prove this instead? If yes, do that.
2. Reach for `scripts/adb-verify.sh` first; it already does 3-6 with pre-filtered output.
3. Clear logcat → act → read only new, tag-filtered lines (`adb-verify.sh logcat-clear` / `logcat`).
4. Assert on structured logs / `dumpsys ... | rg`, not screenshots (`adb-verify.sh alarms` / `boot-log`).
5. Gradle: `--console=plain -q`, scoped `--tests`, background long builds.
6. Redirect verbose output to `/tmp` and `rg` it; keep raw blobs out of context.
7. Capture an image only as last-resort proof — downscaled, exit-code checked (`adb-verify.sh screenshot`).
