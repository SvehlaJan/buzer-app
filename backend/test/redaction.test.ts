import { describe, it, expect } from "vitest";
import * as fs from "node:fs";
import * as path from "node:path";
import {
  redactString,
  redactAny,
  redactObject,
  isCredentialKey
} from "../src/diagnostics/redaction.js";

function findCorpusFile(): string {
  const candidates = [
    path.resolve(process.cwd(), "../test-fixtures/observability/redaction-corpus.json"),
    path.resolve(process.cwd(), "../../test-fixtures/observability/redaction-corpus.json"),
    path.resolve(process.cwd(), "test-fixtures/observability/redaction-corpus.json"),
    path.resolve(__dirname, "../../test-fixtures/observability/redaction-corpus.json")
  ];
  for (const candidate of candidates) {
    if (fs.existsSync(candidate)) {
      return candidate;
    }
  }
  let currentDir = process.cwd();
  while (currentDir !== path.dirname(currentDir)) {
    const probe = path.join(currentDir, "test-fixtures/observability/redaction-corpus.json");
    if (fs.existsSync(probe)) return probe;
    currentDir = path.dirname(currentDir);
  }
  throw new Error("Could not find test-fixtures/observability/redaction-corpus.json");
}

describe("Redaction Parity Engine", () => {
  const corpusPath = findCorpusFile();
  const corpus = JSON.parse(fs.readFileSync(corpusPath, "utf-8"));
  const sentinels: string[] = corpus.sentinels;

  it("passes 100% of stringCases from redaction corpus and purges all sentinels", () => {
    expect(corpus.stringCases.length).toBeGreaterThan(0);
    for (const stringCase of corpus.stringCases) {
      const actual = redactString(stringCase.input);
      expect(actual).toBe(stringCase.expected);

      for (const sentinel of sentinels) {
        expect(actual).not.toContain(sentinel);
      }
    }
  });

  it("passes 100% of jsonCases from redaction corpus and purges all sentinels", () => {
    expect(corpus.jsonCases.length).toBeGreaterThan(0);
    for (const jsonCase of corpus.jsonCases) {
      const actual = redactAny(jsonCase.input);
      expect(actual).toEqual(jsonCase.expected);

      const serialized = JSON.stringify(actual);
      for (const sentinel of sentinels) {
        expect(serialized).not.toContain(sentinel);
      }
    }
  });

  it("correctly identifies credential keys", () => {
    expect(isCredentialKey("authorization")).toBe(true);
    expect(isCredentialKey("Authorization")).toBe(true);
    expect(isCredentialKey("cookie")).toBe(true);
    expect(isCredentialKey("Set-Cookie")).toBe(true);
    expect(isCredentialKey("set_cookie")).toBe(true);
    expect(isCredentialKey("token")).toBe(true);
    expect(isCredentialKey("api_token")).toBe(true);
    expect(isCredentialKey("access_token")).toBe(true);
    expect(isCredentialKey("refresh_token")).toBe(true);
    expect(isCredentialKey("password")).toBe(true);
    expect(isCredentialKey("secret")).toBe(true);
    expect(isCredentialKey("private_key")).toBe(true);
    expect(isCredentialKey("todoist_token")).toBe(true);
    expect(isCredentialKey("sentry_auth_token")).toBe(true);
    expect(isCredentialKey("whatsapp_session")).toBe(true);
    expect(isCredentialKey("qr_code")).toBe(true);
    expect(isCredentialKey("session_token")).toBe(true);
    expect(isCredentialKey("auth_token")).toBe(true);

    // Non-credential keys must not be redacted
    expect(isCredentialKey("reportSessionId")).toBe(false);
    expect(isCredentialKey("occurrenceKey")).toBe(false);
    expect(isCredentialKey("deviceId")).toBe(false);
    expect(isCredentialKey("taskId")).toBe(false);
    expect(isCredentialKey("actionId")).toBe(false);
    expect(isCredentialKey("requestId")).toBe(false);
    expect(isCredentialKey("traceId")).toBe(false);
    expect(isCredentialKey("safeKey")).toBe(false);
  });

  it("redacts Error objects and nested data structures", () => {
    const err = new Error("Failed request with Authorization: Bearer SECRET_ANDROID_123");
    expect(redactAny(err)).toBe("Failed request with Authorization: Bearer [REDACTED]");

    expect(redactAny(null)).toBe(null);
    expect(redactAny(undefined)).toBe(undefined);
    expect(redactAny(12345)).toBe(12345);
    expect(redactAny(true)).toBe(true);

    const obj = {
      nested: {
        token: "SECRET_ANDROID_123",
        count: 5
      },
      list: ["Authorization: Basic SECRET_BASIC_AUTH_ABC"]
    };
    expect(redactObject(obj)).toEqual({
      nested: {
        token: "[REDACTED]",
        count: 5
      },
      list: ["Authorization: Basic [REDACTED]"]
    });
  });

  it("handles Date instances in redactAny and redactObject", () => {
    const date = new Date("2026-08-21T20:00:00.000Z");
    expect(redactAny(date)).toBe("2026-08-21T20:00:00.000Z");

    const objWithDate = {
      timestamp: date,
      nested: {
        updatedAt: new Date("2026-08-21T21:00:00.000Z")
      }
    };
    expect(redactObject(objWithDate)).toEqual({
      timestamp: "2026-08-21T20:00:00.000Z",
      nested: {
        updatedAt: "2026-08-21T21:00:00.000Z"
      }
    });

    const invalidDate = new Date("invalid");
    expect(redactAny(invalidDate)).toBe("Invalid Date");
  });
});
