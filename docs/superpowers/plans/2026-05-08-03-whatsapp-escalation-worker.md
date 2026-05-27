# WhatsApp Escalation Worker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `whatsapp-web.js` accountability provider with dry-run mode, persistent session health, and escalation logging.

**Architecture:** WhatsApp sending is hidden behind an `AccountabilitySender` interface. The domain layer queues escalation intent; the provider sends messages or records dry-run output. Production uses `whatsapp-web.js`, while tests use fake senders.

**Tech Stack:** Node.js/TypeScript, `whatsapp-web.js`, Puppeteer, SQLite, Vitest.

---

## Prerequisites

- Complete backend core plan.
- Complete enough of the REST/action plan that `postpone_start` can request escalation.
- Use Context7 for current `whatsapp-web.js` docs.

## Files

- Create: `backend/src/accountability/accountabilitySender.ts`
- Create: `backend/src/accountability/dryRunSender.ts`
- Create: `backend/src/accountability/whatsappWebSender.ts`
- Create: `backend/src/accountability/escalationMessage.ts`
- Create: `backend/src/services/escalationService.ts`
- Create: `backend/test/accountability/escalationMessage.test.ts`
- Create: `backend/test/accountability/dryRunSender.test.ts`
- Create: `backend/test/services/escalationService.test.ts`
- Modify: `backend/package.json`
- Modify: `backend/src/db/schema.sql`
- Modify: `backend/src/db/migrations.ts` if a new migration mechanism has been introduced.
- Modify: `backend/src/services/taskActionService.ts`

## Task 1: Resolve Current WhatsApp Docs

- [x] **Step 1: Fetch `whatsapp-web.js` docs**

Run:

```bash
npx ctx7@latest library "whatsapp-web.js" "Node TypeScript LocalAuth sendMessage Puppeteer headless persistent session VPS"
npx ctx7@latest docs "/resolved/wwebjs/id" "LocalAuth Client sendMessage Puppeteer headless auth_failure disconnected ready events"
```

Record the resolved ID and the authentication/session notes in the handoff.

- [x] **Step 2: Add dependency**

Update `backend/package.json` dependencies:

```json
{
  "dependencies": {
    "qrcode-terminal": "latest",
    "whatsapp-web.js": "latest"
  }
}
```

Keep existing dependencies. Run:

```bash
cd backend
npm install
```

Expected: lockfile updates successfully.

## Task 2: Create Accountability Interface And Message Builder

- [x] **Step 1: Write message test**

Create `backend/test/accountability/escalationMessage.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { buildEscalationMessage } from "../../src/accountability/escalationMessage.js";

describe("buildEscalationMessage", () => {
  it("creates a clear accountability message", () => {
    expect(buildEscalationMessage({
      taskTitle: "Submit insurance form",
      deadlineAt: "2026-05-08T18:00:00Z",
      dismissCount: 2
    })).toBe("I postponed starting \"Submit insurance form\" 2 times. Deadline: 2026-05-08T18:00:00Z.");
  });
});
```

- [x] **Step 2: Implement interface and message builder**

`backend/src/accountability/accountabilitySender.ts`:

```ts
export interface EscalationInput {
  taskTitle: string;
  deadlineAt: string;
  dismissCount: number;
  recipient: string;
}

export interface AccountabilitySender {
  sendEscalation(input: EscalationInput): Promise<void>;
  getHealth(): Promise<{ connected: boolean; detail: string }>;
}
```

`backend/src/accountability/escalationMessage.ts`:

```ts
export interface EscalationMessageInput {
  taskTitle: string;
  deadlineAt: string;
  dismissCount: number;
}

export function buildEscalationMessage(input: EscalationMessageInput): string {
  return `I postponed starting "${input.taskTitle}" ${input.dismissCount} times. Deadline: ${input.deadlineAt}.`;
}
```

- [x] **Step 3: Run message test**

```bash
cd backend
npm test -- test/accountability/escalationMessage.test.ts
```

Expected: pass.

## Task 3: Implement Dry-Run Sender

- [x] **Step 1: Write dry-run test**

Create `backend/test/accountability/dryRunSender.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { DryRunSender } from "../../src/accountability/dryRunSender.js";

describe("DryRunSender", () => {
  it("records sent messages without external side effects", async () => {
    const sender = new DryRunSender();
    await sender.sendEscalation({
      taskTitle: "Pay tax",
      deadlineAt: "2026-05-08T20:00:00Z",
      dismissCount: 2,
      recipient: "wife"
    });

    expect(sender.sent).toHaveLength(1);
    expect(sender.sent[0]?.recipient).toBe("wife");
    expect(await sender.getHealth()).toEqual({ connected: true, detail: "dry-run" });
  });
});
```

- [x] **Step 2: Implement dry-run sender**

Create `backend/src/accountability/dryRunSender.ts`:

