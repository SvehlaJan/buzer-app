import type Database from "better-sqlite3";
import { z } from "zod";
import type { EscalationService } from "../services/escalationService.js";
import type { TodoistSyncService } from "../services/todoistSyncService.js";
import { collectDiagnosticsCapsule } from "./backendDiagnosticCollector.js";
import type { BackendObservability, DiagnosticCorrelation } from "./backendObservability.js";

const MAX_OPAQUE_VALUE_LENGTH = 200;

export const diagnosticReportRequestSchema = z.strictObject({
  schemaVersion: z.number().int().default(1),
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
  constructor(
    private readonly db: Database.Database,
    private readonly observability: BackendObservability,
    private readonly synchronizer?: TodoistSyncService | null,
    private readonly escalationService?: EscalationService | null
  ) {}

  async handleReport(request: DiagnosticReportRequest): Promise<DiagnosticReportResponse> {
    const backendDiagnosticEventId = this.observability.newEventId();

    const { attachments } = await collectDiagnosticsCapsule({
      reportSessionId: request.reportSessionId,
      androidDiagnosticEventId: request.androidDiagnosticEventId,
      backendDiagnosticEventId,
      capturedAt: request.capturedAt,
      deviceId: request.deviceId,
      taskId: request.taskId,
      occurrenceKey: request.occurrenceKey,
      actionId: request.actionId,
      requestIds: request.requestIds,
      traceId: request.traceId,
      db: this.db,
      synchronizer: this.synchronizer,
      escalationService: this.escalationService
    });

    const correlation: DiagnosticCorrelation = {
      deviceId: request.deviceId,
      reportSessionId: request.reportSessionId,
      ...(request.taskId ? { taskId: request.taskId } : {}),
      ...(request.occurrenceKey ? { occurrenceKey: request.occurrenceKey } : {}),
      ...(request.actionId ? { actionId: request.actionId } : {}),
      ...(request.traceId ? { traceId: request.traceId } : {})
    };

    const capturedEventId = this.observability.captureDiagnosticEvent(
      backendDiagnosticEventId,
      request.reportSessionId,
      true,
      correlation,
      attachments
    );

    return {
      schemaVersion: 1,
      reportSessionId: request.reportSessionId,
      backendDiagnosticEventId: capturedEventId || backendDiagnosticEventId,
      outcome: "captured"
    };
  }
}
