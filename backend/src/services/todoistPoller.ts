export interface SyncOnceService {
  syncOnce(): Promise<void>;
}

export interface TodoistPollerOptions {
  intervalMs?: number;
  runImmediately?: boolean;
  onError?: (error: unknown) => void;
}

export class TodoistPoller {
  private timer: NodeJS.Timeout | null = null;
  private running = false;

  constructor(
    private readonly service: SyncOnceService,
    private readonly options: TodoistPollerOptions = {}
  ) {}

  start(): void {
    if (this.timer !== null) {
      return;
    }

    if (this.options.runImmediately === true) {
      void this.tick();
    }
    this.timer = setInterval(() => {
      void this.tick();
    }, this.options.intervalMs ?? 5 * 60_000);
  }

  stop(): void {
    if (this.timer === null) {
      return;
    }

    clearInterval(this.timer);
    this.timer = null;
  }

  private async tick(): Promise<void> {
    if (this.running) {
      return;
    }

    this.running = true;
    try {
      await this.service.syncOnce();
    } catch (error) {
      this.options.onError?.(error);
    } finally {
      this.running = false;
    }
  }
}
