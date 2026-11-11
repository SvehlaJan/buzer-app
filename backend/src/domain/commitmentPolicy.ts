export const MAX_CHECKLIST_CHILDREN = 30;

export type TaskEnforcementClassification =
  | "ROOT_COMMITMENT"
  | "ROOT_CANDIDATE_INVALID"
  | "ROOT_UNASSIGNED"
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

export type HierarchyViolationCode = HierarchyViolation;

export interface PolicyProjectInfo {
  canAssignTasks: boolean;
}

export interface PolicyTaskRecord {
  id: string;
  todoistTaskId: string;
  title: string;
  projectId: string | null;
  parentId: string | null;
  childOrder?: number | null;
  assigneeId: string | null;
  creatorId: string | null;
  commitmentOwnerId: string | null;
  dueDate?: string | null;
  dueDatetime: string | null;
  dueString?: string | null;
  dueLang?: string | null;
  dueTimezone?: string | null;
  isRecurring: boolean;
  durationAmount?: number | null;
  durationUnit?: string | null;
  durationMinutes: number | null;
  completedAt?: string | null;
  deletedAt?: string | null;
  mirrorRevision?: number;
}

export interface CommitmentPolicyOptions {
  currentUserId: string;
  projectsById: ReadonlyMap<string, PolicyProjectInfo>;
}

export interface TaskEnforcementResult {
  classification: TaskEnforcementClassification;
  violation: HierarchyViolation | null;
  isCompanionOwned: boolean;
  validationErrors?: string[];
}

export interface StrictTaskInput {
  title: string;
  creatorId: string | null;
  assigneeId: string | null;
  dueDatetime: string | null;
  durationMinutes: number | null;
  currentUserId: string;
  isCompanionOwned?: boolean;
}

export interface ValidationResult {
  valid: boolean;
  errors: string[];
}

