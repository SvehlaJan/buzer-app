export interface TodoistTaskMirror {
  todoistTaskId: string;
  title: string;
  description: string | null;
  projectId: string | null;
  parentId: string | null;
  childOrder: number | null;
  assigneeId: string | null;
  creatorId: string | null;
  dueDate: string | null;
  dueDatetime: string | null;
  dueString: string | null;
  dueLang: string | null;
  dueTimezone: string | null;
  isRecurring: boolean;
  durationAmount: number | null;
  durationUnit: string | null;
  durationMinutes: number | null;
  completedAt: string | null;
  deletedAt: string | null;
  rawJson: unknown;
  updatedAt: string;
}

export interface TodoistProjectMirror {
  todoistProjectId: string;
  name: string;
  shared: boolean;
  canAssignTasks: boolean;
  isInboxProject: boolean;
  isDeleted: boolean;
  isArchived: boolean;
  rawJson: unknown;
  updatedAt: string;
}
