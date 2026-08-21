import { EventEmitter } from "node:events";
import { describe, expect, it, vi } from "vitest";
import { WhatsAppWebSender } from "../../src/accountability/whatsappWebSender.js";
import { RingBuffer } from "../../src/diagnostics/ringBuffer.js";
import type { BackendObservability } from "../../src/diagnostics/backendObservability.js";

function createMockObservability(): BackendObservability & {
  handledCaptures: Array<{ marker: string; error: unknown; correlation?: unknown }>;
} {
  const handledCaptures: Array<{ marker: string; error: unknown; correlation?: unknown }> = [];
  return {
    handledCaptures,
    isEnabled: () => true,
    setContext: vi.fn(),
    captureHandled: vi.fn((marker: string, error: unknown, correlation?: unknown) => {
      handledCaptures.push({ marker, error, correlation });
      return "mock-event-id-003";
    }),
    captureFatal: vi.fn(),
    newEventId: () => "mock-event-id",
    captureDiagnosticEvent: vi.fn(),
    flush: vi.fn(async () => true)
  };
}

describe("WhatsAppWebSender Diagnostics", () => {
  it("logs sanitized message without raw QR code upon qr event", async () => {
    const logBuffer = new RingBuffer<string>(50);
    const mockObs = createMockObservability();
    const mockClient = new EventEmitter() as any;

    const sender = new WhatsAppWebSender({
      sessionPath: "/tmp/nonexistent-whatsapp-session",
      clientId: "test-client",
      observability: mockObs,
      logRingBuffer: logBuffer,
      client: mockClient
    });

    const rawQr = "2@SECRET_RAW_QR_TOKEN_STRING_12345,hash,key";
    mockClient.emit("qr", rawQr);

    const logSnapshot = logBuffer.snapshot();
    expect(logSnapshot).toHaveLength(1);
    expect(logSnapshot[0]).toContain("INFO WhatsApp authentication QR required");
    expect(logSnapshot[0]).not.toContain("SECRET_RAW_QR_TOKEN_STRING");

    const health = await sender.getHealth();
    expect(health.connected).toBe(false);
    expect(health.detail).toBe("qr required");
    expect(mockObs.handledCaptures).toHaveLength(0);
  });

  it("logs state transitions upon authenticated and ready events", async () => {
    const logBuffer = new RingBuffer<string>(50);
    const mockObs = createMockObservability();
    const mockClient = new EventEmitter() as any;

    const sender = new WhatsAppWebSender({
      sessionPath: "/tmp/nonexistent-whatsapp-session",
      clientId: "test-client",
      observability: mockObs,
      logRingBuffer: logBuffer,
      client: mockClient
    });

    mockClient.emit("authenticated");
    let health = await sender.getHealth();
    expect(health.connected).toBe(false);
    expect(health.detail).toBe("authenticated");

    mockClient.emit("ready");
    health = await sender.getHealth();
    expect(health.connected).toBe(true);
    expect(health.detail).toBe("ready");

    const logSnapshot = logBuffer.snapshot();
    expect(logSnapshot).toHaveLength(2);
    expect(logSnapshot[0]).toContain("INFO WhatsApp ready");
    expect(logSnapshot[1]).toContain("INFO WhatsApp authenticated");
    expect(mockObs.handledCaptures).toHaveLength(0);
  });

  it("captures handled non-fatal buzer.backend.whatsapp_auth_failure on auth_failure event", async () => {
    const logBuffer = new RingBuffer<string>(50);
    const mockObs = createMockObservability();
    const mockClient = new EventEmitter() as any;

    const sender = new WhatsAppWebSender({
      sessionPath: "/tmp/nonexistent-whatsapp-session",
      clientId: "test-client",
      observability: mockObs,
      logRingBuffer: logBuffer,
      client: mockClient
    });

    mockClient.emit("auth_failure", "Session token expired");

    const logSnapshot = logBuffer.snapshot();
    expect(logSnapshot).toHaveLength(1);
    expect(logSnapshot[0]).toContain("ERROR WhatsApp auth failure: Session token expired");

    const health = await sender.getHealth();
    expect(health.connected).toBe(false);
    expect(health.detail).toBe("auth failure: Session token expired");

    expect(mockObs.handledCaptures).toHaveLength(1);
    expect(mockObs.handledCaptures[0]?.marker).toBe("buzer.backend.whatsapp_auth_failure");
    expect((mockObs.handledCaptures[0]?.error as Error).message).toBe("Session token expired");
  });

  it("captures handled non-fatal buzer.backend.whatsapp_disconnected on disconnected event", async () => {
    const logBuffer = new RingBuffer<string>(50);
    const mockObs = createMockObservability();
    const mockClient = new EventEmitter() as any;

    const sender = new WhatsAppWebSender({
      sessionPath: "/tmp/nonexistent-whatsapp-session",
      clientId: "test-client",
      observability: mockObs,
      logRingBuffer: logBuffer,
      client: mockClient
    });

    mockClient.emit("disconnected", "NAVIGATION_TIMEOUT");

    const logSnapshot = logBuffer.snapshot();
    expect(logSnapshot).toHaveLength(1);
    expect(logSnapshot[0]).toContain("WARN WhatsApp disconnected: NAVIGATION_TIMEOUT");

    const health = await sender.getHealth();
    expect(health.connected).toBe(false);
    expect(health.detail).toBe("disconnected: NAVIGATION_TIMEOUT");

    expect(mockObs.handledCaptures).toHaveLength(1);
    expect(mockObs.handledCaptures[0]?.marker).toBe("buzer.backend.whatsapp_disconnected");
    expect((mockObs.handledCaptures[0]?.error as Error).message).toBe("NAVIGATION_TIMEOUT");
  });
});
