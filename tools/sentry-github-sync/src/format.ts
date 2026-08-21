import {
  SentryIssue,
  SentryEvent,
  SentryTag,
  SentryAttachment,
  TriageCategory,
  PairedManualReportSession
} from './types.js';
import { redactString } from './redact.js';

export interface FormatIssueBodyParams {
  category: TriageCategory;
  fingerprint: string;
  sentryIssueId: string;
  issue?: SentryIssue;
  event?: SentryEvent;
  pairedSession?: PairedManualReportSession;
  attachments?: SentryAttachment[];
}

export interface FormatCommentBodyParams {
  category: TriageCategory;
  fingerprint: string;
  sentryIssueId: string;
  issue?: SentryIssue;
  event?: SentryEvent;
  isReopen?: boolean;
}

export function findTagValue(tags: SentryTag[] | undefined, key: string): string | undefined {
  if (!tags) return undefined;
  const normalizedKey = key.toLowerCase().replace(/[-_]/g, '');
  const match = tags.find((t) => t.key.toLowerCase().replace(/[-_]/g, '') === normalizedKey);
  return match?.value;
}

export function formatIssueTitle(
  category: TriageCategory,
  rawTitle: string,
  reportSessionId?: string
): string {
  if (category === 'manual_report') {
    const sessionPart = reportSessionId ? ` (${reportSessionId})` : '';
    const cleanDesc = rawTitle && !rawTitle.startsWith('buzer.') ? `: ${rawTitle}` : '';
    return redactString(`[Sentry] manual_report: User Report${sessionPart}${cleanDesc}`).trim();
  }

  const clean = rawTitle.replace(/^\[Sentry\]\s*/i, '').trim();
  return redactString(`[Sentry] ${category}: ${clean}`).trim();
}

export function extractStackTrace(event?: SentryEvent): string | null {
  if (!event) return null;

  if (event.entries) {
    for (const entry of event.entries) {
      if (entry.type === 'exception' && entry.data && Array.isArray((entry.data as any).values)) {
        const frames: string[] = [];
        for (const exc of (entry.data as any).values) {
          const type = exc.type || 'Error';
          const value = exc.value || '';
          frames.push(`${type}: ${value}`);
          if (exc.stacktrace && Array.isArray(exc.stacktrace.frames)) {
            for (const frame of exc.stacktrace.frames) {
              const file = frame.filename || frame.module || '<unknown>';
              const fn = frame.function || '<anonymous>';
              const line = frame.lineno ? `:${frame.lineno}` : '';
              frames.push(`  at ${fn} (${file}${line})`);
            }
          }
        }
        if (frames.length > 0) {
          return frames.join('\n');
        }
      }
    }
  }

  if (event.extra && typeof event.extra.stacktrace === 'string') {
    return event.extra.stacktrace;
  }

  return null;
}

