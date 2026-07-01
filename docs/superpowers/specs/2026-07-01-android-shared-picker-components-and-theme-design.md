# Android Shared Picker Components And Theme Tokens Design

Date: 2026-07-01

## Goal

Replace the app's free-text/native-dialog date, time, and duration inputs with shared wheel-style
picker components, and fix the remaining window-inset gap on full-screen Activities, by extracting
one reusable implementation instead of the three copy-pasted ones that exist today. Introduce a
small shared spacing/theme file so the new and touched code stops hardcoding raw `dp` literals.

This design covers the Android client only (`client/androidApp`). Backend API behavior is
unchanged.

## Background / Audit Findings

An audit of `client/androidApp/src/main/kotlin/app/buzer/android` found:

- `InsetSafeContent` (added in the loading/error/insets pass,
  `docs/superpowers/specs/2026-06-15-android-loading-error-and-insets-design.md`) is only applied
  to the main Compose tree via `BuzerApp`/`MainActivity`. `StartBlockerActivity`,
  `DeadlineBlockerActivity`, and `MorningReviewActivity` each call `setContent {}` directly and have
  no inset padding at all.
- The date+time selection flow (Material3 `DatePickerDialog` followed by a `TimePicker` in an
  `AlertDialog`) is duplicated verbatim in `AddTaskScreen.kt`, `BlockerScreens.kt`
  (`DeadlineBlockerScreen`), and `MorningReviewScreen.kt`.
- The duration-in-minutes input (`OutlinedTextField` with `KeyboardType.Number` plus
  `toIntOrNull()` validation) is duplicated the same way in the same three places.
- There is no design-token or theme file. All spacing is inline hardcoded `dp` (`8.dp`, `12.dp`,
  `16.dp`, ...), and styling relies entirely on unwrapped `MaterialTheme` defaults.
- No wheel-picker dependency exists today; only Material3's built-in calendar-grid `DatePicker` and
  clock-dial `TimePicker` are used.

## Scope

### In Scope

- A `WheelDurationField` component: a two-wheel hour/minute duration picker.
- A `WheelDateTimeField` component: a single combined date+time wheel picker.
- Adding the `WheelPickerCompose` dependency (`com.github.commandiron:WheelPickerCompose:1.1.11`,
  via JitPack) to `androidApp`.
- Migrating `AddTaskScreen.kt`, `BlockerScreens.kt` (`DeadlineBlockerScreen`), and
  `MorningReviewScreen.kt` to the two new field components, deleting the duplicated
  dialogs/state/validation they replace.
- A shared Activity content entry point (`setBuzerContent`) that fixes the inset gap on
  `StartBlockerActivity`, `DeadlineBlockerActivity`, and `MorningReviewActivity`, and wraps
  `MainActivity` too, so the fix cannot be skipped by a future Activity.
- A small `ui/theme` package: `Spacing` (named `dp` constants) and `BuzerTheme` (a thin
  `MaterialTheme` wrapper), used by the new/touched code in this pass.
- Compose `@Preview` functions for the new picker components, to support verification without a
  device or backend connection.

### Out Of Scope

- A full design-system/branding pass (custom color scheme, typography scale, dark theme). This
  design adds only spacing tokens and a named theme entry point; it does not change how the app
  looks today.
- Retrofitting `Spacing` tokens onto existing untouched screens. Only new code and the three
  migrated screens use the tokens in this pass.
- Any change to backend endpoints, Todoist task validation rules, or the enforcement/alarm system.
- Redesigning navigation or introducing a full design-system component library beyond the two
  picker components and the theme file described here.

## Architecture

### Dependency

Add to `androidApp/build.gradle.kts`:

```kotlin
implementation("com.github.commandiron:WheelPickerCompose:1.1.11")
```

and the JitPack repository to `settings.gradle.kts`'s `dependencyResolutionManagement.repositories`.

