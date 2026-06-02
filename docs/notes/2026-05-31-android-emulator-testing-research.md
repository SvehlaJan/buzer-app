# Android Emulator Testing Research

Date: 2026-05-31

## Sources Checked

- Android Emulator documentation: `https://developer.android.com/studio/run/emulator`
- Android Emulator networking documentation: `https://developer.android.com/studio/run/emulator-networking`
- Doze and App Standby testing documentation: `https://developer.android.com/training/monitoring-device-state/doze-standby`
- Android testing strategy documentation: `https://developer.android.com/training/testing/fundamentals/strategies`
- UI Automator documentation: `https://developer.android.com/training/testing/other-components/ui-automator`
- Android full-screen intent limits: `https://source.android.com/docs/core/permissions/fsi-limits`
- Android CLI documentation: `https://developer.android.com/tools/agents/android-cli`
- Android skills documentation: `https://developer.android.com/tools/agents/android-skills`
- Maestro documentation: `https://docs.maestro.dev/`

## Findings

The emulator is suitable for most Buzer day-to-day Android verification:

- launch and backend sync;
- blocker activity display;
- Android notification permission paths;
- exact alarm scheduling inspection through `dumpsys alarm`;
- Doze/App Standby experiments through ADB commands;
- app-private file inspection through `run-as`;
- boot receiver smoke checks through simulated broadcasts.

The emulator should not be treated as the final authority for:

- OEM battery manager behavior;
- physical-device lockscreen/full-screen intent quirks;
- launcher-specific widget behavior;
- Tailscale app/device behavior;
- final accessibility or overlay experiments.

## Android CLI

Google's Android CLI is installed locally at:

```text
/opt/homebrew/bin/android
```

Observed version:

```text
1.0.15498356
```

The CLI installed the base `android-cli` skill into multiple agent skill directories, including:

```text
/Users/svehla/.codex/skills/android-cli
/Users/svehla/.agents/skills/android-cli
```

Useful commands for this workflow:

```bash
android info
android skills list
android layout
android screen capture -o /tmp/screen.png
```

## Testing Recommendation

Start with host-driven ADB and Android CLI scripts. Add AndroidX UI Automator only when structured layout assertions become too brittle. Keep Maestro and Appium out of the MVP workflow unless future UI flows become complex enough to justify the extra dependency.

All destructive blocker action tests should use a local fake backend. Live VPS emulator tests should stay read-only.
