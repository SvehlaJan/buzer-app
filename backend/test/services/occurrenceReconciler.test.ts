import { describe, expect, it } from "vitest";
import {
  OccurrenceReconciler,
  recurrenceFingerprint,
  type OccurrenceScheduleFacts
} from "../../src/services/occurrenceReconciler.js";

const recurring = {
  dueDatetime: "2026-07-15T09:00:00Z",
  isRecurring: true,
  dueString: "every day at 09:00",
  dueLang: "en",
  dueTimezone: "Europe/Bratislava"
};

describe("OccurrenceReconciler", () => {
  const reconciler = new OccurrenceReconciler();

  it("initializes an opaque occurrence for the first typed backfill", () => {
    const decision = reconciler.decide({
      current: { occurrenceKey: null, occurrenceDueDatetime: null, occurrenceRecurrenceFingerprint: null },
      incoming: recurring,
      isOwned: true,
      wasOwned: true,
      source: "sync",
      checkedChildIds: []
    });

    expect(decision).toEqual({ kind: "rotate", reason: "initialized" });
  });

  it("leaves an unchanged authoritative occurrence alone", () => {
    const decision = reconciler.decide({
      current: anchored(recurring),
      incoming: recurring,
      isOwned: true,
      wasOwned: true,
      source: "sync",
      checkedChildIds: []
    });

    expect(decision).toEqual({ kind: "unchanged" });
  });

  it("rotates a one-time occurrence after an external schedule edit", () => {
    const oneTime = { ...recurring, isRecurring: false, dueString: null, dueLang: null, dueTimezone: null };
    const decision = reconciler.decide({
      current: anchored(oneTime),
      incoming: { ...oneTime, dueDatetime: "2026-07-15T10:00:00Z" },
      isOwned: true,
      wasOwned: true,
      source: "sync",
      checkedChildIds: []
    });

    expect(decision).toEqual({ kind: "rotate", reason: "external_schedule" });
  });

  it("holds an externally advanced recurring occurrence until checked children are reset", () => {
    const decision = reconciler.decide({
      current: anchored(recurring),
      incoming: { ...recurring, dueDatetime: "2026-07-16T09:00:00Z" },
      isOwned: true,
      wasOwned: true,
      source: "sync",
      checkedChildIds: ["child-2", "child-1"]
    });

    expect(decision).toEqual({ kind: "reset_children_then_rotate", checkedChildIds: ["child-2", "child-1"] });
  });

  it("rotates a recurring occurrence after an external due advance with no checked children", () => {
    const decision = reconciler.decide({
      current: anchored(recurring),
      incoming: { ...recurring, dueDatetime: "2026-07-16T09:00:00Z" },
      isOwned: true,
      wasOwned: true,
      source: "sync",
      checkedChildIds: []
    });

    expect(decision).toEqual({ kind: "rotate", reason: "recurring_advance" });
  });

  it("rotates when an external recurrence rule changes", () => {
    const decision = reconciler.decide({
      current: anchored(recurring),
      incoming: { ...recurring, dueString: "every weekday at 09:00" },
      isOwned: true,
      wasOwned: true,
      source: "sync",
      checkedChildIds: []
    });

    expect(decision).toEqual({ kind: "rotate", reason: "external_schedule" });
  });

  it("does not rotate for a title-only sync", () => {
    const decision = reconciler.decide({
      current: anchored(recurring),
      incoming: recurring,
      isOwned: true,
      wasOwned: true,
      source: "sync",
      checkedChildIds: []
    });

    expect(decision).toEqual({ kind: "unchanged" });
  });

  it("rotates an explicit Buzer schedule replan", () => {
    const decision = reconciler.decide({
      current: anchored(recurring),
      incoming: { ...recurring, dueDatetime: "2026-07-15T10:00:00Z" },
      isOwned: true,
      wasOwned: true,
      source: "editor_replan",
      checkedChildIds: []
    });

    expect(decision).toEqual({ kind: "rotate", reason: "editor_replan" });
  });

  it("preserves occurrence history for the Extend 30 exception", () => {
    const decision = reconciler.decide({
      current: anchored(recurring),
      incoming: { ...recurring, dueDatetime: "2026-07-15T09:00:00Z" },
      isOwned: true,
      wasOwned: true,
      source: "extend",
      checkedChildIds: []
    });

    expect(decision).toEqual({ kind: "unchanged" });
  });

  it("leaves enforcement ownership without rotating", () => {
    const decision = reconciler.decide({
      current: anchored(recurring),
      incoming: recurring,
      isOwned: false,
      wasOwned: true,
      source: "sync",
      checkedChildIds: []
    });

    expect(decision).toEqual({ kind: "leave_ownership" });
  });

  it("rotates when a task returns to enforcement ownership", () => {
    const decision = reconciler.decide({
      current: anchored(recurring),
      incoming: recurring,
      isOwned: true,
      wasOwned: false,
      source: "sync",
      checkedChildIds: []
    });

    expect(decision).toEqual({ kind: "rotate", reason: "ownership_returned" });
  });
});

function anchored(task: OccurrenceScheduleFacts): {
  occurrenceKey: string;
  occurrenceDueDatetime: string | null;
  occurrenceRecurrenceFingerprint: string;
} {
  return {
    occurrenceKey: "opaque-existing-key",
    occurrenceDueDatetime: task.dueDatetime,
    occurrenceRecurrenceFingerprint: recurrenceFingerprint(task)
  };
}
