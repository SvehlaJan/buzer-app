import { describe, it, expect } from 'vitest';
import {
  categorizeSentryIssue,
  derivePlatform,
  generateFingerprint,
  triageSentryIssues
} from '../src/triage.js';
import { SentryIssue, GitHubIssue } from '../src/types.js';

describe('Categorization', () => {
  it('categorizes fatal uncaught error as fatal', () => {
    const issue: SentryIssue = {
      id: 'iss-1',
      title: 'FATAL EXCEPTION in main thread',
      level: 'fatal',
      tags: [{ key: 'handled', value: 'false' }]
    };
    expect(categorizeSentryIssue(issue)).toBe('fatal');
  });

  it('categorizes handled error as handled_non_fatal', () => {
    const issue: SentryIssue = {
      id: 'iss-2',
      title: 'Network timeout during background sync',
      level: 'warning',
      tags: [{ key: 'handled', value: 'true' }]
    };
    expect(categorizeSentryIssue(issue)).toBe('handled_non_fatal');
  });

  it('categorizes manual report event as manual_report', () => {
    const issue: SentryIssue = {
      id: 'iss-3',
      title: 'buzer.android.manual_report',
      tags: [{ key: 'event_type', value: 'manual_report' }]
    };
    expect(categorizeSentryIssue(issue)).toBe('manual_report');
  });

  it('categorizes feedback submission as manual_report', () => {
    const issue: SentryIssue = {
      id: 'iss-4',
      title: 'User feedback',
      tags: [{ key: 'feedback_submitted', value: 'true' }]
    };
    expect(categorizeSentryIssue(issue)).toBe('manual_report');
  });

  it('categorizes validation error as validation', () => {
    const issue: SentryIssue = {
      id: 'iss-5',
      title: 'TaskValidationError: Task title cannot be blank',
      level: 'error',
      tags: [{ key: 'event_type', value: 'validation' }]
    };
    expect(categorizeSentryIssue(issue)).toBe('validation');
  });
});

describe('Platform Derivation', () => {
  it('derives android platform from project or title', () => {
    const issue: SentryIssue = {
      id: '1',
      title: 'buzer.android.manual_report',
      project: { id: 'buzer-android', slug: 'buzer-android' }
    };
    expect(derivePlatform(issue)).toBe('android');
  });

  it('derives backend platform from project or title', () => {
    const issue: SentryIssue = {
      id: '2',
      title: 'Unhandled error in fastify route',
      project: { id: 'buzer-backend', slug: 'buzer-backend' }
    };
    expect(derivePlatform(issue)).toBe('backend');
  });

  it('derives cross-platform for paired sessions', () => {
    expect(derivePlatform(undefined, undefined, true)).toBe('cross-platform');
  });
});

