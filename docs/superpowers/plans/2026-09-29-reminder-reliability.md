# Reminder Reliability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make reminders arrive reliably. Floating Todoist times stop breaking the alarm plan.
The Morning Review is never suppressed by overdue work and always shows by 08:15. Its opening
sync survives Todoist's top-of-the-hour 5xx spike. Boot no longer ANRs.

**Architecture:** The backend resolves every Todoist due value to a UTC instant in one place
(`backend/src/domain/time.ts`), using the owner's Todoist timezone. The Android client parses
snapshot times with one strict shared parser and isolates any task it cannot parse. Morning
Review priority becomes "active Start blocker only, capped at 08:15". The review's forced sync
retries once silently. Boot runs only the cached reconcile.

**Tech Stack:** Node 22 + TypeScript + Fastify + better-sqlite3 + vitest (backend). Kotlin
Multiplatform `shared` module (`kotlin.time.Instant`) and the Android app module
(`java.time`, JUnit/kotlin.test, Compose instrumentation via `scripts/e2e-real-backend.sh`).

**Spec:** `docs/decisions/2026-09-29-reminder-reliability.md`. It amends
`docs/superpowers/specs/2026-08-02-smart-home-morning-review-readiness-design.md` (blocker
priority) and plan `docs/superpowers/plans/2026-08-02-daily-morning-review.md`. Evidence:
`docs/notes/2026-09-23-morning-review-deferred-by-overdue-tasks.md` and
`docs/handoffs/2026-09-23-morning-review-delivery-investigation.md`. Both are uncommitted in the
primary checkout; do not commit them from this branch.

## Global Constraints

- This plan deliberately mixes backend, Android enforcement, and documentation work in one
  branch. The AGENTS.md mixing rule is waived here, explicitly, for this plan only.
- Work in the worktree `.worktrees/reminder-reliability` on branch `feature/reminder-reliability`, created from `origin/master` at `6e18826`. Do not work from the primary checkout's `master`: it is 60 commits behind.
- Owner timezone source order: `todoist_collaborators.time_zone` for `TODOIST_USER_ID` → env `OWNER_TIMEZONE` → `Europe/Bratislava`.
- Outside `backend/src/domain/time.ts`, no backend code may parse a due datetime with `new Date(...)`.
- Morning Review priority cap: 15 minutes after the review date's local 08:00.
- Morning Review forced-sync retry delay: 30 000 ms, exactly one retry.
- Keep it simple: no new persistence, no new endpoints, no new dependencies.
- Every commit message ends with a blank line and then `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Existing suites that assert changed behavior (migrated in this plan)

| Suite | Behavior it locks today | Migrated in |
| --- | --- | --- |
| `backend/test/domain/time.test.ts` | `computeStartAt`/`computeDeadlineAt` take no timezone | Task 1 |
| `backend/test/fakes/e2eFixtures.test.ts` | `priorityStart`/`priorityDeadline` scenarios exist | Task 5 |
| `client/androidApp/src/test/.../morningreview/BlockerPriorityResolverTest.kt` | overdue task ⇒ `Deadline` priority | Task 5 |
| `client/androidApp/src/test/.../morningreview/MorningReviewAlarmHandlerTest.kt` | overdue task defers review; no cap | Task 5 |
| `client/androidApp/src/androidTest/.../morningreview/MorningReviewAlarmInstrumentationTest.kt` | `cachedStartAndDeadlinePriorityDeferReviewThenClearedCacheAllowsRetryDelivery` | Task 5 |

The following were checked and need no migration:
- `AlarmPlanCalculatorTest`, `EnforcementCoordinatorTest`, `HomeScreenState` tests and
  agenda tests all use offset-bearing times.
- `MorningReviewRepairHostTest.kt:272` and `DeadlineBlockerUpdateHostTest.kt:72` use offset-less
  times, but those feed the task editor (`TaskEditorUpdateScreen`), which this plan does not
  change. Task 8's full e2e run confirms this.

## File map

| File | Change |
| --- | --- |
| `backend/src/domain/time.ts` | add `resolveDueInstant`, `normalizeDueDatetime`, `sameDueInstant`; timezone param on `computeStartAt`/`computeDeadlineAt` |
| `backend/src/domain/ownerTimeZone.ts` (new) | `resolveOwnerTimeZone`, `DEFAULT_OWNER_TIME_ZONE` |
| `backend/src/domain/taskRecurrence.ts` | export `isIanaTimeZone` |
| `backend/src/repositories/collaboratorRepository.ts` | add `getTimeZone` |
| `backend/src/services/taskSnapshotService.ts` | resolve `startAt` + `dueDatetime` in owner timezone |
| `backend/src/services/escalationService.ts` | resolve deadline in owner timezone |
| `backend/src/services/taskActionService.ts` | compare schedules by instant; preserve unchanged due |
| `backend/src/server.ts`, `backend/test/e2eServer.ts` | wire collaborator timezone source |
| `backend/test/fakes/e2eFixtures.ts`, `backend/test/e2eServer.ts` | drop `priorityStart`/`priorityDeadline` scenarios |
| `client/shared/.../time/SnapshotInstant.kt` (new) | `parseSnapshotInstantOrNull` |
| `client/shared/.../enforcement/AlarmPlanCalculator.kt` | strict parse, per-task isolation, `invalidTimeTaskIds` |
| `client/androidApp/.../time/SnapshotJavaInstant.kt` (new) | `parseSnapshotJavaInstantOrNull` bridge |
| `client/androidApp/.../enforcement/EnforcementCoordinator.kt` | report invalid-time tasks once |
| `client/androidApp/.../ui/screens/HomeScreenState.kt`, `.../ui/agenda/OwnerAgendaSelector.kt` | use the strict parser; delete guessing parsers |
| `client/androidApp/.../morningreview/MorningReviewAlarmHandler.kt` | Start-only priority, 15-min cap, strict parser |
| `client/androidApp/.../morningreview/MorningReviewScheduleCalculator.kt` | shared `DEFAULT_MORNING_REVIEW_TIME` |
| `client/androidApp/.../morningreview/MorningReviewSessionState.kt` | `forceSyncWithRetry` replaces `forceSync` |
| `client/androidApp/.../blockers/MorningReviewActivity.kt` | use retry; no system notification |
| `client/androidApp/.../enforcement/BootReconcileRunner.kt` | cached reconcile only |
| docs (Task 8) | Morning Review plan text, triage runbook, VPS env, handoff |

---

### Task 0: Workspace setup

**Files:** none (environment only)

- [x] **Step 1: Confirm the worktree and branch**

Run: `git -C .worktrees/reminder-reliability status --short --branch`
Expected: the first line is `## feature/reminder-reliability`. The only untracked files are
`docs/decisions/2026-09-29-reminder-reliability.md` and this plan.

- [x] **Step 2: Install backend dependencies**

Run (from `.worktrees/reminder-reliability/backend`): `npm ci`
Expected: exits 0.

- [x] **Step 3: Baseline backend tests**

Run (from `backend`): `npm test`
Expected: all tests pass. If anything fails before you change code, stop and record it in the
handoff.

- [x] **Step 4: Baseline client JVM tests**

Run (from `.worktrees/reminder-reliability/client`):
```bash
export ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk
./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL.

- [x] **Step 5: Commit the spec and plan**

```bash
git add docs/decisions/2026-09-29-reminder-reliability.md docs/superpowers/plans/2026-09-29-reminder-reliability.md
git commit -m "docs: record reminder reliability decision and plan

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 1: Backend due-time resolver and owner timezone

**Files:**
- Modify: `backend/src/domain/time.ts`
- Create: `backend/src/domain/ownerTimeZone.ts`
- Modify: `backend/src/domain/taskRecurrence.ts:252` (export `isIanaTimeZone`)
- Modify: `backend/src/repositories/collaboratorRepository.ts` (add `getTimeZone`)
- Test: `backend/test/domain/time.test.ts`, `backend/test/domain/ownerTimeZone.test.ts` (new), `backend/test/repositories/collaboratorRepository.test.ts`

**Interfaces:**
- Produces:
  - `resolveDueInstant(dueDatetime: string, timeZone: string): Date`
  - `normalizeDueDatetime(dueDatetime: string | null, timeZone: string): string | null`
  - `sameDueInstant(left: string | null, right: string | null, timeZone: string): boolean`
  - `computeStartAt(dueDatetime: string, durationMinutes: number, timeZone: string): string`
  - `computeDeadlineAt(dueDatetime: string, durationMinutes: number, timeZone: string): string`
  - `DEFAULT_OWNER_TIME_ZONE = "Europe/Bratislava"`
  - `interface CollaboratorTimeZoneSource { getTimeZone(todoistUserId: string): string | null }`
  - `resolveOwnerTimeZone(source: CollaboratorTimeZoneSource | null, currentUserId: string, fallback?: string): string`
  - `CollaboratorRepository.getTimeZone(todoistUserId: string): string | null`

- [x] **Step 1: Write the failing time tests**

Replace the whole of `backend/test/domain/time.test.ts` with:

