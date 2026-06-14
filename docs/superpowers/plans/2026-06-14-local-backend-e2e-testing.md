# Local Backend And E2E Testing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a repeatable fake-backend Android E2E lane and an opt-in local real-backend smoke lane with WhatsApp dry-run and Todoist cleanup.

**Architecture:** Keep Android UI regression tests deterministic by driving the app against `tools/fake-backend` through AndroidX/Compose instrumentation. Keep real Todoist validation in a separate Node smoke script that starts the local backend, creates project-aware tasks through backend HTTP, verifies snapshots, and deletes created Todoist tasks directly through Todoist REST. Do not add production backend debug cleanup endpoints.

**Tech Stack:** Node.js 22, TypeScript/Fastify fake backend, Node ESM scripts, AndroidX Test, Compose UI Test, Gradle Managed Devices, Todoist REST API.

---

## Context

Product truth:

- `docs/superpowers/specs/2026-04-30-todoist-commitment-companion-design.md`
- `docs/superpowers/specs/2026-06-13-project-aware-task-creation-design.md`
- `docs/superpowers/specs/2026-06-14-local-backend-e2e-testing-design.md`

Current relevant implementation:

- `tools/fake-backend/src/server.ts` already has `/projects`, `/tasks`, `__calls`, `__reset`, `__fail-actions`, and `__fail-snapshot`.
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/AddTaskFakeBackendTest.kt` exists but still assumes the old Add Task flow with no project picker.
- `client/androidApp/build.gradle.kts` already defines the `pixel8Api34` managed device and AndroidX/Compose UI test dependencies.
- `backend/src/server.ts` already supports `WHATSAPP_DRY_RUN=true`, local SQLite, and bearer-authenticated routes.

Current dirty-worktree warning:

- The repository may still contain unrelated dirty blocker/overlay watcher files and handoff/backlog notes from earlier work. Do not revert or stage them as part of this plan unless the user explicitly asks.

Documentation already consulted:

- Context7 AndroidX docs for Compose UI Test APIs and `AndroidComposeTestRule`.
- Android emulator/testing project notes:
  - `docs/notes/local-development.md`
  - `docs/notes/2026-06-08-android-16-emulator-setup.md`
- Context7 Todoist API docs for task cleanup:
  - `DELETE /api/v1/tasks/{task_id}` deletes a task and subtasks.

## File Map

Fake backend:

- Modify: `tools/fake-backend/src/server.ts`
- Modify: `tools/fake-backend/src/server.test.ts`

Android instrumentation:

- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/FakeBackendClient.kt`
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/AddTaskFakeBackendTest.kt`

Scripts:

- Create: `scripts/e2e-fake-backend.sh`
- Create: `scripts/local-real-backend-smoke.mjs`
- Create: `scripts/local-real-backend-smoke.test.mjs`

Docs:

- Modify: `docs/notes/local-development.md`
- Create/update: `docs/handoffs/2026-06-14-local-backend-e2e-testing.md`

---

### Task 1: Add Fake Backend Project Failure Toggle

**Files:**
- Modify: `tools/fake-backend/src/server.ts`
- Modify: `tools/fake-backend/src/server.test.ts`

- [ ] **Step 1: Add failing fake-backend project failure tests**

Append these tests to `tools/fake-backend/src/server.test.ts` near the existing project/snapshot failure tests:

```ts
test("test helper can toggle forced project failures at runtime", async (t) => {
  const app = await buildServer();
  t.after(() => app.close());

  const toggle = await app.inject({
    method: "POST",
    url: "/__fail-projects",
    payload: { enabled: true }
  });
  assert.equal(toggle.statusCode, 200);
  assert.deepEqual(toggle.json(), { failProjects: true });

  const response = await app.inject({
    method: "GET",
    url: "/projects",
    headers: { authorization: "Bearer test-token" }
  });
  assert.equal(response.statusCode, 500);
  assert.deepEqual(response.json(), { error: "forced_project_failure" });
});

