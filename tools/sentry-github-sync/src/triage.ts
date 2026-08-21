import {
  SentryIssue,
  SentryEvent,
  SentryTag,
  GitHubIssue,
  TriageCategory,
  TriageDecision,
  PairedManualReportSession
} from './types.js';
import {
  findTagValue,
  formatIssueTitle,
  formatIssueBody,
  formatCommentBody,
  extractFingerprint,
  extractSentryIssueId
} from './format.js';

export function categorizeSentryIssue(issue: SentryIssue, event?: SentryEvent): TriageCategory {
  const allTags: SentryTag[] = [...(issue.tags || []), ...(event?.tags || [])];
  const tagEventType = findTagValue(allTags, 'event_type');
  const feedbackSubmitted = findTagValue(allTags, 'feedback_submitted');
  const title = (issue.title || event?.title || '').toLowerCase();
  const message = (event?.message || '').toLowerCase();

  // 1. Manual Bug Report
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

  // 2. Validation Errors
  if (
    tagEventType === 'validation' ||
    title.includes('validationerror') ||
    title.includes('invalidtask') ||
    title.includes('zoderror') ||
    message.includes('validation error')
  ) {
    return 'validation';
  }

  // 3. Fatal vs Handled Non-Fatal
  const level = (issue.level || event?.tags?.find((t) => t.key === 'level')?.value || 'error').toLowerCase();
  const handledTag = findTagValue(allTags, 'handled');

  if (handledTag === 'true' || tagEventType === 'handled_non_fatal' || level === 'warning' || level === 'info') {
    return 'handled_non_fatal';
  }

  if (level === 'fatal' || level === 'error' || handledTag === 'false') {
    return 'fatal';
  }

  return 'handled_non_fatal';
}