```ts
import { describe, expect, it } from "vitest";
import {
  computeDeadlineAt,
  computeStartAt,
  normalizeDueDatetime,
  resolveDueInstant,
  sameDueInstant
} from "../../src/domain/time.js";

const TZ = "Europe/Bratislava";

describe("resolveDueInstant", () => {
  it("keeps fixed UTC values", () => {
    expect(resolveDueInstant("2026-10-11T07:00:00Z", TZ).toISOString()).toBe("2026-10-11T07:00:00.000Z");
  });

  it("keeps values with an explicit offset", () => {
    expect(resolveDueInstant("2026-10-11T09:00:00+02:00", TZ).toISOString()).toBe("2026-10-11T07:00:00.000Z");
  });

  it("reads a floating summer time as wall-clock time in the owner's timezone", () => {
    expect(resolveDueInstant("2026-10-11T09:00:00", TZ).toISOString()).toBe("2026-10-11T07:00:00.000Z");
  });

  it("reads a floating winter time as wall-clock time in the owner's timezone", () => {
    expect(resolveDueInstant("2026-12-01T09:00:00", TZ).toISOString()).toBe("2026-12-01T08:00:00.000Z");
  });

  it("accepts Todoist's microsecond floating format", () => {
    expect(resolveDueInstant("2026-10-11T09:00:00.000000", TZ).toISOString()).toBe("2026-10-11T07:00:00.000Z");
  });

  it("rejects values that are not timestamps", () => {
    expect(() => resolveDueInstant("not-a-date", TZ)).toThrow("dueDatetime must be a valid ISO timestamp");
  });
});

describe("normalizeDueDatetime", () => {
  it("passes fixed values through unchanged", () => {
    expect(normalizeDueDatetime("2026-10-11T07:00:00Z", TZ)).toBe("2026-10-11T07:00:00Z");
  });

  it("converts floating values to UTC ISO instants", () => {
    expect(normalizeDueDatetime("2026-10-11T09:00:00", TZ)).toBe("2026-10-11T07:00:00.000Z");
  });

  it("keeps null", () => {
    expect(normalizeDueDatetime(null, TZ)).toBeNull();
  });
});

describe("sameDueInstant", () => {
  it("treats a floating value and its resolved instant as the same time", () => {
    expect(sameDueInstant("2026-10-11T09:00:00", "2026-10-11T07:00:00.000Z", TZ)).toBe(true);
  });

  it("detects a real change", () => {
    expect(sameDueInstant("2026-10-11T09:00:00", "2026-10-11T08:00:00.000Z", TZ)).toBe(false);
  });

  it("compares nulls", () => {
    expect(sameDueInstant(null, null, TZ)).toBe(true);
    expect(sameDueInstant(null, "2026-10-11T07:00:00Z", TZ)).toBe(false);
  });
});

describe("computeStartAt", () => {
  it("returns the due datetime unchanged (due = start of block)", () => {
    expect(computeStartAt("2026-07-05T09:30:00.000Z", 30, TZ)).toBe("2026-07-05T09:30:00.000Z");
  });

  it("resolves a floating due datetime in the owner's timezone", () => {
    expect(computeStartAt("2026-10-11T09:00:00", 15, TZ)).toBe("2026-10-11T07:00:00.000Z");
  });

  it("rejects non-positive durations", () => {
    expect(() => computeStartAt("2026-07-05T09:30:00.000Z", 0, TZ)).toThrow();
  });

  it("rejects invalid timestamps", () => {
    expect(() => computeStartAt("not-a-date", 30, TZ)).toThrow("dueDatetime must be a valid ISO timestamp");
  });
});

describe("computeDeadlineAt", () => {
  it("returns due plus duration", () => {
    expect(computeDeadlineAt("2026-07-05T09:30:00.000Z", 30, TZ)).toBe("2026-07-05T10:00:00.000Z");
  });

  it("resolves a floating due datetime before adding the duration", () => {
    expect(computeDeadlineAt("2026-10-11T09:00:00", 15, TZ)).toBe("2026-10-11T07:15:00.000Z");
  });

  it("rejects non-positive durations", () => {
    expect(() => computeDeadlineAt("2026-07-05T09:30:00.000Z", -5, TZ)).toThrow();
  });
});
```

- [x] **Step 2: Write the failing owner-timezone tests**

Create `backend/test/domain/ownerTimeZone.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { resolveOwnerTimeZone } from "../../src/domain/ownerTimeZone.js";

const source = (timeZone: string | null) => ({
  getTimeZone: (todoistUserId: string) => (todoistUserId === "owner" ? timeZone : null)
});

describe("resolveOwnerTimeZone", () => {
  it("uses the owner's Todoist timezone", () => {
    expect(resolveOwnerTimeZone(source("Europe/Warsaw"), "owner", "UTC")).toBe("Europe/Warsaw");
  });

  it("falls back when the owner has no timezone", () => {
    expect(resolveOwnerTimeZone(source(null), "owner", "Asia/Tokyo")).toBe("Asia/Tokyo");
  });

  it("ignores an invalid Todoist timezone", () => {
    expect(resolveOwnerTimeZone(source("Mars/Base"), "owner", "Asia/Tokyo")).toBe("Asia/Tokyo");
  });

  it("uses Europe/Bratislava when the fallback is invalid too", () => {
    expect(resolveOwnerTimeZone(null, "owner", "nope")).toBe("Europe/Bratislava");
  });
});
```

- [x] **Step 3: Write the failing collaborator repository test**

Add inside the existing `describe("CollaboratorRepository", ...)` block of `backend/test/repositories/collaboratorRepository.test.ts`:

```ts
  it("returns a non-deleted collaborator's timezone", () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new CollaboratorRepository(db);
    repo.upsertCollaborator({
      todoistUserId: "user-me",
      fullName: "Me",
      email: null,
      timeZone: "Europe/Warsaw",
      imageId: null,
      isDeleted: false,
      rawJson: {},
      updatedAt: "2026-09-29T10:00:00Z"
    });
    repo.upsertCollaborator({
      todoistUserId: "user-gone",
      fullName: "Gone",
      email: null,
      timeZone: "Asia/Tokyo",
      imageId: null,
      isDeleted: true,
      rawJson: {},
      updatedAt: "2026-09-29T10:00:00Z"
    });

    expect(repo.getTimeZone("user-me")).toBe("Europe/Warsaw");
    expect(repo.getTimeZone("user-gone")).toBeNull();
    expect(repo.getTimeZone("user-unknown")).toBeNull();
  });
```

- [x] **Step 4: Run the tests to verify they fail**

Run (from `backend`): `npm test -- test/domain/time.test.ts test/domain/ownerTimeZone.test.ts test/repositories/collaboratorRepository.test.ts`
Expected: FAIL. `resolveDueInstant`/`normalizeDueDatetime`/`sameDueInstant` are not exported,
`ownerTimeZone.js` is missing, and `repo.getTimeZone` is not a function.

- [x] **Step 5: Implement the resolver**

Replace the whole of `backend/src/domain/time.ts` with:

```ts
const OFFSET_SUFFIX = /(?:Z|[+-]\d{2}:\d{2})$/i;
const FLOATING_DATETIME = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?$/;

export function parseIsoTimestamp(value: string, fieldName: string): Date {
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) {
    throw new Error(`${fieldName} must be a valid ISO timestamp`);
  }
  return parsed;
}

/**
 * Turns a Todoist due datetime into an instant. Fixed values end in `Z` or an offset.
 * Floating values (no offset, `due.timezone === null`) are wall-clock times in the owner's
 * timezone (decision 2026-09-29-reminder-reliability). This is the only place that may
 * parse a due datetime.
 */
export function resolveDueInstant(dueDatetime: string, timeZone: string): Date {
  if (OFFSET_SUFFIX.test(dueDatetime)) {
    return parseIsoTimestamp(dueDatetime, "dueDatetime");
  }
  const match = FLOATING_DATETIME.exec(dueDatetime);
  if (match === null) {
    throw new Error("dueDatetime must be a valid ISO timestamp");
  }
  const [, year, month, day, hour, minute, second] = match;
  const wallClockAsUtc = Date.UTC(
    Number(year),
    Number(month) - 1,
    Number(day),
    Number(hour),
    Number(minute),
    Number(second ?? "0")
  );
  const firstGuess = wallClockAsUtc - zoneOffsetMs(timeZone, wallClockAsUtc);
  return new Date(wallClockAsUtc - zoneOffsetMs(timeZone, firstGuess));
}

/** Snapshot form of a due datetime: fixed values pass through, floating ones become UTC ISO. */
export function normalizeDueDatetime(dueDatetime: string | null, timeZone: string): string | null {
  if (dueDatetime === null || OFFSET_SUFFIX.test(dueDatetime)) {
    return dueDatetime;
  }
  return resolveDueInstant(dueDatetime, timeZone).toISOString();
}

export function sameDueInstant(left: string | null, right: string | null, timeZone: string): boolean {
  if (left === null || right === null) {
    return left === right;
  }
  return resolveDueInstant(left, timeZone).getTime() === resolveDueInstant(right, timeZone).getTime();
}

export function computeStartAt(dueDatetime: string, durationMinutes: number, timeZone: string): string {
  if (!Number.isInteger(durationMinutes) || durationMinutes <= 0) {
    throw new Error("durationMinutes must be positive");
  }

  // Todoist-native semantics (decision 2026-07-05): due.datetime IS the start of the
  // committed block. Kept as a function so the semantic boundary stays explicit and
  // validated. durationMinutes is still validated here so a duration-less task can
  // never yield a start time.
  return resolveDueInstant(dueDatetime, timeZone).toISOString();
}

export function computeDeadlineAt(dueDatetime: string, durationMinutes: number, timeZone: string): string {
  if (!Number.isInteger(durationMinutes) || durationMinutes <= 0) {
    throw new Error("durationMinutes must be positive");
  }

  const due = resolveDueInstant(dueDatetime, timeZone);
  return new Date(due.getTime() + durationMinutes * 60_000).toISOString();
}

export function addMinutes(timestamp: string, minutes: number): string {
  if (!Number.isInteger(minutes)) {
    throw new Error("minutes must be an integer");
  }

  const date = parseIsoTimestamp(timestamp, "timestamp");
  return new Date(date.getTime() + minutes * 60_000).toISOString();
}

function zoneOffsetMs(timeZone: string, epochMs: number): number {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone,
    hourCycle: "h23",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit"
  }).formatToParts(new Date(epochMs));
  const part = (type: Intl.DateTimeFormatPartTypes): number =>
    Number(parts.find((entry) => entry.type === type)?.value);
  const wallClockAsUtc = Date.UTC(
    part("year"),
    part("month") - 1,
    part("day"),
    part("hour"),
    part("minute"),
    part("second")
  );
  return wallClockAsUtc - Math.floor(epochMs / 1000) * 1000;
}
```

