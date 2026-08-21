import Database from "better-sqlite3";
import { describe, expect, it } from "vitest";
import { migrate } from "../src/db/migrations.js";
import { TaskRepository } from "../src/repositories/taskRepository.js";
import { collectDiagnosticsCapsule } from "../src/diagnostics/backendDiagnosticCollector.js";
import { RingBuffer } from "../src/diagnostics/ringBuffer.js";

describe("backendDiagnosticCollector", () => {
  it("generates a valid schema version 1 capsule with runtime and database details", async () => {
    const db = new Database(":memory:");
    migrate(db);

    const tasks = new TaskRepository(db);
    tasks.upsertTodoistTask({
      id: "task-1",
      todoistTaskId: "todoist-1",
      title: "Test Task with Bearer secret-pass",
      description: "Sample description",
      projectId: "proj-1",
      assigneeId: "user-1",
      creatorId: "user-1",
      dueDatetime: "2026-08-21T18:00:00Z",
      durationMinutes: 30,
      completedAt: null,
      deletedAt: null,
      rawJson: "{}",
      updatedAt: "2026-08-21T10:00:00Z"
    });

    db.prepare(`
      insert into task_action_journal (
        action_id, device_id, root_task_id, expected_occurrence_key, action_type,
        request_fingerprint, status, created_at, updated_at
      ) values (
        'action-1', 'device-1', 'task-1', 'occ-1', 'complete_task',
        'fp-1', 'completed', '2026-08-21T12:00:00Z', '2026-08-21T12:00:01Z'
      )
    `).run();

    const logBuffer = new RingBuffer<string>(10);
    logBuffer.add("[2026-08-21T12:00:00.000Z] INFO server started");
    logBuffer.add("[2026-08-21T12:00:01.000Z] WARN slow query detected");

    const diagBuffer = new RingBuffer<any>(10);
    diagBuffer.add({
      timestamp: "2026-08-21T12:00:00.000Z",
      requestId: "req-1",
      method: "GET",
      url: "/tasks/snapshot",
      statusCode: 200,
      durationMs: 45
    });

    const result = await collectDiagnosticsCapsule({
      reportSessionId: "session-123",
      androidDiagnosticEventId: "android-evt-456",
      backendDiagnosticEventId: "backend-evt-789",
      capturedAt: "2026-08-21T12:00:00Z",
      deviceId: "device-1",
      taskId: "task-1",
      actionId: "action-1",
      db,
      logRingBuffer: logBuffer,
      diagnosticRingBuffer: diagBuffer
    });

    expect(result.capsule).toMatchObject({
      schemaVersion: 1,
      reportSessionId: "session-123",
      androidDiagnosticEventId: "android-evt-456",
      backendDiagnosticEventId: "backend-evt-789",
      capturedAt: "2026-08-21T12:00:00Z",
      deviceId: "device-1",
      taskId: "task-1",
      actionId: "action-1",
      runtime: {
        nodeVersion: process.version,
        platform: process.platform,
        arch: process.arch,
        uptimeSeconds: expect.any(Number),
        pid: process.pid,
        memoryUsage: expect.any(Object),
        environment: expect.any(String),
        release: expect.any(String),
        serverTime: expect.any(String)
      },
      database: {
        schemaVersion: expect.any(Number),
        journalMode: expect.any(String),
        quickCheck: "ok",
        tableCounts: expect.objectContaining({
          tasks: 1,
          actionJournal: 1
        }),
        recentActionJournal: expect.arrayContaining([
          expect.objectContaining({ action_id: "action-1" })
        ]),
        matchedTask: expect.objectContaining({
          id: "task-1",
          // Redaction check: "Bearer secret-pass" redacted
          title: expect.stringContaining("[REDACTED]")
        }),
        matchedAction: expect.objectContaining({
          journal: expect.objectContaining({ action_id: "action-1" })
        })
      },
      todoist: expect.objectContaining({
        hasSyncToken: false
      }),
      whatsapp: expect.objectContaining({
        dryRun: expect.any(Boolean)
      }),
      sectionStatuses: expect.objectContaining({
        runtime: { status: "complete" },
        database: { status: "complete" },
        logs: { status: "complete" },
        networkHistory: { status: "complete" }
      }),
      truncations: []
    });

    expect(result.attachments).toHaveLength(3);
    const attachmentNames = result.attachments.map((a) => a.pathname);
    expect(attachmentNames).toContain("backend-diagnostics-v1.json");
    expect(attachmentNames).toContain("backend-app-log.txt");
    expect(attachmentNames).toContain("backend-polling-history.json");

    const logAttachment = result.attachments.find((a) => a.pathname === "backend-app-log.txt")!;
    expect(logAttachment.bytes.toString("utf8")).toContain("slow query detected");

    const diagAttachment = result.attachments.find((a) => a.pathname === "backend-polling-history.json")!;
    expect(diagAttachment.bytes.toString("utf8")).toContain("/tasks/snapshot");
  });

  it("handles missing database gracefully without throwing", async () => {
    const result = await collectDiagnosticsCapsule({
      reportSessionId: "session-no-db",
      androidDiagnosticEventId: "android-evt-no-db",
      backendDiagnosticEventId: "backend-evt-no-db",
      capturedAt: "2026-08-21T12:00:00Z"
    });

    expect(result.capsule.schemaVersion).toBe(1);
    expect(result.capsule.sectionStatuses).toMatchObject({
      runtime: { status: "complete" },
      database: { status: "unavailable" }
    });
    expect(result.attachments).toHaveLength(3);
  });

  it("handles database query failure without throwing and marks database as failed", async () => {
    const db = new Database(":memory:");
    // Closed db will fail queries
    db.close();

    const result = await collectDiagnosticsCapsule({
      reportSessionId: "session-closed-db",
      androidDiagnosticEventId: "android-evt-closed-db",
      backendDiagnosticEventId: "backend-evt-closed-db",
      capturedAt: "2026-08-21T12:00:00Z",
      db
    });

    expect(result.capsule.schemaVersion).toBe(1);
    expect(result.capsule.sectionStatuses).toMatchObject({
      database: { status: "failed" }
    });
    expect(result.attachments).toHaveLength(3);
  });

  it("truncates logs and history to respect maximum budget", async () => {
    const logBuffer = new RingBuffer<string>(500);
    // Add many logs
    for (let i = 0; i < 200; i++) {
      logBuffer.add(`[2026-08-21T12:00:00.${i}Z] INFO log message line number ${i} with lots of padding data 1234567890`);
    }
    logBuffer.add("[2026-08-21T12:00:01.000Z] WARN important warning message that must be kept");
    logBuffer.add("[2026-08-21T12:00:02.000Z] ERROR critical error message that must be kept");

    const diagBuffer = new RingBuffer<any>(200);
    for (let i = 0; i < 100; i++) {
      diagBuffer.add({
        timestamp: `2026-08-21T12:00:00.${i}Z`,
        requestId: `req-${i}`,
        method: "GET",
        url: `/path/${i}`,
        statusCode: i % 10 === 0 ? 500 : 200,
        durationMs: i * 10
      });
    }

    // Set a very small budget (e.g. 2500 bytes)
    const smallBudget = 2500;
    const result = await collectDiagnosticsCapsule({
      reportSessionId: "session-budget",
      androidDiagnosticEventId: "android-evt-budget",
      backendDiagnosticEventId: "backend-evt-budget",
      capturedAt: "2026-08-21T12:00:00Z",
      logRingBuffer: logBuffer,
      diagnosticRingBuffer: diagBuffer,
      maxTotalBytes: smallBudget
    });

    const totalBytes = result.attachments.reduce((sum, att) => sum + att.bytes.byteLength, 0);
    expect(totalBytes).toBeLessThanOrEqual(smallBudget);

    // Verify truncations were recorded
    expect(result.capsule.truncations).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ reason: "budget_exceeded" })
      ])
    );

    // Verify sectionStatuses updated to partial
    const sectionStatuses = result.capsule.sectionStatuses as Record<string, { status: string }>;
    expect(sectionStatuses.logs?.status).toBe("partial");

    // Verify error and warn logs are prioritized
    const logAttachment = result.attachments.find((a) => a.pathname === "backend-app-log.txt")!;
    const logContent = logAttachment.bytes.toString("utf8");
    expect(logContent).toContain("WARN important warning message that must be kept");
  });
});
