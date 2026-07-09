# Android UI Redesign Component Library

Date: 2026-07-08

This document supports `docs/superpowers/specs/2026-07-07-android-ui-redesign.md` and the implementation plan in `docs/superpowers/plans/2026-07-08-android-ui-redesign-implementation.md`.

## Intent

The redesign should be implemented as a small Buzer component library, not as independent screen rewrites. The same visual decisions must appear on Home, Add Task, Morning Review, full-screen blockers, and the compact overlay.

The implementation stays Android-first, but code should be friendly to a future Compose Multiplatform desktop or web port:

- Put pure state builders, formatters, and data classes in `client/shared/src/commonMain` when they do not depend on Android or Compose UI.
- Keep Android composables in `client/androidApp/src/main/kotlin/app/buzer/android/ui/components` for this roadmap.
- Avoid Android platform APIs inside reusable composables. Activities and services own Android-specific behavior.
- Use Material 3 components and theme tokens rather than per-screen colors, padding, or button styles.

## Proposed Packages

```text
client/shared/src/commonMain/kotlin/app/buzer/shared/ui/
  commitment/
    CommitmentFormatters.kt
    CommitmentEditorModels.kt
  home/
    HomeTimelineModels.kt
  blockers/
    BlockerUiModels.kt

client/androidApp/src/main/kotlin/app/buzer/android/ui/
  components/
    AttentionStrip.kt
    BuzerButtons.kt
    BuzerCards.kt
    BuzerFields.kt
    BuzerScaffold.kt
    CommitmentEditor.kt
    BlockerSurface.kt
    TaskDetailsBlock.kt
  theme/
    BuzerColor.kt
    BuzerShape.kt
    BuzerTheme.kt
    BuzerTypography.kt
    Spacing.kt
```

Do not create all files at once if a phase does not use them. The package map is the target boundary.

## Theme Tokens

`BuzerTheme` should remain the only `MaterialTheme` wrapper used by app entry points.

Required token groups:

- Color scheme: dark-first soft blue-grey Material 3 palette, shipped with a basic (not yet polished) light palette from the first theme task, so light mode is never simply absent.
- Typography: platform Material typography with small adjustments only where needed for compact density.
- Shape: Material shapes with restrained radii; repeated cards and panels should stay at or below 8dp radius unless a Material component supplies a larger default.
- Spacing: existing `Spacing` object may grow, but screens must stop hardcoding common repeated spacing values.

Material You dynamic color is used on Android 12+ (API 31+), resolved only inside `BuzerTheme` via `dynamicDarkColorScheme`/`dynamicLightColorScheme`. Below API 31 (minSdk is 26), `BuzerTheme` falls back to the static soft blue-grey dark/light schemes. No screen resolves dynamic color itself. Because dynamic color is an Android platform API, a future Compose Multiplatform desktop/web port uses the static schemes instead.

## Shared Components

### `BuzerScaffold`

Responsibility:

- Insets-safe screen shell.
- Optional compact top header.
- Optional bottom padding for FAB stacks.
- Standard screen padding and vertical spacing.

It must not know about navigation, backend state, or task models.

### `AttentionStrip`

Responsibility:

- Compact warning/info strip for invalid tasks, stale data, health warnings, or refresh failures.
- One primary message and optional retry/action.
- No healthy freshness text.

### `CommitmentEditor`

Responsibility:

- Shared schedule editor for Add Task, Morning Review repair, and Deadline reschedule.
- Standard single-line title field when title editing is enabled.
- Collapsed optional task details field backed by Todoist's existing `description` field, editable in every mode. Add Task persists it through task creation; Repair and Reschedule persist it through the reschedule path, which mirrors the updated description back into the Todoist task.
- Project and assignee controls.
- Separate Start and Duration wheel fields.
- Receipt summary.
- Computed primary submit label, such as `Save 14:30-15:00`, using pure minute-of-day arithmetic with an explicit midnight wrap (`23:50-00:20`) rather than `kotlinx.datetime.LocalDateTime` arithmetic, which that library intentionally omits.

It should take a pure `CommitmentEditorState` plus callbacks. It should not call the backend directly.

### `BlockerSurface`

Responsibility:

- Shared visual structure for Start blocker, Deadline blocker, and compact overlay.
- Eyebrow, title, schedule window, optional task details, and action panel.
- No navigation or backend calls.

The compact overlay renders this same Compose component inside `OverlayBlockerService`'s window, not a parallel classic-view layout. Because a bare `ComposeView` cannot render inside a service-owned `WindowManager` overlay, a small `OverlayComposeHost` supplies the `LifecycleOwner`/`SavedStateRegistryOwner` that `ComposeView` needs and drives the lifecycle across attach/detach. If that approach proves unstable on-device, the approved fallback is restyling the existing classic `TextView`/`Button` overlay layout with theme tokens instead of forcing Compose into the service window.

### `TaskDetailsBlock`

Responsibility:

- Render optional Todoist task details without duplicating truncation/expand behavior.
- Clamp long details by default and expose an explicit expand action.
- Hide entirely when no details exist.

## Anti-Patterns To Avoid

- Per-screen color literals for semantic state.
- Separate Add Task, Repair, and Reschedule form layouts.
- Separate Start, Deadline, and Overlay shell layouts.
- Trailing agenda chips that can be hidden by the Home FAB stack.
- Permanent healthy freshness labels.
- Composables that directly read `Context`, start Activities, or perform network actions unless they are screen roots.

## Portability Notes

Context7 documentation for Compose Multiplatform identifies it as a Kotlin UI framework that can share UI across Android, desktop, iOS, and web, with web currently beta through Kotlin/Wasm. This roadmap does not add macOS or web targets. It prepares for them by isolating pure UI state in shared Kotlin and by keeping reusable Android composables free of Android platform calls.