- [x] **Step 6: Implement the owner timezone lookup**

In `backend/src/domain/taskRecurrence.ts`, change `function isIanaTimeZone(timeZone: string): boolean {` to `export function isIanaTimeZone(timeZone: string): boolean {`.

Create `backend/src/domain/ownerTimeZone.ts`:

```ts
import { isIanaTimeZone } from "./taskRecurrence.js";

export const DEFAULT_OWNER_TIME_ZONE = "Europe/Bratislava";

export interface CollaboratorTimeZoneSource {
  getTimeZone(todoistUserId: string): string | null;
}

/**
 * The owner's Todoist timezone decides what a floating due time means. It falls back to
 * OWNER_TIMEZONE, then Europe/Bratislava (decision 2026-09-29-reminder-reliability).
 */
export function resolveOwnerTimeZone(
  source: CollaboratorTimeZoneSource | null,
  currentUserId: string,
  fallback: string = process.env.OWNER_TIMEZONE ?? DEFAULT_OWNER_TIME_ZONE
): string {
  const candidate = source?.getTimeZone(currentUserId) ?? null;
  if (candidate !== null && isIanaTimeZone(candidate)) {
    return candidate;
  }
  return isIanaTimeZone(fallback) ? fallback : DEFAULT_OWNER_TIME_ZONE;
}
```

In `backend/src/repositories/collaboratorRepository.ts`, add this method to `CollaboratorRepository`, directly after `isMirrorContractReady()`:

```ts
  getTimeZone(todoistUserId: string): string | null {
    const row = this.db.prepare(`
      select time_zone as timeZone
      from todoist_collaborators
      where todoist_user_id = ? and is_deleted = 0
    `).get(todoistUserId) as { timeZone: string | null } | undefined;
    return row?.timeZone ?? null;
  }
```

- [x] **Step 7: Run the tests to verify they pass**

Run (from `backend`): `npm test -- test/domain/time.test.ts test/domain/ownerTimeZone.test.ts test/repositories/collaboratorRepository.test.ts`
Expected: PASS.

Run: `npm run typecheck`
Expected: FAIL only at the callers of `computeStartAt`/`computeDeadlineAt` (`taskSnapshotService.ts:90`,
`escalationService.ts:78`, `escalationService.ts:102`), with "Expected 3 arguments, but got 2".
Task 2 fixes those. Do not commit a red typecheck: go straight on to Task 2 and commit both
together.

---

### Task 2: Snapshot and escalation use the owner timezone

**Files:**
- Modify: `backend/src/services/taskSnapshotService.ts`
- Modify: `backend/src/services/escalationService.ts`
- Modify: `backend/src/server.ts:131` and `backend/src/server.ts:199-205`
- Modify: `backend/test/e2eServer.ts:66-67`
- Test: `backend/test/services/taskSnapshotService.test.ts`, `backend/test/services/escalationService.test.ts`

**Interfaces:**
- Consumes: `resolveOwnerTimeZone`, `CollaboratorTimeZoneSource`, `computeStartAt`, `computeDeadlineAt`, `normalizeDueDatetime` (Task 1)
- Produces:
  - `new TaskSnapshotService(tasks, projects, currentUserId, requireMirrorReady = false, collaborators: CollaboratorTimeZoneSource | null = null)`
  - `EscalationServiceOptions.ownerTimeZone?: () => string`

- [x] **Step 1: Write the failing snapshot test**

Add to `backend/test/services/taskSnapshotService.test.ts`, inside the top-level `describe`:

```ts
  it("resolves floating Todoist times in the owner's Todoist timezone", () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    repo.upsertTodoistTask({
      id: "task-floating",
      todoistTaskId: "todoist-floating",
      title: "Visit",
      description: null,
      projectId: null,
      assigneeId: "user-self",
      creatorId: "user-self",
      dueDatetime: "2026-10-11T09:00:00",
      durationMinutes: 15,
      completedAt: null,
      deletedAt: null,
      rawJson: "{}",
      updatedAt: "2026-09-27T08:50:18Z"
    });
    repo.upsertTodoistTask({
      id: "task-fixed",
      todoistTaskId: "todoist-fixed",
      title: "Fixed",
      description: null,
      projectId: null,
      assigneeId: "user-self",
      creatorId: "user-self",
      dueDatetime: "2026-10-11T07:00:00Z",
      durationMinutes: 15,
      completedAt: null,
      deletedAt: null,
      rawJson: "{}",
      updatedAt: "2026-09-27T08:50:18Z"
    });
    const collaborators = { getTimeZone: (id: string) => (id === "user-self" ? "Asia/Tokyo" : null) };

    const service = new TaskSnapshotService(repo, new ProjectRepository(db), "user-self", false, collaborators);
    const byId = new Map(service.getSnapshot("2026-09-29T06:00:00Z").tasks.map((task) => [task.id, task]));

    expect(byId.get("task-floating")?.dueDatetime).toBe("2026-10-11T00:00:00.000Z");
    expect(byId.get("task-floating")?.startAt).toBe("2026-10-11T00:00:00.000Z");
    expect(byId.get("task-fixed")?.dueDatetime).toBe("2026-10-11T07:00:00Z");
    expect(byId.get("task-fixed")?.startAt).toBe("2026-10-11T07:00:00.000Z");
  });
```

(Asia/Tokyo is UTC+9 with no DST, so floating 09:00 becomes 00:00Z. Using a non-default zone
proves the collaborator timezone is actually used.)

- [x] **Step 2: Write the failing escalation test**

Add to `backend/test/services/escalationService.test.ts`. Add these imports at the top:

```ts
import type { AccountabilitySender, EscalationInput } from "../../src/accountability/accountabilitySender.js";
```

Then add inside the top-level `describe`:

```ts
  it("computes the escalation deadline of a floating task in the owner's timezone", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    repo.upsertTodoistTask({
      id: "task-1",
      todoistTaskId: "todoist-1",
      title: "Visit",
      description: null,
      projectId: null,
      assigneeId: "user-self",
      creatorId: "user-self",
      dueDatetime: "2026-10-11T09:00:00",
      durationMinutes: 15,
      completedAt: null,
      deletedAt: null,
      rawJson: "{}",
      updatedAt: "2026-09-27T08:50:18Z"
    });
    const inputs: EscalationInput[] = [];
    const sender: AccountabilitySender = {
      sendEscalation: async (input) => { inputs.push(input); },
      getHealth: async () => ({ connected: true, detail: "test" })
    };
    const service = new EscalationService(db, repo, sender, "wife-chat", { ownerTimeZone: () => "Europe/Bratislava" });

    await service.sendForTask("task-1", 2);

    expect(inputs.map((input) => input.deadlineAt)).toEqual(["2026-10-11T07:15:00.000Z"]);
  });
```

- [x] **Step 3: Run the tests to verify they fail**

