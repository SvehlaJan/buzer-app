import type Database from "better-sqlite3";
import { CONFIG } from "../config.js";
import type { EscalationService } from "../services/escalationService.js";
import type { TodoistSyncService } from "../services/todoistSyncService.js";
import type { DiagnosticAttachment } from "./backendObservability.js";
import { redactAny, redactObject, redactString } from "./redaction.js";
import { backendDiagnosticRingBuffer, backendLogRingBuffer, RingBuffer, truncateUtf8 } from "./ringBuffer.js";

export type DiagnosticSectionStatusType = "complete" | "partial" | "unavailable" | "failed";

export interface DiagnosticSectionStatus {
  status: DiagnosticSectionStatusType;
  reasonCode?: string;
}

export interface DiagnosticTruncationRecord {
  section: string;
  originalBytes?: number;
  truncatedBytes?: number;
  reason: string;
}

export interface CollectDiagnosticsOptions {
  reportSessionId: string;
  androidDiagnosticEventId: string;
  backendDiagnosticEventId: string;
  capturedAt: string;
  deviceId?: string | undefined;
  taskId?: string | null | undefined;
  occurrenceKey?: string | null | undefined;
  actionId?: string | null | undefined;
  requestIds?: string[] | undefined;
  traceId?: string | null | undefined;
  db?: Database.Database | null | undefined;
  synchronizer?: TodoistSyncService | null | undefined;
  escalationService?: EscalationService | null | undefined;
  logRingBuffer?: RingBuffer<string> | undefined;
  diagnosticRingBuffer?: RingBuffer<any> | undefined;
  maxTotalBytes?: number | undefined;
}

export interface DiagnosticCapsuleResult {
  capsule: Record<string, unknown>;
  attachments: DiagnosticAttachment[];
}

const DEFAULT_MAX_TOTAL_BYTES = 8 * 1024 * 1024; // 8 MiB

