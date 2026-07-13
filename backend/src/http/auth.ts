import { timingSafeEqual } from "node:crypto";
import type { FastifyRequest } from "fastify";

export function isBearerTokenValid(request: FastifyRequest, expectedToken: string): boolean {
  const header = request.headers.authorization;
  if (header === undefined) {
    return false;
  }
  const expected = Buffer.from(`Bearer ${expectedToken}`);
  const received = Buffer.from(header);
  if (received.length !== expected.length) {
    return false;
  }
  return timingSafeEqual(received, expected);
}

export function requireBearerToken(request: FastifyRequest, expectedToken: string): void {
  if (!isBearerTokenValid(request, expectedToken)) {
    throw Object.assign(new Error("unauthorized"), {
      statusCode: 401,
      code: "unauthorized"
    });
  }
}
