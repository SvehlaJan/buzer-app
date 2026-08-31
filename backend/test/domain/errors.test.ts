import { describe, expect, it } from "vitest";
import { DomainError } from "../../src/domain/errors.js";

describe("DomainError action factories", () => {
  it.each([
    ["staleTask", "stale_task", 409],
    ["staleChild", "stale_child", 409],
    ["staleOccurrence", "stale_occurrence", 409],
    ["unsupportedHierarchy", "unsupported_hierarchy", 422],
    ["childrenIncomplete", "children_incomplete", 422],
    ["activityLogFull", "activity_log_full", 422]
  ] as const)("creates %s errors with snapshot context", (factory, code, statusCode) => {
    const error = DomainError[factory]({ taskId: "root-1", revision: "r:2" });

    expect(error).toMatchObject({
      name: "DomainError",
      code,
      statusCode,
      details: { snapshot: { taskId: "root-1", revision: "r:2" } }
    });
  });
});
