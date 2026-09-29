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
