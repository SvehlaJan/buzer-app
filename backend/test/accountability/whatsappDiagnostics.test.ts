import { EventEmitter } from "node:events";
import qrcode from "qrcode-terminal";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { WhatsAppWebSender } from "../../src/accountability/whatsappWebSender.js";
import { RecordingBackendObservability } from "../fakes/recordingBackendObservability.js";

describe("WhatsAppWebSender Diagnostics", () => {
  beforeEach(() => {
    vi.spyOn(qrcode, "generate").mockImplementation(() => {});
  });

  it("updates status upon qr event", async () => {
    const mockObs = new RecordingBackendObservability();
    const mockClient = new EventEmitter() as any;

    const sender = new WhatsAppWebSender({
      sessionPath: "/tmp/nonexistent-whatsapp-session",
      clientId: "test-client",
      observability: mockObs,
      client: mockClient
    });

    const rawQr = "2@SECRET_RAW_QR_TOKEN_STRING_12345,hash,key";
    mockClient.emit("qr", rawQr);

    const health = await sender.getHealth();
    expect(health.connected).toBe(false);
    expect(health.detail).toBe("qr required");
    expect(mockObs.backgroundFailures).toHaveLength(0);
  });

  it("tracks state transitions upon authenticated and ready events", async () => {
    const mockObs = new RecordingBackendObservability();
    const mockClient = new EventEmitter() as any;

    const sender = new WhatsAppWebSender({
      sessionPath: "/tmp/nonexistent-whatsapp-session",
      clientId: "test-client",
      observability: mockObs,
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
    expect(mockObs.backgroundFailures).toHaveLength(0);
  });

  it("captures handled non-fatal buzer.backend.whatsapp_auth_failure on auth_failure event", async () => {
    const mockObs = new RecordingBackendObservability();
    const mockClient = new EventEmitter() as any;

    const sender = new WhatsAppWebSender({
      sessionPath: "/tmp/nonexistent-whatsapp-session",
      clientId: "test-client",
      observability: mockObs,
      client: mockClient
    });

    mockClient.emit("auth_failure", "Session token expired");

    const health = await sender.getHealth();
    expect(health.connected).toBe(false);
    expect(health.detail).toBe("auth failure: Session token expired");

    expect(mockObs.backgroundFailures).toHaveLength(1);
    expect(mockObs.backgroundFailures[0]?.marker).toBe("buzer.backend.whatsapp_auth_failure");
    expect((mockObs.backgroundFailures[0]?.error as Error).message).toBe("Session token expired");
  });

  it("captures handled non-fatal buzer.backend.whatsapp_disconnected on disconnected event", async () => {
    const mockObs = new RecordingBackendObservability();
    const mockClient = new EventEmitter() as any;

    const sender = new WhatsAppWebSender({
      sessionPath: "/tmp/nonexistent-whatsapp-session",
      clientId: "test-client",
      observability: mockObs,
      client: mockClient
    });

    mockClient.emit("disconnected", "NAVIGATION_TIMEOUT");

    const health = await sender.getHealth();
    expect(health.connected).toBe(false);
    expect(health.detail).toBe("disconnected: NAVIGATION_TIMEOUT");

    expect(mockObs.backgroundFailures).toHaveLength(1);
    expect(mockObs.backgroundFailures[0]?.marker).toBe("buzer.backend.whatsapp_disconnected");
    expect((mockObs.backgroundFailures[0]?.error as Error).message).toBe("NAVIGATION_TIMEOUT");
  });
});
