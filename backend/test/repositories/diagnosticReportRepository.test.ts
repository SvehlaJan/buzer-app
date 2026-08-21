import Database from "better-sqlite3";
import { describe, expect, it } from "vitest";
import { migrate } from "../../src/db/migrations.js";
import {
  DiagnosticReportRepository,
  type DiagnosticReportRow
} from "../../src/repositories/diagnosticReportRepository.js";

describe("DiagnosticReportRepository", () => {
  it("saves a diagnostic report and finds it by reportSessionId", () => {
    const db = new Database(":memory:");
    migrate(db);

    const repo = new DiagnosticReportRepository(db);

    expect(repo.findByReportSessionId("non-existent")).toBeNull();

    const row: DiagnosticReportRow = {
      reportSessionId: "session-repo-1",
      androidDiagnosticEventId: "android-evt-repo-1",
      backendDiagnosticEventId: "backend-evt-repo-1",
      deviceId: "device-repo-1",
      capturedAt: "2026-08-21T12:00:00Z",
      responseJson: JSON.stringify({
        schemaVersion: 1,
        reportSessionId: "session-repo-1",
        backendDiagnosticEventId: "backend-evt-repo-1",
        outcome: "captured"
      }),
      createdAt: "2026-08-21T12:00:01Z"
    };

    repo.saveReport(row);

    const found = repo.findByReportSessionId("session-repo-1");
    expect(found).toEqual(row);
  });
});
