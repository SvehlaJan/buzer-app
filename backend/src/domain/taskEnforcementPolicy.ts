import { validateTaskStrictness, type ValidationResult } from "./taskValidation.js";

export type TaskEnforcementClassification =
  | "ROOT_COMMITMENT"
  | "ROOT_CANDIDATE_INVALID"
  | "CHECKLIST_CHILD"
  | "UNSUPPORTED_HIERARCHY"
  | "NOT_OWNED";

export type HierarchyViolation =
  | "too_many_children"
  | "nested_child"
  | "child_has_schedule"
  | "child_has_duration"
  | "child_has_recurrence"
  | "child_has_assignment"
  | "child_project_mismatch";

export interface EnforceableTask {
  id: string;
  todoistTaskId: string;
  title: string;
  projectId: string | null;
  parentId: string | null;
  childOrder?: number | null;
  assigneeId: string | null;
  creatorId: string | null;
  dueDate?: string | null;
  dueDatetime: string | null;
  isRecurring: boolean;
  durationAmount?: number | null;
  durationMinutes: number | null;
  completedAt?: string | null;
  deletedAt?: string | null;
  mirrorRevision?: number;
  commitmentOwnerId: string | null;
}

export interface TaskEnforcementPolicyOptions {
  currentUserId: string;
  projectsById: ReadonlyMap<string, { canAssignTasks: boolean }>;
}

export interface TaskEnforcementResult {
  classification: TaskEnforcementClassification;
  violation: HierarchyViolation | null;
  isCompanionOwned: boolean;
  validationErrors?: string[];
}

export class TaskEnforcementPolicy {
  constructor(private readonly options: TaskEnforcementPolicyOptions) {}

  classify(task: EnforceableTask, allTasks: readonly EnforceableTask[]): TaskEnforcementResult {
    if (task.parentId !== null) {
      return this.classifyChild(task, allTasks);
    }

    return this.classifyRoot(task, allTasks);
  }

  isCompanionOwned(task: EnforceableTask): boolean {
    if (task.assigneeId !== null) {
      return false;
    }

    const isPersonalProjectTask =
      task.creatorId === this.options.currentUserId &&
      task.projectId !== null &&
      this.options.projectsById.get(task.projectId)?.canAssignTasks === false;

    return task.commitmentOwnerId === this.options.currentUserId || isPersonalProjectTask;
  }

  private classifyRoot(task: EnforceableTask, allTasks: readonly EnforceableTask[]): TaskEnforcementResult {
    const isCompanionOwned = this.isCompanionOwned(task);
    if (task.assigneeId !== this.options.currentUserId && !isCompanionOwned) {
      return notOwned();
    }

    const violation = this.findHierarchyViolation(task, allTasks);
    if (violation !== null) {
      return {
        classification: "UNSUPPORTED_HIERARCHY",
        violation,
        isCompanionOwned
      };
    }

    const validation = this.validateRoot(task, isCompanionOwned);
    if (!validation.valid) {
      return {
        classification: "ROOT_CANDIDATE_INVALID",
        violation: null,
        isCompanionOwned,
        validationErrors: validation.errors
      };
    }

    return {
      classification: "ROOT_COMMITMENT",
      violation: null,
      isCompanionOwned
    };
  }

  private classifyChild(task: EnforceableTask, allTasks: readonly EnforceableTask[]): TaskEnforcementResult {
    const parent = allTasks.find((candidate) => candidate.todoistTaskId === task.parentId);
    if (parent === undefined || parent.parentId !== null) {
      return notOwned();
    }

    const parentResult = this.classifyRoot(parent, allTasks);
    if (parentResult.classification !== "ROOT_COMMITMENT") {
      return notOwned();
    }

    if (this.childViolation(parent, task) !== null) {
      return notOwned();
    }

    return {
      classification: "CHECKLIST_CHILD",
      violation: null,
      isCompanionOwned: false
    };
  }

  private validateRoot(task: EnforceableTask, isCompanionOwned: boolean): ValidationResult {
    return validateTaskStrictness({
      title: task.title,
      creatorId: task.creatorId,
      assigneeId: task.assigneeId,
      dueDatetime: task.dueDatetime,
      durationMinutes: task.durationMinutes,
      currentUserId: this.options.currentUserId,
      isCompanionOwned
    });
  }

  private findHierarchyViolation(root: EnforceableTask, allTasks: readonly EnforceableTask[]): HierarchyViolation | null {
    const children = allTasks.filter(
      (task) => task.parentId === root.todoistTaskId && task.deletedAt == null
    );
    if (children.length > 30) {
      return "too_many_children";
    }

    const childIds = new Set(children.map((child) => child.todoistTaskId));
    if (allTasks.some(
      (task) => task.deletedAt == null && task.parentId !== null && childIds.has(task.parentId)
    )) {
      return "nested_child";
    }

    for (const child of children) {
      const violation = this.childViolation(root, child);
      if (violation !== null) {
        return violation;
      }
    }

    return null;
  }

  private childViolation(root: EnforceableTask, child: EnforceableTask): HierarchyViolation | null {
    if (child.dueDatetime !== null || child.dueDate != null) {
      return "child_has_schedule";
    }
    if (child.durationMinutes !== null || child.durationAmount != null) {
      return "child_has_duration";
    }
    if (child.isRecurring) {
      return "child_has_recurrence";
    }
    if (child.assigneeId !== null) {
      return "child_has_assignment";
    }
    if (child.projectId !== root.projectId) {
      return "child_project_mismatch";
    }
    return null;
  }
}

function notOwned(): TaskEnforcementResult {
  return {
    classification: "NOT_OWNED",
    violation: null,
    isCompanionOwned: false
  };
}