export function formatIssueBody(params: FormatIssueBodyParams): string {
  const { category, fingerprint, sentryIssueId, issue, event, pairedSession, attachments } = params;

  const lines: string[] = [];

  // 1. Hidden fingerprint & ID markers
  lines.push(`<!-- sentry-fingerprint: ${fingerprint} -->`);
  lines.push(`<!-- sentry-issue-id: ${sentryIssueId} -->`);
  lines.push(`<!-- buzer-sentry-issue-id:${sentryIssueId} -->`);
  lines.push('');

  // 2. Header and Sentry Links
  lines.push(`## Sentry Triage Report: \`${category}\``);
  lines.push('');

  if (pairedSession) {
    lines.push(`> [!NOTE]`);
    lines.push(`> Correlated manual report session pairing Android client and Backend service events.`);
    lines.push('');
    lines.push(`**Report Session ID:** \`${pairedSession.reportSessionId}\``);
    lines.push('');

    lines.push('### Paired Diagnostic Events');
    lines.push('');
    lines.push('| Platform | Diagnostic Event ID | Sentry Issue | First / Last Seen |');
    lines.push('| :--- | :--- | :--- | :--- |');

    if (pairedSession.androidIssue || pairedSession.androidEvent) {
      const androidEventId = findTagValue(pairedSession.androidEvent?.tags, 'android_diagnostic_event_id') ||
        pairedSession.androidEvent?.id ||
        'N/A';
      const androidLink = pairedSession.androidIssue?.permalink
        ? `[${pairedSession.androidIssue.id}](${pairedSession.androidIssue.permalink})`
        : (pairedSession.androidIssue?.id || 'N/A');
      const seen = pairedSession.androidIssue?.lastSeen || pairedSession.androidEvent?.dateCreated || 'N/A';
      lines.push(`| **Android** | \`${androidEventId}\` | ${androidLink} | ${seen} |`);
    }

    if (pairedSession.backendIssue || pairedSession.backendEvent) {
      const backendEventId = findTagValue(pairedSession.backendEvent?.tags, 'backend_diagnostic_event_id') ||
        pairedSession.backendEvent?.id ||
        'N/A';
      const backendLink = pairedSession.backendIssue?.permalink
        ? `[${pairedSession.backendIssue.id}](${pairedSession.backendIssue.permalink})`
        : (pairedSession.backendIssue?.id || 'N/A');
      const seen = pairedSession.backendIssue?.lastSeen || pairedSession.backendEvent?.dateCreated || 'N/A';
      lines.push(`| **Backend** | \`${backendEventId}\` | ${backendLink} | ${seen} |`);
    }

    lines.push('');
  } else if (issue) {
    const issueLink = issue.permalink ? `[#${issue.shortId || issue.id} (${issue.title})](${issue.permalink})` : issue.title;
    lines.push(`**Sentry Issue:** ${issueLink}`);
    lines.push(`**Project:** \`${issue.project?.slug || issue.project?.name || 'unknown'}\``);
    lines.push(`**Status:** \`${issue.status || 'unresolved'}\` | **Level:** \`${issue.level || 'error'}\``);
    lines.push(`**Events:** ${issue.count ?? 1} | **Users:** ${issue.userCount ?? 1}`);
    lines.push(`**First Seen:** ${issue.firstSeen || 'N/A'} | **Last Seen:** ${issue.lastSeen || 'N/A'}`);
    lines.push('');
  }

  // 3. Metadata & Context Tags Table
  const allTags: SentryTag[] = [
    ...(issue?.tags || []),
    ...(event?.tags || []),
    ...(pairedSession?.androidEvent?.tags || []),
    ...(pairedSession?.backendEvent?.tags || [])
  ];

  const reportSessionId = findTagValue(allTags, 'report_session_id') || pairedSession?.reportSessionId;
  const androidDiagnosticEventId = findTagValue(allTags, 'android_diagnostic_event_id');
  const backendDiagnosticEventId = findTagValue(allTags, 'backend_diagnostic_event_id');
  const deviceId = findTagValue(allTags, 'device_id');
  const taskId = findTagValue(allTags, 'task_id');
  const occurrenceKey = findTagValue(allTags, 'occurrence_key');
  const actionId = findTagValue(allTags, 'action_id');
  const release = findTagValue(allTags, 'release') || (issue as any)?.release;
  const environment = findTagValue(allTags, 'environment') || 'production';
  const schemaVersion = findTagValue(allTags, 'capsule_schema_version') || findTagValue(allTags, 'schema_version') || '1';

  lines.push('### Diagnostic Metadata');
  lines.push('');
  lines.push('| Field | Value |');
  lines.push('| :--- | :--- |');
  lines.push(`| **Category** | \`${category}\` |`);
  if (reportSessionId) lines.push(`| **Report Session ID** | \`${reportSessionId}\` |`);
  if (androidDiagnosticEventId) lines.push(`| **Android Diagnostic ID** | \`${androidDiagnosticEventId}\` |`);
  if (backendDiagnosticEventId) lines.push(`| **Backend Diagnostic ID** | \`${backendDiagnosticEventId}\` |`);
  if (deviceId) lines.push(`| **Device ID** | \`${deviceId}\` |`);
  if (taskId) lines.push(`| **Task ID** | \`${taskId}\` |`);
  if (occurrenceKey) lines.push(`| **Occurrence Key** | \`${occurrenceKey}\` |`);
  if (actionId) lines.push(`| **Action ID** | \`${actionId}\` |`);
  if (release) lines.push(`| **Release** | \`${release}\` |`);
  lines.push(`| **Environment** | \`${environment}\` |`);
  lines.push(`| **Capsule Schema Version** | \`${schemaVersion}\` |`);
  lines.push('');

  // 4. Attachments Summary
  const allAttachments = [
    ...(attachments || []),
    ...(event?.attachments || []),
    ...(pairedSession?.androidEvent?.attachments || []),
    ...(pairedSession?.backendEvent?.attachments || [])
  ];

  if (allAttachments.length > 0) {
    lines.push('### Attachments');
    lines.push('');
    // Deduplicate attachments by name
    const seen = new Set<string>();
    for (const att of allAttachments) {
      if (!seen.has(att.name)) {
        seen.add(att.name);
        const sizeStr = att.size ? ` (${Math.round(att.size / 1024)} KiB)` : '';
        lines.push(`- \`${att.name}\`${sizeStr}`);
      }
    }
    lines.push('');
  }

  // 5. Stack Trace / Error Details
  const stackTrace = extractStackTrace(event) ||
    extractStackTrace(pairedSession?.androidEvent) ||
    extractStackTrace(pairedSession?.backendEvent);

  if (stackTrace) {
    lines.push('### Stack Trace / Details');
    lines.push('');
    lines.push('```text');
    lines.push(stackTrace);
    lines.push('```');
    lines.push('');
  }

  // 6. Reproduction & Triage Checklist
  lines.push('### Triage Checklist');
  lines.push('');
  lines.push('- [ ] Check Sentry issue for full diagnostic capsule and logs');
  lines.push('- [ ] Verify correlation IDs across Android and Backend telemetry');
  lines.push('- [ ] Reproduce issue on matching device / app release');
  lines.push('- [ ] Apply fix and add regression test coverage');
  lines.push('');

  return redactString(lines.join('\n'));
}

