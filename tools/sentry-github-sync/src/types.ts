export interface SentryTag {
  key: string;
  value: string;
}

export interface SentryAttachment {
  id: string;
  name: string;
  type?: string;
  size?: number;
  dateCreated?: string;
}

export interface SentryStackFrame {
  filename?: string;
  function?: string;
  module?: string;
  lineno?: number;
  colno?: number;
  in_app?: boolean;
  context_line?: string;
  pre_context?: string[];
  post_context?: string[];
}

export interface SentryEntry {
  type: string;
  data: Record<string, unknown>;
}

export interface SentryEvent {
  id: string;
  eventID?: string;
  title?: string;
  message?: string;
  platform?: string;
  dateCreated?: string;
  tags?: SentryTag[];
  entries?: SentryEntry[];
  attachments?: SentryAttachment[];
  contexts?: Record<string, unknown>;
  extra?: Record<string, unknown>;
  user?: Record<string, unknown>;
}

export interface SentryIssueProject {
  id: string;
  name?: string;
  slug?: string;
}

export interface SentryIssue {
  id: string;
  title: string;
  culprit?: string;
  permalink?: string;
  shortId?: string;
  level?: string;
  status?: string;
  substatus?: string;
  count?: string | number;
  userCount?: number;
  firstSeen?: string;
  lastSeen?: string;
  platform?: string;
  project?: SentryIssueProject;
  metadata?: Record<string, unknown>;
  tags?: SentryTag[];
  latestEvent?: SentryEvent;
}

export interface GitHubLabel {
  id?: number;
  name: string;
  description?: string;
  color?: string;
}

export interface GitHubIssue {
  number: number;
  title: string;
  body?: string;
  state: 'open' | 'closed';
  html_url?: string;
  labels?: Array<string | GitHubLabel>;
  created_at?: string;
  updated_at?: string;
  closed_at?: string | null;
}

export interface GitHubComment {
  id?: number;
  body: string;
  html_url?: string;
  created_at?: string;
}

export type TriageCategory = 'fatal' | 'handled_non_fatal' | 'manual_report' | 'validation';

export type TriageDecisionAction = 'create_issue' | 'comment' | 'reopen_and_comment' | 'ignore';

export interface PairedManualReportSession {
  reportSessionId: string;
  androidEvent?: SentryEvent;
  backendEvent?: SentryEvent;
  androidIssue?: SentryIssue;
  backendIssue?: SentryIssue;
}

export interface TriageDecision {
  action: TriageDecisionAction;
  category: TriageCategory;
  reason: string;
  fingerprint: string;
  sentryIssueId: string;
  issueTitle: string;
  issueBody?: string;
  commentBody?: string;
  targetGitHubIssueNumber?: number;
  labels?: string[];
  sentryIssues: SentryIssue[];
  pairedSession?: PairedManualReportSession;
}

export interface SyncOptions {
  dryRun?: boolean;
  sentryToken?: string;
  sentryOrg?: string;
  sentryProjects?: string[];
  githubToken?: string;
  githubRepo?: string;
  lookbackHours?: number;
}