test("project failure toggle can be disabled", async (t) => {
  const app = await buildServer();
  t.after(() => app.close());

  await app.inject({
    method: "POST",
    url: "/__fail-projects",
    payload: { enabled: true }
  });
  await app.inject({
    method: "POST",
    url: "/__fail-projects",
    payload: { enabled: false }
  });

  const response = await app.inject({
    method: "GET",
    url: "/projects",
    headers: { authorization: "Bearer test-token" }
  });
  assert.equal(response.statusCode, 200);
  assert.equal(response.json().projects.length, 2);
});
```

- [ ] **Step 2: Run fake-backend tests to verify RED**

Run:

```bash
cd tools/fake-backend
npm test
```

Expected: FAIL because `POST /__fail-projects` returns 404.

- [ ] **Step 3: Implement project failure toggle**

In `tools/fake-backend/src/server.ts`, add state after `let failSnapshot = false;`:

```ts
  let failProjects = false;
```

Change the `/projects` handler to:

```ts
  app.get("/projects", async (_request, reply) => {
    await maybeDelay();
    if (failProjects) {
      return reply.code(500).send({ error: "forced_project_failure" });
    }
    return {
      projects: [
        {
          id: "project-test",
          name: "Test Shared",
          canAssignTasks: true,
          shared: true,
          isInboxProject: false
        },
        {
          id: "project-personal",
          name: "Test Personal",
          canAssignTasks: false,
          shared: false,
          isInboxProject: false
        }
      ]
    };
  });
```

Add this helper endpoint near `__fail-snapshot`:

```ts
  app.post<{ Body: { enabled?: boolean } }>("/__fail-projects", async (request) => {
    failProjects = request.body?.enabled === true;
    return { failProjects };
  });
```

- [ ] **Step 4: Run fake-backend tests**

Run:

```bash
cd tools/fake-backend
npm test
```

Expected: PASS, including the new project failure toggle tests.

- [ ] **Step 5: Commit Task 1**

Run:

```bash
git add tools/fake-backend/src/server.ts tools/fake-backend/src/server.test.ts
git commit -m "test: add fake backend project failure toggle"
```

Expected: commit succeeds.

---

### Task 2: Update Android Add Task Fake-Backend E2E Tests

**Files:**
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/FakeBackendClient.kt`
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/AddTaskFakeBackendTest.kt`

- [ ] **Step 1: Run the current Add Task E2E test to verify existing RED**

Start fake backend in one terminal:

```bash
cd tools/fake-backend
PORT=18433 npm run dev
```

Then run:

```bash
cd client
./gradlew pixel8Api34DebugAndroidTest --rerun-tasks \
  -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 \
  -PBUZER_BACKEND_TOKEN=test-token \
  -PBUZER_ASSIGNEE_ME_ID=user-me \
  -PBUZER_ASSIGNEE_WIFE_ID=user-wife \
  -PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433
```

Expected: FAIL because the current Add Task tests try to click `Me` before selecting an assignable project. If the managed-device suite fails on another already-known test first, record that and continue with the replacement tests in Step 3.

- [ ] **Step 2: Extend the instrumentation fake backend client**

Modify `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/FakeBackendClient.kt`.

Add:

```kotlin
    fun setFailProjects(enabled: Boolean) {
        request("POST", "/__fail-projects", """{"enabled":$enabled}""")
    }

    fun assertRecordedWithout(path: String, vararg forbiddenFragments: String) {
        val body = calls()
        check(body.contains("\"path\":\"$path\"")) {
            "Expected fake backend call $path, got $body"
        }
        forbiddenFragments.forEach { fragment ->
            check(!body.contains(fragment)) {
                "Expected fake backend call $path body to omit $fragment, got $body"
            }
        }
    }
```

- [ ] **Step 3: Replace Add Task fake-backend tests with project-aware flows**

Replace `client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/AddTaskFakeBackendTest.kt` with:

```kotlin
package app.buzer.android.addtask

