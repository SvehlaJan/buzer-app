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
