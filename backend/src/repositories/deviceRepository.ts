import Database from "better-sqlite3";

export type DevicePermissions = Record<string, boolean | string | number | null>;

export interface DeviceCheckIn {
  id: string;
  appVersion: string;
  permissions: DevicePermissions;
  checkedInAt: string;
}

export interface DeviceRecord {
  id: string;
  appVersion: string;
  permissions: DevicePermissions;
  lastCheckInAt: string;
}

interface DeviceRow {
  id: string;
  appVersion: string;
  permissionJson: string;
  lastCheckInAt: string;
}

export class DeviceRepository {
  constructor(private readonly db: Database.Database) {}

  upsertCheckIn(checkIn: DeviceCheckIn): void {
    this.db.prepare(`
      insert into devices (id, app_version, permission_json, last_check_in_at)
      values (@id, @appVersion, @permissionJson, @checkedInAt)
      on conflict(id) do update set
        app_version = excluded.app_version,
        permission_json = excluded.permission_json,
        last_check_in_at = excluded.last_check_in_at
    `).run({
      ...checkIn,
      permissionJson: JSON.stringify(checkIn.permissions)
    });
  }

  getById(id: string): DeviceRecord | null {
    const row = this.db.prepare(`
      select
        id,
        app_version as appVersion,
        permission_json as permissionJson,
        last_check_in_at as lastCheckInAt
      from devices
      where id = ?
    `).get(id) as DeviceRow | undefined;

    if (row === undefined) {
      return null;
    }

    return {
      id: row.id,
      appVersion: row.appVersion,
      permissions: JSON.parse(row.permissionJson) as DevicePermissions,
      lastCheckInAt: row.lastCheckInAt
    };
  }
}