`WheelPickerCompose` depends on the archived `dev.chrisbanes.snapper` library. Before wiring it into
real screens, the first implementation step must be a spike: add the dependency and compile a
trivial `WheelTextPicker` usage in isolation, to confirm it still resolves and renders correctly
against Compose Multiplatform 1.11.1 / Compose Material3 1.9.0 (the versions already pinned in
`client/gradle/libs.versions.toml`). If the spike fails, fall back to building the wheel mechanics
directly on Compose Foundation's `LazyColumn` + `SnapFlingBehavior` (no third-party dependency);
that fallback is not designed further here because the spike is expected to succeed.

### `ui/pickers` package (new)

**`WheelDurationField`**

- Public API: `@Composable fun WheelDurationField(minutes: Int, onMinutesChange: (Int) -> Unit, label: String, modifier: Modifier = Modifier)`. Callers pass/receive a plain `Int` total minutes; this
  is unchanged in spirit from what `AddTaskScreen` etc. already track today.
- Renders as a labeled field/button (matching the existing tap-to-open-dialog pattern already used
  for date/time) that opens a dialog containing two side-by-side `WheelTextPicker`s from
  `WheelPickerCompose`:
  - Hours: `"0"` through `"6"` (7 rows).
  - Minutes: `"0"`, `"5"`, `"10"`, ... `"55"` (12 rows, 5-minute steps).
- Combined range is 0 to 415 minutes (6h55m). Default/start value is 0h 30m (30 minutes) for new
  tasks.
- Because `0h 0m` (total 0 minutes) is a reachable combination, the field's confirm action is
  disabled whenever the combined total is 0. This is a single `total > 0` check, replacing the old
  `toIntOrNull() != null && it > 0` free-text validation, but it is not a fully "impossible to
  misuse" component the way a single bounded wheel would be, and is called out here so it isn't
  lost during implementation.

**`WheelDateTimeField`**

- Public API: `@Composable fun WheelDateTimeField(selection: DateTimeSelection, onSelectionChange: (DateTimeSelection) -> Unit, label: String, modifier: Modifier = Modifier)`.
- Renders as a labeled tap-to-open field, matching today's interaction pattern, that opens a dialog
  containing a single `WheelDateTimePicker` from `WheelPickerCompose`.
- `WheelDateTimePicker`'s callback yields a `java.time.LocalDateTime`. This maps to the existing
  `DateTimeSelection(date, hour, minute)` model in one line:
  `DateTimeSelection(dateTime.toLocalDate(), dateTime.hour, dateTime.minute)`. No new data model is
  introduced.
- `min`/`max` bounds (if any are needed per call site) are passed through to the underlying
  `WheelDateTimePicker`'s `minDateTime`/`maxDateTime` parameters; existing screens do not currently
  enforce date/time bounds beyond "must be set," so this design does not add new bounds unless a
  screen already required them.

Both components replace the currently-duplicated dialog/state/validation in `AddTaskScreen.kt`,
`BlockerScreens.kt` (`DeadlineBlockerScreen`), and `MorningReviewScreen.kt`. They keep the existing
"tap a field to open a wheel in a dialog" interaction rather than permanently-visible inline wheels,
so screen layouts do not need to be reflowed to make room for always-visible wheels.

### `ui/theme` package (new)

- `Spacing.kt`: an object with named `dp` constants — `xs = 4.dp`, `sm = 8.dp`, `md = 16.dp`,
  `lg = 24.dp`, `xl = 32.dp` — matching the values already in ad hoc use today.
- `BuzerTheme.kt`: `@Composable fun BuzerTheme(content: @Composable () -> Unit)`, a thin wrapper
  around `MaterialTheme` using Material3 defaults for color scheme and typography (no visual change
  from today). `BuzerApp` and the new `setBuzerContent` helper call `BuzerTheme { }` instead of a
  raw `MaterialTheme { }`, giving one named place to add dark mode or custom colors later without
  touching every call site.
- Only the new picker components, the three migrated screens, and the Activity entry point use
  `Spacing`/`BuzerTheme` in this pass, per the Out Of Scope section above.

