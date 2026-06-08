# Handoff: scrcpy.py Wrapper Investigation

Date: 2026-06-08

## Task / Plan

Task: Investigate why `scrcpy.py` does not work.

Plan: No repository implementation plan was executed. This was a local tooling investigation against the `scrcpy.py` helper found on `PATH` at `/Users/svehla/bin/scripts/scrcpy.py`.

Status: Investigation complete. No product code was changed.

## Files Changed

- `docs/handoffs/2026-06-08-scrcpy-wrapper-investigation.md`

## Findings

- `scrcpy.py` is on `PATH` at `/Users/svehla/bin/scripts/scrcpy.py`.
- The wrapper imports `adb_select_device()` from `/Users/svehla/bin/scripts/utils.py`.
- The wrapper can select a connected ADB device, but live mirroring fails because the `scrcpy` executable is not installed or linked on `PATH`.
- `brew info scrcpy` reports `Not installed`; `/opt/homebrew/bin/scrcpy`, `/usr/local/bin/scrcpy`, `/opt/homebrew/opt/scrcpy`, and `/opt/homebrew/Cellar/scrcpy` are absent.
- ADB sees a Pixel 5 on Android 14, and later also saw `emulator-5584`. With multiple devices, `adb_select_device()` prompts on stdin; non-interactive executions fail with `EOFError`.
- Screenshot mode has a separate weakness: it does not check the `adb exec-out screencap -p` exit status. It printed a success message while creating a 0-byte PNG when the device was showing `BiometricPrompt`. After dismissing that prompt, direct `adb exec-out screencap -p` produced 173605 bytes.
- The initial `adb` failure inside Codex's default sandbox was environmental: ADB could not create its smartsocket listener with `Operation not permitted`. Running ADB outside the sandbox was required for valid results.

## Commands Run

Verification:
- Command: `which scrcpy.py`
- Result: PASS
- Evidence: `/Users/svehla/bin/scripts/scrcpy.py`.

Verification:
- Command: `sed -n '1,260p' /Users/svehla/bin/scripts/scrcpy.py`
- Result: PASS
- Evidence: wrapper shells out to `scrcpy --no-mouse-hover -s <device_serial>` for live mirroring and to `adb exec-out screencap -p` for screenshots.

Verification:
- Command: `sed -n '1,240p' /Users/svehla/bin/scripts/utils.py`
- Result: PASS
- Evidence: helper parses `adb devices` output and prompts with `input()` when multiple devices are attached.

Verification:
- Command: `scrcpy.py`
- Result: FAIL
- Evidence: `FileNotFoundError: [Errno 2] No such file or directory: 'scrcpy'`.

Verification:
- Command: `command -v scrcpy`
- Result: FAIL
- Evidence: no output; command exited non-zero.

Verification:
- Command: `brew info scrcpy`
- Result: FAIL for installed state
- Evidence: Homebrew formula exists, stable `4.0`, but says `Not installed`.

Verification:
- Command: `ls -l /opt/homebrew/bin/scrcpy /usr/local/bin/scrcpy /Applications/scrcpy.app/Contents/MacOS/scrcpy`
- Result: FAIL
- Evidence: all candidate paths were missing.

Verification:
- Command: `adb devices -l`
- Result: PASS
- Evidence: detected `11141FDD4002R1`, model `Pixel_5`; later also detected `emulator-5584`.

Verification:
- Command: `adb -s 11141FDD4002R1 shell getprop ro.product.model`
- Result: PASS
- Evidence: `Pixel 5`.

Verification:
- Command: `adb -s 11141FDD4002R1 shell getprop ro.build.version.release`
- Result: PASS
- Evidence: `14`.

Verification:
- Command: `adb -s 11141FDD4002R1 exec-out screencap -p | wc -c`
- Result: FAIL then PASS after clearing the foreground prompt
- Evidence: initially `0`; after `KEYCODE_BACK`, `173605`.

Verification:
- Command: `scrcpy.py --screenshot`
- Result: FAIL for robust behavior
- Evidence: initially printed `Screenshot saved...` while the file was 0 bytes; later failed with `EOFError` when multiple devices were attached and no stdin was available for the selection prompt.

Verification:
- Command: `npx ctx7@latest library "scrcpy" "investigate why scrcpy.py wrapper does not work: current scrcpy CLI usage flags --no-mouse-hover --record --no-audio -s macOS Android"`
- Result: PASS
- Evidence: resolved `/genymobile/scrcpy`.

Verification:
- Command: `npx ctx7@latest docs /genymobile/scrcpy "current scrcpy CLI usage flags --no-mouse-hover --record --no-audio -s macOS Android"`
- Result: PASS
- Evidence: docs confirm scrcpy is launched from terminal with the `scrcpy` command, and recording uses `scrcpy --no-audio --record=file.mp4`.

## Manual Verification

- Confirmed `scrcpy.py` itself is executable.
- Confirmed ADB device communication works outside Codex's default sandbox.
- Confirmed the main live mirroring failure is missing `scrcpy`, not missing ADB or a disconnected device.

## Known Failures Or Skipped Checks

- Did not install Homebrew `scrcpy`.
- Did not edit `/Users/svehla/bin/scripts/scrcpy.py` because it is outside the repository writable root and the user asked for investigation, not a tooling patch.
- Did not launch an actual scrcpy mirror because the `scrcpy` binary is absent.

## Assumptions

- The intended `scrcpy.py` is the one currently resolved by `PATH`.
- The missing `scrcpy` executable is the primary failure for normal live mirroring.
- Multiple-device prompting and screenshot error handling are secondary wrapper bugs worth fixing after `scrcpy` is installed.

## Next Recommended Step

Install or link `scrcpy` so `command -v scrcpy` resolves, then rerun `scrcpy.py`. After that, update the wrapper to accept an explicit device serial and to check subprocess exit codes before printing screenshot success.