import android.content.Context
import android.os.SystemClock
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.buzer.android.MainActivity
import app.buzer.android.blockers.FakeBackendClient
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AddTaskFakeBackendTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val fakeBackend = FakeBackendClient()

    @Test
    fun addTask_requiresExplicitProjectSelectionOnFirstOpen() {
        resetTestState()

        ActivityScenario.launch(MainActivity::class.java)

        composeRule.onNodeWithText("Add task").performClick()
        waitForText("Select project")
        composeRule.onNodeWithContentDescription("Title input").performTextInput("Buy milk")
        selectDefaultDeadline()
        composeRule.onNodeWithContentDescription("Duration minutes input").performTextInput("45")
        composeRule.onNodeWithText("Save").performClick()

        composeRule.onNodeWithText("Project is required").assertIsDisplayed()
        assertNoRecordedCall("/tasks")
    }

    @Test
    fun addTask_sharedProjectRequiresAssigneeAndCreatesTask() {
        resetTestState()

        ActivityScenario.launch(MainActivity::class.java)

        composeRule.onNodeWithText("Add task").performClick()
        selectProject("Test Shared")
        composeRule.onNodeWithContentDescription("Title input").performTextInput("Buy milk")
        selectDefaultDeadline()
        composeRule.onNodeWithContentDescription("Duration minutes input").performTextInput("45")
        composeRule.onNodeWithText("Save").performClick()
        composeRule.onNodeWithText("Assignee is required for this project").assertIsDisplayed()

        composeRule.onNodeWithText("Me").performClick()
        composeRule.onNodeWithText("Save").performClick()

        waitForRecordedCall("/tasks")
        fakeBackend.assertRecorded(
            "/tasks",
            "\"title\":\"Buy milk\"",
            "\"projectId\":\"project-test\"",
            "\"assigneeId\":\"user-me\"",
            "\"durationMinutes\":45",
            "dueDatetime"
        )
    }

    @Test
    fun addTask_personalProjectOmitsAssignee() {
        resetTestState()

        ActivityScenario.launch(MainActivity::class.java)

        composeRule.onNodeWithText("Add task").performClick()
        selectProject("Test Personal (personal)")
        composeRule.onAllNodesWithText("Me").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Title input").performTextInput("Personal task")
        selectDefaultDeadline()
        composeRule.onNodeWithContentDescription("Duration minutes input").performTextInput("30")
        composeRule.onNodeWithText("Save").performClick()

        waitForRecordedCall("/tasks")
        fakeBackend.assertRecorded(
            "/tasks",
            "\"title\":\"Personal task\"",
            "\"projectId\":\"project-personal\"",
            "\"durationMinutes\":30",
            "dueDatetime"
        )
        fakeBackend.assertRecordedWithout("/tasks", "assigneeId")
    }

    @Test
    fun addTask_projectLoadFailureDisablesSaveAndRetryRecovers() {
        resetTestState()
        fakeBackend.setFailProjects(true)
        try {
            ActivityScenario.launch(MainActivity::class.java)

            composeRule.onNodeWithText("Add task").performClick()
            waitForText("500")
            composeRule.onNodeWithText("Save").assertIsNotEnabled()

            fakeBackend.setFailProjects(false)
            composeRule.onNodeWithText("Retry").performClick()
            waitForText("Select project")
            composeRule.onNodeWithText("Select project").assertIsDisplayed()
        } finally {
            fakeBackend.setFailProjects(false)
        }
    }

    @Test
    fun addTask_backendFailureKeepsFormAndEnteredTitle() {
        resetTestState()
        fakeBackend.setFailActions(true)
        try {
            ActivityScenario.launch(MainActivity::class.java)

            composeRule.onNodeWithText("Add task").performClick()
            selectProject("Test Shared")
            composeRule.onNodeWithContentDescription("Title input").performTextInput("Buy milk")
            composeRule.onNodeWithText("Me").performClick()
            selectDefaultDeadline()
            composeRule.onNodeWithContentDescription("Duration minutes input").performTextInput("45")
            composeRule.onNodeWithText("Save").performClick()

            waitForRecordedCall("/tasks")
            composeRule.onNodeWithText("Add committed task").assertIsDisplayed()
            composeRule.onNodeWithText("Buy milk").assertIsDisplayed()
            composeRule.onNodeWithText("500", substring = true).assertIsDisplayed()
        } finally {
            fakeBackend.setFailActions(false)
        }
    }

    @Test
    fun addTask_reopensWithPersistedProjectSelected() {
        resetTestState()

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.onNodeWithText("Add task").performClick()
            selectProject("Test Personal (personal)")
            composeRule.onNodeWithText("Back").performClick()
        }

        ActivityScenario.launch(MainActivity::class.java)

        composeRule.onNodeWithText("Add task").performClick()
        waitForText("Test Personal")
        composeRule.onNodeWithText("Test Personal").assertIsDisplayed()
    }

    private fun selectProject(label: String) {
        waitForText("Select project")
        composeRule.onNodeWithContentDescription("Project picker").performClick()
        composeRule.onNodeWithText(label).performClick()
    }

    private fun selectDefaultDeadline() {
        composeRule.onNodeWithContentDescription("Deadline picker").performClick()
        composeRule.onNodeWithText("OK").performClick()
        composeRule.onNodeWithText("OK").performClick()
    }

    private fun resetTestState() {
        fakeBackend.setFailActions(false)
        fakeBackend.setFailProjects(false)
        fakeBackend.resetCalls()
        clearProjectSelection()
    }

    private fun clearProjectSelection() {
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("buzer_add_task", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun waitForText(text: String) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        var lastError: Throwable? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                composeRule.onNodeWithText(text, substring = true).assertIsDisplayed()
                return
            } catch (error: Throwable) {
                lastError = error
                SystemClock.sleep(100)
            }
        }
        throw AssertionError("Expected text containing $text", lastError)
    }

    private fun waitForRecordedCall(path: String) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        var lastCalls = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            lastCalls = runCatching { fakeBackend.calls() }.getOrDefault(lastCalls)
            if (lastCalls.contains("\"path\":\"$path\"")) return
            SystemClock.sleep(100)
        }
        fail("Expected fake backend call $path. Recorded calls: $lastCalls")
    }

    private fun assertNoRecordedCall(path: String) {
        val calls = fakeBackend.calls()
        check(!calls.contains("\"path\":\"$path\"")) {
            "Expected no fake backend call $path. Recorded calls: $calls"
        }
    }
}
```

- [ ] **Step 4: Run Android instrumentation test**

Start fake backend if it is not already running:

```bash
cd tools/fake-backend
PORT=18433 npm run dev
```

Run:

```bash
cd client
./gradlew pixel8Api34DebugAndroidTest --rerun-tasks \
  -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 \
  -PBUZER_BACKEND_TOKEN=test-token \
  -PBUZER_ASSIGNEE_ME_ID=user-me \
  -PBUZER_ASSIGNEE_WIFE_ID=user-wife \
  -PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433
