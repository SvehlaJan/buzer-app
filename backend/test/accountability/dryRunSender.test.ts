import { describe, expect, it } from "vitest";
import { DryRunSender } from "../../src/accountability/dryRunSender.js";

describe("DryRunSender", () => {
  it("records sent messages without external side effects", async () => {
    const sender = new DryRunSender();
    await sender.sendEscalation({
      taskTitle: "Pay tax",
      deadlineAt: "2026-05-08T20:00:00Z",
      dismissCount: 2,
      recipient: "wife"
    });

    expect(sender.sent).toHaveLength(1);
    expect(sender.sent[0]?.recipient).toBe("wife");
    expect(await sender.getHealth()).toEqual({ connected: true, detail: "dry-run" });
  });
});
