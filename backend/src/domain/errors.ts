export class DomainError extends Error {
  constructor(
    readonly code: string,
    message: string,
    readonly details?: Record<string, unknown>,
    readonly statusCode = 422
  ) {
    super(message);
    this.name = "DomainError";
  }

  static staleTask(snapshot: unknown): DomainError {
    return actionError("stale_task", "Task changed on another device", snapshot, 409);
  }

  static staleChild(snapshot: unknown): DomainError {
    return actionError("stale_child", "Checklist item changed on another device", snapshot, 409);
  }

  static staleOccurrence(snapshot: unknown): DomainError {
    return actionError("stale_occurrence", "Task occurrence is no longer current", snapshot, 409);
  }

  static unsupportedHierarchy(snapshot: unknown): DomainError {
    return actionError("unsupported_hierarchy", "Task hierarchy is not supported", snapshot, 422);
  }

  static childrenIncomplete(snapshot: unknown): DomainError {
    return actionError("children_incomplete", "Complete every checklist item first", snapshot, 422);
  }

  static activityLogFull(snapshot: unknown): DomainError {
    return actionError("activity_log_full", "Activity log has reached its description limit", snapshot, 422);
  }
}

function actionError(code: string, message: string, snapshot: unknown, statusCode: number): DomainError {
  return new DomainError(code, message, { snapshot }, statusCode);
}
