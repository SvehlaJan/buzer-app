import type { FastifyRequest } from "fastify";
import { describe, expect, it } from "vitest";
import { isBearerTokenValid, requireBearerToken } from "../../src/http/auth.js";

function fakeRequest(authorization?: string): FastifyRequest {
  return { headers: authorization === undefined ? {} : { authorization } } as FastifyRequest;
}

describe("isBearerTokenValid", () => {
  it("accepts the exact bearer token", () => {
    expect(isBearerTokenValid(fakeRequest("Bearer secret"), "secret")).toBe(true);
  });

  it("rejects a wrong token of the same length", () => {
    expect(isBearerTokenValid(fakeRequest("Bearer secreX"), "secret")).toBe(false);
  });

  it("rejects a token of a different length without throwing", () => {
    expect(isBearerTokenValid(fakeRequest("Bearer s"), "secret")).toBe(false);
    expect(isBearerTokenValid(fakeRequest("Bearer secret-and-more"), "secret")).toBe(false);
  });

  it("rejects a missing authorization header", () => {
    expect(isBearerTokenValid(fakeRequest(), "secret")).toBe(false);
  });

  it("rejects a non-bearer scheme", () => {
    expect(isBearerTokenValid(fakeRequest("Basic secret"), "secret")).toBe(false);
  });
});

describe("requireBearerToken", () => {
  it("throws a 401-coded error for an invalid token", () => {
    expect(() => requireBearerToken(fakeRequest("Bearer wrong!"), "secret")).toThrowError(
      expect.objectContaining({ statusCode: 401, code: "unauthorized" })
    );
  });

  it("does not throw for a valid token", () => {
    expect(() => requireBearerToken(fakeRequest("Bearer secret"), "secret")).not.toThrow();
  });
});
