import { describe, it, expect } from 'vitest';
import {
  formatIssueTitle,
  formatIssueBody,
  formatCommentBody,
  extractFingerprint,
  extractSentryIssueId,
  extractStackTrace,
  findTagValue
} from '../src/format.js';
import { redactString, redactUrl, isCredentialKey } from '../src/redact.js';
import { SentryIssue, SentryEvent, PairedManualReportSession } from '../src/types.js';

describe('Redaction', () => {
  it('redacts bearer tokens, basic auth, and passwords in strings', () => {
    const raw = 'Failed request with Bearer secret-token-xyz and password: "mySuperSecretPassword"';
    const redacted = redactString(raw);
    expect(redacted).not.toContain('secret-token-xyz');
    expect(redacted).not.toContain('mySuperSecretPassword');
    expect(redacted).toContain('Bearer [REDACTED]');
    expect(redacted).toContain('password: "[REDACTED]"');
  });

  it('redacts PEM private key blocks', () => {
    const raw = 'Key is:\n-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA...\n-----END RSA PRIVATE KEY-----';
    const redacted = redactString(raw);
    expect(redacted).not.toContain('MIIEowIBAAKCAQEA');
    expect(redacted).toContain('[REDACTED]');
  });

  it('redacts credentials and sensitive query params in URLs', () => {
    const url = 'https://admin:pass123@example.com/api?token=secret456&user=alice';
    const redacted = redactUrl(url);
    expect(redacted).not.toContain('pass123');
    expect(redacted).not.toContain('secret456');
    expect(redacted).toContain('https://[REDACTED]:[REDACTED]@example.com/api?token=[REDACTED]&user=alice');
  });

  it('identifies credential keys correctly', () => {
    expect(isCredentialKey('authorization')).toBe(true);
    expect(isCredentialKey('sentry_auth_token')).toBe(true);
    expect(isCredentialKey('todoist_token')).toBe(true);
    expect(isCredentialKey('client_secret')).toBe(true);
    expect(isCredentialKey('report_session_id')).toBe(false);
    expect(isCredentialKey('occurrence_key')).toBe(false);
    expect(isCredentialKey('device_id')).toBe(false);
    expect(isCredentialKey('task_id')).toBe(false);
  });
});

describe('Format Title', () => {
  it('formats fatal issue title', () => {
    const title = formatIssueTitle('fatal', 'Uncaught NullPointerException in MainActivity');
    expect(title).toBe('[Sentry] fatal: Uncaught NullPointerException in MainActivity');
  });

  it('formats handled non-fatal issue title', () => {
    const title = formatIssueTitle('handled_non_fatal', 'Network request timeout on sync');
    expect(title).toBe('[Sentry] handled_non_fatal: Network request timeout on sync');
  });

  it('formats manual_report title with session ID', () => {
    const title = formatIssueTitle('manual_report', 'buzer.android.manual_report', 'sess-uuid-1234');
    expect(title).toBe('[Sentry] manual_report: User Report (sess-uuid-1234)');
  });

  it('formats manual_report title with session ID and custom user feedback message', () => {
    const title = formatIssueTitle('manual_report', 'Alarms not ringing on time', 'sess-uuid-1234');
    expect(title).toBe('[Sentry] manual_report: User Report (sess-uuid-1234): Alarms not ringing on time');
  });

  it('redacts secrets within the title', () => {
    const title = formatIssueTitle('fatal', 'Auth failed with Bearer secret-auth-token');
    expect(title).not.toContain('secret-auth-token');
    expect(title).toContain('[Sentry] fatal: Auth failed with Bearer [REDACTED]');
  });
});

