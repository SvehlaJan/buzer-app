import * as crypto from "node:crypto";
import type Database from "better-sqlite3";
import { z } from "zod";
import { DiagnosticReportRepository } from "../repositories/diagnosticReportRepository.js";
import type { EscalationService } from "../services/escalationService.js";
import type { TodoistSyncService } from "../services/todoistSyncService.js";
import type { BackendObservability } from "./backendObservability.js";

const MAX_OPAQUE_VALUE_LENGTH = 200;

export const diagnosticReportRequestSchema = z.strictObject({
  schemaVersion: z.literal(1).default(1),
  reportSessionId: z.string().trim().min(1).max(MAX_OPAQUE_VALUE_LENGTH),
  androidDiagnosticEventId: z.string().trim().min(1).max(MAX_OPAQUE_VALUE_LENGTH),
  deviceId: z.string().trim().min(1).max(MAX_OPAQUE_VALUE_LENGTH),
  capturedAt: z.string().trim().min(1).max(MAX_OPAQUE_VALUE_LENGTH),
  taskId: z.string().trim().min(1).max(MAX_OPAQUE_VALUE_LENGTH).nullable().optional(),
  occurrenceKey: z.string().trim().min(1).max(MAX_OPAQUE_VALUE_LENGTH).nullable().optional(),
  actionId: z.string().trim().min(1).max(MAX_OPAQUE_VALUE_LENGTH).nullable().optional(),
  requestIds: z.array(z.string().trim().min(1).max(MAX_OPAQUE_VALUE_LENGTH)).max(20).default([]),
  traceId: z.string().trim().min(1).max(MAX_OPAQUE_VALUE_LENGTH).nullable().optional()
});

export type DiagnosticReportRequest = z.infer<typeof diagnosticReportRequestSchema>;

export interface DiagnosticReportResponse {
  schemaVersion: number;
  reportSessionId: string;
  backendDiagnosticEventId: string;
  outcome: "captured";
}

export class DiagnosticReportService {
  private readonly repository: DiagnosticReportRepository;

  constructor(
    private readonly db: Database.Database,
    _observability?: BackendObservability | null,
    _synchronizer?: TodoistSyncService | null,
    _escalationService?: EscalationService | null,
    repository?: DiagnosticReportRepository
  ) {
    this.repository = repository ?? new DiagnosticReportRepository(db);
  }

  async handleReport(request: DiagnosticReportRequest): Promise<DiagnosticReportResponse> {
    const existing = this.repository.findByReportSessionId(request.reportSessionId);
    if (existing !== null) {
      return JSON.parse(existing.responseJson) as DiagnosticReportResponse;
    }

    const backendDiagnosticEventId = crypto.randomUUID().replace(/-/g, "").toLowerCase();

    const response: DiagnosticReportResponse = {
      schemaVersion: 1,
      reportSessionId: request.reportSessionId,
      backendDiagnosticEventId,
      outcome: "captured"
    };

    this.repository.saveReport({
      reportSessionId: request.reportSessionId,
      androidDiagnosticEventId: request.androidDiagnosticEventId,
      backendDiagnosticEventId: response.backendDiagnosticEventId,
      deviceId: request.deviceId,
      capturedAt: request.capturedAt,
      responseJson: JSON.stringify(response),
      createdAt: new Date().toISOString()
    });

    return response;
  }
}
