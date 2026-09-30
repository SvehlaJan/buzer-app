import { decodeTaskDescription } from "../domain/taskDescriptionCodec.js";
import { TaskEnforcementPolicy } from "../domain/taskEnforcementPolicy.js";
import { describeRecurrence } from "../domain/taskRecurrence.js";
import { type CollaboratorTimeZoneSource, resolveOwnerTimeZone } from "../domain/ownerTimeZone.js";
import { computeStartAt, normalizeDueDatetime } from "../domain/time.js";
import { validateTaskStrictness } from "../domain/taskValidation.js";
import type { ProjectRepository } from "../repositories/projectRepository.js";
import type { TaskRepository, TaskWithCommitment } from "../repositories/taskRepository.js";
import { TaskHierarchyProjector } from "./taskHierarchyProjector.js";

export class TaskSnapshotService {
  constructor(
    private readonly tasks: TaskRepository,
    private readonly projects: ProjectRepository,
    private readonly currentUserId: string,
    private readonly requireMirrorReady = false,
    private readonly collaborators: CollaboratorTimeZoneSource | null = null
  ) {}

  getSnapshot(serverTime: string) {
    this.assertMirrorReady();
    return this.tasks.withSnapshotRead(() => {
      const rows = this.tasks.listAll();
      const projectsById = new Map(
        this.projects.listActive().map((project) => [project.todoistProjectId, project])
      );
      const policy = new TaskEnforcementPolicy({
        currentUserId: this.currentUserId,
        projectsById
      });
      const ownerTimeZone = resolveOwnerTimeZone(this.collaborators, this.currentUserId);
      const projections = new TaskHierarchyProjector(policy).project(rows);
      const items = projections.flatMap((projection) => {
        if (projection.classification === "NOT_OWNED" || projection.classification === "CHECKLIST_CHILD") {
          return [];
        }
        return [this.projectRoot(projection.root, projection.children, {
          classification: projection.classification,
          violation: projection.violation,
          checkedCount: projection.checkedCount,
          totalCount: projection.totalCount,
          isCompanionOwned: policy.isCompanionOwned(projection.root)
        }, ownerTimeZone)];
      });

      return {
        scheduleVersion: Math.max(0, ...rows.map((row) => row.scheduleVersion)),
        snapshotGeneration: this.tasks.currentSnapshotGeneration(),
        serverTime,
        tasks: items,
        health: []
      };
    });
  }

  private projectRoot(
    task: TaskWithCommitment,
    children: readonly TaskWithCommitment[],
    hierarchy: {
      classification: "ROOT_COMMITMENT" | "ROOT_CANDIDATE_INVALID" | "ROOT_UNASSIGNED" | "UNSUPPORTED_HIERARCHY";
      violation: string | null;
      checkedCount: number;
      totalCount: number;
      isCompanionOwned: boolean;
    },
    ownerTimeZone: string
  ) {
    const description = decodeTaskDescription(task.description);
    const recurrence = describeRecurrence({
      isRecurring: task.isRecurring,
      rule: task.dueString,
      language: task.dueLang,
      timeZone: task.dueTimezone
    });
    const terminalState = task.deletedAt !== null
      ? "deleted"
      : task.completedAt !== null
        ? "completed"
        : null;
    const isCompanionOwnedByUser = hierarchy.isCompanionOwned;

    return {
      id: task.id,
      todoistTaskId: task.todoistTaskId,
      title: task.title,
      description: description.userDescription,
      projectId: task.projectId,
      assigneeId: task.assigneeId,
      creatorId: task.creatorId,
      dueDatetime: normalizeDueDatetime(task.dueDatetime, ownerTimeZone),
      dueDate: task.dueDate,
      durationMinutes: task.durationMinutes,
      startAt: task.dueDatetime !== null && task.durationMinutes !== null
        ? computeStartAt(task.dueDatetime, task.durationMinutes, ownerTimeZone)
        : null,
      state: terminalState ?? (hierarchy.classification === "ROOT_COMMITMENT" || hierarchy.classification === "ROOT_UNASSIGNED" ? task.state : "invalid"),
      isAssignedToUser: task.assigneeId === this.currentUserId || isCompanionOwnedByUser,
      isCreatedByUser: task.creatorId === this.currentUserId,
      isCompleted: task.completedAt !== null,
      isDeleted: task.deletedAt !== null,
      validationErrors: hierarchy.classification === "ROOT_CANDIDATE_INVALID"
        ? validateTaskStrictness({
          title: task.title,
          creatorId: task.creatorId,
          assigneeId: task.assigneeId,
          dueDatetime: task.dueDatetime,
          durationMinutes: task.durationMinutes,
          currentUserId: this.currentUserId,
          isCompanionOwned: isCompanionOwnedByUser
        }).errors
        : [],
      startedAt: task.startedAt,
      startDismissCount: task.startDismissCount,
      deadlineExtensionCount: task.deadlineExtensionCount,
      escalationSentAt: task.escalationSentAt,
      updatedAt: task.updatedAt,
      occurrenceKey: task.occurrenceKey,
      taskRevision: `r:${task.mirrorRevision}`,
      activityLogEntries: description.activityLogEntries,
      recurrence: {
        kind: recurrence.kind,
        preset: recurrence.preset,
        displayLabel: recurrenceLabel(recurrence.kind, recurrence.preset, task.dueString),
        rule: task.dueString,
        language: task.dueLang,
        timeZone: task.dueTimezone
      },
      checklistItems: children.map((child) => projectChecklistItem(child)),
      checkedCount: hierarchy.checkedCount,
      totalCount: hierarchy.totalCount,
      hierarchyStatus: hierarchy.classification === "UNSUPPORTED_HIERARCHY" ? "unsupported" : "valid",
      hierarchyViolation: hierarchy.violation
    };
  }

  private assertMirrorReady(): void {
    if (this.requireMirrorReady && !this.tasks.isMirrorContractReady()) {
      throw syncInitializingError();
    }
  }
}

function projectChecklistItem(task: TaskWithCommitment) {
  const description = decodeTaskDescription(task.description).userDescription;
  return {
    id: task.id,
    todoistTaskId: task.todoistTaskId,
    title: task.title,
    description: description === "" ? null : description,
    checked: task.completedAt !== null,
    order: task.childOrder,
    childRevision: `r:${task.mirrorRevision}`
  };
}

function recurrenceLabel(kind: "none" | "supported" | "custom", preset: string | null, rule: string | null): string {
  if (kind === "custom") return rule ?? "Custom Todoist recurrence";
  if (preset === null || preset === "never") return "Never";
  return preset.slice(0, 1).toUpperCase() + preset.slice(1);
}

function syncInitializingError(): Error & { code: string; statusCode: number } {
  return Object.assign(new Error("Todoist synchronization is initializing"), {
    code: "sync_initializing",
    statusCode: 503
  });
}