```

Expected: PASS. If it fails because a Compose node is not found, inspect the test report and update the test to match the actual displayed text, not by using coordinates.

- [ ] **Step 5: Run full fake-backend Android instrumentation suite**

Run:

```bash
cd client
./gradlew pixel8Api34DebugAndroidTest --rerun-tasks \
  -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 \
  -PBUZER_BACKEND_TOKEN=test-token \
  -PBUZER_ASSIGNEE_ME_ID=user-me \
  -PBUZER_ASSIGNEE_WIFE_ID=user-wife \
  -PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433
```

Expected: PASS.

- [ ] **Step 6: Commit Task 2**

Run:

```bash
git add client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/FakeBackendClient.kt client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/AddTaskFakeBackendTest.kt
git commit -m "test: cover project-aware add task e2e"
```

Expected: commit succeeds.

---

### Task 3: Add Fake Backend E2E Runner Script

**Files:**
- Create: `scripts/e2e-fake-backend.sh`
- Modify: `docs/notes/local-development.md`

- [ ] **Step 1: Create the fake-backend E2E runner script**

Create `scripts/e2e-fake-backend.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT="${PORT:-18433}"
TOKEN="${BUZER_BACKEND_TOKEN:-test-token}"
FAKE_BACKEND_URL="http://127.0.0.1:${PORT}"
EMULATOR_BACKEND_URL="http://10.0.2.2:${PORT}"
STARTED_PID=""

