import { readdirSync, rmSync, statSync } from "node:fs";
import { join } from "node:path";
import qrcode from "qrcode-terminal";
import whatsappWeb from "whatsapp-web.js";
import type { AccountabilitySender, EscalationInput } from "./accountabilitySender.js";
import { buildEscalationMessage } from "./escalationMessage.js";

import { type BackendObservability, backendObservability } from "../diagnostics/backendObservability.js";

const { Client, LocalAuth } = whatsappWeb;

export interface WhatsAppWebSenderOptions {
  sessionPath: string;
  clientId: string;
  observability?: BackendObservability;
  client?: InstanceType<typeof Client>;
}

const chromiumSingletonFiles = new Set(["SingletonLock", "SingletonSocket", "SingletonCookie"]);

export function removeStaleChromiumSingletonLocks(sessionPath: string): string[] {
  const removed: string[] = [];

  function visit(path: string, depth: number): void {
    if (depth > 5) {
      return;
    }

    let entries: string[];
    try {
      entries = readdirSync(path);
    } catch {
      return;
    }

    for (const entry of entries) {
      const child = join(path, entry);
      if (chromiumSingletonFiles.has(entry)) {
        rmSync(child, { force: true });
        removed.push(child);
        continue;
      }

      try {
        if (statSync(child).isDirectory()) {
          visit(child, depth + 1);
        }
      } catch {
        // Ignore broken symlinks or files that disappear during cleanup.
      }
    }
  }

  visit(sessionPath, 0);
  return removed;
}

export class WhatsAppWebSender implements AccountabilitySender {
  private readonly client: InstanceType<typeof Client>;
  private readonly observability: BackendObservability;
  private ready = false;
  private lastDetail = "not initialized";

  constructor(options: WhatsAppWebSenderOptions) {
    this.observability = options.observability ?? backendObservability;

    const removedLocks = removeStaleChromiumSingletonLocks(options.sessionPath);
    if (removedLocks.length > 0) {
      console.warn(`Removed ${removedLocks.length} stale WhatsApp Chromium profile lock file(s)`);
    }

    this.client = options.client ?? new Client({
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
      this.ready = false;
      this.lastDetail = "qr required";
      console.log("WhatsApp authentication QR required");
      qrcode.generate(qr, { small: true });
    });
    this.client.on("authenticated", () => {
      this.ready = false;
      this.lastDetail = "authenticated";
      console.log("WhatsApp authenticated");
    });
    this.client.on("ready", () => {
      this.ready = true;
      this.lastDetail = "ready";
      console.log("WhatsApp ready");
    });
    this.client.on("auth_failure", (message: string) => {
      this.ready = false;
      this.lastDetail = `auth failure: ${message}`;
      console.error(`WhatsApp auth failure: ${message}`);
      this.observability.captureBackgroundFailure(
        "buzer.backend.whatsapp_auth_failure",
        new Error(message)
      );
    });
    this.client.on("disconnected", (reason: string) => {
      this.ready = false;
      this.lastDetail = `disconnected: ${reason}`;
      console.warn(`WhatsApp disconnected: ${reason}`);
      this.observability.captureBackgroundFailure(
        "buzer.backend.whatsapp_disconnected",
        new Error(reason)
      );
    });
  }

  async initialize(): Promise<void> {
    this.lastDetail = "initializing";
    await this.client.initialize();
  }

  async destroy(): Promise<void> {
    await this.client.destroy();
    this.ready = false;
    this.lastDetail = "destroyed";
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
