create table if not exists schema_migrations (
  version integer primary key,
  applied_at text not null
);

create table if not exists todoist_sync_state (
  id integer primary key check (id = 1),
  sync_token text not null,
  mirror_contract_version integer not null default 1,
  updated_at text not null
);

create table if not exists todoist_tasks (
  id text primary key,
  todoist_task_id text not null unique,
  title text not null,
  description text,
  project_id text,
  parent_id text,
  child_order integer,
  assignee_id text,
  creator_id text,
  due_date text,
  due_datetime text,
  due_string text,
  due_lang text,
  due_timezone text,
  is_recurring integer not null default 0,
  duration_amount integer,
  duration_unit text,
  duration_minutes integer,
  completed_at text,
  deleted_at text,
  raw_json text not null,
  mirror_revision integer not null default 1,
  updated_at text not null
);

create table if not exists commitment_states (
  task_id text primary key references todoist_tasks(id) on delete cascade,
  state text not null,
  started_at text,
  commitment_owner_id text,
  start_dismiss_count integer not null default 0,
  deadline_extension_count integer not null default 0,
  last_snoozed_at text,
  escalation_sent_at text,
  schedule_version integer not null default 1,
  occurrence_key text,
  occurrence_due_datetime text,
  occurrence_recurrence_fingerprint text,
  reconciliation_status text not null default 'confirmed'
    check (reconciliation_status in ('confirmed', 'pending')),
  updated_at text not null
);

create table if not exists todoist_projects (
  todoist_project_id text primary key,
  name text not null,
  shared integer not null,
  can_assign_tasks integer not null,
  is_inbox_project integer not null,
  is_deleted integer not null,
  is_archived integer not null,
  raw_json text not null,
  updated_at text not null
);

create table if not exists devices (
  id text primary key,
  app_version text not null,
  permission_json text not null,
  last_check_in_at text not null
);

create table if not exists whatsapp_escalations (
  id text primary key,
  task_id text not null references todoist_tasks(id) on delete cascade,
  occurrence_key text,
  recipient_id text not null,
  message text not null,
  status text not null,
  error_message text,
  created_at text not null,
  sent_at text
);

create table if not exists task_action_journal (
  action_id text primary key,
  device_id text not null,
  root_task_id text,
  expected_occurrence_key text,
  action_type text not null,
  request_fingerprint text not null,
  status text not null check (status in ('processing', 'completed', 'failed', 'reconciliation_pending')),
  response_json text,
  error_json text,
  created_at text not null,
  updated_at text not null
);

create table if not exists task_action_commands (
  action_id text not null references task_action_journal(action_id) on delete cascade,
  step_key text not null,
  command_uuid text not null unique,
  temp_id text,
  status text not null check (status in ('reserved', 'sent', 'confirmed', 'failed')),
  result_json text,
  updated_at text not null,
  primary key (action_id, step_key)
);
