import { describe, expect, it } from "vitest";
import {
  describeRecurrence,
  formatOrdinalDay,
  translateRecurrencePreset,
  type RecurrencePreset,
} from "../../src/domain/taskRecurrence.js";

describe("translateRecurrencePreset", () => {
  const selectedStart = "2026-07-15T12:05:00.000Z";
  const timeZone = "Europe/Bratislava";

  it.each<[RecurrencePreset, string | null]>([
    ["never", null],
    ["daily", "every day at 14:05"],
    ["weekdays", "every weekday at 14:05"],
    ["weekly", "every Wednesday at 14:05"],
    ["monthly", "every month on the 15th at 14:05"],
    ["yearly", "every year on July 15th at 14:05"],
  ])("translates %s from the selected local start", (preset, expectedRule) => {
    expect(translateRecurrencePreset({ preset, selectedStart, timeZone })).toEqual(
      expectedRule === null
        ? null
        : {
            string: expectedRule,
            lang: "en",
            timezone: timeZone,
          },
    );
  });

  it("preserves the selected IANA zone across a daylight-saving transition", () => {
    expect(
      translateRecurrencePreset({
        preset: "weekly",
        selectedStart: "2026-03-29T01:30:00.000Z",
        timeZone: "Europe/Bratislava",
      }),
    ).toEqual({
      string: "every Sunday at 03:30",
      lang: "en",
      timezone: "Europe/Bratislava",
    });
  });

  it.each([
    "2026-02-31T12:05:00Z",
    "2026-07-15",
    "2026-07-15T12:05:00",
    "0",
  ])("rejects non-strict selected starts: %s", (selectedStart) => {
    expect(() =>
      translateRecurrencePreset({
        preset: "daily",
        selectedStart,
        timeZone,
      }),
    ).toThrow("selectedStart must be a valid ISO timestamp");
  });
});

describe("formatOrdinalDay", () => {
  it.each([
    [1, "1st"],
    [2, "2nd"],
    [3, "3rd"],
    [4, "4th"],
    [11, "11th"],
    [12, "12th"],
    [13, "13th"],
    [21, "21st"],
  ])("formats %i as %s", (day, expected) => {
    expect(formatOrdinalDay(day)).toBe(expected);
  });
});

describe("describeRecurrence", () => {
  it.each<[RecurrencePreset, string]>([
    ["daily", "every day at 14:05"],
    ["weekdays", "every weekday at 14:05"],
    ["weekly", "every Wednesday at 14:05"],
    ["monthly", "every month on the 15th at 14:05"],
    ["yearly", "every year on July 15th at 14:05"],
  ])("recognizes the exact Buzer-generated %s rule", (preset, rule) => {
    expect(
      describeRecurrence({
        isRecurring: true,
        rule,
        language: "en",
        timeZone: "Europe/Bratislava",
      }),
    ).toEqual({ kind: "supported", preset });
  });

  it("reports a non-recurring task as none", () => {
    expect(
      describeRecurrence({
        isRecurring: false,
        rule: null,
        language: null,
        timeZone: null,
      }),
    ).toEqual({ kind: "none", preset: "never" });
  });

  it.each([
    { rule: "every 2 days at 14:05", language: "en", timeZone: "Europe/Bratislava" },
    { rule: "every wednesday at 14:05", language: "en", timeZone: "Europe/Bratislava" },
    { rule: "every Wednesday at 2:05 PM", language: "en", timeZone: "Europe/Bratislava" },
    { rule: "every Wednesday at 14:05", language: "cs", timeZone: "Europe/Bratislava" },
    { rule: "every Wednesday at 14:05", language: "en", timeZone: null },
    { rule: "every year on February 31st at 14:05", language: "en", timeZone: "Europe/Bratislava" },
  ])("preserves unrecognized Todoist recurrence as custom", ({ rule, language, timeZone }) => {
    expect(describeRecurrence({ isRecurring: true, rule, language, timeZone })).toEqual({
      kind: "custom",
      preset: null,
    });
  });
});
