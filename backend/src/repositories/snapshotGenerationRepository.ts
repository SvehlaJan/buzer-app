import Database from "better-sqlite3";

/** Durable total order for every committed task-snapshot projection. */
export class SnapshotGenerationRepository {
  constructor(private readonly db: Database.Database) {}

  current(): number {
    const row = this.db.prepare("select generation from snapshot_generations where id = 1").get() as { generation: number } | undefined;
    if (row === undefined) throw new Error("snapshot generation is not initialized");
    return row.generation;
  }
}