describe('Markers and Tag Extraction', () => {
  it('extracts fingerprint and sentry issue ID from markdown body', () => {
    const body = `<!-- sentry-fingerprint: manual-report:sess-123 -->\n<!-- sentry-issue-id: 987654 -->\n## Sentry Triage`;
    expect(extractFingerprint(body)).toBe('manual-report:sess-123');
    expect(extractSentryIssueId(body)).toBe('987654');
  });

  it('extracts legacy buzer-sentry-issue-id marker', () => {
    const body = `<!-- buzer-sentry-issue-id:987654 -->\n## Sentry Triage`;
    expect(extractSentryIssueId(body)).toBe('987654');
  });

  it('finds tag values case-insensitively and regardless of dashes/underscores', () => {
    const tags = [
      { key: 'report_session_id', value: 'sess-abc-123' },
      { key: 'capsule-schema-version', value: '1' }
    ];
    expect(findTagValue(tags, 'report_session_id')).toBe('sess-abc-123');
    expect(findTagValue(tags, 'reportSessionId')).toBe('sess-abc-123');
    expect(findTagValue(tags, 'capsule_schema_version')).toBe('1');
  });
});

describe('Format Issue Body', () => {
  it('formats complete single issue body with tags and attachments', () => {
    const issue: SentryIssue = {
      id: 'issue-100',
      title: 'Database locked during transaction',
      permalink: 'https://sentry.io/organizations/buzer/issues/100/',
      status: 'unresolved',
      level: 'error',
      count: '5',
      userCount: 2,
      firstSeen: '2026-08-21T20:00:00Z',
      lastSeen: '2026-08-21T21:00:00Z',
      project: { id: 'buzer-backend', name: 'buzer-backend', slug: 'buzer-backend' }
    };

    const event: SentryEvent = {
      id: 'evt-200',
      tags: [
        { key: 'device_id', value: 'dev-456' },
        { key: 'task_id', value: 'task-789' },
        { key: 'environment', value: 'staging' },
        { key: 'release', value: 'backend@0.1.0' }
      ],
      attachments: [
        { id: 'att-1', name: 'backend-app-log.txt', size: 2048 },
        { id: 'att-2', name: 'backend-diagnostics-v1.json', size: 4096 }
      ]
    };

    const body = formatIssueBody({
      category: 'fatal',
      fingerprint: 'sentry-issue:issue-100',
      sentryIssueId: 'issue-100',
      issue,
      event
    });

    expect(body).toContain('<!-- sentry-fingerprint: sentry-issue:issue-100 -->');
    expect(body).toContain('<!-- sentry-issue-id: issue-100 -->');
    expect(body).toContain('## Sentry Triage Report: `fatal`');
    expect(body).toContain('[#issue-100 (Database locked during transaction)](https://sentry.io/organizations/buzer/issues/100/)');
    expect(body).toContain('| **Device ID** | `dev-456` |');
    expect(body).toContain('| **Task ID** | `task-789` |');
    expect(body).toContain('| **Environment** | `staging` |');
    expect(body).toContain('- `backend-app-log.txt` (2 KiB)');
    expect(body).toContain('- `backend-diagnostics-v1.json` (4 KiB)');
    expect(body).toContain('### Triage Checklist');
  });

  it('formats paired manual report combining Android and Backend telemetry', () => {
    const androidIssue: SentryIssue = {
      id: 'android-iss-1',
      title: 'buzer.android.manual_report',
      permalink: 'https://sentry.io/org/issues/android-iss-1/'
    };
    const backendIssue: SentryIssue = {
      id: 'backend-iss-2',
      title: 'buzer.backend.manual_report',
      permalink: 'https://sentry.io/org/issues/backend-iss-2/'
    };

    const pairedSession: PairedManualReportSession = {
      reportSessionId: 'sess-paired-1234',
      androidIssue,
      backendIssue,
      androidEvent: {
        id: 'and-evt-1',
        tags: [
          { key: 'report_session_id', value: 'sess-paired-1234' },
          { key: 'android_diagnostic_event_id', value: 'and-diag-111' },
          { key: 'device_id', value: 'pixel-8' }
        ],
        attachments: [{ id: 'att-a', name: 'android-screenshot.webp', size: 102400 }]
      },
      backendEvent: {
        id: 'back-evt-2',
        tags: [
          { key: 'report_session_id', value: 'sess-paired-1234' },
          { key: 'backend_diagnostic_event_id', value: 'back-diag-222' }
        ],
        attachments: [{ id: 'att-b', name: 'backend-diagnostics-v1.json', size: 8192 }]
      }
    };

    const body = formatIssueBody({
      category: 'manual_report',
      fingerprint: 'manual-report:sess-paired-1234',
      sentryIssueId: 'android-iss-1+backend-iss-2',
      pairedSession
    });

    expect(body).toContain('<!-- sentry-fingerprint: manual-report:sess-paired-1234 -->');
    expect(body).toContain('**Report Session ID:** `sess-paired-1234`');
    expect(body).toContain('| **Android** | `and-diag-111` | [android-iss-1](https://sentry.io/org/issues/android-iss-1/) |');
    expect(body).toContain('| **Backend** | `back-diag-222` | [backend-iss-2](https://sentry.io/org/issues/backend-iss-2/) |');
    expect(body).toContain('| **Android Diagnostic ID** | `and-diag-111` |');
    expect(body).toContain('| **Backend Diagnostic ID** | `back-diag-222` |');
    expect(body).toContain('- `android-screenshot.webp` (100 KiB)');
    expect(body).toContain('- `backend-diagnostics-v1.json` (8 KiB)');
  });

  it('extracts stack trace from Sentry exception entries and formats in markdown block', () => {
    const event: SentryEvent = {
      id: 'evt-stack-1',
      entries: [
        {
          type: 'exception',
          data: {
            values: [
              {
                type: 'IllegalStateException',
                value: 'Task not found in store',
                stacktrace: {
                  frames: [
                    { filename: 'app/buzer/TaskService.kt', function: 'getTask', lineno: 42 },
                    { filename: 'app/buzer/MainActivity.kt', function: 'onCreate', lineno: 108 }
                  ]
                }
              }
            ]
          }
        }
      ]
    };

    const stack = extractStackTrace(event);
    expect(stack).toContain('IllegalStateException: Task not found in store');
    expect(stack).toContain('at getTask (app/buzer/TaskService.kt:42)');
    expect(stack).toContain('at onCreate (app/buzer/MainActivity.kt:108)');

    const body = formatIssueBody({
      category: 'fatal',
      fingerprint: 'sentry-issue:1',
      sentryIssueId: '1',
      event
    });

    expect(body).toContain('### Stack Trace / Details');
    expect(body).toContain('IllegalStateException: Task not found in store');
  });
});