export function validateTaskStrictness(input: StrictTaskInput): ValidationResult {
  const errors: string[] = [];
  const title = input.title.trim();
  const createdByUser = input.creatorId === input.currentUserId;
  const assignedToUser = input.assigneeId === input.currentUserId || input.isCompanionOwned === true;

  if (title.length === 0) {
    errors.push("title_required");
  }

  if (createdByUser && input.assigneeId === null && !input.isCompanionOwned) {
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

export class CommitmentPolicy {
  constructor(private readonly options: CommitmentPolicyOptions) {}

  isCompanionOwned(
    task: Pick<PolicyTaskRecord, "projectId" | "assigneeId" | "creatorId" | "commitmentOwnerId">
  ): boolean {
    if (task.assigneeId !== null) {
      return false;
    }

    const isPersonalProjectTask =
      task.creatorId === this.options.currentUserId &&
      task.projectId !== null &&
      this.options.projectsById.get(task.projectId)?.canAssignTasks === false;

    return task.commitmentOwnerId === this.options.currentUserId || isPersonalProjectTask;
  }

  validateRoot(task: PolicyTaskRecord, isCompanionOwned: boolean): ValidationResult {
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

  findChildViolation(
    root: Pick<PolicyTaskRecord, "projectId">,
    child: PolicyTaskRecord
  ): Exclude<HierarchyViolation, "too_many_children" | "nested_child"> | null {
    if (
      child.dueDatetime !== null ||
      child.dueDate != null ||
      child.dueString != null ||
      child.dueLang != null ||
      child.dueTimezone != null
    ) {
      return "child_has_schedule";
    }
    if (
      child.durationMinutes !== null ||
      child.durationAmount != null ||
      child.durationUnit != null
    ) {
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

  inspectRootHierarchy<T extends PolicyTaskRecord = PolicyTaskRecord>(
    root: PolicyTaskRecord,
    allTasks: readonly T[]
  ): {
    status: "valid" | "unsupported";
    violation: HierarchyViolation | null;
    directChildren: T[];
  } {
    const directChildren = allTasks
      .filter((task) => task.parentId === root.todoistTaskId && task.deletedAt == null)
      .sort(compareChildren);

    if (directChildren.length > MAX_CHECKLIST_CHILDREN) {
      return {
        status: "unsupported",
        violation: "too_many_children",
        directChildren
      };
    }

    const childIds = new Set(directChildren.map((child) => child.todoistTaskId));
    if (
      allTasks.some(
        (task) => task.deletedAt == null && task.parentId !== null && childIds.has(task.parentId)
      )
    ) {
      return {
        status: "unsupported",
        violation: "nested_child",
        directChildren
      };
    }

    for (const child of directChildren) {
      const violation = this.findChildViolation(root, child);
      if (violation !== null) {
        return {
          status: "unsupported",
          violation,
          directChildren
        };
      }
    }

    return {
      status: "valid",
      violation: null,
      directChildren
    };
  }

  eligibleCheckedChildTodoistIds(
    root: PolicyTaskRecord,
    allTasks: readonly PolicyTaskRecord[]
  ): string[] {
    const parentsWithActiveChildren = new Set<string>();
    for (const candidate of allTasks) {
      if (candidate.deletedAt == null && candidate.parentId !== null) {
        parentsWithActiveChildren.add(candidate.parentId);
      }
    }

    return allTasks
      .filter(
        (task) =>
          task.parentId === root.todoistTaskId &&
          task.deletedAt == null &&
          task.completedAt != null &&
          !parentsWithActiveChildren.has(task.todoistTaskId) &&
          this.findChildViolation(root, task) === null
      )
      .map((task) => task.todoistTaskId);
  }

  classify(task: PolicyTaskRecord, allTasks: readonly PolicyTaskRecord[]): TaskEnforcementResult {
    if (task.parentId !== null) {
      return this.classifyChild(task, allTasks);
    }

    return this.classifyRoot(task, allTasks);
  }

  private classifyRoot(
    task: PolicyTaskRecord,
    allTasks: readonly PolicyTaskRecord[]
  ): TaskEnforcementResult {
    const isCompanionOwned = this.isCompanionOwned(task);
    const isGenuinelyUnassigned =
      task.assigneeId === null &&
      !isCompanionOwned &&
      task.projectId !== null &&
      this.options.projectsById.get(task.projectId)?.canAssignTasks === true;

    if (
      task.assigneeId !== this.options.currentUserId &&
      !isCompanionOwned &&
      !isGenuinelyUnassigned
    ) {
      return notOwned();
    }

    const hierarchy = this.inspectRootHierarchy(task, allTasks);
    if (hierarchy.violation !== null) {
      if (isGenuinelyUnassigned) {
        return {
          classification: "UNSUPPORTED_HIERARCHY",
          violation: hierarchy.violation,
          isCompanionOwned: false
        };
      }

      return {
        classification: "UNSUPPORTED_HIERARCHY",
        violation: hierarchy.violation,
        isCompanionOwned,
        validationErrors: this.validateRoot(task, isCompanionOwned).errors
      };
    }

    if (isGenuinelyUnassigned) {
      return {
        classification: "ROOT_UNASSIGNED",
        violation: null,
        isCompanionOwned: false,
        validationErrors: []
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

  private classifyChild(
    task: PolicyTaskRecord,
    allTasks: readonly PolicyTaskRecord[]
  ): TaskEnforcementResult {
    const parent = allTasks.find((candidate) => candidate.todoistTaskId === task.parentId);
    if (parent === undefined || parent.parentId !== null) {
      return notOwned();
    }

    const parentResult = this.classifyRoot(parent, allTasks);
    if (
      parentResult.classification !== "ROOT_COMMITMENT" &&
      parentResult.classification !== "ROOT_UNASSIGNED"
    ) {
      return notOwned();
    }

    if (this.findChildViolation(parent, task) !== null) {
      return notOwned();
    }

    return {
      classification: "CHECKLIST_CHILD",
      violation: null,
      isCompanionOwned: false
    };
  }
}

function compareChildren<T extends PolicyTaskRecord>(left: T, right: T): number {
  const leftOrder = left.childOrder ?? Number.MAX_SAFE_INTEGER;
  const rightOrder = right.childOrder ?? Number.MAX_SAFE_INTEGER;
  if (leftOrder !== rightOrder) {
    return leftOrder - rightOrder;
  }
  return left.todoistTaskId.localeCompare(right.todoistTaskId);
}

function notOwned(): TaskEnforcementResult {
  return {
    classification: "NOT_OWNED",
    violation: null,
    isCompanionOwned: false
  };
}