```ts
import type { AccountabilitySender, EscalationInput } from "./accountabilitySender.js";
import { buildEscalationMessage } from "./escalationMessage.js";

export interface DryRunRecord {
  recipient: string;
  message: string;
  createdAt: string;
}

export class DryRunSender implements AccountabilitySender {
  readonly sent: DryRunRecord[] = [];

  async sendEscalation(input: EscalationInput): Promise<void> {
    this.sent.push({
      recipient: input.recipient,
      message: buildEscalationMessage(input),
      createdAt: new Date().toISOString()
    });
  }

  async getHealth(): Promise<{ connected: boolean; detail: string }> {
    return { connected: true, detail: "dry-run" };
  }
}
```

- [x] **Step 3: Run dry-run test**

```bash
cd backend
npm test -- test/accountability/dryRunSender.test.ts
```

Expected: pass.

## Task 4: Implement Escalation Service

- [x] **Step 1: Write service test**

Create `backend/test/services/escalationService.test.ts`:

```ts
import Database from "better-sqlite3";
import { describe, expect, it } from "vitest";
import { migrate } from "../../src/db/migrations.js";
import { TaskRepository } from "../../src/repositories/taskRepository.js";
import { DryRunSender } from "../../src/accountability/dryRunSender.js";
import { EscalationService } from "../../src/services/escalationService.js";

describe("EscalationService", () => {
  it("logs successful escalation", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    repo.upsertTodoistTask({
      id: "task-1",
      todoistTaskId: "todoist-1",
      title: "Book dentist",
      projectId: null,
      assigneeId: "user-self",
      creatorId: "user-self",
      dueDatetime: "2026-05-08T16:00:00Z",
      durationMinutes: 30,
      completedAt: null,
      deletedAt: null,
      rawJson: "{}",
      updatedAt: "2026-05-08T10:00:00Z"
    });
    const sender = new DryRunSender();
    const service = new EscalationService(db, repo, sender, "wife-chat");

    await service.sendForTask("task-1", 2);

    const row = db.prepare("select status, recipient_id as recipientId from whatsapp_escalations").get() as { status: string; recipientId: string };
    expect(row).toEqual({ status: "sent", recipientId: "wife-chat" });
    expect(sender.sent).toHaveLength(1);
  });
});
```

- [x] **Step 2: Implement escalation service**

Create `backend/src/services/escalationService.ts`:

```ts
import type Database from "better-sqlite3";
import { randomUUID } from "node:crypto";
import { buildEscalationMessage } from "../accountability/escalationMessage.js";
import type { AccountabilitySender } from "../accountability/accountabilitySender.js";
import type { TaskRepository } from "../repositories/taskRepository.js";

export class EscalationService {
  constructor(
    private readonly db: Database.Database,
    private readonly tasks: TaskRepository,
    private readonly sender: AccountabilitySender,
    private readonly recipientId: string
  ) {}

  async sendForTask(taskId: string, dismissCount: number): Promise<void> {
    const task = this.tasks.getById(taskId);
    if (task === null) {
      throw new Error("task_not_found");
    }
    if (task.dueDatetime === null) {
      throw new Error("task_missing_deadline");
    }

    const message = buildEscalationMessage({
      taskTitle: task.title,
      deadlineAt: task.dueDatetime,
      dismissCount
    });
    const id = randomUUID();
    const createdAt = new Date().toISOString();

    this.db.prepare(`
      insert into whatsapp_escalations (id, task_id, recipient_id, message, status, created_at)
      values (?, ?, ?, ?, 'pending', ?)
    `).run(id, taskId, this.recipientId, message, createdAt);

    try {
      await this.sender.sendEscalation({
        taskTitle: task.title,
        deadlineAt: task.dueDatetime,
        dismissCount,
        recipient: this.recipientId
      });
      this.db.prepare("update whatsapp_escalations set status = 'sent', sent_at = ? where id = ?").run(new Date().toISOString(), id);
      this.db.prepare("update commitment_states set escalation_sent_at = ?, updated_at = ? where task_id = ?").run(new Date().toISOString(), new Date().toISOString(), taskId);
    } catch (error) {
      const messageText = error instanceof Error ? error.message : String(error);
      this.db.prepare("update whatsapp_escalations set status = 'failed', error_message = ? where id = ?").run(messageText, id);
      throw error;
    }
  }
}
```

- [x] **Step 3: Run service test**

```bash
cd backend
npm test -- test/services/escalationService.test.ts
```

Expected: pass.

## Task 5: Implement WhatsApp Web Sender

- [x] **Step 1: Implement `backend/src/accountability/whatsappWebSender.ts`**

