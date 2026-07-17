export class RootTaskMutex {
  private readonly tails = new Map<string, Promise<void>>();

  get activeRootCount(): number {
    return this.tails.size;
  }

  /**
   * Rebinds a currently held provisional root lock to its canonical root.
   * Existing canonical work is allowed to finish first; new canonical work
   * then queues behind the provisional operation's tail.
   */
  async handoff(provisionalRootTaskId: string, canonicalRootTaskId: string): Promise<void> {
    if (provisionalRootTaskId === canonicalRootTaskId) {
      return;
    }

    const provisionalTail = this.tails.get(provisionalRootTaskId);
    if (provisionalTail === undefined) {
      throw new Error(`Cannot hand off inactive root lock: ${provisionalRootTaskId}`);
    }

    while (true) {
      const canonicalTail = this.tails.get(canonicalRootTaskId);
      if (canonicalTail === undefined || canonicalTail === provisionalTail) {
        this.tails.set(canonicalRootTaskId, provisionalTail);
        return;
      }

      await canonicalTail.catch(() => undefined);
    }
  }

  async run<T>(rootTaskId: string, operation: () => Promise<T>): Promise<T> {
    const previous = this.tails.get(rootTaskId) ?? Promise.resolve();
    let release: () => void = () => undefined;
    const tail = new Promise<void>((resolve) => { release = resolve; });
    this.tails.set(rootTaskId, tail);

    await previous.catch(() => undefined);
    try {
      return await operation();
    } finally {
      release();
      for (const [rootTaskId, activeTail] of this.tails) {
        if (activeTail === tail) {
          this.tails.delete(rootTaskId);
        }
      }
    }
  }
}
