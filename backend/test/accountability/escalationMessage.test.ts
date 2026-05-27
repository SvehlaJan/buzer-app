import { describe, expect, it } from "vitest";
import { buildEscalationMessage } from "../../src/accountability/escalationMessage.js";

describe("buildEscalationMessage", () => {
  it("creates a clear accountability message", () => {
    expect(buildEscalationMessage({
      taskTitle: "Submit insurance form",
      deadlineAt: "2026-05-08T18:00:00Z",
      dismissCount: 2
    })).toBe("I postponed starting \"Submit insurance form\" 2 times. Deadline: 2026-05-08T18:00:00Z.");
  });
});