```ts
import qrcode from "qrcode-terminal";
import pkg from "whatsapp-web.js";
import type { AccountabilitySender, EscalationInput } from "./accountabilitySender.js";
import { buildEscalationMessage } from "./escalationMessage.js";

const { Client, LocalAuth } = pkg;

export interface WhatsAppWebSenderOptions {
  sessionPath: string;
  clientId: string;
}

export class WhatsAppWebSender implements AccountabilitySender {
  private readonly client: InstanceType<typeof Client>;
  private ready = false;
  private lastDetail = "not initialized";

  constructor(options: WhatsAppWebSenderOptions) {
    this.client = new Client({
      authStrategy: new LocalAuth({
        clientId: options.clientId,
        dataPath: options.sessionPath
      }),
      puppeteer: {
        headless: true,
        args: ["--no-sandbox", "--disable-setuid-sandbox"]
      }
    });

    this.client.on("qr", (qr: string) => {
      this.lastDetail = "qr required";
      qrcode.generate(qr, { small: true });
    });
    this.client.on("ready", () => {
      this.ready = true;
      this.lastDetail = "ready";
    });
    this.client.on("auth_failure", (message: string) => {
      this.ready = false;
      this.lastDetail = `auth failure: ${message}`;
    });
    this.client.on("disconnected", (reason: string) => {
      this.ready = false;
      this.lastDetail = `disconnected: ${reason}`;
    });
  }

  async initialize(): Promise<void> {
    await this.client.initialize();
  }

  async sendEscalation(input: EscalationInput): Promise<void> {
    if (!this.ready) {
      throw new Error(`whatsapp_not_ready: ${this.lastDetail}`);
    }

    await this.client.sendMessage(input.recipient, buildEscalationMessage(input));
  }

  async getHealth(): Promise<{ connected: boolean; detail: string }> {
    return { connected: this.ready, detail: this.lastDetail };
  }
}
```

- [x] **Step 2: Typecheck**

```bash
cd backend
npm run typecheck
```

Expected: pass. If package import types differ from current docs, adjust import style and document the exact correction in the handoff.

## Task 6: Wire Second Postpone To Escalation

- [x] **Step 1: Extend `TaskActionService` constructor**

Update the constructor signature:

```ts
import type { EscalationService } from "./escalationService.js";

export class TaskActionService {
  constructor(
    private readonly db: Database.Database,
    private readonly tasks: TaskRepository,
    private readonly todoist: TodoistAdapter,
    private readonly escalationService: EscalationService | null = null
  ) {}
}
```

Add this method:

```ts
async postponeStart(taskId: string, now: string): Promise<void> {
  const task = this.tasks.getById(taskId);
  if (task === null) {
    throw new Error("task_not_found");
  }

  const nextCount = task.startDismissCount + 1;
  this.db.prepare(`
    update commitment_states
    set start_dismiss_count = ?,
        last_snoozed_at = ?,
        schedule_version = schedule_version + 1,
        updated_at = ?
    where task_id = ?
  `).run(nextCount, now, now, taskId);

  if (nextCount >= 2 && task.escalationSentAt === null && this.escalationService !== null) {
    await this.escalationService.sendForTask(taskId, nextCount);
  }
}
```

- [x] **Step 2: Add test**

Add this test to `backend/test/services/taskActionService.test.ts`:

```ts
it("sends escalation on second start postpone", async () => {
  const db = new Database(":memory:");
  migrate(db);
  const repo = new TaskRepository(db);
  repo.upsertTodoistTask({
    id: "task-1",
    todoistTaskId: "todoist-1",
    title: "Send invoice",
    projectId: null,
    assigneeId: "user-self",
    creatorId: "user-self",
    dueDatetime: "2026-05-08T16:00:00Z",
    durationMinutes: 30,
    completedAt: null,
    deletedAt: null,
    rawJson: "{}",
    updatedAt: "2026-05-08T10:00:00Z"
  });
  const adapter = new FakeTodoistAdapter();
  const sender = new DryRunSender();
  const escalation = new EscalationService(db, repo, sender, "wife-chat");
  const service = new TaskActionService(db, repo, adapter, escalation);

  await service.postponeStart("task-1", "2026-05-08T15:00:00Z");
  await service.postponeStart("task-1", "2026-05-08T15:05:00Z");

  const count = db.prepare("select count(*) as count from whatsapp_escalations").get() as { count: number };
  expect(count.count).toBe(1);
});
```

- [x] **Step 3: Run tests**

```bash
cd backend
npm test -- test/services/taskActionService.test.ts test/services/escalationService.test.ts
```

Expected: pass.

## Task 7: Final Verification And Handoff

- [x] **Step 1: Run backend verification**

```bash
cd backend
npm test
npm run typecheck
```

Expected: pass.

- [x] **Step 2: Ensure secrets/session data are ignored**

Run:

```bash
git check-ignore backend/.wwebjs_auth backend/.wwebjs_cache whatsapp-sessions/session
```

Expected: each path is printed, confirming it is ignored.

- [x] **Step 3: Commit**

```bash
git add backend docs/superpowers/plans/2026-05-08-03-whatsapp-escalation-worker.md
git commit -m "feat: add WhatsApp escalation provider"
```

- [x] **Step 4: Write handoff**

Create `docs/handoffs/YYYY-MM-DD-whatsapp-escalation.md` with session auth notes, dry-run verification, and whether real QR login was attempted.
