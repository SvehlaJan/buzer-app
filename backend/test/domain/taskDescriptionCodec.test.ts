import { describe, expect, it } from "vitest";
import {
  appendExtension,
  BUZER_ACTIVITY_DELIMITER,
  decodeTaskDescription,
  reconstructTaskDescription,
  TODOIST_DESCRIPTION_MAX_CHARS,
} from "../../src/domain/taskDescriptionCodec.js";

const extension = {
  timestamp: "2026-07-15T12:05:00.000Z",
  timeZone: "Europe/Bratislava",
  oldDurationMinutes: 60,
  newDurationMinutes: 90,
};

describe("decodeTaskDescription", () => {
  it("treats a description without the marker as entirely user-owned", () => {
    expect(decodeTaskDescription("A user note\nwith whitespace  ")).toMatchObject({
      userDescription: "A user note\nwith whitespace  ",
      activityLogEntries: [],
      isManaged: false,
    });
  });

  it("splits one valid managed activity suffix and reconstructs it unchanged", () => {
    const description = [
      "User note",
      BUZER_ACTIVITY_DELIMITER,
      "2026-07-15 14:05 Europe/Bratislava — Extended +30 min (60 → 90 min)",
    ].join("\n");
    const decoded = decodeTaskDescription(description);

    expect(decoded).toMatchObject({
      userDescription: "User note",
      isManaged: true,
      activityLogEntries: [
        {
          timestamp: "2026-07-15 14:05",
          timeZone: "Europe/Bratislava",
          message: "Extended +30 min (60 → 90 min)",
        },
      ],
    });
    expect(reconstructTaskDescription(decoded)).toBe(description);
  });

  it("preserves malformed activity content as user text", () => {
    const description = `User note\n${BUZER_ACTIVITY_DELIMITER}\nnot an activity`;

    expect(decodeTaskDescription(description)).toMatchObject({
      userDescription: description,
      activityLogEntries: [],
      isManaged: false,
    });
  });

  it("preserves duplicate or corrupt markers as user text", () => {
    const description = [
      "User note",
      BUZER_ACTIVITY_DELIMITER,
      "2026-07-15 14:05 Europe/Bratislava — Extended +30 min (60 → 90 min)",
      BUZER_ACTIVITY_DELIMITER,
    ].join("\n");

    expect(decodeTaskDescription(description)).toMatchObject({
      userDescription: description,
      activityLogEntries: [],
      isManaged: false,
    });
  });

  it("preserves user whitespace while splitting and reconstructing", () => {
    const description = `  User note  \n\n${BUZER_ACTIVITY_DELIMITER}\n\n2026-07-15 14:05 UTC — Extended +30 min (60 → 90 min)`;
    const decoded = decodeTaskDescription(description);

    expect(decoded.userDescription).toBe("  User note  \n");
    expect(reconstructTaskDescription(decoded)).toBe(description);
  });
});

describe("appendExtension", () => {
  it("appends a managed suffix and preserves Unicode arrow text", () => {
    expect(appendExtension({ description: "User note", ...extension })).toBe(
      `User note\n${BUZER_ACTIVITY_DELIMITER}\n2026-07-15 14:05 Europe/Bratislava — Extended +30 min (60 → 90 min)`,
    );
  });

  it("adds to an existing managed suffix without rewriting its user text", () => {
    const existing = [
      "User note  ",
      BUZER_ACTIVITY_DELIMITER,
      "2026-07-15 13:00 Europe/Bratislava — Extended +30 min (30 → 60 min)",
    ].join("\n");

    expect(appendExtension({ description: existing, ...extension })).toBe(
      `${existing}\n2026-07-15 14:05 Europe/Bratislava — Extended +30 min (60 → 90 min)`,
    );
  });

  it("starts a fresh managed suffix after malformed content without deleting it", () => {
    const malformed = `User note\n${BUZER_ACTIVITY_DELIMITER}\nnot an activity`;

    expect(appendExtension({ description: malformed, ...extension })).toBe(
      `${malformed}\n${BUZER_ACTIVITY_DELIMITER}\n2026-07-15 14:05 Europe/Bratislava — Extended +30 min (60 → 90 min)`,
    );
  });

  it("recovers a trailing managed suffix after malformed marker text", () => {
    const malformed = `User note\n${BUZER_ACTIVITY_DELIMITER}\nnot an activity`;
    const firstExtension = appendExtension({ description: malformed, ...extension });
    const decoded = decodeTaskDescription(firstExtension);

    expect(decoded).toMatchObject({
      userDescription: malformed,
      isManaged: true,
      activityLogEntries: [
        {
          timestamp: "2026-07-15 14:05",
          timeZone: "Europe/Bratislava",
          message: "Extended +30 min (60 → 90 min)",
        },
      ],
    });

    expect(
      appendExtension({
        description: firstExtension,
        timestamp: "2026-07-15T13:05:00.000Z",
        timeZone: "Europe/Bratislava",
        oldDurationMinutes: 90,
        newDurationMinutes: 120,
      }),
    ).toBe(
      `${firstExtension}\n2026-07-15 15:05 Europe/Bratislava — Extended +30 min (90 → 120 min)`,
    );
  });

  it("does not add an extra blank line after user text that already ends at a line boundary", () => {
    expect(appendExtension({ description: "User note\n", ...extension })).toBe(
      `User note\n${BUZER_ACTIVITY_DELIMITER}\n2026-07-15 14:05 Europe/Bratislava — Extended +30 min (60 → 90 min)`,
    );
  });

  it("falls back to UTC when the Todoist time zone is unavailable", () => {
    expect(appendExtension({ description: null, ...extension, timeZone: null })).toBe(
      `${BUZER_ACTIVITY_DELIMITER}\n2026-07-15 12:05 UTC — Extended +30 min (60 → 90 min)`,
    );
  });

  it.each([
    "2026-02-31T12:05:00Z",
    "2026-07-15",
    "2026-07-15T12:05:00",
    "0",
  ])("rejects non-strict extension timestamps: %s", (timestamp) => {
    expect(() => appendExtension({ description: null, ...extension, timestamp })).toThrow(
      "timestamp must be a valid ISO timestamp",
    );
  });

  it("rejects an overflow before a Todoist mutation can be attempted", () => {
    const description = "x".repeat(TODOIST_DESCRIPTION_MAX_CHARS);

    expect(() => appendExtension({ description, ...extension })).toThrow("activity_log_full");
  });
});
