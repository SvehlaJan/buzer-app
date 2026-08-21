import "dotenv/config";

export interface AppConfig {
  SENTRY_BACKEND_DSN: string;
  SENTRY_ENVIRONMENT: string;
  SENTRY_RELEASE: string;
  DATABASE_PATH: string;
  TODOIST_USER_ID: string;
  ANDROID_API_TOKEN: string;
  TODOIST_API_TOKEN: string;
  WHATSAPP_RECIPIENT_ID: string;
  PORT: number;
  WHATSAPP_DRY_RUN: boolean;
  WHATSAPP_SESSION_PATH: string;
  WHATSAPP_CLIENT_ID: string;
}

export function loadConfig(): AppConfig {
  return {
    SENTRY_BACKEND_DSN: process.env.SENTRY_BACKEND_DSN ?? "",
    SENTRY_ENVIRONMENT: process.env.SENTRY_ENVIRONMENT ?? "development-vps",
    SENTRY_RELEASE: process.env.SENTRY_RELEASE ?? "buzer-backend@0.1.0+local",
    DATABASE_PATH: process.env.DATABASE_PATH ?? "data/buzer.sqlite",
    TODOIST_USER_ID: process.env.TODOIST_USER_ID ?? process.env.TODOIST_CURRENT_USER_ID ?? "",
    ANDROID_API_TOKEN: process.env.ANDROID_API_TOKEN ?? "",
    TODOIST_API_TOKEN: process.env.TODOIST_API_TOKEN ?? "",
    WHATSAPP_RECIPIENT_ID: process.env.WHATSAPP_RECIPIENT_ID ?? "",
    PORT: Number(process.env.PORT ?? 3000),
    WHATSAPP_DRY_RUN: process.env.WHATSAPP_DRY_RUN !== "false",
    WHATSAPP_SESSION_PATH: process.env.WHATSAPP_SESSION_PATH ?? "whatsapp-sessions",
    WHATSAPP_CLIENT_ID: process.env.WHATSAPP_CLIENT_ID ?? "buzer"
  };
}

export const CONFIG = loadConfig();

export const SENTRY_BACKEND_DSN = CONFIG.SENTRY_BACKEND_DSN;
export const SENTRY_ENVIRONMENT = CONFIG.SENTRY_ENVIRONMENT;
export const SENTRY_RELEASE = CONFIG.SENTRY_RELEASE;
export const DATABASE_PATH = CONFIG.DATABASE_PATH;
export const TODOIST_USER_ID = CONFIG.TODOIST_USER_ID;
export const ANDROID_API_TOKEN = CONFIG.ANDROID_API_TOKEN;
export const TODOIST_API_TOKEN = CONFIG.TODOIST_API_TOKEN;
export const WHATSAPP_RECIPIENT_ID = CONFIG.WHATSAPP_RECIPIENT_ID;
export const PORT = CONFIG.PORT;
export const WHATSAPP_DRY_RUN = CONFIG.WHATSAPP_DRY_RUN;
export const WHATSAPP_SESSION_PATH = CONFIG.WHATSAPP_SESSION_PATH;
export const WHATSAPP_CLIENT_ID = CONFIG.WHATSAPP_CLIENT_ID;