describe('Format Comment Body', () => {
  it('formats regular new occurrence comment', () => {
    const issue: SentryIssue = {
      id: 'issue-1',
      title: 'Crash',
      count: 12,
      lastSeen: '2026-08-21T22:00:00Z',
      permalink: 'https://sentry.io/issues/1/'
    };

    const comment = formatCommentBody({
      category: 'fatal',
      fingerprint: 'sentry-issue:issue-1',
      sentryIssueId: 'issue-1',
      issue,
      isReopen: false
    });

    expect(comment).toContain('### ℹ️ New Sentry Occurrence');
    expect(comment).toContain('[#issue-1](https://sentry.io/issues/1/)');
    expect(comment).toContain('- **Total Count:** 12');
  });

  it('formats regression reopen comment', () => {
    const issue: SentryIssue = {
      id: 'issue-1',
      title: 'Crash',
      count: 13,
      permalink: 'https://sentry.io/issues/1/'
    };

    const comment = formatCommentBody({
      category: 'fatal',
      fingerprint: 'sentry-issue:issue-1',
      sentryIssueId: 'issue-1',
      issue,
      isReopen: true
    });

    expect(comment).toContain('### ⚠️ Regression Detected — Reopening Issue');
    expect(comment).toContain('A new occurrence of this issue was captured in Sentry after the GitHub issue was closed.');
  });
});
