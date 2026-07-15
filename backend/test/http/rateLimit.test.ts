import Database from "better-sqlite3";
import { describe, expect, it } from "vitest";
import { migrate } from "../../src/db/migrations.js";
import { buildServer } from "../../src/server.js";
import { FakeTodoistAdapter } from "../fakes/fakeTodoistAdapter.js";

function createApp() {
  const db = new Database(":memory:");
  migrate(db);
  return buildServer({
    db,
    currentUserId: "user-self",
    androidApiToken: "secret",
    todoist: new FakeTodoistAdapter(),
    rateLimit: { max: 3, timeWindowMs: 60_000 }
  });
}

describe("rate limiting", () => {
  it("throttles a burst from a single client IP", async () => {
    const app = createApp();

    for (let i = 0; i < 3; i += 1) {
      const ok = await app.inject({ method: "GET", url: "/health", remoteAddress: "198.51.100.10" });
      expect(ok.statusCode).toBe(200);
    }
    const blocked = await app.inject({ method: "GET", url: "/health", remoteAddress: "198.51.100.10" });
    await app.close();

    expect(blocked.statusCode).toBe(429);
  });

  it("keys by X-Forwarded-For client when the peer is a trusted proxy", async () => {
    const app = createApp();
    const proxyPeer = "172.28.0.1";

    for (let i = 0; i < 3; i += 1) {
      await app.inject({
        method: "GET",
        url: "/health",
        remoteAddress: proxyPeer,
        headers: { "x-forwarded-for": "203.0.113.1" }
      });
    }
    const exhaustedClient = await app.inject({
      method: "GET",
      url: "/health",
      remoteAddress: proxyPeer,
      headers: { "x-forwarded-for": "203.0.113.1" }
    });
    const otherClient = await app.inject({
      method: "GET",
      url: "/health",
      remoteAddress: proxyPeer,
      headers: { "x-forwarded-for": "203.0.113.2" }
    });
    await app.close();

    expect(exhaustedClient.statusCode).toBe(429);
    expect(otherClient.statusCode).toBe(200);
  });

  it("ignores X-Forwarded-For from untrusted peers", async () => {
    const app = createApp();

    for (let i = 0; i < 3; i += 1) {
      await app.inject({
        method: "GET",
        url: "/health",
        remoteAddress: "100.100.7.7",
        headers: { "x-forwarded-for": "203.0.113.1" }
      });
    }
    const samePeer = await app.inject({
      method: "GET",
      url: "/health",
      remoteAddress: "100.100.7.7",
      headers: { "x-forwarded-for": "203.0.113.9" }
    });
    const differentPeerSameHeader = await app.inject({
      method: "GET",
      url: "/health",
      remoteAddress: "100.100.7.8",
      headers: { "x-forwarded-for": "203.0.113.1" }
    });
    await app.close();

    expect(samePeer.statusCode).toBe(429);
    expect(differentPeerSameHeader.statusCode).toBe(200);
  });

  it("rate limits unknown paths", async () => {
    const app = createApp();

    for (let i = 0; i < 3; i += 1) {
      const miss = await app.inject({ method: "GET", url: "/nope", remoteAddress: "198.51.100.20" });
      expect(miss.statusCode).toBe(404);
    }
    const blocked = await app.inject({ method: "GET", url: "/nope", remoteAddress: "198.51.100.20" });
    await app.close();

    expect(blocked.statusCode).toBe(429);
  });
});
