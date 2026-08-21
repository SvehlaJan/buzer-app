#!/usr/bin/env node

import { SentryClient } from './sentry.js';
import { GitHubClient } from './github.js';
import { triageSentryIssues } from './triage.js';
import { SentryIssue, SyncOptions, TriageDecision } from './types.js';

export function parseArgs(args: string[]): SyncOptions {
  const options: SyncOptions = {
    dryRun: false,
    lookbackHours: 24,
    sentryProjects: []
  };

  for (let i = 0; i < args.length; i++) {
    const arg = args[i];
    if (!arg) continue;

    if (arg === '--dry-run') {
      options.dryRun = true;
    } else if (arg === '--sentry-token' && args[i + 1]) {
      options.sentryToken = args[++i];
    } else if (arg === '--sentry-org' && args[i + 1]) {
      options.sentryOrg = args[++i];
    } else if (arg === '--sentry-project' && args[i + 1]) {
      options.sentryProjects?.push(args[++i]!);
    } else if (arg === '--github-token' && args[i + 1]) {
      options.githubToken = args[++i];
    } else if (arg === '--repo' && args[i + 1]) {
      options.githubRepo = args[++i];
    } else if (arg === '--lookback-hours' && args[i + 1]) {
      options.lookbackHours = parseInt(args[++i]!, 10);
    }
  }

  // Fallback to environment variables
  options.sentryToken = options.sentryToken || process.env.SENTRY_AUTH_TOKEN;
  options.sentryOrg = options.sentryOrg || process.env.SENTRY_ORG;
  options.githubToken = options.githubToken || process.env.GITHUB_TOKEN;
  options.githubRepo = options.githubRepo || process.env.GITHUB_REPOSITORY;

  if (process.env.SENTRY_PROJECTS) {
    options.sentryProjects?.push(...process.env.SENTRY_PROJECTS.split(',').map((s: string) => s.trim()));
  }
  if (process.env.SENTRY_ANDROID_PROJECT_ID) {
    options.sentryProjects?.push(process.env.SENTRY_ANDROID_PROJECT_ID.trim());
  }
  if (process.env.SENTRY_BACKEND_PROJECT_ID) {
    options.sentryProjects?.push(process.env.SENTRY_BACKEND_PROJECT_ID.trim());
  }

  // Deduplicate projects
  if (options.sentryProjects) {
    options.sentryProjects = Array.from(new Set(options.sentryProjects.filter(Boolean)));
  }

  return options;
}

export async function runSync(options: SyncOptions): Promise<TriageDecision[]> {
  const {
    dryRun = false,
    sentryToken,
    sentryOrg,
    sentryProjects = [],
    githubToken,
    githubRepo,
    lookbackHours = 24
  } = options;

  if (!sentryToken || !sentryOrg || sentryProjects.length === 0) {
    throw new Error('Missing Sentry configuration (token, org, or project(s))');
  }

  if (!dryRun && (!githubToken || !githubRepo)) {
    throw new Error('Missing GitHub configuration (token or repository) for non-dry-run mode');
  }

  const sentryClient = new SentryClient({
    authToken: sentryToken,
    org: sentryOrg
  });

  const allSentryIssues: SentryIssue[] = [];
  for (const project of sentryProjects) {
    try {
      const issues = await sentryClient.fetchProjectIssuesWithDetails(project, 'is:unresolved', lookbackHours);
      allSentryIssues.push(...issues);
    } catch (err: any) {
      console.error(`Warning: Failed to fetch issues for project ${project}: ${err?.message || err}`);
    }
  }

  let existingGitHubIssues: any[] = [];
  let githubClient: GitHubClient | null = null;

  if (githubToken && githubRepo) {
    githubClient = new GitHubClient({
      token: githubToken,
      repo: githubRepo
    });
    try {
      existingGitHubIssues = await githubClient.fetchIssues('all');
    } catch (err: any) {
      console.error(`Warning: Failed to fetch GitHub issues: ${err?.message || err}`);
    }
  }

  const decisions = triageSentryIssues(allSentryIssues, existingGitHubIssues);

  console.log(`\n--- Sentry-To-GitHub Triage Report (${decisions.length} decisions) ---`);
  if (dryRun) {
    console.log('[DRY-RUN MODE] No GitHub mutations will be executed.\n');
  }

  for (const decision of decisions) {
    console.log(`[${decision.action.toUpperCase()}] ${decision.issueTitle}`);
    console.log(`  Category: ${decision.category}`);
    console.log(`  Reason: ${decision.reason}`);
    console.log(`  Fingerprint: ${decision.fingerprint}`);

    if (decision.action === 'create_issue') {
      if (dryRun) {
        console.log(`  Preview Issue Body:\n${decision.issueBody}\n`);
      } else if (githubClient) {
        const created = await githubClient.createIssue({
          title: decision.issueTitle,
          body: decision.issueBody || '',
          ...(decision.labels ? { labels: decision.labels } : {})
        });
        console.log(`  -> Created GitHub Issue #${created.number} (${created.html_url})`);
      }
    } else if (decision.action === 'comment') {
      if (dryRun) {
        console.log(`  Target Issue: #${decision.targetGitHubIssueNumber}`);
        console.log(`  Preview Comment:\n${decision.commentBody}\n`);
      } else if (githubClient && decision.targetGitHubIssueNumber) {
        await githubClient.createComment(decision.targetGitHubIssueNumber, decision.commentBody || '');
        console.log(`  -> Added comment to Issue #${decision.targetGitHubIssueNumber}`);
      }
    } else if (decision.action === 'reopen_and_comment') {
      if (dryRun) {
        console.log(`  Target Issue (Reopen): #${decision.targetGitHubIssueNumber}`);
        console.log(`  Preview Reopen Comment:\n${decision.commentBody}\n`);
      } else if (githubClient && decision.targetGitHubIssueNumber) {
        await githubClient.reopenIssue(decision.targetGitHubIssueNumber);
        await githubClient.createComment(decision.targetGitHubIssueNumber, decision.commentBody || '');
        console.log(`  -> Reopened and commented on Issue #${decision.targetGitHubIssueNumber}`);
      }
    } else if (decision.action === 'ignore') {
      console.log(`  -> Ignored: ${decision.reason}`);
    }
  }

  return decisions;
}

// CLI entry point
if (import.meta.url === `file://${process.argv[1]}`) {
  const options = parseArgs(process.argv.slice(2));
  runSync(options)
    .then(() => process.exit(0))
    .catch((err) => {
      console.error(`Sync error: ${err?.message || err}`);
      process.exit(1);
    });
}
