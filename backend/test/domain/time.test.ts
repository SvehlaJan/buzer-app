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
    expect(computeStartAt("2026-10-11T09:00:00", 15, "Asia/Tokyo")).toBe("2026-10-11T00:00:00.000Z");
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
    expect(computeDeadlineAt("2026-10-11T09:00:00", 15, "Asia/Tokyo")).toBe("2026-10-11T00:15:00.000Z");
  });

  it("rejects non-positive durations", () => {
    expect(() => computeDeadlineAt("2026-07-05T09:30:00.000Z", -5, TZ)).toThrow();
  });
});
