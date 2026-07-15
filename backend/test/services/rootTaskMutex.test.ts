import { describe, expect, it, vi } from "vitest";
import { RootTaskMutex } from "../../src/services/rootTaskMutex.js";

describe("RootTaskMutex", () => {
  it("runs operations for one root in submission order", async () => {
    const mutex = new RootTaskMutex();
    const order: string[] = [];
    let releaseFirst: (() => void) | undefined;
    const firstGate = new Promise<void>((resolve) => { releaseFirst = resolve; });

    const first = mutex.run("root-1", async () => {
      order.push("first:start");
      await firstGate;
      order.push("first:end");
    });
    const second = mutex.run("root-1", async () => {
      order.push("second");
    });

    await vi.waitFor(() => expect(order).toEqual(["first:start"]));
    releaseFirst?.();
    await Promise.all([first, second]);

    expect(order).toEqual(["first:start", "first:end", "second"]);
  });

  it("permits operations for different roots to overlap", async () => {
    const mutex = new RootTaskMutex();
    let releaseFirst: (() => void) | undefined;
    const firstGate = new Promise<void>((resolve) => { releaseFirst = resolve; });
    const secondStarted = vi.fn();

    const first = mutex.run("root-1", () => firstGate);
    const second = mutex.run("root-2", async () => { secondStarted(); });

    await vi.waitFor(() => expect(secondStarted).toHaveBeenCalledOnce());
    releaseFirst?.();
    await Promise.all([first, second]);
  });

  it("releases a root queue after a rejected operation", async () => {
    const mutex = new RootTaskMutex();
    const later = vi.fn();

    const failed = mutex.run("root-1", async () => { throw new Error("expected"); });
    const next = mutex.run("root-1", async () => { later(); });

    await expect(failed).rejects.toThrow("expected");
    await expect(next).resolves.toBeUndefined();
    expect(later).toHaveBeenCalledOnce();
  });

  it("removes completed roots from its lock map", async () => {
    const mutex = new RootTaskMutex();

    await mutex.run("root-1", async () => undefined);

    expect(mutex.activeRootCount).toBe(0);
  });
});