cleanup() {
  if [[ -n "${STARTED_PID}" ]]; then
    kill "${STARTED_PID}" >/dev/null 2>&1 || true
    wait "${STARTED_PID}" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

wait_for_fake_backend() {
  local deadline=$((SECONDS + 30))
  while (( SECONDS < deadline )); do
    if curl --silent --fail \
      -H "Authorization: Bearer ${TOKEN}" \
      "${FAKE_BACKEND_URL}/projects" >/dev/null; then
      return 0
    fi
    sleep 1
  done
  echo "Timed out waiting for fake backend at ${FAKE_BACKEND_URL}" >&2
  return 1
}

if curl --silent --fail \
  -H "Authorization: Bearer ${TOKEN}" \
  "${FAKE_BACKEND_URL}/projects" >/dev/null; then
  echo "Using existing fake backend at ${FAKE_BACKEND_URL}"
else
  echo "Starting fake backend at ${FAKE_BACKEND_URL}"
  (
    cd "${ROOT_DIR}/tools/fake-backend"
    PORT="${PORT}" npm run dev
  ) &
  STARTED_PID="$!"
  wait_for_fake_backend
fi

cd "${ROOT_DIR}/client"
./gradlew pixel8Api34DebugAndroidTest --rerun-tasks \
  -PBUZER_BACKEND_BASE_URL="${EMULATOR_BACKEND_URL}" \
  -PBUZER_BACKEND_TOKEN="${TOKEN}" \
  -PBUZER_ASSIGNEE_ME_ID="${BUZER_ASSIGNEE_ME_ID:-user-me}" \
  -PBUZER_ASSIGNEE_WIFE_ID="${BUZER_ASSIGNEE_WIFE_ID:-user-wife}" \
  -PFAKE_BACKEND_BASE_URL="${EMULATOR_BACKEND_URL}"
```

- [ ] **Step 2: Make the script executable**

Run:

```bash
chmod +x scripts/e2e-fake-backend.sh
```

- [ ] **Step 3: Run the fake-backend E2E runner**

Run:

```bash
scripts/e2e-fake-backend.sh
```

Expected: PASS with Gradle reporting `BUILD SUCCESSFUL`.

- [ ] **Step 4: Document the fake E2E command**

In `docs/notes/local-development.md`, add under `### Instrumentation harnesses`:

```md
### Fake-backend Android E2E

Use this as the default deterministic Android E2E lane:

```bash
scripts/e2e-fake-backend.sh
```

The script starts `tools/fake-backend` on port `18433` when needed, runs `pixel8Api34DebugAndroidTest`, and passes the emulator URL `http://10.0.2.2:18433` into both the app and instrumentation helper.
```
```

- [ ] **Step 5: Commit Task 3**

Run:

```bash
git add scripts/e2e-fake-backend.sh docs/notes/local-development.md
git commit -m "test: add fake backend e2e runner"
```

Expected: commit succeeds.

---

### Task 4: Add Local Real-Backend Smoke Script

**Files:**
- Create: `scripts/local-real-backend-smoke.mjs`
- Create: `scripts/local-real-backend-smoke.test.mjs`
- Modify: `docs/notes/local-development.md`

- [ ] **Step 1: Write failing tests for smoke helper functions**

Create `scripts/local-real-backend-smoke.test.mjs`:

```js
import assert from "node:assert/strict";
import test from "node:test";
import {
  buildSmokeTitle,
  chooseProjects,
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
```

- [ ] **Step 2: Run smoke helper tests to verify RED**

Run:

```bash
node --test scripts/local-real-backend-smoke.test.mjs
```

Expected: FAIL because `scripts/local-real-backend-smoke.mjs` does not exist.

- [ ] **Step 3: Create local real-backend smoke script**

Create `scripts/local-real-backend-smoke.mjs`:

```js
#!/usr/bin/env node
import { spawn } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const rootDir = resolve(__dirname, "..");

export function parseEnvText(text) {
  const result = {};
  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (line === "" || line.startsWith("#")) continue;
    const equalsIndex = line.indexOf("=");
    if (equalsIndex === -1) continue;
    const key = line.slice(0, equalsIndex).trim();
    let value = line.slice(equalsIndex + 1).trim();
    if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1);
    }
    result[key] = value;
  }
  return result;
}

export function validateConfig(config) {
  const required = [
    "ANDROID_API_TOKEN",
    "TODOIST_API_TOKEN",
    "TODOIST_USER_ID",
    "WHATSAPP_RECIPIENT_ID",
    "WHATSAPP_DRY_RUN",
    "DATABASE_PATH",
    "PORT"
  ];
  const missing = required.filter((key) => !config[key]);
  if (missing.length > 0) {
    throw new Error(`Missing required local smoke env values: ${missing.join(", ")}`);
  }
  if (config.WHATSAPP_DRY_RUN !== "true") {
    throw new Error("WHATSAPP_DRY_RUN must be true for local real-backend smoke");
  }
  return config;
}

export function chooseProjects(projects) {
  const assignable = projects.find((project) => project.canAssignTasks === true);
  const personal = projects.find((project) => project.canAssignTasks === false);
  if (assignable === undefined) {
    throw new Error("No assignable Todoist project found in GET /projects response");
  }
  if (personal === undefined) {
    throw new Error("No non-assignable Todoist project found in GET /projects response");
  }
  return { assignable, personal };
}

export function buildSmokeTitle(runId, flowName) {
  return `Buzer smoke ${runId} ${flowName}`;
}

export function todoistDeleteUrl(baseUrl, taskId) {
  return `${baseUrl.replace(/\/$/, "")}/tasks/${encodeURIComponent(taskId)}`;
}

function loadEnvFile(path) {
  if (!existsSync(path)) {
    throw new Error(`Env file not found: ${path}`);
  }
  return parseEnvText(readFileSync(path, "utf8"));
}

async function requestJson(url, options = {}) {
  const response = await fetch(url, options);
  const text = await response.text();
  const body = text.length > 0 ? JSON.parse(text) : null;
  if (!response.ok) {
    throw new Error(`${options.method ?? "GET"} ${url} failed with HTTP ${response.status}: ${text}`);
  }
  return body;
}

async function waitForBackend(baseUrl, token) {
  const deadline = Date.now() + 30_000;
  let lastError = "";
  while (Date.now() < deadline) {
    try {
      await requestJson(`${baseUrl}/health`);
      return;
    } catch (error) {
      lastError = error.message;
      await new Promise((resolve) => setTimeout(resolve, 1_000));
    }
  }
  throw new Error(`Timed out waiting for local backend: ${lastError}`);
}

async function waitForProjects(baseUrl, token) {
  const deadline = Date.now() + 60_000;
  let lastError = "";
  while (Date.now() < deadline) {
    try {
      const response = await requestJson(`${baseUrl}/projects`, {
        headers: { authorization: `Bearer ${token}` }
      });
      chooseProjects(response.projects);
      return response.projects;
    } catch (error) {
      lastError = error.message;
      await new Promise((resolve) => setTimeout(resolve, 2_000));
    }
  }
  throw new Error(`Timed out waiting for assignable and non-assignable projects: ${lastError}`);
}

async function waitForSnapshotTasks(baseUrl, token, localTaskIds) {
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    const snapshot = await requestJson(`${baseUrl}/tasks/snapshot`, {
      headers: { authorization: `Bearer ${token}` }
    });
    const ids = new Set(snapshot.tasks.map((task) => task.id));
    if (localTaskIds.every((id) => ids.has(id))) {
      return snapshot;
    }
    await new Promise((resolve) => setTimeout(resolve, 1_000));
  }
  throw new Error(`Timed out waiting for created tasks in snapshot: ${localTaskIds.join(", ")}`);
}

async function cleanupTodoistTasks(todoistBaseUrl, token, createdTasks) {
  for (const task of createdTasks.reverse()) {
    try {
      const response = await fetch(todoistDeleteUrl(todoistBaseUrl, task.todoistTaskId), {
        method: "DELETE",
        headers: { authorization: `Bearer ${token}` }
      });
      if (!response.ok && response.status !== 404) {
        const body = await response.text();
        throw new Error(`HTTP ${response.status}: ${body}`);
      }
      console.log(`Cleaned Todoist task ${task.todoistTaskId} (${task.title})`);
    } catch (error) {
      console.error(`Failed to clean Todoist task ${task.todoistTaskId} (${task.title}): ${error.message}`);
    }
  }
}

async function main() {
  if (process.argv.includes("--help")) {
    console.log("Usage: scripts/local-real-backend-smoke.mjs --i-understand-this-uses-live-todoist [--env backend/.env.local-smoke]");
    return;
  }
  if (!process.argv.includes("--i-understand-this-uses-live-todoist")) {
    throw new Error("Refusing to run without --i-understand-this-uses-live-todoist");
  }

  const envArgIndex = process.argv.indexOf("--env");
  const envPath = envArgIndex === -1
    ? resolve(rootDir, "backend/.env.local-smoke")
    : resolve(rootDir, process.argv[envArgIndex + 1]);
  const config = validateConfig({ ...process.env, ...loadEnvFile(envPath) });
  const port = Number(config.PORT);
  const baseUrl = `http://127.0.0.1:${port}`;
  const todoistBaseUrl = config.TODOIST_API_BASE_URL ?? "https://api.todoist.com/api/v1";
  const runId = new Date().toISOString().replace(/[-:.TZ]/g, "").slice(0, 14);
  const createdTasks = [];

  const backendProcess = spawn("npm", ["run", "dev"], {
    cwd: resolve(rootDir, "backend"),
    env: { ...process.env, ...config },
    stdio: "inherit"
  });

  try {
    await waitForBackend(baseUrl, config.ANDROID_API_TOKEN);
    const projects = await waitForProjects(baseUrl, config.ANDROID_API_TOKEN);
    const { assignable, personal } = chooseProjects(projects);
    const dueDatetime = new Date(Date.now() + 10 * 60_000).toISOString().replace(".000Z", "Z");

    const assignableTitle = buildSmokeTitle(runId, "assignable");
    const assignableCreated = await requestJson(`${baseUrl}/tasks`, {
      method: "POST",
      headers: {
        authorization: `Bearer ${config.ANDROID_API_TOKEN}`,
        "content-type": "application/json"
      },
      body: JSON.stringify({
        title: assignableTitle,
        projectId: assignable.id,
        assigneeId: config.SMOKE_ASSIGNEE_ID ?? config.TODOIST_USER_ID,
        dueDatetime,
        durationMinutes: 3
      })
    });
    createdTasks.push({ ...assignableCreated, title: assignableTitle });

    const personalTitle = buildSmokeTitle(runId, "personal");
    const personalCreated = await requestJson(`${baseUrl}/tasks`, {
      method: "POST",
      headers: {
        authorization: `Bearer ${config.ANDROID_API_TOKEN}`,
        "content-type": "application/json"
      },
      body: JSON.stringify({
        title: personalTitle,
        projectId: personal.id,
        dueDatetime,
        durationMinutes: 3
      })
    });
    createdTasks.push({ ...personalCreated, title: personalTitle });

    await waitForSnapshotTasks(baseUrl, config.ANDROID_API_TOKEN, createdTasks.map((task) => task.id));
    console.log(`Local real-backend smoke passed for run ${runId}`);
  } finally {
    await cleanupTodoistTasks(todoistBaseUrl, config.TODOIST_API_TOKEN, createdTasks);
    backendProcess.kill("SIGTERM");
  }
}

