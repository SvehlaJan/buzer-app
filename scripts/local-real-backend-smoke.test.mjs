import assert from "node:assert/strict";
import test from "node:test";
import {
  buildSmokeTitle,
  chooseProjects,
  extractTodoistTaskIdFromErrorText,
  parseEnvText,
  todoistDeleteUrl,
  validateConfig
} from "./local-real-backend-smoke.mjs";

test("parseEnvText parses simple dotenv values", () => {
  assert.deepEqual(parseEnvText("PORT=18434\nWHATSAPP_DRY_RUN=true\n"), {
    PORT: "18434",
    WHATSAPP_DRY_RUN: "true"
  });
});

test("validateConfig refuses live WhatsApp", () => {
  assert.throws(() => validateConfig({
    ANDROID_API_TOKEN: "token",
    TODOIST_API_TOKEN: "todoist",
    TODOIST_USER_ID: "123",
    WHATSAPP_RECIPIENT_ID: "recipient",
    WHATSAPP_DRY_RUN: "false",
    DATABASE_PATH: "./data/smoke.db",
    PORT: "18434"
  }), /WHATSAPP_DRY_RUN must be true/);
});

test("chooseProjects returns assignable and personal projects", () => {
  const chosen = chooseProjects([
    { id: "project-personal", name: "Personal", canAssignTasks: false },
    { id: "project-shared", name: "Shared", canAssignTasks: true }
  ]);
  assert.equal(chosen.assignable.id, "project-shared");
  assert.equal(chosen.personal.id, "project-personal");
});

test("chooseProjects fails when required project capabilities are missing", () => {
  assert.throws(() => chooseProjects([
    { id: "project-shared", name: "Shared", canAssignTasks: true }
  ]), /non-assignable/);
});

test("buildSmokeTitle includes run id and flow name", () => {
  assert.equal(buildSmokeTitle("run-123", "assignable"), "Buzer smoke run-123 assignable");
});

test("todoistDeleteUrl encodes task id", () => {
  assert.equal(
    todoistDeleteUrl("https://api.todoist.com/api/v1", "task/1"),
    "https://api.todoist.com/api/v1/tasks/task%2F1"
  );
});

test("extractTodoistTaskIdFromErrorText returns verification failure task id", () => {
  assert.equal(
    extractTodoistTaskIdFromErrorText(
      "{\"code\":\"todoist_create_verification_failed\",\"details\":{\"todoistTaskId\":\"6grjp9GQ9HQvxJGg\"}}"
    ),
    "6grjp9GQ9HQvxJGg"
  );
});
