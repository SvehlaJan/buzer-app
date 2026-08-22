import Database from "better-sqlite3";
import { describe, expect, it } from "vitest";
import { migrate } from "../src/db/migrations.js";
import { buildServer } from "../src/server.js";
import { FakeTodoistAdapter } from "./fakes/fakeTodoistAdapter.js";

describe("POST /diagnostics/reports endpoint absence", () => {
  it("returns 404 for POST /diagnostics/reports", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const app = buildServer({
      db,
      currentUserId: "user-1",
      androidApiToken: "secret-token",
      todoist: new FakeTodoistAdapter()
    });

    const response = await app.inject({
      method: "POST",
      url: "/diagnostics/reports",
      headers: { authorization: "Bearer secret-token" },
      payload: {
        schemaVersion: 1,
        reportSessionId: "session-1",
        androidDiagnosticEventId: "android-evt-1",
        deviceId: "device-1",
        capturedAt: "2026-08-21T12:00:00Z"
      }
    });

    expect(response.statusCode).toBe(404);
    await app.close();
  });
});
