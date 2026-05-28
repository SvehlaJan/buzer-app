# Android Permission Findings

Date: 2026-05-28

## Context7 Resolution

- Library query: `npx ctx7@latest library "Android Developers" "exact alarms full-screen notifications boot receiver app widget Android 14 permissions"`
- Resolved ID: `/websites/developer_android`
- Docs queries:
  - `npx ctx7@latest docs "/websites/developer_android" "SCHEDULE_EXACT_ALARM full-screen intent notification boot completed receiver app widget permissions"`
  - `npx ctx7@latest docs "/websites/developer_android" "AppWidgetProvider appwidget-provider metadata PendingIntent launcher widget Android Kotlin"`

## Findings

- Exact alarms require `android.permission.SCHEDULE_EXACT_ALARM` in the manifest on Android 12 and higher.
- Runtime exact-alarm health should use `AlarmManager.canScheduleExactAlarms()` on Android 12 and higher.
- If exact alarm access is missing, Android documents opening settings with `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM`.
- Android 13+ notification posting requires `android.permission.POST_NOTIFICATIONS`.
- Full-screen notification intents require `android.permission.USE_FULL_SCREEN_INTENT` for apps targeting Android 10/API 29 and later.
- Boot rescheduling needs `android.permission.RECEIVE_BOOT_COMPLETED` plus a `BroadcastReceiver` registered for `android.intent.action.BOOT_COMPLETED`.
- Widgets require an `AppWidgetProvider`, a receiver with `android.appwidget.action.APPWIDGET_UPDATE`, and `android.appwidget.provider` metadata pointing at an XML `AppWidgetProviderInfo` resource.

## MVP Implications

- The app can declare the permissions now, but the device still needs user-granted exact alarm and notification access.
- Android-native code should expose permission health so the morning review/setup UI can warn when exact alarms or notifications are not ready.
- Boot handling in this MVP starts `MainActivity` with a boot-reschedule extra rather than adding WorkManager. A later background-only implementation should write a decision record.
