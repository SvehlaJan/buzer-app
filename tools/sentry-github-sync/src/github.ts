import { GitHubIssue, GitHubComment } from './types.js';
import { redactString } from './redact.js';

export interface GitHubClientOptions {
  token: string;
  repo: string; // "owner/repo"
  baseUrl?: string;
  fetchFn?: typeof fetch;
}

export function parseGitHubLinkHeader(linkHeader: string | null | undefined): { nextUrl?: string } {
  if (!linkHeader) return {};
  const parts = linkHeader.split(',');
  for (const part of parts) {
    if (/rel="?next"?/i.test(part)) {
      const match = part.match(/<([^>]+)>/);
      if (match && match[1]) {
        return { nextUrl: match[1] };
      }
    }
  }
  return {};
}

export class GitHubClient {
  private readonly token: string;
  private readonly repo: string;
  private readonly baseUrl: string;
  private readonly fetchFn: typeof fetch;

  constructor(options: GitHubClientOptions) {
    this.token = options.token;
    this.repo = options.repo;
    this.baseUrl = options.baseUrl?.replace(/\/$/, '') || 'https://api.github.com';
    this.fetchFn = options.fetchFn || fetch;
  }

  private async rawRequest(pathOrUrl: string, options: RequestInit = {}): Promise<Response> {
    const url = pathOrUrl.startsWith('http://') || pathOrUrl.startsWith('https://')
      ? pathOrUrl
      : `${this.baseUrl}${pathOrUrl.startsWith('/') ? pathOrUrl : `/${pathOrUrl}`}`;
    try {
      const response = await this.fetchFn(url, {
        ...options,
        headers: {
          Authorization: `Bearer ${this.token}`,
          Accept: 'application/vnd.github.v3+json',
          'User-Agent': 'sentry-github-sync/0.1.0',
          'Content-Type': 'application/json',
          ...(options.headers || {})
        }
      });

      if (!response.ok) {
        const errorText = await response.text().catch(() => '');
        throw new Error(`GitHub API error ${response.status} ${response.statusText}: ${errorText}`);
      }

      return response;
    } catch (err: any) {
      throw new Error(redactString(`Failed GitHub request: ${err?.message || String(err)}`));
    }
  }

  private async request<T>(path: string, options: RequestInit = {}): Promise<T> {
    const response = await this.rawRequest(path, options);
    return (await response.json()) as T;
  }

  async fetchIssues(state: 'open' | 'closed' | 'all' = 'all', maxPages = 5): Promise<GitHubIssue[]> {
    const allIssues: GitHubIssue[] = [];
    let nextUrl: string | undefined = `/repos/${this.repo}/issues?state=${encodeURIComponent(state)}&per_page=100`;
    let page = 0;

    while (nextUrl && page < maxPages) {
      page++;
      const response = await this.rawRequest(nextUrl);
      const items = (await response.json()) as (GitHubIssue & { pull_request?: unknown })[];

      if (!Array.isArray(items) || items.length === 0) {
        break;
      }

      // Filter out pull requests
      const issuesOnly = items.filter((item) => !item.pull_request);
      allIssues.push(...issuesOnly);

      const linkHeader = response.headers?.get('link');
      const { nextUrl: linkNextUrl } = parseGitHubLinkHeader(linkHeader);
      if (linkNextUrl) {
        nextUrl = linkNextUrl;
      } else if (items.length === 100 && page < maxPages) {
        nextUrl = `/repos/${this.repo}/issues?state=${encodeURIComponent(state)}&per_page=100&page=${page + 1}`;
      } else {
        nextUrl = undefined;
      }
    }

    return allIssues;
  }

  async createIssue(params: { title: string; body: string; labels?: string[] }): Promise<GitHubIssue> {
    const path = `/repos/${this.repo}/issues`;
    return await this.request<GitHubIssue>(path, {
      method: 'POST',
      body: JSON.stringify({
        title: redactString(params.title),
        body: redactString(params.body),
        labels: params.labels
      })
    });
  }

  async createComment(issueNumber: number, body: string): Promise<GitHubComment> {
    const path = `/repos/${this.repo}/issues/${issueNumber}/comments`;
    return await this.request<GitHubComment>(path, {
      method: 'POST',
      body: JSON.stringify({
        body: redactString(body)
      })
    });
  }

  async reopenIssue(issueNumber: number): Promise<GitHubIssue> {
    const path = `/repos/${this.repo}/issues/${issueNumber}`;
    return await this.request<GitHubIssue>(path, {
      method: 'PATCH',
      body: JSON.stringify({
        state: 'open'
      })
    });
  }

  async ensureLabel(name: string, color = 'd73a4a', description = ''): Promise<void> {
    const path = `/repos/${this.repo}/labels`;
    try {
      await this.request(path, {
        method: 'POST',
        body: JSON.stringify({ name, color, description })
      });
    } catch {
      // Ignore if label already exists (422) or token lacks label permissions
    }
  }
}
