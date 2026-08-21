import Database from "better-sqlite3";

export interface DiagnosticReportRow {
  reportSessionId: string;
  androidDiagnosticEventId: string;
  backendDiagnosticEventId: string;
  deviceId: string;
  capturedAt: string;
  responseJson: string;
  createdAt: string;
}

export class DiagnosticReportRepository {
  constructor(private readonly db: Database.Database) {}

  findByReportSessionId(reportSessionId: string): DiagnosticReportRow | null {
    const row = this.db.prepare(`
      select
        report_session_id as reportSessionId,
        android_diagnostic_event_id as androidDiagnosticEventId,
        backend_diagnostic_event_id as backendDiagnosticEventId,
        device_id as deviceId,
        captured_at as capturedAt,
        response_json as responseJson,
        created_at as createdAt
      from diagnostic_reports
      where report_session_id = ?
    `).get(reportSessionId) as DiagnosticReportRow | undefined;

    return row ?? null;
  }

  saveReport(row: DiagnosticReportRow): void {
    this.db.prepare(`
      insert into diagnostic_reports (
        report_session_id,
        android_diagnostic_event_id,
        backend_diagnostic_event_id,
        device_id,
        captured_at,
        response_json,
        created_at
      ) values (
        @reportSessionId,
        @androidDiagnosticEventId,
        @backendDiagnosticEventId,
        @deviceId,
        @capturedAt,
        @responseJson,
        @createdAt
      )
    `).run(row);
  }
}
