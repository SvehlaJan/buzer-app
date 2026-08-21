import { GitHubIssue, GitHubComment } from './types.js';
import { redactString } from './redact.js';

export interface GitHubClientOptions {
  token: string;
  repo: string; // "owner/repo"
  baseUrl?: string;
  fetchFn?: typeof fetch;
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

  private async request<T>(path: string, options: RequestInit = {}): Promise<T> {
    const url = `${this.baseUrl}${path.startsWith('/') ? path : `/${path}`}`;
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

      return (await response.json()) as T;
    } catch (err: any) {
      throw new Error(redactString(`Failed GitHub request: ${err?.message || String(err)}`));
    }
  }

  async fetchIssues(state: 'open' | 'closed' | 'all' = 'all'): Promise<GitHubIssue[]> {
    const path = `/repos/${this.repo}/issues?state=${encodeURIComponent(state)}&per_page=100`;
    const issues = await this.request<GitHubIssue[]>(path);
    return issues;
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
