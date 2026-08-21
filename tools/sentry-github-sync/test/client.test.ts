import { describe, it, expect, vi } from 'vitest';
import { GitHubClient, parseGitHubLinkHeader } from '../src/github.js';
import { SentryClient, parseSentryLinkHeader } from '../src/sentry.js';

describe('GitHubClient', () => {
  it('parses GitHub link headers correctly', () => {
    const header = '<https://api.github.com/repositories/1/issues?page=2>; rel="next", <https://api.github.com/repositories/1/issues?page=5>; rel="last"';
    const parsed = parseGitHubLinkHeader(header);
    expect(parsed.nextUrl).toBe('https://api.github.com/repositories/1/issues?page=2');

    const empty = parseGitHubLinkHeader(null);
    expect(empty.nextUrl).toBeUndefined();
  });

  it('filters out pull requests from fetchIssues', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      headers: new Headers(),
      json: async () => [
        { number: 1, title: 'Real issue', body: 'Issue body', state: 'open' },
        { number: 2, title: 'Pull request', body: 'PR body', state: 'open', pull_request: { url: 'https://...' } },
        { number: 3, title: 'Another issue', body: 'Issue 3', state: 'closed' }
      ]
    });

    const client = new GitHubClient({
      token: 'gh-token',
      repo: 'test-org/test-repo',
      fetchFn: mockFetch as any
    });

    const issues = await client.fetchIssues('all');
    expect(issues).toHaveLength(2);
    expect(issues.map((i) => i.number)).toEqual([1, 3]);
  });

  it('paginates across multiple pages using Link header', async () => {
    let callCount = 0;
    const mockFetch = vi.fn().mockImplementation(async (url: string) => {
      callCount++;
      if (callCount === 1) {
        return {
          ok: true,
          headers: new Headers({
            link: '<https://api.github.com/repos/test-org/test-repo/issues?page=2>; rel="next"'
          }),
          json: async () => [{ number: 10, title: 'Page 1 issue', state: 'open' }]
        };
      } else {
        return {
          ok: true,
          headers: new Headers(),
          json: async () => [{ number: 20, title: 'Page 2 issue', state: 'open' }]
        };
      }
    });

    const client = new GitHubClient({
      token: 'gh-token',
      repo: 'test-org/test-repo',
      fetchFn: mockFetch as any
    });

    const issues = await client.fetchIssues('all');
    expect(issues).toHaveLength(2);
    expect(issues.map((i) => i.number)).toEqual([10, 20]);
    expect(callCount).toBe(2);
  });
});

describe('SentryClient', () => {
  it('parses Sentry link header correctly', () => {
    const header = '<https://sentry.io/api/0/issues/?cursor=0:0:1>; rel="previous"; results="false", <https://sentry.io/api/0/issues/?cursor=0:100:0>; rel="next"; results="true"';
    const parsed = parseSentryLinkHeader(header);
    expect(parsed.hasMore).toBe(true);
    expect(parsed.nextUrl).toBe('https://sentry.io/api/0/issues/?cursor=0:100:0');

    const noMore = '<https://sentry.io/api/0/issues/?cursor=0:0:1>; rel="next"; results="false"';
    expect(parseSentryLinkHeader(noMore).hasMore).toBe(false);
  });

  it('includes last_seen lookback query parameter', async () => {
    let requestedUrl = '';
    const mockFetch = vi.fn().mockImplementation(async (url: string) => {
      requestedUrl = url;
      return {
        ok: true,
        headers: new Headers(),
        json: async () => []
      };
    });

    const client = new SentryClient({
      authToken: 'sentry-token',
      org: 'buzer-org',
      fetchFn: mockFetch as any
    });

    await client.fetchIssues('buzer-backend', 'is:unresolved', 48);
    expect(requestedUrl).toContain('last_seen%3A-48h');
  });

  it('paginates across Sentry issue pages when results="true"', async () => {
    let callCount = 0;
    const mockFetch = vi.fn().mockImplementation(async (url: string) => {
      callCount++;
      if (callCount === 1) {
        return {
          ok: true,
          headers: new Headers({
            link: '<https://sentry.io/api/0/issues/?cursor=0:100:0>; rel="next"; results="true"'
          }),
          json: async () => [{ id: 'iss-1', title: 'Issue 1' }]
        };
      } else {
        return {
          ok: true,
          headers: new Headers({
            link: '<https://sentry.io/api/0/issues/?cursor=0:200:0>; rel="next"; results="false"'
          }),
          json: async () => [{ id: 'iss-2', title: 'Issue 2' }]
        };
      }
    });

    const client = new SentryClient({
      authToken: 'sentry-token',
      org: 'buzer-org',
      fetchFn: mockFetch as any
    });

    const issues = await client.fetchIssues('buzer-backend');
    expect(issues).toHaveLength(2);
    expect(issues.map((i) => i.id)).toEqual(['iss-1', 'iss-2']);
    expect(callCount).toBe(2);
  });
});
