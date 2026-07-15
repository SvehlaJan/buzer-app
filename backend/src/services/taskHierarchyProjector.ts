import {
  TaskEnforcementPolicy,
  type EnforceableTask,
  type HierarchyViolation,
  type TaskEnforcementClassification
} from "../domain/taskEnforcementPolicy.js";

export interface TaskHierarchyProjection<T extends EnforceableTask = EnforceableTask> {
  root: T;
  children: T[];
  checkedCount: number;
  totalCount: number;
  classification: TaskEnforcementClassification;
  violation: HierarchyViolation | null;
}

export class TaskHierarchyProjector {
  constructor(private readonly policy: TaskEnforcementPolicy) {}

  project<T extends EnforceableTask>(tasks: readonly T[]): TaskHierarchyProjection<T>[] {
    return tasks
      .filter((task) => task.parentId === null)
      .map((root) => this.projectRoot(root, tasks));
  }

  private projectRoot<T extends EnforceableTask>(root: T, tasks: readonly T[]): TaskHierarchyProjection<T> {
    const children = tasks
      .filter((task) => task.parentId === root.todoistTaskId && task.deletedAt == null)
      .sort(compareChildren);
    const result = this.policy.classify(root, tasks);

    return {
      root,
      children,
      checkedCount: children.filter((child) => child.completedAt !== null).length,
      totalCount: children.length,
      classification: result.classification,
      violation: result.violation
    };
  }
}

function compareChildren<T extends EnforceableTask>(left: T, right: T): number {
  const leftOrder = left.childOrder ?? Number.MAX_SAFE_INTEGER;
  const rightOrder = right.childOrder ?? Number.MAX_SAFE_INTEGER;
  if (leftOrder !== rightOrder) {
    return leftOrder - rightOrder;
  }
  return left.todoistTaskId.localeCompare(right.todoistTaskId);
}