if (import.meta.url === `file://${process.argv[1]}`) {
  main().catch((error) => {
    console.error(error.message);
    process.exit(1);
  });
}
```

- [ ] **Step 4: Make the smoke script executable**

Run:

```bash
chmod +x scripts/local-real-backend-smoke.mjs
```

- [ ] **Step 5: Run smoke helper tests**

Run:

```bash
node --test scripts/local-real-backend-smoke.test.mjs
```

Expected: PASS.

- [ ] **Step 6: Run smoke script non-live checks**

Run:

```bash
scripts/local-real-backend-smoke.mjs --help
```

Expected: PASS and prints usage.

Run:

```bash
scripts/local-real-backend-smoke.mjs
```

Expected: FAIL with `Refusing to run without --i-understand-this-uses-live-todoist`.

- [ ] **Step 7: Document local real-backend smoke**

In `docs/notes/local-development.md`, add:

```md
### Local Real-Backend Smoke

This opt-in smoke uses live Todoist and must not be run casually. It starts the real backend locally with `WHATSAPP_DRY_RUN=true`, creates one assignable-project task and one non-assignable-project task through backend `POST /tasks`, verifies they appear in `/tasks/snapshot`, then deletes the created Todoist tasks directly through Todoist REST.

Create an ignored `backend/.env.local-smoke`:

