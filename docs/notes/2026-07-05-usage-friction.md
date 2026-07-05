# Usage Friction Log

Running log of daily-use friction, per the go-live dogfooding loop (2026-07-05). Each entry:
observed date, friction, proposed fix, status. Fix the top open item each maintenance session.

## Open

### 1. Deadline picker defaults to now+1h; should default to now

- Observed: 2026-07-05, first go-live session.
- `WheelDateTimeField.startDateTimeFor()` defaults the wheel to `now.plusHours(1)`.
- Fix as part of the Todoist-native due-semantics change (see backlog): under the new
  semantics the field means "Start at", where defaulting to the current time is correct.
  Under the old deadline semantics a "now" default would create an instantly-overdue task —
  do not fix this independently of the semantics change.

### 2. Title input should capitalize sentences

- Observed: 2026-07-05.
- Add Task title `OutlinedTextField` lacks
  `KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)`.
- Small independent fix; batch with item 3.

### 3. Home refresh indicator reflows the layout

- Observed: 2026-07-05.
- The refresh indicator participates in Home's vertical Column, so content below it jumps
  when the indicator leaves composition.
- Owner preference: show it as the last item, or in a non-reflowing way (e.g. a fixed-height
  slot or a thin progress line that doesn't shift content). Small independent fix; batch
  with item 2. If the Home task list lands first, fold this into that design instead.

## Fixed

(nothing yet)
