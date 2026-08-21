#!/usr/bin/env node

/**
 * Deduplicated Sentry-to-GitHub Triage Script
 */

const PEM_PATTERN = /-----BEGIN [A-Z0-9_ -]*PRIVATE KEY-----[\s\S]*?(?:-----END [A-Z0-9_ -]*PRIVATE KEY-----|$)/g;
const AUTH_SCHEME_PATTERN = /\b(bearer|basic)\s+(?:(["'])(?!\[REDACTED\]\2)([^\r\n]*?)\2|(?!\[REDACTED\])[^\s,;"'\r\n]+)/gi;
const URL_CREDENTIALS_PATTERN = /([a-zA-Z][a-zA-Z0-9+.-]*:\/\/)([^@\/\s]+)@/g;
const SENSITIVE_QUERY_PARAM_PATTERN = /([?&](?:token|auth|key|secret|api_key|apikey|access_token|refresh_token|auth_token|sentry_auth_token|sentry_key|password|pwd)=)([^&#\s]+)/gi;
const COOKIE_HEADER_PATTERN = /\b(cookie|set-cookie|set_cookie)\s*:\s*(?!\[REDACTED\])[^\r\n]+/gi;
const QR_KEY_PATTERN = /\b(qr_code|qr_string|qr_content|whatsapp_session)\s*([:=])\s*(?!\[REDACTED\])[^\r\n]+/gi;
const KEY_VALUE_PATTERN = /(["']?)\b(password|passwd|pwd|secret|secret_token|secret_cookie|client_secret|app_secret|shared_secret|api_secret|api_token|access_token|refresh_token|auth_token|id_token|todoist_token|sentry_auth_token|sentry_token|devicetoken|device_token|session_token|session_key|credentials|credential|private_key|api_key|token|cookie|set-cookie|set_cookie)\1\s*([:=])\s*(?:(["'])(?!\[REDACTED\]\4)([^\r\n]*?)\4|(?!\[REDACTED\])([^\s,;"'\r\n]+))/gi;

export function redactString(input) {
  if (!input || typeof input !== 'string') return input;
  if (
    !input.includes(':') &&
    !input.includes('=') &&
    !input.includes('@') &&
    !input.includes('-----') &&
    !/bearer|basic/i.test(input)
  ) {
    return input;
  }

  let text = input;
  if (text.includes('-----')) text = text.replace(PEM_PATTERN, '[REDACTED]');
  if (/bearer|basic/i.test(text)) {
    text = text.replace(AUTH_SCHEME_PATTERN, (_match, scheme, quote) => {
      return quote ? `${scheme} ${quote}[REDACTED]${quote}` : `${scheme} [REDACTED]`;
    });
  }
  if (text.includes('@')) {
    text = text.replace(URL_CREDENTIALS_PATTERN, (match, scheme, creds) => {
      if (creds === '[REDACTED]' || creds === '[REDACTED]:[REDACTED]') return match;
      return creds.includes(':') ? `${scheme}[REDACTED]:[REDACTED]@` : `${scheme}[REDACTED]@`;
    });
  }
  if (text.includes('?')) {
    text = text.replace(SENSITIVE_QUERY_PARAM_PATTERN, (_match, prefix) => `${prefix}[REDACTED]`);
  }
  if (/cookie|set-cookie|set_cookie/i.test(text)) {
    text = text.replace(COOKIE_HEADER_PATTERN, (_match, headerName) => `${headerName}: [REDACTED]`);
  }
  if (/qr|whatsapp/i.test(text)) {
    text = text.replace(QR_KEY_PATTERN, (_match, keyName, sep) => {
      return sep.includes(':') ? `${keyName}: [REDACTED]` : `${keyName}=[REDACTED]`;
    });
  }
  if (text.includes(':') || text.includes('=')) {
    text = text.replace(
      KEY_VALUE_PATTERN,
      (match, keyQuote, key, delimiter, valQuote, quotedVal, unquotedVal) => {
        const value = valQuote ? quotedVal : unquotedVal;
        if (value === '[REDACTED]') return match;
        const q = valQuote ?? '';
        return delimiter.includes(':')
          ? `${keyQuote}${key}${keyQuote}: ${q}[REDACTED]${q}`
          : `${keyQuote}${key}${keyQuote}=${q}[REDACTED]${q}`;
      }
    );
  }
  return text;
}

export function findTagValue(tags, key) {
  if (!tags || !Array.isArray(tags)) return undefined;
  const normalizedKey = key.toLowerCase().replace(/[-_]/g, '');
  const match = tags.find((t) => (t.key || '').toLowerCase().replace(/[-_]/g, '') === normalizedKey);
  return match ? match.value : undefined;
}

export function categorizeSentryIssue(issue, event) {
  const allTags = [...(issue.tags || []), ...(event?.tags || [])];
  const tagEventType = findTagValue(allTags, 'event_type');
  const feedbackSubmitted = findTagValue(allTags, 'feedback_submitted');
  const title = (issue.title || event?.title || '').toLowerCase();
  const message = (event?.message || '').toLowerCase();

  if (
    tagEventType === 'manual_report' ||
    feedbackSubmitted === 'true' ||
    title.startsWith('buzer.android.manual_report') ||
    title.startsWith('buzer.backend.manual_report') ||
    message.startsWith('buzer.android.manual_report') ||
    message.startsWith('buzer.backend.manual_report')
  ) {
    return 'manual_report';
  }

  if (
    tagEventType === 'validation' ||
    title.includes('validationerror') ||
    title.includes('invalidtask') ||
    title.includes('zoderror') ||
    message.includes('validation error')
  ) {
    return 'validation';
  }

  const level = (issue.level || findTagValue(allTags, 'level') || 'error').toLowerCase();
  const handledTag = findTagValue(allTags, 'handled');

  if (handledTag === 'true' || tagEventType === 'handled_non_fatal' || level === 'warning' || level === 'info') {
    return 'handled_non_fatal';
  }

  if (level === 'fatal' || level === 'error' || handledTag === 'false') {
    return 'fatal';
  }

  return 'handled_non_fatal';
}

export function derivePlatform(issue, event, isPaired) {
  if (isPaired) return 'cross-platform';
  const slug = (issue?.project?.slug || issue?.project?.name || issue?.platform || event?.platform || '').toLowerCase();
  const title = (issue?.title || event?.title || '').toLowerCase();

  if (slug.includes('android') || title.includes('.android.') || title.includes('android')) {
    return 'android';
  }
  if (slug.includes('backend') || title.includes('.backend.') || title.includes('backend') || slug.includes('node')) {
    return 'backend';
  }
  return 'android';
}

export function formatIssueTitle(category, rawTitle, reportSessionId) {
  if (category === 'manual_report') {
    const sessionPart = reportSessionId ? ` (${reportSessionId})` : '';
    const cleanDesc = rawTitle && !rawTitle.startsWith('buzer.') ? `: ${rawTitle}` : '';
    return redactString(`[Sentry] manual_report: User Report${sessionPart}${cleanDesc}`).trim();
  }
  const clean = rawTitle.replace(/^\[Sentry\]\s*/i, '').trim();
  return redactString(`[Sentry] ${category}: ${clean}`).trim();
}

export function formatIssueBody(params) {
  const { category, fingerprint, sentryIssueId, issue, event, pairedSession } = params;
  const lines = [];

  lines.push(`<!-- sentry-fingerprint: ${fingerprint} -->`);
  lines.push(`<!-- sentry-issue-id: ${sentryIssueId} -->`);
  lines.push(`<!-- buzer-sentry-issue-id:${sentryIssueId} -->`);
  lines.push('');
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
      const androidEventId = findTagValue(pairedSession.androidEvent?.tags, 'android_diagnostic_event_id') || pairedSession.androidEvent?.id || 'N/A';
      const androidLink = pairedSession.androidIssue?.permalink ? `[${pairedSession.androidIssue.id}](${pairedSession.androidIssue.permalink})` : (pairedSession.androidIssue?.id || 'N/A');
      const seen = pairedSession.androidIssue?.lastSeen || pairedSession.androidEvent?.dateCreated || 'N/A';
      lines.push(`| **Android** | \`${androidEventId}\` | ${androidLink} | ${seen} |`);
    }

    if (pairedSession.backendIssue || pairedSession.backendEvent) {
      const backendEventId = findTagValue(pairedSession.backendEvent?.tags, 'backend_diagnostic_event_id') || pairedSession.backendEvent?.id || 'N/A';
      const backendLink = pairedSession.backendIssue?.permalink ? `[${pairedSession.backendIssue.id}](${pairedSession.backendIssue.permalink})` : (pairedSession.backendIssue?.id || 'N/A');
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

  const allTags = [
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
  const release = findTagValue(allTags, 'release') || issue?.release;
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

  lines.push('### Triage Checklist');
  lines.push('');
  lines.push('- [ ] Check Sentry issue for full diagnostic capsule and logs');
  lines.push('- [ ] Verify correlation IDs across Android and Backend telemetry');
  lines.push('- [ ] Reproduce issue on matching device / app release');
  lines.push('- [ ] Apply fix and add regression test coverage');
  lines.push('');

  return redactString(lines.join('\n'));
}

export function extractFingerprint(body) {
  if (!body) return null;
  const match = body.match(/<!--\s*sentry-fingerprint:\s*([^\s>]+)\s*-->/);
  return match && match[1] ? match[1] : null;
}

export function extractSentryIssueId(body) {
  if (!body) return null;
  const match = body.match(/<!--\s*(?:sentry-issue-id:\s*|buzer-sentry-issue-id:)(\S+?)\s*-->/);
  return match && match[1] ? match[1] : null;
}

export function triageSentryIssues(issues, existingGitHubIssues) {
  const decisions = [];
  const manualReportsBySession = new Map();
  const individualIssues = [];

  for (const issue of issues) {
    const event = issue.latestEvent;
    const allTags = [...(issue.tags || []), ...(event?.tags || [])];
    const category = categorizeSentryIssue(issue, event);

    if (category === 'manual_report') {
      const sessionId = findTagValue(allTags, 'report_session_id') || `unpaired-${issue.id}`;
      const existing = manualReportsBySession.get(sessionId) || { other: [] };
      const platform = derivePlatform(issue, event);

      if (platform === 'android') {
        existing.android = issue;
      } else if (platform === 'backend') {
        existing.backend = issue;
      } else {
        existing.other.push(issue);
      }
      manualReportsBySession.set(sessionId, existing);
    } else {
      individualIssues.push(issue);
    }
  }

  for (const [sessionId, group] of manualReportsBySession.entries()) {
    const primaryIssue = group.android || group.backend || group.other[0];
    if (!primaryIssue) continue;

    const isPaired = Boolean(group.android && group.backend);
    const androidEvent = group.android?.latestEvent;
    const backendEvent = group.backend?.latestEvent;
    const primaryEvent = primaryIssue.latestEvent;

    const pairedSession = {
      reportSessionId: sessionId,
      androidIssue: group.android,
      backendIssue: group.backend,
      androidEvent,
      backendEvent
    };

    const fingerprint = `manual-report:${sessionId}`;
    const sentryIssueId = isPaired ? `${group.android?.id}+${group.backend?.id}` : primaryIssue.id;
    const category = 'manual_report';
    const title = formatIssueTitle(category, primaryIssue.title, sessionId.startsWith('unpaired-') ? undefined : sessionId);
    const relatedSentryIssues = [group.android, group.backend, ...group.other].filter(Boolean);

    const matchingGitHubIssue = existingGitHubIssues.find((gh) => {
      const ghFingerprint = extractFingerprint(gh.body);
      const ghSentryId = extractSentryIssueId(gh.body);
      if (ghFingerprint && ghFingerprint === fingerprint) return true;
      if (ghSentryId && relatedSentryIssues.some((si) => ghSentryId.includes(si.id))) return true;
      return false;
    });

    const labels = ['observability', 'bug', 'manual_report', derivePlatform(primaryIssue, primaryEvent, isPaired)];

    if (!matchingGitHubIssue) {
      decisions.push({
        action: 'create_issue',
        category,
        reason: isPaired ? 'Paired Android+Backend manual report session' : 'New manual bug report',
        fingerprint,
        sentryIssueId,
        issueTitle: title,
        issueBody: formatIssueBody({ category, fingerprint, sentryIssueId, issue: primaryIssue, event: primaryEvent, pairedSession }),
        labels,
        sentryIssues: relatedSentryIssues,
        pairedSession
      });
    } else if (matchingGitHubIssue.state === 'open') {
      decisions.push({
        action: 'comment',
        category,
        reason: 'New event received for existing open issue',
        fingerprint,
        sentryIssueId,
        issueTitle: title,
        commentBody: `<!-- sentry-fingerprint: ${fingerprint} -->\n<!-- sentry-issue-id: ${sentryIssueId} -->\n### ℹ️ New Sentry Occurrence\n- **Sentry Issue:** ${primaryIssue.id}`,
        targetGitHubIssueNumber: matchingGitHubIssue.number,
        labels,
        sentryIssues: relatedSentryIssues,
        pairedSession
      });
    } else {
      decisions.push({
        action: 'reopen_and_comment',
        category,
        reason: 'Regression: new event received for closed issue',
        fingerprint,
        sentryIssueId,
        issueTitle: title,
        commentBody: `<!-- sentry-fingerprint: ${fingerprint} -->\n<!-- sentry-issue-id: ${sentryIssueId} -->\n### ⚠️ Regression Detected — Reopening Issue\n- **Sentry Issue:** ${primaryIssue.id}`,
        targetGitHubIssueNumber: matchingGitHubIssue.number,
        labels,
        sentryIssues: relatedSentryIssues,
        pairedSession
      });
    }
  }

  for (const issue of individualIssues) {
    const event = issue.latestEvent;
    const category = categorizeSentryIssue(issue, event);

    if (issue.status === 'ignored') {
      decisions.push({
        action: 'ignore',
        category,
        reason: 'Sentry issue is marked as ignored',
        fingerprint: `sentry-issue:${issue.id}`,
        sentryIssueId: issue.id,
        issueTitle: issue.title,
        sentryIssues: [issue]
      });
      continue;
    }

    const fingerprint = `sentry-issue:${issue.id}`;
    const sentryIssueId = issue.id;
    const title = formatIssueTitle(category, issue.title);
    const platform = derivePlatform(issue, event);
    const labels = ['observability', 'bug', category, platform];

    const matchingGitHubIssue = existingGitHubIssues.find((gh) => {
      const ghFingerprint = extractFingerprint(gh.body);
      const ghSentryId = extractSentryIssueId(gh.body);
      return ghFingerprint === fingerprint || ghSentryId === sentryIssueId;
    });

    if (!matchingGitHubIssue) {
      decisions.push({
        action: 'create_issue',
        category,
        reason: `New ${category} issue observed in Sentry`,
        fingerprint,
        sentryIssueId,
        issueTitle: title,
        issueBody: formatIssueBody({ category, fingerprint, sentryIssueId, issue, event }),
        labels,
        sentryIssues: [issue]
      });
    } else if (matchingGitHubIssue.state === 'open') {
      decisions.push({
        action: 'comment',
        category,
        reason: 'New event received for existing open issue',
        fingerprint,
        sentryIssueId,
        issueTitle: title,
        commentBody: `<!-- sentry-fingerprint: ${fingerprint} -->\n<!-- sentry-issue-id: ${sentryIssueId} -->\n### ℹ️ New Sentry Occurrence`,
        targetGitHubIssueNumber: matchingGitHubIssue.number,
        labels,
        sentryIssues: [issue]
      });
    } else {
      decisions.push({
        action: 'reopen_and_comment',
        category,
        reason: 'Regression: new event received for closed issue',
        fingerprint,
        sentryIssueId,
        issueTitle: title,
        commentBody: `<!-- sentry-fingerprint: ${fingerprint} -->\n<!-- sentry-issue-id: ${sentryIssueId} -->\n### ⚠️ Regression Detected — Reopening Issue`,
        targetGitHubIssueNumber: matchingGitHubIssue.number,
        labels,
        sentryIssues: [issue]
      });
    }
  }

  return decisions;
}
