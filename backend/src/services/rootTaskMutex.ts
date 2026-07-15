export class RootTaskMutex {
  private readonly tails = new Map<string, Promise<void>>();

  get activeRootCount(): number {
    return this.tails.size;
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
      if (this.tails.get(rootTaskId) === tail) {
        this.tails.delete(rootTaskId);
      }
    }
  }
}
