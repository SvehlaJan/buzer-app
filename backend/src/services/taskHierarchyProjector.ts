import {
  CommitmentPolicy,
  type HierarchyViolation,
  type PolicyTaskRecord,
  type TaskEnforcementClassification
} from "../domain/commitmentPolicy.js";

export interface TaskHierarchyProjection<T extends PolicyTaskRecord = PolicyTaskRecord> {
  root: T;
  children: T[];
  checkedCount: number;
  totalCount: number;
  classification: TaskEnforcementClassification;
  violation: HierarchyViolation | null;
  isCompanionOwned: boolean;
  validationErrors?: string[];
}

export class TaskHierarchyProjector {
  constructor(private readonly policy: CommitmentPolicy) {}

  project<T extends PolicyTaskRecord>(tasks: readonly T[]): TaskHierarchyProjection<T>[] {
    return tasks
      .filter((task) => task.parentId === null)
      .map((root) => this.projectRoot(root, tasks));
  }

  private projectRoot<T extends PolicyTaskRecord>(root: T, tasks: readonly T[]): TaskHierarchyProjection<T> {
    const hierarchy = this.policy.inspectRootHierarchy(root, tasks);
    const result = this.policy.classify(root, tasks);

    return {
      root,
      children: hierarchy.directChildren,
      checkedCount: hierarchy.directChildren.filter((child) => child.completedAt != null).length,
      totalCount: hierarchy.directChildren.length,
      classification: result.classification,
      violation: result.violation,
      isCompanionOwned: result.isCompanionOwned,
      ...(result.validationErrors !== undefined ? { validationErrors: result.validationErrors } : {})
    };
  }
}
