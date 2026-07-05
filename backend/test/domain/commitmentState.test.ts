import { describe, expect, it } from "vitest";
import { applyAction } from "../../src/domain/commitmentState.js";

describe("applyAction", () => {
  it("marks planned task as started", () => {
    const next = applyAction({
      state: "start_blocker_active",
      startDismissCount: 0,
      deadlineExtensionCount: 0,
      startedAt: null,
      escalationSentAt: null
    }, {
      type: "start",
      now: "2026-05-08T15:00:00Z"
    });

    expect(next.state).toBe("started");
    expect(next.startedAt).toBe("2026-05-08T15:00:00Z");
  });

  it("triggers escalation on second start postpone", () => {
    const next = applyAction({
      state: "start_blocker_active",
      startDismissCount: 1,
      deadlineExtensionCount: 0,
      startedAt: null,
      escalationSentAt: null
    }, {
      type: "postpone_start",
      now: "2026-05-08T15:05:00Z"
    });

    expect(next.startDismissCount).toBe(2);
    expect(next.escalationRequired).toBe(true);
  });

  it("increments extension count", () => {
    const next = applyAction({
      state: "deadline_blocker_active",
      startDismissCount: 0,
      deadlineExtensionCount: 2,
      startedAt: "2026-05-08T15:00:00Z",
      escalationSentAt: null
    }, {
      type: "extend_deadline",
      now: "2026-05-08T16:00:00Z"
    });

    expect(next.deadlineExtensionCount).toBe(3);
    expect(next.state).toBe("started");
  });
});
