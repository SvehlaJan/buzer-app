import type {
  TodoistAdapter,
  TodoistCommand,
  TodoistItemAddInput,
  TodoistItemAddResult
} from "../todoist/todoistAdapter.js";
import { TodoistOperationError } from "../todoist/todoistErrors.js";

export const MAX_COMMAND_ATTEMPTS = 2;
export const MAX_HTTP_ATTEMPT_TIMEOUT_MS = 5000;

export type TodoistCommandOutcome = "success" | "rejected" | "delivery_unknown";

export interface TodoistCommandSuccessResult {
  outcome: "success";
  todoistTaskId?: string | null;
}

export interface TodoistCommandFailureResult {
  outcome: "rejected" | "delivery_unknown";
  error: TodoistOperationError;
}

export type TodoistCommandExecutionResult =
  | TodoistCommandSuccessResult
  | TodoistCommandFailureResult;

export interface ExecuteCommandOptions {
  command: TodoistCommand;
  commandUuid: string;
  deadlineMs: number;
  signal?: AbortSignal;
  now?: () => number;
  sleep?: (ms: number) => Promise<void>;
}

export class TodoistCommandExecutor {
  constructor(private readonly adapter: TodoistAdapter) {}

  async execute(options: ExecuteCommandOptions): Promise<TodoistCommandExecutionResult> {
    return this.executeCommand(options);
  }

  async executeCommand(options: ExecuteCommandOptions): Promise<TodoistCommandExecutionResult> {
    const {
      command,
      commandUuid,
      deadlineMs,
      signal,
      now = Date.now,
      sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))
    } = options;

    let lastError: TodoistOperationError | undefined;

    for (let attempt = 1; attempt <= MAX_COMMAND_ATTEMPTS; attempt++) {
      if (signal?.aborted) {
        const error = lastError ?? new TodoistOperationError("timeout");
        return this.mapFailureOutcome(error);
      }

      const currentTime = now();
      const remainingBudget = deadlineMs - currentTime;
      if (remainingBudget <= 0) {
        const error = lastError ?? new TodoistOperationError("timeout");
        return this.mapFailureOutcome(error);
      }

      const attemptTimeoutMs = Math.min(MAX_HTTP_ATTEMPT_TIMEOUT_MS, remainingBudget);

      const attemptController = new AbortController();
      let timer: ReturnType<typeof setTimeout> | undefined;
      const onParentAbort = () => attemptController.abort(signal?.reason);

      if (signal) {
        signal.addEventListener("abort", onParentAbort, { once: true });
      }

      timer = setTimeout(() => {
        const timeoutErr = new Error("Attempt timeout");
        timeoutErr.name = "TimeoutError";
        attemptController.abort(timeoutErr);
      }, attemptTimeoutMs);

      try {
        const result = await this.dispatch(command, commandUuid, attemptController.signal);
        if (
          command.type === "item_add" &&
          (
            result === undefined ||
            typeof result.todoistTaskId !== "string" ||
            result.todoistTaskId.trim().length === 0
          )
        ) {
          throw new TodoistOperationError("parse", undefined, {
            response_shape: "missing_temp_id_mapping"
          });
        }
        return {
          outcome: "success",
          ...(result && "todoistTaskId" in result ? { todoistTaskId: result.todoistTaskId } : {})
        };
      } catch (caught: unknown) {
        const error = caught instanceof TodoistOperationError
          ? caught
          : new TodoistOperationError(
              attemptController.signal.aborted || signal?.aborted ? "timeout" : "transport"
            );
        lastError = error;

        if (!this.isEligibleForRetry(error) || attempt >= MAX_COMMAND_ATTEMPTS) {
          return this.mapFailureOutcome(error);
        }

        const waitMs = (error.kind === "rate_limited" && error.retryAfterMs !== undefined)
          ? error.retryAfterMs
          : 0;

        const timeRemainingNow = deadlineMs - now();
        if (waitMs >= timeRemainingNow) {
          return this.mapFailureOutcome(error);
        }

        if (waitMs > 0) {
          await sleep(waitMs);
        }

        if (now() >= deadlineMs || signal?.aborted) {
          return this.mapFailureOutcome(error);
        }
      } finally {
        if (timer !== undefined) {
          clearTimeout(timer);
        }
        if (signal) {
          signal.removeEventListener("abort", onParentAbort);
        }
      }
    }

    const finalError = lastError ?? new TodoistOperationError("timeout");
    return this.mapFailureOutcome(finalError);
  }

  private isEligibleForRetry(error: TodoistOperationError): boolean {
    return (
      error.kind === "transport" ||
      error.kind === "timeout" ||
      error.kind === "rate_limited" ||
      error.kind === "server"
    );
  }

  private mapFailureOutcome(error: TodoistOperationError): TodoistCommandFailureResult {
    if (
      error.kind === "command_rejected" ||
      error.kind === "client" ||
      error.kind === "rate_limited" ||
      error.kind === "server"
    ) {
      return { outcome: "rejected", error };
    }
    return { outcome: "delivery_unknown", error };
  }

  private async dispatch(
    command: TodoistCommand,
    commandUuid: string,
    signal: AbortSignal
  ): Promise<TodoistItemAddResult | void> {
    switch (command.type) {
      case "item_add":
        return await this.adapter.itemAdd({ ...command.input, commandUuid } as TodoistItemAddInput, { signal });
      case "item_update":
        return await this.adapter.itemUpdate({ ...command.input, commandUuid }, { signal });
      case "item_move":
        return await this.adapter.itemMove({ ...command.input, commandUuid }, { signal });
      case "item_close":
        return await this.adapter.itemClose({ ...command.input, commandUuid }, { signal });
      case "item_uncomplete":
        return await this.adapter.itemUncomplete({ ...command.input, commandUuid }, { signal });
      case "item_delete":
        return await this.adapter.itemDelete({ ...command.input, commandUuid }, { signal });
      case "item_update_date_complete":
        return await this.adapter.itemUpdateDateComplete({ ...command.input, commandUuid }, { signal });
    }
  }
}