```properties
NODE_ENV=development
PORT=18434
DATABASE_PATH=./data/buzer-local-smoke.db
ANDROID_API_TOKEN=replace-with-local-token
TODOIST_API_TOKEN=replace-with-real-token
TODOIST_USER_ID=replace-with-current-user-id
SMOKE_ASSIGNEE_ID=replace-with-current-user-id
WHATSAPP_RECIPIENT_ID=dry-run-recipient
WHATSAPP_DRY_RUN=true
WHATSAPP_SESSION_PATH=./whatsapp-sessions
WHATSAPP_CLIENT_ID=buzer-local-smoke
```

Run:

```bash
scripts/local-real-backend-smoke.mjs --i-understand-this-uses-live-todoist
```

The script refuses to run if `WHATSAPP_DRY_RUN` is not `true`. If cleanup fails, it prints the Todoist task IDs and titles for manual deletion.
```
```

- [ ] **Step 8: Optionally run local real-backend smoke**

Only run this if `backend/.env.local-smoke` exists with real local credentials and the user has explicitly approved live Todoist use in this session:

```bash
scripts/local-real-backend-smoke.mjs --i-understand-this-uses-live-todoist
```

Expected: PASS, with logs indicating created tasks were deleted. If credentials are unavailable, SKIP and document the skip in the handoff.