export function formatCommentBody(params: FormatCommentBodyParams): string {
  const { category, fingerprint, sentryIssueId, issue, event, isReopen } = params;

  const lines: string[] = [];
  lines.push(`<!-- sentry-fingerprint: ${fingerprint} -->`);
  lines.push(`<!-- sentry-issue-id: ${sentryIssueId} -->`);
  lines.push('');

  if (isReopen) {
    lines.push(`### ⚠️ Regression Detected — Reopening Issue`);
    lines.push('');
    lines.push(`A new occurrence of this issue was captured in Sentry after the GitHub issue was closed.`);
  } else {
    lines.push(`### ℹ️ New Sentry Occurrence`);
  }

  lines.push('');
  if (issue) {
    const issueLink = issue.permalink ? `[#${issue.shortId || issue.id}](${issue.permalink})` : issue.id;
    lines.push(`- **Sentry Issue:** ${issueLink}`);
    lines.push(`- **Total Count:** ${issue.count ?? 'N/A'}`);
    lines.push(`- **Last Seen:** ${issue.lastSeen || event?.dateCreated || new Date().toISOString()}`);
  }

  const allTags = [...(issue?.tags || []), ...(event?.tags || [])];
  const release = findTagValue(allTags, 'release');
  const environment = findTagValue(allTags, 'environment') || 'production';

  if (release) lines.push(`- **Release:** \`${release}\``);
  lines.push(`- **Environment:** \`${environment}\``);

  const stack = extractStackTrace(event);
  if (stack) {
    lines.push('');
    lines.push('<details>');
    lines.push('<summary>Latest Stack Trace</summary>');
    lines.push('');
    lines.push('```text');
    lines.push(stack);
    lines.push('```');
    lines.push('</details>');
  }

  return redactString(lines.join('\n'));
}

export function extractFingerprint(body?: string): string | null {
  if (!body) return null;
  const match = body.match(/<!--\s*sentry-fingerprint:\s*([^\s>]+)\s*-->/);
  return match && match[1] ? match[1] : null;
}

export function extractSentryIssueId(body?: string): string | null {
  if (!body) return null;
  const match = body.match(/<!--\s*(?:sentry-issue-id:\s*|buzer-sentry-issue-id:)(\S+?)\s*-->/);
  return match && match[1] ? match[1] : null;
}