export async function collectDiagnosticsCapsule(
  options: CollectDiagnosticsOptions
): Promise<DiagnosticCapsuleResult> {
  const maxBudget = options.maxTotalBytes ?? DEFAULT_MAX_TOTAL_BYTES;
  const sectionStatuses: Record<string, DiagnosticSectionStatus> = {};
  const truncations: DiagnosticTruncationRecord[] = [];

  // 1. Runtime section
  let runtimeSection: Record<string, unknown> = {};
  try {
    runtimeSection = {
      nodeVersion: process.version,
      platform: process.platform,
      arch: process.arch,
      uptimeSeconds: Math.floor(process.uptime()),
      pid: process.pid,
      memoryUsage: process.memoryUsage(),
      environment: CONFIG.SENTRY_ENVIRONMENT ?? process.env.NODE_ENV ?? "development-vps",
      release: CONFIG.SENTRY_RELEASE ?? "buzer-backend@0.1.0+local",
      serverTime: new Date().toISOString()
    };
    sectionStatuses["runtime"] = { status: "complete" };
  } catch (err) {
    sectionStatuses["runtime"] = {
      status: "failed",
      reasonCode: redactString(err instanceof Error ? err.message : String(err))
    };
  }

  // 2. Database section
  let databaseSection: Record<string, unknown> | null = null;
  if (options.db) {
    try {
      const db = options.db;
      if (!db.open) {
        throw new Error("Database connection is closed");
      }
      db.prepare("select 1").get();

      let schemaVersion: number | null = null;
      try {
        const row = db.prepare("select max(version) as version from schema_migrations").get() as { version?: number } | undefined;
        schemaVersion = row?.version ?? null;
      } catch {
        try {
          schemaVersion = Number(db.pragma("user_version", { simple: true })) || null;
        } catch {
          schemaVersion = null;
        }
      }

      let journalMode: string | null = null;
      try {
        journalMode = String(db.pragma("journal_mode", { simple: true }));
      } catch {
        journalMode = null;
      }

      let quickCheck: string | null = null;
      try {
        quickCheck = String(db.pragma("quick_check(1)", { simple: true }));
      } catch {
        quickCheck = null;
      }

      const tableCounts: Record<string, number> = {};
      const countTables = [
        ["tasks", "todoist_tasks"],
        ["projects", "todoist_projects"],
        ["collaborators", "todoist_collaborators"],
        ["devices", "devices"],
        ["actionJournal", "task_action_journal"],
        ["whatsappEscalations", "whatsapp_escalations"]
      ] as const;

      for (const [key, tableName] of countTables) {
        try {
          const row = db.prepare(`select count(*) as c from ${tableName}`).get() as { c: number } | undefined;
          tableCounts[key] = row?.c ?? 0;
        } catch {
          // Table might not exist yet
        }
      }

      let recentActionJournal: Array<Record<string, unknown>> = [];
      try {
        const rows = db.prepare(`
          select action_id, device_id, root_task_id, expected_occurrence_key, action_type, status, created_at, updated_at
          from task_action_journal
          order by created_at desc
          limit 20
        `).all() as Array<Record<string, unknown>>;
        recentActionJournal = rows.map((r) => redactObject(r));
      } catch {
        // Ignored if table doesn't exist
      }

      let matchedTask: Record<string, unknown> | null = null;
      if (options.taskId) {
        try {
          const row = db.prepare(`
            select t.id, t.todoist_task_id, t.title, t.project_id, t.due_date, t.due_datetime,
                   t.is_recurring, t.duration_minutes, t.completed_at, t.deleted_at, t.updated_at,
                   c.state as commitment_state, c.occurrence_key, c.occurrence_due_datetime
            from todoist_tasks t
            left join commitment_states c on c.task_id = t.id
            where t.id = ? or t.todoist_task_id = ?
          `).get(options.taskId, options.taskId) as Record<string, unknown> | undefined;
          if (row) {
            matchedTask = redactObject(row);
          }
        } catch {
          // Ignored
        }
      }

      let matchedAction: Record<string, unknown> | null = null;
      if (options.actionId) {
        try {
          const journalRow = db.prepare(`
            select * from task_action_journal where action_id = ?
          `).get(options.actionId) as Record<string, unknown> | undefined;
          const commandRows = db.prepare(`
            select * from task_action_commands where action_id = ?
          `).all(options.actionId) as Array<Record<string, unknown>>;
          if (journalRow) {
            matchedAction = {
              journal: redactObject(journalRow),
              commands: commandRows.map((c) => redactObject(c))
            };
          }
        } catch {
          // Ignored
        }
      }

      let device: Record<string, unknown> | null = null;
      if (options.deviceId) {
        try {
          const row = db.prepare(`
            select id, app_version, last_check_in_at from devices where id = ?
          `).get(options.deviceId) as Record<string, unknown> | undefined;
          if (row) {
            device = redactObject(row);
          }
        } catch {
          // Ignored
        }
      }

      databaseSection = {
        schemaVersion,
        journalMode,
        quickCheck,
        tableCounts,
        recentActionJournal,
        ...(matchedTask ? { matchedTask } : {}),
        ...(matchedAction ? { matchedAction } : {}),
        ...(device ? { device } : {})
      };
      sectionStatuses["database"] = { status: "complete" };
    } catch (err) {
      sectionStatuses["database"] = {
        status: "failed",
        reasonCode: redactString(err instanceof Error ? err.message : String(err))
      };
    }
  } else {
    sectionStatuses["database"] = { status: "unavailable", reasonCode: "no_db_instance" };
  }

  // 3. Todoist section
  let todoistSection: Record<string, unknown> | null = null;
  if (options.db) {
    try {
      if (!options.db.open) {
        throw new Error("Database connection is closed");
      }
      const row = options.db.prepare(
        "select sync_token, mirror_contract_version, updated_at from todoist_sync_state where id = 1"
      ).get() as { sync_token?: string; mirror_contract_version?: number; updated_at?: string } | undefined;

      if (row) {
        todoistSection = {
          hasSyncToken: Boolean(row.sync_token && row.sync_token.trim() !== ""),
          syncTokenLength: row.sync_token ? row.sync_token.length : 0,
          mirrorContractVersion: row.mirror_contract_version ?? 1,
          lastSyncAt: row.updated_at ?? null
        };
        sectionStatuses["todoist"] = { status: "complete" };
      } else {
        todoistSection = {
          hasSyncToken: false,
          syncTokenLength: 0,
          mirrorContractVersion: null,
          lastSyncAt: null
        };
        sectionStatuses["todoist"] = { status: "partial", reasonCode: "no_sync_state_row" };
      }
    } catch (err) {
      sectionStatuses["todoist"] = {
        status: "failed",
        reasonCode: redactString(err instanceof Error ? err.message : String(err))
      };
    }
  } else {
    sectionStatuses["todoist"] = { status: "unavailable", reasonCode: "no_db_instance" };
  }

  // 4. WhatsApp section
  let whatsappSection: Record<string, unknown> | null = null;
  try {
    const isDryRun = Boolean(CONFIG.WHATSAPP_DRY_RUN);
    const hasRecipient = Boolean(CONFIG.WHATSAPP_RECIPIENT_ID && CONFIG.WHATSAPP_RECIPIENT_ID.trim() !== "");

    let recentEscalations: Array<Record<string, unknown>> = [];
    if (options.db && options.db.open) {
      try {
        const rows = options.db.prepare(`
          select id, task_id, occurrence_key, status, error_message, created_at, sent_at
          from whatsapp_escalations
          order by created_at desc
          limit 10
        `).all() as Array<Record<string, unknown>>;
        recentEscalations = rows.map((r) => redactObject(r));
      } catch {
        // Table might not exist
      }
    }

    whatsappSection = {
      configured: hasRecipient,
      dryRun: isDryRun,
      isReady: isDryRun || Boolean(options.escalationService),
      recentEscalations
    };
    sectionStatuses["whatsapp"] = { status: hasRecipient ? "complete" : "partial" };
  } catch (err) {
    sectionStatuses["whatsapp"] = {
      status: "failed",
      reasonCode: redactString(err instanceof Error ? err.message : String(err))
    };
  }

  // 5. Build raw logs and polling history
  const logBuffer = options.logRingBuffer ?? backendLogRingBuffer;
  const rawLogEntries = logBuffer.snapshot();
  const redactedLogEntries = rawLogEntries.map((l) => redactString(l));
  let fullLogsText = redactedLogEntries.join("\n");
  sectionStatuses["logs"] = { status: "complete" };

  const diagBuffer = options.diagnosticRingBuffer ?? backendDiagnosticRingBuffer;
  const rawDiagEntries = diagBuffer.snapshot();
  const redactedDiagEntries = rawDiagEntries.map((d) => redactAny(d));
  let fullDiagJson = JSON.stringify(redactedDiagEntries, null, 2);
  sectionStatuses["networkHistory"] = { status: "complete" };

  // 6. Assemble base capsule
  const capsule: Record<string, unknown> = {
    schemaVersion: 1,
    reportSessionId: options.reportSessionId,
    androidDiagnosticEventId: options.androidDiagnosticEventId,
    backendDiagnosticEventId: options.backendDiagnosticEventId,
    capturedAt: options.capturedAt,
    ...(options.deviceId ? { deviceId: options.deviceId } : {}),
    ...(options.taskId ? { taskId: options.taskId } : {}),
    ...(options.occurrenceKey ? { occurrenceKey: options.occurrenceKey } : {}),
    ...(options.actionId ? { actionId: options.actionId } : {}),
    ...(options.requestIds && options.requestIds.length > 0 ? { requestIds: options.requestIds } : {}),
    ...(options.traceId ? { traceId: options.traceId } : {}),
    runtime: runtimeSection,
    ...(databaseSection ? { database: databaseSection } : {}),
    ...(todoistSection ? { todoist: todoistSection } : {}),
    ...(whatsappSection ? { whatsapp: whatsappSection } : {}),
    sectionStatuses,
    truncations
  };

  // 7. Budget calculation and priority truncation
  // Budget order:
  // 1. Capsule metadata & core state (backend-diagnostics-v1.json)
  // 2. Logs (errors/warns first, then info/debug)
  // 3. Polling history (failed/slow first, then info/debug)
  let capsuleJson = JSON.stringify(capsule, null, 2);
  let capsuleBytes = Buffer.byteLength(capsuleJson, "utf8");

  let logsBytes = Buffer.byteLength(fullLogsText, "utf8");
  let diagBytes = Buffer.byteLength(fullDiagJson, "utf8");

  if (capsuleBytes + logsBytes + diagBytes > maxBudget) {
    const originalLogBytes = logsBytes;
    const originalDiagBytes = diagBytes;

    const logTruncation: DiagnosticTruncationRecord = {
      section: "backend-app-log.txt",
      originalBytes: originalLogBytes,
      truncatedBytes: 0,
      reason: "budget_exceeded"
    };
    truncations.push(logTruncation);
    sectionStatuses["logs"] = { status: "partial", reasonCode: "truncated_budget" };

    const diagTruncation: DiagnosticTruncationRecord = {
      section: "backend-polling-history.json",
      originalBytes: originalDiagBytes,
      truncatedBytes: 0,
      reason: "budget_exceeded"
    };
    truncations.push(diagTruncation);
    sectionStatuses["networkHistory"] = { status: "partial", reasonCode: "truncated_budget" };

    // Recompute capsule size with truncation records included
    capsuleJson = JSON.stringify(capsule, null, 2);
    capsuleBytes = Buffer.byteLength(capsuleJson, "utf8");

    const remainingBudget = Math.max(0, maxBudget - capsuleBytes);
    const logsBudget = Math.floor(remainingBudget * 0.6);

    // Filter logs: Keep error and warn lines first
    const errorWarnLines = redactedLogEntries.filter(
      (line) => line.includes("WARN") || line.includes("ERROR") || line.includes("fatal") || line.includes("client_error")
    );
    let truncatedLogText = errorWarnLines.join("\n");
    if (Buffer.byteLength(truncatedLogText, "utf8") > logsBudget) {
      truncatedLogText = truncateUtf8(truncatedLogText, logsBudget);
    } else {
      const infoLines = redactedLogEntries.filter(
        (line) => !line.includes("WARN") && !line.includes("ERROR") && !line.includes("fatal") && !line.includes("client_error")
      );
      for (const line of infoLines) {
        const candidate = truncatedLogText ? `${truncatedLogText}\n${line}` : line;
        if (Buffer.byteLength(candidate, "utf8") <= logsBudget) {
          truncatedLogText = candidate;
        } else {
          break;
        }
      }
    }
    fullLogsText = truncatedLogText;
    logsBytes = Buffer.byteLength(fullLogsText, "utf8");

    // Recompute remaining budget for polling history
    const remainingForDiag = Math.max(0, maxBudget - capsuleBytes - logsBytes);
    const failedOrSlow = (redactedDiagEntries as Array<Record<string, unknown>>).filter(
      (entry) =>
        typeof entry === "object" &&
        entry !== null &&
        (Number(entry.statusCode) >= 400 || Number(entry.durationMs) > 1000 || entry.outcome !== "success")
    );
    let truncatedDiagJson = JSON.stringify(failedOrSlow, null, 2);
    if (Buffer.byteLength(truncatedDiagJson, "utf8") > remainingForDiag) {
      while (failedOrSlow.length > 0 && Buffer.byteLength(truncatedDiagJson, "utf8") > remainingForDiag) {
        failedOrSlow.pop();
        truncatedDiagJson = failedOrSlow.length > 0 ? JSON.stringify(failedOrSlow, null, 2) : "[]";
      }
      if (Buffer.byteLength(truncatedDiagJson, "utf8") > remainingForDiag) {
        truncatedDiagJson = "";
      }
    }
    fullDiagJson = truncatedDiagJson;
    diagBytes = Buffer.byteLength(fullDiagJson, "utf8");

    logTruncation.truncatedBytes = logsBytes;
    diagTruncation.truncatedBytes = diagBytes;

    capsuleJson = JSON.stringify(capsule, null, 2);
    capsuleBytes = Buffer.byteLength(capsuleJson, "utf8");

    let total = capsuleBytes + logsBytes + diagBytes;
    if (total > maxBudget) {
      if (diagBytes > 0) {
        fullDiagJson = "";
        diagBytes = 0;
        diagTruncation.truncatedBytes = 0;
      }
      total = capsuleBytes + logsBytes + diagBytes;
      if (total > maxBudget) {
        const targetLogBytes = Math.max(0, maxBudget - capsuleBytes);
        fullLogsText = truncateUtf8(fullLogsText, targetLogBytes);
        logsBytes = Buffer.byteLength(fullLogsText, "utf8");
        logTruncation.truncatedBytes = logsBytes;
      }
      capsuleJson = JSON.stringify(capsule, null, 2);
    }
  }

  const attachments: DiagnosticAttachment[] = [
    {
      pathname: "backend-diagnostics-v1.json",
      bytes: Buffer.from(capsuleJson, "utf8"),
      contentType: "application/json"
    },
    {
      pathname: "backend-app-log.txt",
      bytes: Buffer.from(fullLogsText, "utf8"),
      contentType: "text/plain"
    },
    {
      pathname: "backend-polling-history.json",
      bytes: Buffer.from(fullDiagJson, "utf8"),
      contentType: "application/json"
    }
  ];

  return {
    capsule,
    attachments
  };
}
