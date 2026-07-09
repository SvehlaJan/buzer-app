export interface TodoistTaskMirror {
  todoistTaskId: string;
  title: string;
  description: string | null;
  projectId: string | null;
  assigneeId: string | null;
  creatorId: string | null;
  dueDatetime: string | null;
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
