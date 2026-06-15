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

export function extractTodoistTaskIdFromErrorText(text) {
  try {
    const parsed = JSON.parse(text);
    const taskId = parsed?.details?.todoistTaskId;
    return typeof taskId === "string" && taskId.length > 0 ? taskId : null;
  } catch {
    return null;
  }
}

function loadEnvFile(path) {
  if (!existsSync(path)) {
    throw new Error(`Env file not found: ${path}`);
  }
  return parseEnvText(readFileSync(path, "utf8"));
}

async function delay(ms) {
  await new Promise((resolve) => setTimeout(resolve, ms));
}

async function requestJson(url, options = {}) {
  const response = await fetch(url, options);
  const text = await response.text();
  const body = text.length > 0 ? JSON.parse(text) : null;
  if (!response.ok) {
    const error = new Error(`${options.method ?? "GET"} ${url} failed with HTTP ${response.status}: ${text}`);
    error.todoistTaskId = extractTodoistTaskIdFromErrorText(text);
    throw error;
  }
  return body;
}

async function createSmokeTask(baseUrl, token, createdTasks, title, body) {
  try {
    const created = await requestJson(`${baseUrl}/tasks`, {
      method: "POST",
      headers: {
        authorization: `Bearer ${token}`,
        "content-type": "application/json"
      },
      body: JSON.stringify(body)
    });
    createdTasks.push({ ...created, title });
    return created;
  } catch (error) {
    if (typeof error.todoistTaskId === "string") {
      createdTasks.push({ todoistTaskId: error.todoistTaskId, title });
    }
    throw error;
  }
}

async function waitForBackend(baseUrl) {
  const deadline = Date.now() + 30_000;
  let lastError = "";
  while (Date.now() < deadline) {
    try {
      await requestJson(`${baseUrl}/health`);
      return;
    } catch (error) {
      lastError = error.message;
      await delay(1_000);
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
      await delay(2_000);
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
    await delay(1_000);
  }
  throw new Error(`Timed out waiting for created tasks in snapshot: ${localTaskIds.join(", ")}`);
}

async function cleanupTodoistTasks(todoistBaseUrl, token, createdTasks) {
  for (const task of [...createdTasks].reverse()) {
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

function parseArgs(argv) {
  if (argv.includes("--help")) {
    return { help: true };
  }
  const envArgIndex = argv.indexOf("--env");
  return {
    help: false,
    approved: argv.includes("--i-understand-this-uses-live-todoist"),
    envPath: envArgIndex === -1 ? "backend/.env.local-smoke" : argv[envArgIndex + 1]
  };
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) {
    console.log("Usage: scripts/local-real-backend-smoke.mjs --i-understand-this-uses-live-todoist [--env backend/.env.local-smoke]");
    return;
  }
  if (!args.approved) {
    throw new Error("Refusing to run without --i-understand-this-uses-live-todoist");
  }
  if (!args.envPath) {
    throw new Error("--env requires a path");
  }

  const envPath = resolve(rootDir, args.envPath);
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
  let backendExited = false;
  const backendExit = new Promise((resolve) => {
    backendProcess.once("exit", (code, signal) => {
      backendExited = true;
      resolve({ code, signal });
    });
  });

  try {
    await waitForBackend(baseUrl);
    const projects = await waitForProjects(baseUrl, config.ANDROID_API_TOKEN);
    const { assignable, personal } = chooseProjects(projects);
    const dueDatetime = new Date(Date.now() + 10 * 60_000).toISOString().replace(".000Z", "Z");

    const assignableTitle = buildSmokeTitle(runId, "assignable");
    const assignableCreated = await createSmokeTask(
      baseUrl,
      config.ANDROID_API_TOKEN,
      createdTasks,
      assignableTitle,
      {
        title: assignableTitle,
        projectId: assignable.id,
        assigneeId: config.SMOKE_ASSIGNEE_ID ?? config.TODOIST_USER_ID,
        dueDatetime,
        durationMinutes: 3
      }
    );

    const personalTitle = buildSmokeTitle(runId, "personal");
    const personalCreated = await createSmokeTask(
      baseUrl,
      config.ANDROID_API_TOKEN,
      createdTasks,
      personalTitle,
      {
        title: personalTitle,
        projectId: personal.id,
        dueDatetime,
        durationMinutes: 3
      }
    );

    await waitForSnapshotTasks(baseUrl, config.ANDROID_API_TOKEN, createdTasks.map((task) => task.id));
    console.log(`Local real-backend smoke passed for run ${runId}`);
  } finally {
    await cleanupTodoistTasks(todoistBaseUrl, config.TODOIST_API_TOKEN, createdTasks);
    if (!backendExited) {
      backendProcess.kill("SIGTERM");
      await backendExit;
    }
  }
}

if (import.meta.url === `file://${process.argv[1]}`) {
  main().catch((error) => {
    console.error(error.message);
    process.exit(1);
  });
}