describe('Triage Pairing and Decision Logic', () => {
  it('pairs Android and Backend manual reports sharing the same report_session_id into a single issue creation', () => {
    const androidIssue: SentryIssue = {
      id: 'sentry-and-1',
      title: 'buzer.android.manual_report',
      permalink: 'https://sentry.io/issues/sentry-and-1/',
      project: { id: 'buzer-android', slug: 'buzer-android' },
      latestEvent: {
        id: 'and-evt-1',
        tags: [
          { key: 'report_session_id', value: 'session-uuid-9999' },
          { key: 'android_diagnostic_event_id', value: 'and-diag-123' },
          { key: 'device_id', value: 'device-xyz' }
        ],
        attachments: [{ id: 'att-1', name: 'android-screenshot.webp', size: 102400 }]
      }
    };

    const backendIssue: SentryIssue = {
      id: 'sentry-back-2',
      title: 'buzer.backend.manual_report',
      permalink: 'https://sentry.io/issues/sentry-back-2/',
      project: { id: 'buzer-backend', slug: 'buzer-backend' },
      latestEvent: {
        id: 'back-evt-2',
        tags: [
          { key: 'report_session_id', value: 'session-uuid-9999' },
          { key: 'backend_diagnostic_event_id', value: 'back-diag-456' },
          { key: 'device_id', value: 'device-xyz' }
        ],
        attachments: [{ id: 'att-2', name: 'backend-diagnostics-v1.json', size: 4096 }]
      }
    };

    const existingGitHubIssues: GitHubIssue[] = [];

    const decisions = triageSentryIssues([androidIssue, backendIssue], existingGitHubIssues);

    // Must yield exactly 1 paired decision, not 2 separate decisions
    expect(decisions).toHaveLength(1);
    const decision = decisions[0]!;

    expect(decision.action).toBe('create_issue');
    expect(decision.category).toBe('manual_report');
    expect(decision.fingerprint).toBe('manual-report:session-uuid-9999');
    expect(decision.sentryIssueId).toBe('sentry-and-1+sentry-back-2');
    expect(decision.issueTitle).toBe('[Sentry] manual_report: User Report (session-uuid-9999)');
    expect(decision.labels).toContain('cross-platform');
    expect(decision.labels).toContain('manual_report');
    expect(decision.labels).toContain('bug');
    expect(decision.labels).toContain('observability');

    expect(decision.issueBody).toContain('session-uuid-9999');
    expect(decision.issueBody).toContain('and-diag-123');
    expect(decision.issueBody).toContain('back-diag-456');
    expect(decision.issueBody).toContain('android-screenshot.webp');
    expect(decision.issueBody).toContain('backend-diagnostics-v1.json');
  });

  it('creates new GitHub issue when no matching issue exists', () => {
    const issue: SentryIssue = {
      id: 'sentry-fatal-1',
      title: 'Database connection failed',
      level: 'fatal',
      project: { id: 'buzer-backend', slug: 'buzer-backend' }
    };

    const existingGitHubIssues: GitHubIssue[] = [];
    const decisions = triageSentryIssues([issue], existingGitHubIssues);

    expect(decisions).toHaveLength(1);
    expect(decisions[0]!.action).toBe('create_issue');
    expect(decisions[0]!.category).toBe('fatal');
    expect(decisions[0]!.issueTitle).toBe('[Sentry] fatal: Database connection failed');
  });

  it('adds comment when matching open GitHub issue exists', () => {
    const issue: SentryIssue = {
      id: 'sentry-fatal-1',
      title: 'Database connection failed',
      level: 'fatal',
      project: { id: 'buzer-backend', slug: 'buzer-backend' }
    };

    const existingGitHubIssues: GitHubIssue[] = [
      {
        number: 42,
        title: '[Sentry] fatal: Database connection failed',
        state: 'open',
        body: '<!-- sentry-fingerprint: sentry-issue:sentry-fatal-1 -->\n<!-- sentry-issue-id: sentry-fatal-1 -->\nOriginal report'
      }
    ];

    const decisions = triageSentryIssues([issue], existingGitHubIssues);

    expect(decisions).toHaveLength(1);
    expect(decisions[0]!.action).toBe('comment');
    expect(decisions[0]!.targetGitHubIssueNumber).toBe(42);
    expect(decisions[0]!.commentBody).toContain('### ℹ️ New Sentry Occurrence');
  });

  it('reopens and comments when matching closed GitHub issue exists (regression)', () => {
    const issue: SentryIssue = {
      id: 'sentry-fatal-1',
      title: 'Database connection failed',
      level: 'fatal',
      project: { id: 'buzer-backend', slug: 'buzer-backend' }
    };

    const existingGitHubIssues: GitHubIssue[] = [
      {
        number: 42,
        title: '[Sentry] fatal: Database connection failed',
        state: 'closed',
        body: '<!-- sentry-fingerprint: sentry-issue:sentry-fatal-1 -->\n<!-- sentry-issue-id: sentry-fatal-1 -->\nOld fixed report'
      }
    ];

    const decisions = triageSentryIssues([issue], existingGitHubIssues);

    expect(decisions).toHaveLength(1);
    expect(decisions[0]!.action).toBe('reopen_and_comment');
    expect(decisions[0]!.targetGitHubIssueNumber).toBe(42);
    expect(decisions[0]!.commentBody).toContain('### ⚠️ Regression Detected — Reopening Issue');
  });

  it('ignores Sentry issues marked as ignored', () => {
    const issue: SentryIssue = {
      id: 'sentry-ignored-1',
      title: 'Deprecated API warning',
      level: 'warning',
      status: 'ignored'
    };

    const decisions = triageSentryIssues([issue], []);

    expect(decisions).toHaveLength(1);
    expect(decisions[0]!.action).toBe('ignore');
    expect(decisions[0]!.reason).toContain('ignored');
  });

  it('performs exact Sentry ID matching and avoids numeric substring collisions', () => {
    const issue12: SentryIssue = {
      id: '12',
      title: 'Crash in module A',
      level: 'fatal',
      project: { id: 'buzer-android', slug: 'buzer-android' }
    };

    // Existing issue has ID 12345 (which contains '12' as substring)
    const existingGitHubIssues: GitHubIssue[] = [
      {
        number: 99,
        title: '[Sentry] fatal: Crash in module B',
        state: 'open',
        body: '<!-- sentry-fingerprint: sentry-issue:12345 -->\n<!-- sentry-issue-id: 12345 -->\nOther crash'
      }
    ];

    const decisions = triageSentryIssues([issue12], existingGitHubIssues);
    expect(decisions).toHaveLength(1);
    // Should create a new issue, NOT match issue #99 via substring collision
    expect(decisions[0]!.action).toBe('create_issue');
    expect(decisions[0]!.sentryIssueId).toBe('12');
  });

  it('correctly matches exact ID in paired plus-separated Sentry ID string', () => {
    const issue12: SentryIssue = {
      id: '12',
      title: 'buzer.android.manual_report',
      project: { id: 'buzer-android', slug: 'buzer-android' },
      latestEvent: {
        id: 'evt-12',
        tags: [{ key: 'report_session_id', value: 'sess-xyz' }]
      }
    };

    const existingGitHubIssues: GitHubIssue[] = [
      {
        number: 55,
        title: '[Sentry] manual_report: User Report (sess-xyz)',
        state: 'open',
        body: '<!-- sentry-issue-id: 12+34 -->\nOld report'
      }
    ];

    const decisions = triageSentryIssues([issue12], existingGitHubIssues);
    expect(decisions).toHaveLength(1);
    expect(decisions[0]!.action).toBe('comment');
    expect(decisions[0]!.targetGitHubIssueNumber).toBe(55);
  });
});

