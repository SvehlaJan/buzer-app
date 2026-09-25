# Cloud (claude.ai/code) Sessions And Mobile Development

Date: 2026-09-25

What a Claude Code cloud session can and cannot do for this repo, and how device work
should be routed. Everything else in `docs/notes/` about devices (Pixel 5, `Buzer_API_36`,
`scripts/adb-verify.sh`, the `android` CLI) assumes a **local** session on the dev Mac.

## Verified container facts (this environment, 2026-09-25)

| Check | Result |
| --- | --- |
| `ls /dev/kvm` | missing; `/proc/cpuinfo` has no `vmx`/`svm` flags |
| Android SDK / `adb` / `sdkmanager` | not installed, `ANDROID_HOME` unset |
| `curl https://dl.google.com/...` | proxy answers 403 to CONNECT (network policy denial) |
| `maven.google.com`, Maven Central, `plugins.gradle.org`, `services.gradle.org` | reachable (200) |
| `sentry.io`, `firebase.google.com`, `api.browserstack.com`, `pkgs.tailscale.com` | 403 (policy denial) |
| Resources | 4 vCPU, 15 GiB RAM |
| `cd backend && npm ci && npm test` (with `PUPPETEER_SKIP_DOWNLOAD=1`) | PASS: 38 files, 482 tests, ~25 s total |

Consequences:

- **Backend work is fully supported** in the cloud (tests, typecheck, build).
- **Android JVM tests and `assembleDebug` cannot run** until the SDK is installable:
  AGP needs `sdk.dir`/`ANDROID_HOME`, and SDK packages come from `dl.google.com`.
- **No emulator, ever, in this container**: without KVM an x86_64 emulator does not boot
  in useful time. `scripts/e2e-real-backend.sh` therefore cannot run here, so a cloud
  session cannot satisfy the AGENTS.md rule for UI-affecting changes on its own.
- **No physical device**: the container has no USB and no route to the home LAN.

## Unlocking Android JVM tests in the cloud (not yet verified)

1. Environment settings -> Network access: add `dl.google.com` to allowed domains.
2. Environment setup script (cloud-only, so it does not affect local sessions):

   ```bash
   export ANDROID_HOME=/opt/android-sdk
   mkdir -p "$ANDROID_HOME/cmdline-tools"
   # 13114758 = cmdline-tools 19.0; check developer.android.com/studio#command-tools for newer
   curl -fsSL -o /tmp/clt.zip https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
   unzip -q /tmp/clt.zip -d "$ANDROID_HOME/cmdline-tools"
   mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
   yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null
   "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "platforms;android-36" "platform-tools"
   echo "sdk.dir=$ANDROID_HOME" > /home/user/buzer-app/client/local.properties
   ```

   Expected result: `:shared:testAndroidHostTest`, `:androidApp:testDebugUnitTest`, and
   `:androidApp:assembleDebug` (the same trio `ci.yml` runs) become runnable in-session.
   Instrumentation tests still will not.

## Routing device work

- **Real device / emulator verification**: run a local session on the Mac with the
  Pixel 5 attached (`claude remote-control` in the repo folder makes it drivable from
  the Claude app on the phone). All existing tooling (`e2e-real-backend.sh`,
  `e2e-connected-device.sh`, `adb-verify.sh`, `android layout`) works there unchanged.
- **Emulator in CI**: GitHub-hosted Linux runners expose KVM, so an on-demand
  (`workflow_dispatch`) emulator workflow is the cloud-reachable option. A cloud session
  can trigger it and read job logs through the GitHub tools. It is the Follow-Up in
  `docs/decisions/2026-07-25-remove-fake-backend-tool.md` (45-60 min managed-emulator
  runs, 12 known failing instrumentation tests) and is not set up.
- **Field feedback from the phone**: the app already reports crashes, replays, and user
  feedback to Sentry. Allowing `sentry.io` plus a read-scoped token (environment secret)
  would let a cloud session triage real-device issues asynchronously. Not set up.
- **Do not** expose the phone's adb to a cloud container (for example over Tailscale):
  adb is full device control, and the container is ephemeral and shared-infrastructure.

## Recommended split

- Cloud session: backend, shared KMP logic, docs/plans, PR review, CI fixes, and
  Android code edits that are then handed off with `e2e-real-backend.sh` marked
  SKIPPED and the exact class to run.
- Local (Remote Control) session: anything needing Gradle Android builds today,
  instrumentation tests, `adb-verify.sh` evidence, and final UI verification.
