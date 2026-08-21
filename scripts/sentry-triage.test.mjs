import test from 'node:test';
import assert from 'node:assert/strict';
import {
  redactString,
  findTagValue,
  categorizeSentryIssue,
  derivePlatform,
  formatIssueTitle,
  formatIssueBody,
  extractFingerprint,
  extractSentryIssueId,
  triageSentryIssues
} from './sentry-triage.mjs';

test('redactString removes secrets and auth headers', () => {
  const secret = 'Bearer super-secret-key-12345';
  const out = redactString(`Auth: ${secret}`);
  assert.ok(!out.includes('super-secret-key-12345'));
  assert.ok(out.includes('Bearer [REDACTED]'));
});

test('categorizeSentryIssue categorizes correctly', () => {
  assert.equal(categorizeSentryIssue({ level: 'fatal', tags: [] }), 'fatal');
  assert.equal(categorizeSentryIssue({ level: 'warning', tags: [{ key: 'handled', value: 'true' }] }), 'handled_non_fatal');
  assert.equal(categorizeSentryIssue({ title: 'buzer.android.manual_report' }), 'manual_report');
  assert.equal(categorizeSentryIssue({ title: 'TaskValidationError: invalid date' }), 'validation');
});

test('triageSentryIssues pairs Android and Backend manual reports by report_session_id', () => {
  const androidIssue = {
    id: 'and-1',
    title: 'buzer.android.manual_report',
    permalink: 'https://sentry.io/issues/and-1/',
    project: { slug: 'buzer-android' },
    latestEvent: {
      tags: [
        { key: 'report_session_id', value: 'sess-test-123' },
        { key: 'android_diagnostic_event_id', value: 'diag-and-123' }
      ]
    }
  };

  const backendIssue = {
    id: 'back-2',
    title: 'buzer.backend.manual_report',
    permalink: 'https://sentry.io/issues/back-2/',
    project: { slug: 'buzer-backend' },
    latestEvent: {
      tags: [
        { key: 'report_session_id', value: 'sess-test-123' },
        { key: 'backend_diagnostic_event_id', value: 'diag-back-456' }
      ]
    }
  };

  const decisions = triageSentryIssues([androidIssue, backendIssue], []);
  assert.equal(decisions.length, 1);
  const d = decisions[0];
  assert.equal(d.action, 'create_issue');
  assert.equal(d.category, 'manual_report');
  assert.equal(d.fingerprint, 'manual-report:sess-test-123');
  assert.ok(d.labels.includes('cross-platform'));
  assert.ok(d.issueBody.includes('diag-and-123'));
  assert.ok(d.issueBody.includes('diag-back-456'));
});

test('triageSentryIssues handles open issue comments and closed issue reopens', () => {
  const issue = {
    id: 'sentry-issue-999',
    title: 'Crash in sync',
    level: 'fatal',
    project: { slug: 'buzer-backend' }
  };

  // 1. Comment on open issue
  const openGhIssues = [
    {
      number: 10,
      title: '[Sentry] fatal: Crash in sync',
      state: 'open',
      body: '<!-- sentry-fingerprint: sentry-issue:sentry-issue-999 -->\n<!-- sentry-issue-id: sentry-issue-999 -->'
    }
  ];
  const dec1 = triageSentryIssues([issue], openGhIssues);
  assert.equal(dec1[0].action, 'comment');
  assert.equal(dec1[0].targetGitHubIssueNumber, 10);

  // 2. Reopen and comment on closed issue (regression)
  const closedGhIssues = [
    {
      number: 10,
      title: '[Sentry] fatal: Crash in sync',
      state: 'closed',
      body: '<!-- sentry-fingerprint: sentry-issue:sentry-issue-999 -->\n<!-- sentry-issue-id: sentry-issue-999 -->'
    }
  ];
  const dec2 = triageSentryIssues([issue], closedGhIssues);
  assert.equal(dec2[0].action, 'reopen_and_comment');
  assert.equal(dec2[0].targetGitHubIssueNumber, 10);
});
