export interface EscalationMessageInput {
  taskTitle: string;
  deadlineAt: string;
  dismissCount: number;
}

export function buildEscalationMessage(input: EscalationMessageInput): string {
  return `I postponed starting "${input.taskTitle}" ${input.dismissCount} times. Deadline: ${input.deadlineAt}.`;
}
