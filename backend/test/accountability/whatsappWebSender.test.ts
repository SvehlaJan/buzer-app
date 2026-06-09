import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { removeStaleChromiumSingletonLocks } from "../../src/accountability/whatsappWebSender.js";

describe("WhatsAppWebSender", () => {
  it("removes stale Chromium singleton locks without deleting session data", () => {
    const root = mkdtempSync(join(tmpdir(), "buzer-whatsapp-session-"));
    const session = join(root, "session-buzer");
    mkdirSync(session);
    writeFileSync(join(session, "SingletonLock"), "stale lock");
    writeFileSync(join(session, "SingletonSocket"), "stale socket");
    writeFileSync(join(session, "SingletonCookie"), "stale cookie");
    writeFileSync(join(session, "Default"), "session data");

    try {
      const removed = removeStaleChromiumSingletonLocks(root);

      expect(removed.map((path) => path.split("/").pop()).sort()).toEqual([
        "SingletonCookie",
        "SingletonLock",
        "SingletonSocket"
      ]);
      expect(readFileSync(join(session, "Default"), "utf8")).toBe("session data");
    } finally {
      rmSync(root, { recursive: true, force: true });
    }
  });
});