export function derivePlatform(
  issue?: SentryIssue,
  event?: SentryEvent,
  isPaired?: boolean
): 'android' | 'backend' | 'cross-platform' {
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

export function generateFingerprint(
  category: TriageCategory,
  issue: SentryIssue,
  _event?: SentryEvent,
  reportSessionId?: string
): string {
  if (category === 'manual_report' && reportSessionId) {
    return `manual-report:${reportSessionId}`;
  }
  return `sentry-issue:${issue.id}`;
}

export function matchesIssue(
  gh: GitHubIssue,
  fingerprint: string,
  relatedSentryIssues: SentryIssue[]
): boolean {
  const ghFingerprint = extractFingerprint(gh.body);
  const ghSentryId = extractSentryIssueId(gh.body);

  if (ghFingerprint && ghFingerprint === fingerprint) return true;
  if (ghSentryId && relatedSentryIssues.some((si) => ghSentryId.split('+').includes(si.id))) return true;
  return false;
}

export function triageSentryIssues(
  issues: SentryIssue[],
  existingGitHubIssues: GitHubIssue[]
): TriageDecision[] {
  const decisions: TriageDecision[] = [];

  // Group manual_report issues by report_session_id
  const manualReportsBySession = new Map<string, { android?: SentryIssue; backend?: SentryIssue; other: SentryIssue[] }>();
  const individualIssues: SentryIssue[] = [];

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

  // 1. Process Paired and Unpaired Manual Reports
  for (const [sessionId, group] of manualReportsBySession.entries()) {
    const primaryIssue = group.android || group.backend || group.other[0];
    if (!primaryIssue) continue;

    const isPaired = Boolean(group.android && group.backend);
    const androidEvent = group.android?.latestEvent;
    const backendEvent = group.backend?.latestEvent;
    const primaryEvent = primaryIssue.latestEvent;

    const pairedSession: PairedManualReportSession = {
      reportSessionId: sessionId,
      androidIssue: group.android,
      backendIssue: group.backend,
      androidEvent,
      backendEvent
    };

    const fingerprint = `manual-report:${sessionId}`;
    const sentryIssueId = isPaired
      ? `${group.android?.id}+${group.backend?.id}`
      : primaryIssue.id;

    const category: TriageCategory = 'manual_report';
    const title = formatIssueTitle(category, primaryIssue.title, sessionId.startsWith('unpaired-') ? undefined : sessionId);

    const relatedSentryIssues = [group.android, group.backend, ...group.other].filter((i): i is SentryIssue => Boolean(i));

    // Match against existing GitHub issues
    const matchingGitHubIssue = existingGitHubIssues.find((gh) =>
      matchesIssue(gh, fingerprint, relatedSentryIssues)
    );

    const labels = [
      'observability',
      'bug',
      'manual_report',
      derivePlatform(primaryIssue, primaryEvent, isPaired)
    ];

    if (!matchingGitHubIssue) {
      const issueBody = formatIssueBody({
        category,
        fingerprint,
        sentryIssueId,
        issue: primaryIssue,
        event: primaryEvent,
        pairedSession,
        attachments: [
          ...(androidEvent?.attachments || []),
          ...(backendEvent?.attachments || [])
        ]
      });

      decisions.push({
        action: 'create_issue',
        category,
        reason: isPaired ? 'Paired Android+Backend manual report session' : 'New manual bug report',
        fingerprint,
        sentryIssueId,
        issueTitle: title,
        issueBody,
        labels,
        sentryIssues: relatedSentryIssues,
        pairedSession
      });
    } else if (matchingGitHubIssue.state === 'open') {
      const commentBody = formatCommentBody({
        category,
        fingerprint,
        sentryIssueId,
        issue: primaryIssue,
        event: primaryEvent,
        isReopen: false
      });

      decisions.push({
        action: 'comment',
        category,
        reason: 'New event received for existing open issue',
        fingerprint,
        sentryIssueId,
        issueTitle: title,
        commentBody,
        targetGitHubIssueNumber: matchingGitHubIssue.number,
        labels,
        sentryIssues: relatedSentryIssues,
        pairedSession
      });
    } else {
      const commentBody = formatCommentBody({
        category,
        fingerprint,
        sentryIssueId,
        issue: primaryIssue,
        event: primaryEvent,
        isReopen: true
      });

      decisions.push({
        action: 'reopen_and_comment',
        category,
        reason: 'Regression: new event received for closed issue',
        fingerprint,
        sentryIssueId,
        issueTitle: title,
        commentBody,
        targetGitHubIssueNumber: matchingGitHubIssue.number,
        labels,
        sentryIssues: relatedSentryIssues,
        pairedSession
      });
    }
  }

  // 2. Process Individual Sentry Issues (Fatal, Handled, Validation)
  for (const issue of individualIssues) {
    const event = issue.latestEvent;
    const category = categorizeSentryIssue(issue, event);

    // Ignore resolved/ignored issues if no new event
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

    const fingerprint = generateFingerprint(category, issue, event);
    const sentryIssueId = issue.id;
    const title = formatIssueTitle(category, issue.title);
    const platform = derivePlatform(issue, event);

    const labels = [
      'observability',
      'bug',
      category,
      platform
    ];

    const matchingGitHubIssue = existingGitHubIssues.find((gh) =>
      matchesIssue(gh, fingerprint, [issue])
    );

    if (!matchingGitHubIssue) {
      const issueBody = formatIssueBody({
        category,
        fingerprint,
        sentryIssueId,
        issue,
        event,
        attachments: event?.attachments
      });

      decisions.push({
        action: 'create_issue',
        category,
        reason: `New ${category} issue observed in Sentry`,
        fingerprint,
        sentryIssueId,
        issueTitle: title,
        issueBody,
        labels,
        sentryIssues: [issue]
      });
    } else if (matchingGitHubIssue.state === 'open') {
      const commentBody = formatCommentBody({
        category,
        fingerprint,
        sentryIssueId,
        issue,
        event,
        isReopen: false
      });

      decisions.push({
        action: 'comment',
        category,
        reason: 'New event received for existing open issue',
        fingerprint,
        sentryIssueId,
        issueTitle: title,
        commentBody,
        targetGitHubIssueNumber: matchingGitHubIssue.number,
        labels,
        sentryIssues: [issue]
      });
    } else {
      const commentBody = formatCommentBody({
        category,
        fingerprint,
        sentryIssueId,
        issue,
        event,
        isReopen: true
      });

      decisions.push({
        action: 'reopen_and_comment',
        category,
        reason: 'Regression: new event received for closed issue',
        fingerprint,
        sentryIssueId,
        issueTitle: title,
        commentBody,
        targetGitHubIssueNumber: matchingGitHubIssue.number,
        labels,
        sentryIssues: [issue]
      });
    }
  }

  return decisions;
}
