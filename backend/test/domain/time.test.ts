import { describe, expect, it } from "vitest";
import { computeDeadlineAt, computeStartAt } from "../../src/domain/time.js";

describe("computeStartAt", () => {
  it("returns the due datetime unchanged (due = start of block)", () => {
    expect(computeStartAt("2026-07-05T09:30:00.000Z", 30)).toBe("2026-07-05T09:30:00.000Z");
  });

  it("rejects non-positive durations", () => {
    expect(() => computeStartAt("2026-07-05T09:30:00.000Z", 0)).toThrow();
  });

  it("rejects invalid timestamps", () => {
    expect(() => computeStartAt("nope", 30)).toThrow();
  });
});

describe("computeDeadlineAt", () => {
  it("returns due plus duration", () => {
    expect(computeDeadlineAt("2026-07-05T09:30:00.000Z", 30)).toBe("2026-07-05T10:00:00.000Z");
  });

  it("rejects non-positive durations", () => {
    expect(() => computeDeadlineAt("2026-07-05T09:30:00.000Z", -5)).toThrow();
  });
});