- [ ] **Step 9: Commit Task 4**

Run:

```bash
git add scripts/local-real-backend-smoke.mjs scripts/local-real-backend-smoke.test.mjs docs/notes/local-development.md
git commit -m "test: add local real backend smoke"
```

Expected: commit succeeds.

---

### Task 5: Full Verification And Handoff

**Files:**
- Create/update: `docs/handoffs/2026-06-14-local-backend-e2e-testing.md`
- Modify: `docs/superpowers/plans/2026-06-14-local-backend-e2e-testing.md`

- [ ] **Step 1: Run fake backend tests**

Run:

```bash
cd tools/fake-backend
npm test
```

Expected: PASS.

- [ ] **Step 2: Run Android unit/shared tests**

Run:

```bash
cd client
./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain
```

Expected: PASS.

- [ ] **Step 3: Run fake-backend Android E2E script**

Run:

```bash
scripts/e2e-fake-backend.sh
```

Expected: PASS. If the managed device image is unavailable or emulator startup fails, document the exact failure and whether `connectedDebugAndroidTest` was run on an already-available emulator.

- [ ] **Step 4: Run smoke helper tests**

Run:

```bash
node --test scripts/local-real-backend-smoke.test.mjs
```

Expected: PASS.

- [ ] **Step 5: Run backend checks**

Run:

```bash
cd backend
npm test
npm run typecheck
npm run build
```

Expected: PASS.

- [ ] **Step 6: Run local real-backend smoke or document skip**

If `backend/.env.local-smoke` exists and the user approves live Todoist use in the execution session, run:

```bash
scripts/local-real-backend-smoke.mjs --i-understand-this-uses-live-todoist
```

Expected: PASS and cleanup of created Todoist tasks. If skipped, record `SKIPPED` with the reason, for example `backend/.env.local-smoke not present`.

- [ ] **Step 7: Update plan checkboxes**

Mark completed steps in `docs/superpowers/plans/2026-06-14-local-backend-e2e-testing.md`. Leave any skipped live-smoke/device steps unchecked if they were not executed.

- [ ] **Step 8: Write handoff**

Create `docs/handoffs/2026-06-14-local-backend-e2e-testing.md`:

```md
# 2026-06-14 Local Backend And E2E Testing

## Task / Plan

- Plan: `docs/superpowers/plans/2026-06-14-local-backend-e2e-testing.md`
- Checkbox status: <completed/skipped summary>

## Files Changed

- <list files changed>

## Commands Run

Verification:
- Command: `<exact command>`
- Result: PASS/FAIL/SKIPPED
- Evidence: `<important output or reason>`

## Manual Verification

- <manual checks, if any>

## Known Failures / Skipped Checks

- <known failures/skips>

## Assumptions

- Fake E2E is the default regression lane.
- Local real-backend smoke uses live Todoist and deletes tasks through Todoist REST.

## Next Recommended Step

- <recommended next action>
```

- [ ] **Step 9: Commit Task 5**

Run:

```bash
git add docs/superpowers/plans/2026-06-14-local-backend-e2e-testing.md docs/handoffs/2026-06-14-local-backend-e2e-testing.md
git commit -m "docs: record local backend e2e verification"
```

Expected: commit succeeds.

---

## Final Acceptance Criteria

- `scripts/e2e-fake-backend.sh` runs fake backend Android E2E on the managed device.
- `AddTaskFakeBackendTest` covers explicit project selection, persisted project preselection, assignable project assignee rules, non-assignable assignee omission, project load retry, and create failure form preservation.
- `tools/fake-backend` can force project loading failure for instrumentation tests.
- `scripts/local-real-backend-smoke.mjs` refuses accidental live Todoist use without an explicit opt-in flag.
- Local real-backend smoke refuses `WHATSAPP_DRY_RUN=false`.
- Local real-backend smoke creates one assignable and one non-assignable task through local backend HTTP, waits for snapshot visibility, and cleans Todoist tasks through REST.
- Local development docs explain both lanes and the live Todoist cleanup warning.