### Insets: shared Activity entry point

Add one extension function, `setBuzerContent`, in `ui/status/StatusComponents.kt` alongside
`InsetSafeContent`:

```kotlin
fun ComponentActivity.setBuzerContent(content: @Composable () -> Unit) {
    setContent {
        BuzerTheme {
            InsetSafeContent {
                content()
            }
        }
    }
}
```

`MainActivity`, `StartBlockerActivity`, `DeadlineBlockerActivity`, and `MorningReviewActivity` all
switch their `setContent { ... }` call to `setBuzerContent { ... }`. This fixes the current gap on
the three blocker/full-screen Activities, and ensures any Activity added in the future gets inset
safety and theming by construction rather than by each author remembering to wrap manually — the
actual root cause of today's gap.

### Data model cleanup

`DateTimeSelection.kt`'s `datePickerMillisToLocalDate` and `localDateToDatePickerMillis` helper
functions exist only to bridge Material3's millis-based `DatePickerState`. Once
`WheelDateTimeField` replaces that flow everywhere it's used, these two functions become dead code
and are deleted as part of this change.

## Screen Integration

`AddTaskScreen.kt`, `BlockerScreens.kt` (`DeadlineBlockerScreen`'s reschedule flow), and
`MorningReviewScreen.kt` (its repair flow) each:

- Replace their local `showDatePicker`/`showTimePicker`/`pendingDate`/`initialHour`/`initialMinute`
  state and dialog composition with a single `WheelDateTimeField`.
- Replace their local duration `OutlinedTextField` + `durationText` parsing/validation with a
  single `WheelDurationField`.
- Drop the now-dead validation error states that only existed to report "duration is not a valid
  positive integer" or "deadline is required," since the wheel components cannot produce those
  states (aside from the 0-minute duration guard noted above, which is handled inside
  `WheelDurationField` itself).

No other behavior in these screens (loading states, error dialogs, submit flow) changes; this
design only touches the date/time/duration input mechanism.

## Testing Strategy

Unit tests:

- `WheelDurationField`'s hour/minute-to-total-minutes mapping and the 0-minute guard.
- `WheelDateTimeField`'s `LocalDateTime` → `DateTimeSelection` mapping.
- Existing screen-level tests that asserted "invalid duration text" or "duration parse failure"
  behavior are deleted, since that state is no longer reachable; any screen-level test that asserts
  a valid duration/date-time flows through to submission is kept and updated to use the new
  components.

Compose `@Preview` functions (new):

- `WheelDurationField` and `WheelDateTimeField` each get at least one `@Preview` so they render in
  Android Studio's preview pane without a device, emulator, or backend connection.
- At least one `@Preview` per migrated screen, covering the picker-visible state, to visually
  confirm layout and (for the blocker Activities) inset padding.

Manual verification:

- Given neither the dev machine nor the test device is guaranteed to be on Tailscale, but both are
  confirmed to be on the same local network: run the backend locally, find the Mac's LAN IP, and
  set `BUZER_BACKEND_BASE_URL` in `client/local.properties` to `http://<mac-lan-ip>:<port>` before
  rebuilding the debug APK. This avoids depending on Tailscale for this verification pass.
- Confirm on a real device that `StartBlockerActivity`, `DeadlineBlockerActivity`, and
  `MorningReviewActivity` no longer render content under the status bar or navigation bar.
- Confirm the duration and date/time wheels are usable (scrollable, snap to a value, submit the
  expected value) on a real device, since wheel/fling physics are hard to fully judge from a
  Compose Preview alone.

## Risks

- `WheelPickerCompose` depends on the archived `dev.chrisbanes.snapper` library. Mitigated by the
  spike step described in the Dependency section above, before any screen migration work begins.
- Combined hour/minute duration range (0–415 minutes) is wider than the previous 5–240 minute
  range. If this turns out to be too wide in practice, narrowing the hour wheel to fewer rows is a
  small follow-up change, not a redesign.

## Out of Scope

(See Scope > Out Of Scope above.)
