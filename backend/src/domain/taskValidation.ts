export interface StrictTaskInput {
  title: string;
  creatorId: string | null;
  assigneeId: string | null;
  dueDatetime: string | null;
  durationMinutes: number | null;
  currentUserId: string;
}

export interface ValidationResult {
  valid: boolean;
  errors: string[];
}

export function validateTaskStrictness(input: StrictTaskInput): ValidationResult {
  const errors: string[] = [];
  const title = input.title.trim();
  const createdByUser = input.creatorId === input.currentUserId;
  const assignedToUser = input.assigneeId === input.currentUserId;

  if (title.length === 0) {
    errors.push("title_required");
  }

  if (createdByUser && input.assigneeId === null) {
    errors.push("assignee_required_for_created_task");
  }

  if (assignedToUser) {
    if (input.dueDatetime === null) {
      errors.push("due_datetime_required");
    }

    if (input.durationMinutes === null) {
      errors.push("duration_required");
    } else if (!Number.isInteger(input.durationMinutes) || input.durationMinutes <= 0) {
      errors.push("duration_must_be_positive_minutes");
    }
  }

  return { valid: errors.length === 0, errors };
}
