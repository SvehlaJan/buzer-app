import { SentryIssue, SentryEvent, SentryAttachment } from './types.js';
import { redactString } from './redact.js';

export interface SentryClientOptions {
  authToken: string;
  org: string;
  baseUrl?: string;
  fetchFn?: typeof fetch;
}

export class SentryClient {
  private readonly authToken: string;
  private readonly org: string;
  private readonly baseUrl: string;
  private readonly fetchFn: typeof fetch;

  constructor(options: SentryClientOptions) {
    this.authToken = options.authToken;
    this.org = options.org;
    this.baseUrl = options.baseUrl?.replace(/\/$/, '') || 'https://sentry.io';
    this.fetchFn = options.fetchFn || fetch;
  }

  private async request<T>(path: string): Promise<T> {
    const url = `${this.baseUrl}${path.startsWith('/') ? path : `/${path}`}`;
    try {
      const response = await this.fetchFn(url, {
        method: 'GET',
        headers: {
          Authorization: `Bearer ${this.authToken}`,
          Accept: 'application/json',
          'User-Agent': 'sentry-github-sync/0.1.0'
        }
      });

      if (!response.ok) {
        throw new Error(`Sentry API error: ${response.status} ${response.statusText} at ${path}`);
      }

      return (await response.json()) as T;
    } catch (err: any) {
      throw new Error(redactString(`Failed Sentry request: ${err?.message || String(err)}`));
    }
  }

  async fetchIssues(projectSlug: string, query = 'is:unresolved', lookbackHours?: number): Promise<SentryIssue[]> {
    let queryParam = query;
    if (lookbackHours && lookbackHours > 0) {
      queryParam = `${query} age:-${lookbackHours}h`;
    }
    const path = `/api/0/projects/${encodeURIComponent(this.org)}/${encodeURIComponent(projectSlug)}/issues/?query=${encodeURIComponent(queryParam)}`;
    const issues = await this.request<SentryIssue[]>(path);
    return issues;
  }

  async fetchLatestEvent(issueId: string): Promise<SentryEvent | null> {
    try {
      const path = `/api/0/issues/${encodeURIComponent(issueId)}/events/latest/`;
      return await this.request<SentryEvent>(path);
    } catch (err) {
      return null;
    }
  }

  async fetchAttachments(projectSlug: string, eventId: string): Promise<SentryAttachment[]> {
    try {
      const path = `/api/0/projects/${encodeURIComponent(this.org)}/${encodeURIComponent(projectSlug)}/events/${encodeURIComponent(eventId)}/attachments/`;
      return await this.request<SentryAttachment[]>(path);
    } catch (err) {
      return [];
    }
  }

  async fetchProjectIssuesWithDetails(
    projectSlug: string,
    query = 'is:unresolved',
    lookbackHours?: number
  ): Promise<SentryIssue[]> {
    const issues = await this.fetchIssues(projectSlug, query, lookbackHours);
    const results: SentryIssue[] = [];

    for (const issue of issues) {
      let latestEvent: SentryEvent | null = null;
      try {
        latestEvent = await this.fetchLatestEvent(issue.id);
        if (latestEvent) {
          const attachments = await this.fetchAttachments(projectSlug, latestEvent.id || (latestEvent as any).eventID);
          latestEvent.attachments = attachments;
        }
      } catch {
        // Fall back to issue metadata if event retrieval fails
      }

      results.push({
        ...issue,
        project: issue.project || { id: projectSlug, slug: projectSlug, name: projectSlug },
        latestEvent: latestEvent || issue.latestEvent
      });
    }

    return results;
  }
}
