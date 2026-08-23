import { EventEmitter } from "node:events";
import { mkdtempSync, readFileSync, rmSync, statSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
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

  it("writes a permission-restricted local QR artifact without terminal QR output", () => {
    const root = mkdtempSync(join(tmpdir(), "buzer-whatsapp-session-"));
    const mockClient = new EventEmitter() as any;
    const consoleInfo = vi.spyOn(console, "info").mockImplementation(() => {});
    const consoleLog = vi.spyOn(console, "log").mockImplementation(() => {});
    const consoleError = vi.spyOn(console, "error").mockImplementation(() => {});
    vi.mocked(qrcode.generate).mockImplementation((_input, _options, callback) => callback?.("QR GRAPHIC"));

    try {
      const sender = new WhatsAppWebSender({
        sessionPath: root,
        clientId: "test-client",
        client: mockClient
      });

      mockClient.emit("qr", "2@SECRET_RAW_QR_TOKEN_STRING_12345,hash,key");

      const artifactPath = join(root, "whatsapp-qr.txt");
      expect(readFileSync(artifactPath, "utf8")).toBe("QR GRAPHIC");
      expect(statSync(artifactPath).mode & 0o777).toBe(0o600);
      expect(consoleInfo).toHaveBeenCalledWith(expect.stringContaining(artifactPath));
      expect(consoleLog).not.toHaveBeenCalled();
      expect(consoleError).not.toHaveBeenCalled();

      mockClient.emit("authenticated");
      expect(() => statSync(artifactPath)).toThrow();
    } finally {
      consoleInfo.mockRestore();
      consoleLog.mockRestore();
      consoleError.mockRestore();
      rmSync(root, { recursive: true, force: true });
    }
  });

  it("refuses QR artifact symlinks and repairs pre-existing file permissions before writing", () => {
    const root = mkdtempSync(join(tmpdir(), "buzer-whatsapp-session-"));
    const target = join(root, "outside.txt");
    const artifactPath = join(root, "whatsapp-qr.txt");
    const mockClient = new EventEmitter() as any;
    vi.mocked(qrcode.generate).mockImplementation((_input, _options, callback) => callback?.("QR GRAPHIC"));

    try {
      writeFileSync(target, "outside");
      new WhatsAppWebSender({ sessionPath: root, clientId: "test-client", client: mockClient });
      symlinkSync(target, artifactPath);
      expect(() => mockClient.emit("qr", "raw-qr")).toThrow();
      expect(readFileSync(target, "utf8")).toBe("outside");

      rmSync(artifactPath);
      writeFileSync(artifactPath, "old", { mode: 0o644 });
      mockClient.emit("qr", "raw-qr");
      expect(statSync(artifactPath).mode & 0o777).toBe(0o600);
      expect(readFileSync(artifactPath, "utf8")).toBe("QR GRAPHIC");
    } finally {
      rmSync(root, { recursive: true, force: true });
    }
  });

  it("clears stale QR artifacts on construction and when destroy rejects", async () => {
    const root = mkdtempSync(join(tmpdir(), "buzer-whatsapp-session-"));
    const artifactPath = join(root, "whatsapp-qr.txt");
    const mockClient = Object.assign(new EventEmitter(), { destroy: vi.fn().mockRejectedValue(new Error("destroy failed")) }) as any;

    try {
      writeFileSync(artifactPath, "stale");
      const sender = new WhatsAppWebSender({ sessionPath: root, clientId: "test-client", client: mockClient });
      expect(() => statSync(artifactPath)).toThrow();

      writeFileSync(artifactPath, "current");
      await expect(sender.destroy()).rejects.toThrow("destroy failed");
      expect(() => statSync(artifactPath)).toThrow();
    } finally {
      rmSync(root, { recursive: true, force: true });
    }
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
    expect(health.detail).toBe("auth failure");

    expect(mockObs.backgroundFailures).toHaveLength(1);
    expect(mockObs.backgroundFailures[0]?.marker).toBe("buzer.backend.whatsapp_auth_failure");
    expect((mockObs.backgroundFailures[0]?.error as Error).message).toBe("WhatsApp authentication failed");
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
    expect(health.detail).toBe("disconnected");

    expect(mockObs.backgroundFailures).toHaveLength(1);
    expect(mockObs.backgroundFailures[0]?.marker).toBe("buzer.backend.whatsapp_disconnected");
    expect((mockObs.backgroundFailures[0]?.error as Error).message).toBe("WhatsApp disconnected");
  });
});
