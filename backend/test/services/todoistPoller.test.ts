import { afterEach, describe, expect, it, vi } from "vitest";
import { TodoistPoller } from "../../src/services/todoistPoller.js";

describe("TodoistPoller", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it("runs sync on the configured interval without overlapping calls", async () => {
    vi.useFakeTimers();
    let releaseFirstSync: (() => void) | undefined;
    const syncOnce = vi.fn()
      .mockImplementationOnce(() => new Promise<void>((resolve) => {
        releaseFirstSync = resolve;
      }))
      .mockResolvedValue(undefined);
    const poller = new TodoistPoller({ syncOnce }, { intervalMs: 300_000 });

    poller.start();
    await vi.advanceTimersByTimeAsync(300_000);
    await vi.advanceTimersByTimeAsync(300_000);
    expect(syncOnce).toHaveBeenCalledTimes(1);

    releaseFirstSync?.();
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(300_000);

    expect(syncOnce).toHaveBeenCalledTimes(2);
    poller.stop();
  });
});
