# Add Task Widget Manual Test

Date: 2026-05-28

## Status

Skipped in this agent session because no physical Android device or emulator was attached.

## Commands

After building and installing the debug APK:

```bash
adb install -r client/androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

Then add the `Add strict task` widget from the launcher widget picker.

## Expected Result

- The widget appears with the `Add strict task` label.
- Tapping the widget opens `MainActivity`.
- The app starts on the add-task form so assignment, exact deadline, and duration can be entered.
