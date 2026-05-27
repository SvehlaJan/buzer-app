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
