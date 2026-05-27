import type { FastifyRequest } from "fastify";

export function requireBearerToken(request: FastifyRequest, expectedToken: string): void {
  const header = request.headers.authorization;
  if (header !== `Bearer ${expectedToken}`) {
    throw Object.assign(new Error("unauthorized"), {
      statusCode: 401,
      code: "unauthorized"
    });
  }
}