Run (from `backend`): `npm test -- test/services/taskSnapshotService.test.ts test/services/escalationService.test.ts`
Expected: FAIL. The snapshot returns the raw floating value, and `EscalationService` ignores
`ownerTimeZone` (it doesn't compile yet).

- [x] **Step 4: Implement the snapshot change**

In `backend/src/services/taskSnapshotService.ts`:

1. Replace the import `import { computeStartAt } from "../domain/time.js";` with:

```ts
import { type CollaboratorTimeZoneSource, resolveOwnerTimeZone } from "../domain/ownerTimeZone.js";
import { computeStartAt, normalizeDueDatetime } from "../domain/time.js";
```

2. Replace the constructor with:

```ts
  constructor(
    private readonly tasks: TaskRepository,
    private readonly projects: ProjectRepository,
    private readonly currentUserId: string,
    private readonly requireMirrorReady = false,
    private readonly collaborators: CollaboratorTimeZoneSource | null = null
  ) {}
```

3. In `getSnapshot`, directly after `const policy = new TaskEnforcementPolicy({ ... });`, add:

```ts
      const ownerTimeZone = resolveOwnerTimeZone(this.collaborators, this.currentUserId);
```

   Then change the `this.projectRoot(projection.root, projection.children, {` call so its hierarchy object also passes `ownerTimeZone`:

```ts
        return [this.projectRoot(projection.root, projection.children, {
          classification: projection.classification,
          violation: projection.violation,
          checkedCount: projection.checkedCount,
          totalCount: projection.totalCount,
          isCompanionOwned: policy.isCompanionOwned(projection.root)
        }, ownerTimeZone)];
```

4. Change the `projectRoot` signature to take the timezone as its last parameter:

```ts
  private projectRoot(
    task: TaskWithCommitment,
    children: readonly TaskWithCommitment[],
    hierarchy: {
      classification: "ROOT_COMMITMENT" | "ROOT_CANDIDATE_INVALID" | "ROOT_UNASSIGNED" | "UNSUPPORTED_HIERARCHY";
      violation: string | null;
      checkedCount: number;
      totalCount: number;
      isCompanionOwned: boolean;
    },
    ownerTimeZone: string
  ) {
```

5. In the returned object, replace the `dueDatetime:` and `startAt:` entries with:

```ts
      dueDatetime: normalizeDueDatetime(task.dueDatetime, ownerTimeZone),
      dueDate: task.dueDate,
      durationMinutes: task.durationMinutes,
      startAt: task.dueDatetime !== null && task.durationMinutes !== null
        ? computeStartAt(task.dueDatetime, task.durationMinutes, ownerTimeZone)
        : null,
```

   Leave the `validateTaskStrictness({ dueDatetime: task.dueDatetime, ... })` call unchanged:
   validation only checks presence.

- [x] **Step 5: Implement the escalation change**

In `backend/src/services/escalationService.ts`:

1. Add the import `import { resolveOwnerTimeZone } from "../domain/ownerTimeZone.js";`.
2. Replace `EscalationServiceOptions` with:

```ts
export interface EscalationServiceOptions {
  observability?: BackendObservability;
  /** Owner timezone for floating due times (decision 2026-09-29-reminder-reliability). */
  ownerTimeZone?: () => string;
}
```

3. Add a field and set it in the constructor. The final constructor body:

```ts
  private readonly observability: BackendObservability;
  private readonly ownerTimeZone: () => string;

  constructor(
    private readonly db: Database.Database,
    private readonly tasks: TaskRepository,
    private readonly sender: AccountabilitySender,
    private readonly recipientId: string,
    options?: EscalationServiceOptions | BackendObservability
  ) {
    if (options && "captureBackgroundFailure" in options) {
      this.observability = options;
      this.ownerTimeZone = () => resolveOwnerTimeZone(null, "");
    } else {
      this.observability = options?.observability ?? backendObservability;
      this.ownerTimeZone = options?.ownerTimeZone ?? (() => resolveOwnerTimeZone(null, ""));
    }
  }
```

4. In `sendForTask`, directly before `const message = buildEscalationMessage({`, add
   `const deadlineAt = computeDeadlineAt(dueDatetime, durationMinutes, this.ownerTimeZone());`.
   Then replace both `deadlineAt: computeDeadlineAt(dueDatetime, durationMinutes),` entries with
   `deadlineAt,`.

- [x] **Step 6: Wire production and e2e**

In `backend/src/server.ts`:
- Add the import `import { resolveOwnerTimeZone } from "./domain/ownerTimeZone.js";`.
- Line 131: `const snapshots = new TaskSnapshotService(tasks, projects, options.currentUserId, true, collaborators);`
- In the `EscalationService` construction (lines 199–205), make the options object:

```ts
    {
      observability: backendObservability,
      ownerTimeZone: () => resolveOwnerTimeZone(collaborators, currentUserId)
    }
```

In `backend/test/e2eServer.ts`:
- Add the import `import { CollaboratorRepository } from "../src/repositories/collaboratorRepository.js";` (skip it if already imported).
- Line 66: `const snapshots = new TaskSnapshotService(tasks, projects, CURRENT_USER_ID, true, new CollaboratorRepository(db));`

- [x] **Step 7: Run the tests and typecheck**

Run (from `backend`): `npm test -- test/services/taskSnapshotService.test.ts test/services/escalationService.test.ts test/domain`
Expected: PASS.

Run: `npm run typecheck`
Expected: exits 0.

Run: `grep -rn "new Date(.*due" src | grep -v "src/domain/time.ts"`
Expected: no output.

- [x] **Step 8: Commit Tasks 1 and 2**

```bash
git add backend/src/domain/time.ts backend/src/domain/ownerTimeZone.ts backend/src/domain/taskRecurrence.ts backend/src/repositories/collaboratorRepository.ts backend/src/services/taskSnapshotService.ts backend/src/services/escalationService.ts backend/src/server.ts backend/test/e2eServer.ts backend/test/domain/time.test.ts backend/test/domain/ownerTimeZone.test.ts backend/test/repositories/collaboratorRepository.test.ts backend/test/services/taskSnapshotService.test.ts backend/test/services/escalationService.test.ts
git commit -m "fix: resolve floating Todoist times in the owner's timezone

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Edits compare schedules by instant

**Files:**
- Modify: `backend/src/services/taskActionService.ts:520` and `:818-844`
- Test: `backend/test/services/taskActionService.test.ts`

**Interfaces:**
- Consumes: `sameDueInstant` (Task 1), `resolveOwnerTimeZone` (Task 1), `CollaboratorRepository.getTimeZone` (Task 1)
- Produces: `recurrenceForUpdate(task, input, ownerTimeZone: string)` (module-private)

- [x] **Step 1: Write the failing tests**

Add inside `describe("editTask (update)", ...)` in `backend/test/services/taskActionService.test.ts`:

```ts
    it("does not rewrite an unchanged floating due time echoed back as an instant", async () => {
      const { service, repo, adapter } = setupTestService();
      repo.upsertTodoistTask({
        id: "task-floating",
        todoistTaskId: "todoist-floating",
        title: "Visit",
        description: null,
        projectId: "project-personal",
        assigneeId: null,
        creatorId: "user-self",
        dueDatetime: "2026-10-11T09:00:00",
        durationMinutes: 15,
        completedAt: null,
        deletedAt: null,
        rawJson: "{}",
        updatedAt: "2026-09-27T08:50:18Z"
      });
      repo.initializeOccurrence("task-floating", {
        occurrenceKey: "occurrence-floating",
        occurrenceDueDatetime: "2026-10-11T09:00:00",
        occurrenceRecurrenceFingerprint: "one-time",
        updatedAt: "2026-09-27T08:50:18Z"
      });

      const result = await service.editTask("task-floating", {
        expectedOccurrenceKey: "occurrence-floating",
        rootPatch: {
          expectedTaskRevision: `r:${repo.getById("task-floating")?.mirrorRevision}`,
          title: "Visit grandparents",
          dueDatetime: "2026-10-11T07:00:00.000Z",
          durationMinutes: 15
        },
        childMutations: []
      });

      expect(result.kind).toBe("success");
      expect(adapter.itemUpdates).toHaveLength(1);
      expect(adapter.itemUpdates[0]).toMatchObject({ title: "Visit grandparents", due: { kind: "preserve" } });
    });

    it("writes a changed due time for a floating task as the new instant", async () => {
      const { service, repo, adapter } = setupTestService();
      repo.upsertTodoistTask({
        id: "task-floating",
        todoistTaskId: "todoist-floating",
        title: "Visit",
        description: null,
        projectId: "project-personal",
        assigneeId: null,
        creatorId: "user-self",
        dueDatetime: "2026-10-11T09:00:00",
        durationMinutes: 15,
        completedAt: null,
        deletedAt: null,
        rawJson: "{}",
        updatedAt: "2026-09-27T08:50:18Z"
      });
      repo.initializeOccurrence("task-floating", {
        occurrenceKey: "occurrence-floating",
        occurrenceDueDatetime: "2026-10-11T09:00:00",
        occurrenceRecurrenceFingerprint: "one-time",
        updatedAt: "2026-09-27T08:50:18Z"
      });

      await service.editTask("task-floating", {
        expectedOccurrenceKey: "occurrence-floating",
        rootPatch: {
          expectedTaskRevision: `r:${repo.getById("task-floating")?.mirrorRevision}`,
          dueDatetime: "2026-10-11T08:00:00.000Z",
          durationMinutes: 15
        },
        childMutations: []
      });

      expect(adapter.itemUpdates[0]).toMatchObject({
        due: { kind: "replace", datetime: "2026-10-11T08:00:00.000Z" }
      });
    });
```

(`project-personal` has `canAssignTasks: false`, so an edit there needs `assigneeId: null`;
the owner's real floating tasks are unassigned too. The owner timezone resolves to the
`Europe/Bratislava` default because the test DB has no collaborator row for `user-self`.)

- [x] **Step 2: Run the tests to verify they fail**

Run (from `backend`): `npm test -- test/services/taskActionService.test.ts -t "floating"`
Expected: the first test FAILS (`due.kind` is `"replace"`, not `"preserve"`). The second may
already pass; that's fine, it guards the change.

- [x] **Step 3: Implement the comparison**

In `backend/src/services/taskActionService.ts`:

1. Add the imports:

```ts
import { resolveOwnerTimeZone } from "../domain/ownerTimeZone.js";
import { sameDueInstant } from "../domain/time.js";
```

   (If a `../domain/time.js` import already exists, add `sameDueInstant` to it instead.)

2. Line 520: `const due = recurrenceForUpdate(task, input, resolveOwnerTimeZone(this.collaborators, this.currentUserId));`

3. Replace `function recurrenceForUpdate` (lines 818–844) with:

```ts
function recurrenceForUpdate(task: TaskWithCommitment, input: ResolvedRootEdit, ownerTimeZone: string) {
  const scheduleChanged = !sameDueInstant(task.dueDatetime, input.dueDatetime, ownerTimeZone)
    || task.durationMinutes !== input.durationMinutes;
  if (input.dueDatetime === null || input.durationMinutes === null) {
    return { kind: "clear" as const };
  }
  if (input.recurrencePreset !== undefined) {
    const timeZone = input.timeZone ?? task.dueTimezone ?? "UTC";
    if (!isIanaTimeZone(timeZone)) {
      throw Object.assign(new Error("Time zone must be an IANA time zone"), { code: "time_zone_invalid", statusCode: 422 });
    }
    const recurrence = translateRecurrencePreset({
      preset: input.recurrencePreset,
      selectedStart: input.dueDatetime,
      timeZone
    });
    return recurrence === null
      ? { kind: "replace" as const, datetime: input.dueDatetime, string: null, lang: null, timezone: null }
      : { kind: "replace" as const, datetime: input.dueDatetime, ...recurrence };
  }
  if (!scheduleChanged) {
    return { kind: "preserve" as const };
  }
  if (!task.isRecurring) {
    return { kind: "replace" as const, datetime: input.dueDatetime, string: null, lang: null, timezone: null };
  }
  return recurrenceForScheduleUpdate(task, input.dueDatetime);
}
```

- [x] **Step 4: Run the whole backend suite**

Run (from `backend`): `npm test`
Expected: PASS. If an existing edit test fails because it expected `due.kind === "replace"`
for an unchanged schedule, change its expectation to `{ kind: "preserve" }`: that is the
intended behavior change (decision 3). Record the test name in the handoff.

Run: `npm run typecheck`
Expected: exits 0.

- [x] **Step 5: Commit**

```bash
git add backend/src/services/taskActionService.ts backend/test/services/taskActionService.test.ts
git commit -m "fix: compare edited schedules by instant so unchanged times are not rewritten

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Client strict parser and per-task isolation

**Files:**
- Create: `client/shared/src/commonMain/kotlin/app/buzer/shared/time/SnapshotInstant.kt`
- Create: `client/shared/src/commonTest/kotlin/app/buzer/shared/time/SnapshotInstantTest.kt`
- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/enforcement/AlarmPlanCalculator.kt`
- Modify: `client/shared/src/commonTest/kotlin/app/buzer/shared/enforcement/AlarmPlanCalculatorTest.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/time/SnapshotJavaInstant.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementCoordinator.kt:181-192`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/EnforcementCoordinatorTest.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreenState.kt:121,158,163,327-337`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/agenda/OwnerAgendaSelector.kt:47,74-92`

**Interfaces:**
- Produces:
  - `fun parseSnapshotInstantOrNull(value: String): kotlin.time.Instant?` (package `app.buzer.shared.time`)
  - `fun parseSnapshotJavaInstantOrNull(value: String): java.time.Instant?` (package `app.buzer.android.time`)
  - `AlarmPlan.invalidTimeTaskIds: Set<String>` (defaults to empty)

- [x] **Step 1: Write the failing parser test**

Create `client/shared/src/commonTest/kotlin/app/buzer/shared/time/SnapshotInstantTest.kt`:

```kotlin
package app.buzer.shared.time

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class SnapshotInstantTest {
    private val expected = Instant.parse("2026-10-11T07:00:00Z")

    @Test
    fun parsesUtcInstants() = assertEquals(expected, parseSnapshotInstantOrNull("2026-10-11T07:00:00Z"))

    @Test
    fun parsesExplicitOffsets() = assertEquals(expected, parseSnapshotInstantOrNull("2026-10-11T09:00:00+02:00"))

    @Test
    fun parsesTodoistMicroseconds() = assertEquals(expected, parseSnapshotInstantOrNull("2026-10-11T07:00:00.000000Z"))

    @Test
    fun ignoresBracketedZoneSuffix() = assertEquals(expected, parseSnapshotInstantOrNull("2026-10-11T07:00:00Z[UTC]"))

    @Test
    fun rejectsFloatingValuesInsteadOfGuessingATimezone() = assertNull(parseSnapshotInstantOrNull("2026-10-11T09:00:00"))

    @Test
    fun rejectsGarbage() = assertNull(parseSnapshotInstantOrNull("not-a-date"))
}
```

- [x] **Step 2: Write the failing planner test**

Add to `AlarmPlanCalculatorTest`:

```kotlin
    @Test
    fun taskWithUnparseableTimeIsSkippedWithoutDroppingOtherTasks() {
        val plan = AlarmPlanCalculator.calculate(
            snapshot(
                task(id = "floating", dueDatetime = "2026-05-28T12:00:00"),
                task(id = "valid")
            ),
            nowIso = "2026-05-28T09:30:00Z"
        )

        assertEquals(setOf("floating"), plan.invalidTimeTaskIds)
        assertTrue(plan.alarms.none { it.taskId == "floating" })
        assertTrue(plan.alarms.any { it.taskId == "valid" })
    }
```

- [x] **Step 3: Write the failing coordinator test**

Add to `EnforcementCoordinatorTest`:

```kotlin
    @Test
    fun taskWithOffsetLessTimeIsSkippedAndReportedOncePerProcess() {
        val scheduler = RecordingAlarmScheduler()
        val reporter = RecordingErrorReporter()
        val cached = snapshot(task("task-1"), task("floating", dueDatetime = "2026-05-28T12:00:00"))
        val coordinator = EnforcementCoordinator(
            api = FakeBuzerApi(cached),
            snapshotStore = FakeSnapshotStore(cached),
            alarmScheduler = scheduler,
            errorReporter = reporter,
            nowProvider = { "2026-05-28T09:30:00Z" }
        )

        assertTrue(coordinator.reconcileLatestSnapshot())
        assertTrue(coordinator.reconcileLatestSnapshot())

        assertTrue(scheduler.scheduled.none { it.taskId == "floating" })
        assertTrue(scheduler.scheduled.any { it.taskId == "task-1" })
        assertEquals(listOf<String?>("floating"), reporter.errors.map { it.taskId })
        assertEquals(BlockerAction.REFRESH_SNAPSHOT, reporter.errors.single().action)
    }
```

- [x] **Step 4: Run the tests to verify they fail**

Run (from `client`, with `ANDROID_HOME`/`ANDROID_SDK_ROOT` exported):
```bash
./gradlew :shared:testAndroidHostTest --tests '*SnapshotInstantTest*' --tests '*AlarmPlanCalculatorTest*'
```
Expected: compilation FAILS (`parseSnapshotInstantOrNull` and `invalidTimeTaskIds` are unresolved).

- [x] **Step 5: Implement the shared parser**

Create `client/shared/src/commonMain/kotlin/app/buzer/shared/time/SnapshotInstant.kt`:

```kotlin
package app.buzer.shared.time

import kotlin.time.Instant

/**
 * Parses a timestamp from a backend snapshot. The backend always sends an offset
 * (decision 2026-09-29-reminder-reliability); an offset-less value is a contract violation
 * and yields null instead of a guessed timezone.
 */
fun parseSnapshotInstantOrNull(value: String): Instant? =
    runCatching { Instant.parse(value.substringBefore('[')) }.getOrNull()
```

- [x] **Step 6: Implement planner isolation**

In `AlarmPlanCalculator.kt`:

1. Add the import `import app.buzer.shared.time.parseSnapshotInstantOrNull`.
2. Replace `data class AlarmPlan` with:

```kotlin
data class AlarmPlan(
    val alarms: List<DesiredAlarm>,
    val cancelTaskIds: Set<String>,
    /** Tasks skipped because a snapshot time could not be parsed (a backend contract violation). */
    val invalidTimeTaskIds: Set<String> = emptySet()
)
```

3. In `calculate`:
   - Add `val invalidTimeTaskIds = mutableSetOf<String>()` next to `cancelTaskIds`.
   - Replace `alarms += desiredAlarmsFor(task, now)` with:

```kotlin
            val taskAlarms = desiredAlarmsFor(task, now)
            if (taskAlarms == null) {
                invalidTimeTaskIds += task.id
                continue
            }
            alarms += taskAlarms
```

   - Pass `invalidTimeTaskIds = invalidTimeTaskIds` into the returned `AlarmPlan(...)`.

4. Change `desiredAlarmsFor` to return `List<DesiredAlarm>?`, and replace its first two lines with:

```kotlin
    /** Returns null when the task carries a time the strict snapshot parser rejects. */
    private fun desiredAlarmsFor(task: TaskSnapshotItem, now: Instant): List<DesiredAlarm>? {
        val startAt = task.startAt?.let { parseSnapshotInstantOrNull(it) ?: return null }
        val dueAt = task.dueDatetime?.let { parseSnapshotInstantOrNull(it) ?: return null }
```

- [x] **Step 7: Run the shared tests**

Run: `./gradlew :shared:testAndroidHostTest`
Expected: PASS.

- [x] **Step 8: Implement the Android bridge and coordinator report**

Create `client/androidApp/src/main/kotlin/app/buzer/android/time/SnapshotJavaInstant.kt`:

```kotlin
package app.buzer.android.time

import app.buzer.shared.time.parseSnapshotInstantOrNull
import java.time.Instant

/** java.time bridge for [parseSnapshotInstantOrNull], with the same strict contract. */
fun parseSnapshotJavaInstantOrNull(value: String): Instant? =
    parseSnapshotInstantOrNull(value)?.let { Instant.ofEpochSecond(it.epochSeconds, it.nanosecondsOfSecond.toLong()) }
```

In `EnforcementCoordinator.kt`:
- Add the import `import java.util.concurrent.ConcurrentHashMap`.
- Add the field `private val reportedInvalidTimeTaskIds: MutableSet<String> = ConcurrentHashMap.newKeySet()` next to `snapshotApplicationLock`.
- Make `reconcile` call the report first:

```kotlin
    private fun reconcile(snapshot: TasksSnapshotResponse) {
        val plan = AlarmPlanCalculator.calculate(snapshot, nowProvider())
        reportInvalidTimes(plan.invalidTimeTaskIds)
        val scheduledAlarms = plan.alarms
            .map(AndroidEnforcementMapper::toScheduledAlarm)
        scheduledAlarmRegistry.reconcile(scheduledAlarms, alarmScheduler)
        logger(
            "reconcile complete scheduled=${plan.alarms.size} " +
                "preStart=${plan.alarms.count { it.kind == DesiredAlarmKind.PRE_START_WARNING }} " +
                "start=${plan.alarms.count { it.kind == DesiredAlarmKind.START_BLOCKER }} " +
                "deadline=${plan.alarms.count { it.kind == DesiredAlarmKind.DEADLINE_BLOCKER }} " +
                "invalidTime=${plan.invalidTimeTaskIds.size}"
        )
    }

    /** A snapshot time without an offset is a backend contract violation; report each task once per process. */
    private fun reportInvalidTimes(taskIds: Set<String>) {
        for (taskId in taskIds) {
            if (!reportedInvalidTimeTaskIds.add(taskId)) continue
            val message = "snapshot task time has no offset; no alarms scheduled for it"
            errorReporter.report(
                action = BlockerAction.REFRESH_SNAPSHOT,
                taskId = taskId,
                message = message,
                showNotification = false,
                throwable = IllegalStateException(message)
            )
        }
    }
```

- [x] **Step 9: Switch Home and agenda to the strict parser**

In `HomeScreenState.kt`:
- Add the import `import app.buzer.android.time.parseSnapshotJavaInstantOrNull`.
- Line 121: `val updatedLabel = parseSnapshotJavaInstantOrNull(snapshot.serverTime)?.let {`
- Line 158: `compareByDescending<TaskSnapshotItem> { parseSnapshotJavaInstantOrNull(it.updatedAt) ?: Instant.EPOCH }`
- Line 163: `val dueInstant = task.dueDatetime?.let(::parseSnapshotJavaInstantOrNull)`
- Delete the whole `private fun parseInstant(...)` (lines 327–337).
- Delete the imports `java.time.LocalDateTime` and `java.time.OffsetDateTime` if nothing else in the file uses them (check with `grep -n "LocalDateTime\|OffsetDateTime" HomeScreenState.kt`).

In `OwnerAgendaSelector.kt`:
- Add the import `import app.buzer.android.time.parseSnapshotJavaInstantOrNull`.
- Line 47: `.mapNotNull { task -> task.toAgendaEntry() }`
- Replace `toAgendaEntry` and delete `parseAgendaInstant`:

```kotlin
private fun TaskSnapshotItem.toAgendaEntry(): OwnerAgendaEntry? {
    val due = dueDatetime?.let(::parseSnapshotJavaInstantOrNull) ?: return null
    val duration = durationMinutes?.takeIf { it > 0 } ?: return null
    val end = runCatching { due.plusSeconds(duration * 60L) }.getOrNull() ?: return null
    return OwnerAgendaEntry(task = this, start = due, end = end)
}
```

- Delete the imports `java.time.LocalDateTime` and `java.time.OffsetDateTime`. Keep `java.time.ZoneId` only if `grep -n "ZoneId" OwnerAgendaSelector.kt` still shows a use.

- [x] **Step 10: Run the client JVM tests**

Run: `./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest`
Expected: PASS, including `taskWithOffsetLessTimeIsSkippedAndReportedOncePerProcess`.

- [x] **Step 11: Commit**

```bash
git add client/shared/src/commonMain/kotlin/app/buzer/shared/time/SnapshotInstant.kt client/shared/src/commonTest/kotlin/app/buzer/shared/time/SnapshotInstantTest.kt client/shared/src/commonMain/kotlin/app/buzer/shared/enforcement/AlarmPlanCalculator.kt client/shared/src/commonTest/kotlin/app/buzer/shared/enforcement/AlarmPlanCalculatorTest.kt client/androidApp/src/main/kotlin/app/buzer/android/time/SnapshotJavaInstant.kt client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementCoordinator.kt client/androidApp/src/test/kotlin/app/buzer/android/enforcement/EnforcementCoordinatorTest.kt client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreenState.kt client/androidApp/src/main/kotlin/app/buzer/android/ui/agenda/OwnerAgendaSelector.kt
git commit -m "fix: skip and report tasks with unparseable snapshot times instead of aborting the alarm plan

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Morning Review priority is Start-only and capped

**Files:**
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewAlarmHandler.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewScheduleCalculator.kt:16-18`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewScheduler.kt:55` (doc comment only)
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/morningreview/BlockerPriorityResolverTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/morningreview/MorningReviewAlarmHandlerTest.kt`
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/morningreview/MorningReviewAlarmInstrumentationTest.kt:137-164`
- Modify: `backend/test/fakes/e2eFixtures.ts:34,44-45,519-524`, `backend/test/fakes/e2eFixtures.test.ts:56-59`, `backend/test/e2eServer.ts:204`

**Interfaces:**
- Consumes: `parseSnapshotJavaInstantOrNull` (Task 4)
- Produces:
  - `enum class ActiveBlockerPriority { None, Start }`
  - `internal val DEFAULT_MORNING_REVIEW_TIME: LocalTime` (in `MorningReviewScheduleCalculator.kt`)
  - `internal val MORNING_REVIEW_PRIORITY_CAP: Duration`
  - `MorningReviewAlarmHandler(..., reviewTime: LocalTime = DEFAULT_MORNING_REVIEW_TIME)`

- [x] **Step 1: Migrate the resolver tests (failing)**

In `BlockerPriorityResolverTest.kt`, replace `deadlineBeatsAnActiveStartBlocker` and `intervalEndIsDeadlineRatherThanStart` with:

```kotlin
    @Test
    fun overdueTaskDoesNotHideAnActiveStartBlocker() {
        val snapshot = snapshot(
            task(id = "start", start = "2026-08-03T07:00:00Z", durationMinutes = 120),
            task(id = "overdue", start = "2026-08-03T05:00:00Z", durationMinutes = 60)
        )

        assertEquals(ActiveBlockerPriority.Start, BlockerPriorityResolver.resolve(snapshot, now))
    }

    @Test
    fun overdueTasksAloneNeverProducePriority() {
        val snapshot = snapshot(task(start = "2026-08-03T05:00:00Z", durationMinutes = 60))

        assertEquals(ActiveBlockerPriority.None, BlockerPriorityResolver.resolve(snapshot, now))
    }

    @Test
    fun taskAtItsIntervalEndIsNotABlocker() {
        val snapshot = snapshot(task(start = "2026-08-03T07:00:00Z", durationMinutes = 60))

        assertEquals(ActiveBlockerPriority.None, BlockerPriorityResolver.resolve(snapshot, now))
    }

    @Test
    fun taskWithOffsetLessTimeNeverProducesPriority() {
        val snapshot = snapshot(task(start = "2026-08-03T07:00:00", durationMinutes = 120))

        assertEquals(ActiveBlockerPriority.None, BlockerPriorityResolver.resolve(snapshot, now))
    }
```

- [x] **Step 2: Migrate the handler tests (failing)**

In `MorningReviewAlarmHandlerTest.kt`:

1. Give `fixture(...)` a `now` parameter. Change its signature to add
   `now: Instant = this.now,` as the first parameter after `initialState`, and change
   `nowProvider = { now },` to use that parameter (the name already matches).
2. Replace `activeDeadlineDefersReview` with:

```kotlin
    @Test
    fun overdueTaskNeverDefersReview() {
        val daily = alarm(MorningReviewAlarmReason.Daily, now)
        val fixture = fixture(
            MorningReviewLocalState(scheduledAlarm = daily),
            snapshot = snapshot(task(start = "2026-08-03T06:00:00Z", durationMinutes = 120))
        )

        assertEquals(MorningReviewAlarmDecision.Launch, fixture.handler.handle(identity(daily)))
        assertEquals(listOf<Call>(Consume(daily)), fixture.actions.calls)
    }

    @Test
    fun activeStartStopsDeferringFifteenMinutesAfterReviewTime() {
        val activeStart = snapshot(task(start = "2026-08-03T07:00:00Z", durationMinutes = 120))
        val justBefore = Instant.parse("2026-08-03T08:14:59Z")
        val atCap = Instant.parse("2026-08-03T08:15:00Z")
        val earlyRetry = alarm(MorningReviewAlarmReason.PriorityRetry, justBefore)
        val lateRetry = alarm(MorningReviewAlarmReason.PriorityRetry, atCap)
        val early = fixture(MorningReviewLocalState(scheduledAlarm = earlyRetry), now = justBefore, snapshot = activeStart)
        val late = fixture(MorningReviewLocalState(scheduledAlarm = lateRetry), now = atCap, snapshot = activeStart)

        assertEquals(MorningReviewAlarmDecision.Deferred, early.handler.handle(identity(earlyRetry)))
        assertEquals(MorningReviewAlarmDecision.Launch, late.handler.handle(identity(lateRetry)))
        assertEquals(listOf<Call>(Consume(lateRetry)), late.actions.calls)
    }
```

   (`zone` is UTC in this test class, so the review time is 08:00Z and the cap ends at 08:15Z.)

- [x] **Step 3: Run the tests to verify they fail**

Run (from `client`): `./gradlew :androidApp:testDebugUnitTest --tests '*BlockerPriorityResolverTest*' --tests '*MorningReviewAlarmHandlerTest*'`
Expected: FAIL: `overdueTasksAloneNeverProducePriority` returns `Deadline`,
`overdueTaskNeverDefersReview` returns `Deferred`, and so on.

- [x] **Step 4: Implement the rule**

In `MorningReviewScheduleCalculator.kt`, add this above the class and use it as the default:

```kotlin
internal val DEFAULT_MORNING_REVIEW_TIME: LocalTime = LocalTime.of(8, 0)

class MorningReviewScheduleCalculator(
    private val reviewTime: LocalTime = DEFAULT_MORNING_REVIEW_TIME
) {
```

In `MorningReviewAlarmHandler.kt`:

1. Add the imports `import app.buzer.android.time.parseSnapshotJavaInstantOrNull`, `import java.time.Duration`, and `import java.time.LocalTime`.
2. Replace `enum class ActiveBlockerPriority { None, Start, Deadline }` and the whole `object BlockerPriorityResolver` with:

```kotlin
/** How long after the review time an active Start blocker may still delay it (decision 2026-09-29). */
internal val MORNING_REVIEW_PRIORITY_CAP: Duration = Duration.ofMinutes(15)

enum class ActiveBlockerPriority { None, Start }

/**
 * Only an unstarted owner task inside its active interval (a Start blocker) outranks the
 * Morning Review. Overdue work never does: a Deadline blocker is only armed for a future
 * deadline (decision 2026-09-29-reminder-reliability).
 */
object BlockerPriorityResolver {
    fun resolve(snapshot: SnapshotViewState, now: Instant): ActiveBlockerPriority {
        val authoritativeInvalidIds = snapshot.invalidTasks.mapTo(mutableSetOf()) { it.id }
        val hasStartBlocker = snapshot.tasks.any { task ->
            task.id !in authoritativeInvalidIds &&
                task.startedAt == null &&
                task.state != CommitmentState.started &&
                task.activeIntervalOrNull()?.let { !now.isBefore(it.start) && now.isBefore(it.end) } == true
        }
        return if (hasStartBlocker) ActiveBlockerPriority.Start else ActiveBlockerPriority.None
    }

    private fun TaskSnapshotItem.activeIntervalOrNull(): ActiveInterval? {
        if (
            !isAssignedToUser ||
            occurrenceKey.isBlank() ||
            isCompleted ||
            isDeleted ||
            state == CommitmentState.completed ||
            state == CommitmentState.deleted ||
            state == CommitmentState.invalid ||
            validationErrors.isNotEmpty() ||
            hierarchyStatus != HierarchyStatus.valid
        ) {
            return null
        }
        val start = dueDatetime?.let(::parseSnapshotJavaInstantOrNull) ?: return null
        val duration = durationMinutes?.takeIf { it > 0 } ?: return null
        return ActiveInterval(start, start.plusSeconds(duration * 60L))
    }

    private data class ActiveInterval(val start: Instant, val end: Instant)
}
```

3. Add a constructor parameter to `MorningReviewAlarmHandler` after `errorReporter`:
   `private val reviewTime: LocalTime = DEFAULT_MORNING_REVIEW_TIME`.
4. Replace `if (priority != ActiveBlockerPriority.None) {` with:

```kotlin
        val priorityCapEnd = fired.reviewDate.atTime(reviewTime).atZone(zoneId).toInstant()
            .plus(MORNING_REVIEW_PRIORITY_CAP)
        if (priority == ActiveBlockerPriority.Start && now.isBefore(priorityCapEnd)) {
```

In `MorningReviewScheduler.kt:55`, change the doc comment to
`/** Defers only the currently persisted occurrence while an active Start blocker has priority. */`.

- [x] **Step 5: Run the JVM tests**

Run: `./gradlew :androidApp:testDebugUnitTest`
Expected: PASS. Fix any remaining compile references to `ActiveBlockerPriority.Deadline`
(`grep -rn "ActiveBlockerPriority.Deadline" client` must print nothing).

- [x] **Step 6: Migrate the instrumentation test and e2e fixtures**

In `MorningReviewAlarmInstrumentationTest.kt`, replace the whole
`cachedStartAndDeadlinePriorityDeferReviewThenClearedCacheAllowsRetryDelivery` test (lines 137–164)
with:

```kotlin
    @Test
    fun cachedOverdueTasksNeverDeferReview() {
        clearMorningReviewState()
        fakeBackend.reset("overdue")
        runBlocking { EnforcementDependencies.coordinator(context).forceSyncAndReconcile() }
        grantNotifications()
        MorningReviewDependencies.scheduler(context).snooze(Instant.now(), ZoneId.systemDefault())
        val fired = requireNotNull(stateStore.read().scheduledAlarm)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val notificationId = AlarmIntentFactory.requestCode(MORNING_REVIEW_TASK_ID, fired.occurrenceKey, AlarmKind.MORNING_REVIEW)

        AlarmReceiver().onReceive(
            context,
            AlarmIntentFactory.receiverIntent(context, MORNING_REVIEW_TASK_ID, fired.occurrenceKey, AlarmKind.MORNING_REVIEW)
        )

        assertEquals(1, waitForNotification(notifications, notificationId).size)
        assertNotEquals(MorningReviewAlarmReason.PriorityRetry, stateStore.read().scheduledAlarm?.reason)
        notifications.cancel(notificationId)
    }
```

Add `import org.junit.Assert.assertNotEquals` if the file does not already import it. Remove the
`java.io.File` import if nothing else uses it.

In `backend/test/fakes/e2eFixtures.ts`:
- Line 34: remove `| "priorityStart" | "priorityDeadline"` from `E2EMorningReviewScenario`.
- Lines 44–45: delete the `priorityStart` and `priorityDeadline` entries.
- Lines 519–524: delete `const omitPriorityAuthority = ...` and drop `&& !omitPriorityAuthority` from both filters.

In `backend/test/fakes/e2eFixtures.test.ts`, delete lines 56–59 (the four `priorityStart`/`priorityDeadline` expectations).

In `backend/test/e2eServer.ts:204`, delete `|| value === "priorityStart" || value === "priorityDeadline"`.

- [x] **Step 7: Verify backend and instrumentation**

Run (from `backend`): `npm test -- test/fakes/e2eFixtures.test.ts` and then `npm run typecheck`
Expected: PASS / exits 0.

Run (from the worktree root; emulator `emulator-5554` booted):
```bash
ANDROID_SERIAL=emulator-5554 ANDROID_TEST_CLASS=app.buzer.android.morningreview.MorningReviewAlarmInstrumentationTest scripts/e2e-real-backend.sh
```
Expected: all tests in the class PASS. On the old code, `cachedOverdueTasksNeverDeferReview`
would fail, because the review would be deferred as `PriorityRetry`.

- [x] **Step 8: Commit**

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewAlarmHandler.kt client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewScheduleCalculator.kt client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewScheduler.kt client/androidApp/src/test/kotlin/app/buzer/android/morningreview/BlockerPriorityResolverTest.kt client/androidApp/src/test/kotlin/app/buzer/android/morningreview/MorningReviewAlarmHandlerTest.kt client/androidApp/src/androidTest/kotlin/app/buzer/android/morningreview/MorningReviewAlarmInstrumentationTest.kt backend/test/fakes/e2eFixtures.ts backend/test/fakes/e2eFixtures.test.ts backend/test/e2eServer.ts
git commit -m "fix: let only an active start blocker delay the morning review, for at most 15 minutes

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Morning Review sync retries once, silently

**Files:**
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewSessionState.kt:43-54`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/blockers/MorningReviewActivity.kt:85-94`
- Test: `client/androidApp/src/test/kotlin/app/buzer/android/morningreview/MorningReviewSessionStateTest.kt`

**Interfaces:**
- Produces: `suspend fun MorningReviewSessionController.forceSyncWithRetry(retryDelayMillis: Long, operation: suspend () -> SnapshotViewState): Exception?` (replaces `forceSync`)

- [x] **Step 1: Write the failing tests**

Add to `MorningReviewSessionStateTest` (add imports `kotlin.test.assertFalse`, `kotlin.test.assertNull` and `kotlin.test.assertTrue` if missing):

```kotlin
    @Test
    fun forcedSyncRetriesOnceSilentlyAfterAFailure() = runBlocking {
        val fresh = snapshot(scheduleVersion = 2)
        val published = mutableListOf<MorningReviewSessionState>()
        val controller = MorningReviewSessionController({ published += it }, MorningReviewSessionState().cache(snapshot()))
        var calls = 0

        val failure = controller.forceSyncWithRetry(retryDelayMillis = 1) {
            calls += 1
            if (calls == 1) error("todoist 503") else fresh
        }

        assertNull(failure)
        assertEquals(2, calls)
        assertEquals(fresh, controller.state.snapshot)
        assertTrue(published.none { it.refreshError != null })
    }

    @Test
    fun forcedSyncShowsTheErrorOnlyAfterTheRetryFails() = runBlocking {
        val cached = snapshot()
        val controller = MorningReviewSessionController({}, MorningReviewSessionState().cache(cached))
        var calls = 0

        val failure = controller.forceSyncWithRetry(retryDelayMillis = 1) {
            calls += 1
            error("todoist 503 #$calls")
        }

        assertEquals("todoist 503 #2", failure?.message)
        assertEquals(2, calls)
        assertEquals("todoist 503 #2", controller.state.refreshError)
        assertEquals(cached, controller.state.snapshot)
        assertFalse(controller.state.refreshInFlight)
    }
```

- [x] **Step 2: Run the tests to verify they fail**

Run (from `client`): `./gradlew :androidApp:testDebugUnitTest --tests '*MorningReviewSessionStateTest*'`
Expected: compilation FAILS (`forceSyncWithRetry` is unresolved).

- [x] **Step 3: Implement the retry**

In `MorningReviewSessionState.kt`, add the import `import kotlinx.coroutines.delay`, and replace
`suspend fun forceSync(...)` (lines 43–54) with:

```kotlin
    /**
     * Forced sync that absorbs one transient failure: Todoist's 5xx spike at the top of the hour
     * usually clears within seconds (decision 2026-09-29). Only a second failure is surfaced.
     */
    suspend fun forceSyncWithRetry(
        retryDelayMillis: Long,
        operation: suspend () -> SnapshotViewState
    ): Exception? {
        if (!beginForcedSync()) return null
        attemptForcedSync(operation) ?: return null
        delay(retryDelayMillis)
        val error = attemptForcedSync(operation) ?: return null
        forceSyncFailed(error.message ?: "snapshot refresh failed")
        return error
    }

    private suspend fun attemptForcedSync(operation: suspend () -> SnapshotViewState): Exception? =
        try {
            forceSyncSucceeded(operation())
            null
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            error
        }
```

In `MorningReviewActivity.kt`, add
`private const val FORCED_SYNC_RETRY_DELAY_MILLIS = 30_000L` at file top level, below the
imports. Then replace `fun runForcedRefresh()` (lines 85–94) with:

```kotlin
        fun runForcedRefresh() {
            val currentCoordinator = coordinator ?: return
            lifecycleScope.launch {
                session.forceSyncWithRetry(FORCED_SYNC_RETRY_DELAY_MILLIS) {
                    SnapshotReducer.reduce(currentCoordinator.forceSyncAndReconcile())
                }?.let { error ->
                    val message = error.message ?: "snapshot refresh failed"
                    AppLog.error("morning_review forced sync failed", error)
                    // The inline refresh warning already tells the owner; no system notification.
                    errorReporter.report(
                        BlockerAction.REFRESH_SNAPSHOT,
                        null,
                        message,
                        showNotification = false,
                        throwable = error
                    )
                }
            }
        }
```

- [x] **Step 4: Run the tests**

Run: `./gradlew :androidApp:testDebugUnitTest`
Expected: PASS. `grep -rn "\.forceSync {" client/androidApp/src` must print nothing.

- [x] **Step 5: Commit**

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewSessionState.kt client/androidApp/src/main/kotlin/app/buzer/android/blockers/MorningReviewActivity.kt client/androidApp/src/test/kotlin/app/buzer/android/morningreview/MorningReviewSessionStateTest.kt
git commit -m "fix: retry the morning review sync once and drop its system notification

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Boot runs only the cached reconcile

**Files:**
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/BootReconcileRunner.kt`

There is no existing test, and `runProductionWork` needs a real `Context`. Verification is by
compiling, the JVM suite, and Task 8's e2e run. The change is a deletion.

- [x] **Step 1: Remove the refresh**

Replace the whole file with:

```kotlin
package app.buzer.android.enforcement

import android.content.BroadcastReceiver
import android.content.Context
import app.buzer.shared.diagnostics.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Background-safe boot recovery. Runs short work off a [BroadcastReceiver.goAsync] result so
 * alarms are rescheduled after reboot without starting an activity (which Android blocks from
 * the background with BAL_BLOCK).
 *
 * Only the cached reconcile runs here (decision 2026-09-29-reminder-reliability): the network
 * refresh outlived the 60 s boot-broadcast window (BUZER-ANDROID-D). The next app open
 * refreshes as usual.
 */
object BootReconcileRunner {
    fun run(
        context: Context,
        pendingResult: BroadcastReceiver.PendingResult,
        work: suspend (Context) -> Unit = ::runProductionWork
    ) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                AppLog.info("boot_reconcile received")
                work(context.applicationContext)
                AppLog.info("boot_reconcile complete")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                AppLog.error("boot_reconcile failed ${error.message}", error)
                BlockerErrorReporter(context).report(
                    action = BlockerAction.REFRESH_SNAPSHOT,
                    taskId = null,
                    message = error.message ?: "boot reconcile failed",
                    showNotification = false,
                    throwable = error
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    @Suppress("RedundantSuspendModifier")
    private suspend fun runProductionWork(context: Context) {
        val cached = EnforcementDependencies.coordinator(context).reconcileLatestSnapshot()
        AppLog.info("boot_reconcile cached=$cached")
    }
}
```

- [x] **Step 2: Build and test**

Run (from `client`): `./gradlew :androidApp:assembleDebug :androidApp:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [x] **Step 3: Commit**

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/enforcement/BootReconcileRunner.kt
git commit -m "fix: run only the cached reconcile at boot to stay inside the broadcast window

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Docs, full verification, handoff

**Files:**
- Modify: `docs/superpowers/plans/2026-08-02-daily-morning-review.md:176-180`
- Modify: `docs/runbooks/observability-triage.md` (§4 Cross-System Correlation)
- Modify: `docs/notes/vps-deployment.md` (Environment block)
- Create: `docs/handoffs/2026-09-29-reminder-reliability-implementation.md`

- [x] **Step 1: Update the Morning Review plan text**

In `docs/superpowers/plans/2026-08-02-daily-morning-review.md`, replace item 5
(`enforce blocker priority \`Deadline > Start > Morning Review\` ...`) with:

```text
5. enforce blocker priority from the latest cached snapshot: only an active Start blocker outranks Morning Review, and only until 15 minutes after the review date's 08:00 (amended by `docs/decisions/2026-09-29-reminder-reliability.md`);
```

Replace the paragraph that starts `Derive higher priority from unfinished, valid owner tasks` with:

```text
Derive priority from unfinished, valid owner tasks using the same half-open interval as Home: start when `start <= now < end` and `startedAt == null`. Overdue work (`now >= end`) never outranks the review (amended 2026-09-29; the original "deadline when `now >= start + duration`" rule suppressed the review all day whenever any task was overdue). Exclude invalid, pending, unassigned, completed, and deleted entries. If no cached snapshot exists, allow Morning Review rather than suppressing it on a guess.
```

- [x] **Step 2: Document where backend operational failures live**

In `docs/runbooks/observability-triage.md`, add at the end of §4, after "3. **By Timestamp & Release**":

```markdown
4. **Backend operational failures (Sentry Logs, not Issues)**:
   - Expected upstream failures are not captured as issues. They are written to the
     `buzer-backend` project's **Logs** as `warn` records: `forced_refresh_failed`
     (attribute `request_id`) and `todoist_poll_failed` (attributes `error_kind` and
     `consecutive_failure_count`). Search `request_id:<id>` there. For the phone side, search
     the same id in `buzer-android` Logs, in the `backend_request_failed` line.
   - The container stdout (`docker logs buzer-backend`) only has Fastify access lines for
     these requests (method, URL, status, response time).
```

- [x] **Step 3: Document the owner-timezone fallback**

In `docs/notes/vps-deployment.md`, add this to the `.env` "Expected shape" block, after
`SENTRY_ENVIRONMENT=production-vps`:

```env
# Optional. Floating Todoist due times use the owner's Todoist timezone; this is only the fallback.
OWNER_TIMEZONE=Europe/Bratislava
```

- [x] **Step 4: Full backend verification**

Run (from `backend`): `npm test` and then `npm run typecheck`
Expected: PASS / exits 0.

- [x] **Step 5: Full client JVM verification**

Run (from `client`): `./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [x] **Step 6: Full instrumentation verification (required: UI and user-visible behavior changed)**

Run (from the worktree root; emulator booted):
```bash
ANDROID_SERIAL=emulator-5554 scripts/e2e-real-backend.sh
```
Expected: the whole suite PASSES, with no `ANDROID_TEST_CLASS` filter. If
`MorningReviewRepairHostTest` or `DeadlineBlockerUpdateHostTest` fails on a time label, record
it: those fixtures use offset-less times that only the unchanged editor should read. Migrate
the fixture to `"2026-07-24T09:00:00Z"` only if the failure shows a strict parser reading it.

> **Result (2026-09-29/30):** the first full run (emulator-5554, with
> `CONNECTED_DEVICE_BACKEND_URL=http://10.0.2.2:18433`) ran 115 tests, 111 passed. Two failures were real and
> caused by Task 6's 30 s retry: `BlockerActivityFakeBackendTest.morningReview_cleanAndNoDataStartAcknowledgeAndClose`
> and `.morningReview_refreshRetainsCachedInvalidAndRetryUsesTasksSnapshot` (both waited 15 s for
> a failed-sync UI state). This plan's "existing suites" table missed `BlockerActivityFakeBackendTest`.
> They were migrated (45 s waits, test-only) and the class re-run passed 43/43. Two other failures
> were flakes that passed on a class re-run. `MorningReviewRepairHostTest` and
> `DeadlineBlockerUpdateHostTest` passed. The full unfiltered suite then passed at `4c60da7`
> (2026-09-30): 115 run, 0 failed, `BUILD SUCCESSFUL in 5m 47s`; the two earlier flakes did not recur.
> Details: `docs/handoffs/2026-09-29-reminder-reliability-implementation.md`.

- [x] **Step 7: Write the handoff**

Create `docs/handoffs/2026-09-29-reminder-reliability-implementation.md` following AGENTS.md
"Handoff Requirements". It must include a `Verification:` block for each of Steps 4–6, the
checkbox status of Tasks 0–8, and any test whose expectation was changed in Task 3 Step 4.

- [x] **Step 8: Commit**

```bash
git add docs/superpowers/plans/2026-08-02-daily-morning-review.md docs/runbooks/observability-triage.md docs/notes/vps-deployment.md docs/handoffs/2026-09-29-reminder-reliability-implementation.md docs/superpowers/plans/2026-09-29-reminder-reliability.md
git commit -m "docs: record reminder reliability rollout notes and handoff

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Rollout (owner-approved steps, after the branch is reviewed)

These are outward-facing. Do each one only with the owner's explicit go-ahead.

1. Merge `feature/reminder-reliability` into `master` and push. Check CI:
   `gh run list --workflow=ci.yml --limit 1`.
2. Deploy the backend: `ssh oc-vps.taila50381.ts.net /home/skclaw/buzer-app/scripts/deploy-vps.sh`,
   then `curl --fail https://oc-vps.taila50381.ts.net/buzer/health`. This alone stops
   BUZER-ANDROID-E on the installed APK.
3. Build and install the new APK on the phone, using the same device-install path as release
   `e859f1e`.
4. Live checks:
   - Sentry: no new BUZER-ANDROID-E events after the deploy.
   - `buzer-android` Logs show `overlay_start skipped … kind=MORNING_REVIEW` every morning at 08:00.
   - On 2026-10-11, the two floating tasks ("Benčekovci návšteva", "Shebby návšteva")
     log `overlay_start attempted … kind=START_BLOCKER` at 09:00 CEST (07:00Z), not 11:00.
