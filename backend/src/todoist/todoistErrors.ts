export type TodoistFailureKind =
  | "transport"
  | "timeout"
  | "rate_limited"
  | "server"
  | "command_rejected"
  | "client"
  | "parse";

export class TodoistOperationError extends Error {
  constructor(
    readonly kind: TodoistFailureKind,
    readonly retryAfterMs?: number,
    readonly safeAttributes: Record<string, string | number> = {},
  ) {
    super(`Todoist operation failed: ${kind}`);
    this.name = "TodoistOperationError";
  }
}

export function parseRetryAfterHeader(header: string | null | undefined, now = Date.now()): number | undefined {
  if (header === null || header === undefined) {
    return undefined;
  }
  const trimmed = header.trim();
  if (trimmed.length === 0) {
    return undefined;
  }
  if (/^\d+$/.test(trimmed)) {
    const seconds = parseInt(trimmed, 10);
    return seconds >= 0 ? seconds * 1000 : undefined;
  }
  const timestamp = Date.parse(trimmed);
  if (!Number.isNaN(timestamp)) {
    const diff = timestamp - now;
    return Math.max(0, diff);
  }
  return undefined;
}
