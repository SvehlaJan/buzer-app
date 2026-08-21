export interface SentryTag {
  key: string;
  value: string;
}

export interface SentryAttachment {
  id: string;
  name: string;
  type?: string | undefined;
  size?: number | undefined;
  dateCreated?: string | undefined;
}

export interface SentryStackFrame {
  filename?: string | undefined;
  function?: string | undefined;
  module?: string | undefined;
  lineno?: number | undefined;
  colno?: number | undefined;
  in_app?: boolean | undefined;
  context_line?: string | undefined;
  pre_context?: string[] | undefined;
  post_context?: string[] | undefined;
}

export interface SentryEntry {
  type: string;
  data: Record<string, unknown>;
}

export interface SentryEvent {
  id: string;
  eventID?: string | undefined;
  title?: string | undefined;
  message?: string | undefined;
  platform?: string | undefined;
  dateCreated?: string | undefined;
  tags?: SentryTag[] | undefined;
  entries?: SentryEntry[] | undefined;
  attachments?: SentryAttachment[] | undefined;
  contexts?: Record<string, unknown> | undefined;
  extra?: Record<string, unknown> | undefined;
  user?: Record<string, unknown> | undefined;
}

export interface SentryIssueProject {
  id: string;
  name?: string | undefined;
  slug?: string | undefined;
}

export interface SentryIssue {
  id: string;
  title: string;
  culprit?: string | undefined;
  permalink?: string | undefined;
  shortId?: string | undefined;
  level?: string | undefined;
  status?: string | undefined;
  substatus?: string | undefined;
  count?: string | number | undefined;
  userCount?: number | undefined;
  firstSeen?: string | undefined;
  lastSeen?: string | undefined;
  platform?: string | undefined;
  project?: SentryIssueProject | undefined;
  metadata?: Record<string, unknown> | undefined;
  tags?: SentryTag[] | undefined;
  latestEvent?: SentryEvent | undefined;
}

export interface GitHubLabel {
  id?: number | undefined;
  name: string;
  description?: string | undefined;
  color?: string | undefined;
}

export interface GitHubIssue {
  number: number;
  title: string;
  body?: string | undefined;
  state: 'open' | 'closed';
  html_url?: string | undefined;
  labels?: Array<string | GitHubLabel> | undefined;
  pull_request?: unknown | undefined;
  created_at?: string | undefined;
  updated_at?: string | undefined;
  closed_at?: string | null | undefined;
}

export interface GitHubComment {
  id?: number | undefined;
  body: string;
  html_url?: string | undefined;
  created_at?: string | undefined;
}

export type TriageCategory = 'fatal' | 'handled_non_fatal' | 'manual_report' | 'validation';

export type TriageDecisionAction = 'create_issue' | 'comment' | 'reopen_and_comment' | 'ignore';

export interface PairedManualReportSession {
  reportSessionId: string;
  androidEvent?: SentryEvent | undefined;
  backendEvent?: SentryEvent | undefined;
  androidIssue?: SentryIssue | undefined;
  backendIssue?: SentryIssue | undefined;
}

export interface TriageDecision {
  action: TriageDecisionAction;
  category: TriageCategory;
  reason: string;
  fingerprint: string;
  sentryIssueId: string;
  issueTitle: string;
  issueBody?: string | undefined;
  commentBody?: string | undefined;
  targetGitHubIssueNumber?: number | undefined;
  labels?: string[] | undefined;
  sentryIssues: SentryIssue[];
  pairedSession?: PairedManualReportSession | undefined;
}

export interface SyncOptions {
  dryRun?: boolean | undefined;
  sentryToken?: string | undefined;
  sentryOrg?: string | undefined;
  sentryProjects?: string[] | undefined;
  githubToken?: string | undefined;
  githubRepo?: string | undefined;
  lookbackHours?: number | undefined;
}
