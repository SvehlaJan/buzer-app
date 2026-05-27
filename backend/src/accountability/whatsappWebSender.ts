import qrcode from "qrcode-terminal";
import whatsappWeb from "whatsapp-web.js";
import type { AccountabilitySender, EscalationInput } from "./accountabilitySender.js";
import { buildEscalationMessage } from "./escalationMessage.js";

const { Client, LocalAuth } = whatsappWeb;

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
      this.ready = false;
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
