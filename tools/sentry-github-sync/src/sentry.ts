import { SentryIssue, SentryEvent, SentryAttachment } from './types.js';
import { redactString } from './redact.js';

export interface SentryClientOptions {
  authToken: string;
  org: string;
  baseUrl?: string;
  fetchFn?: typeof fetch;
}

export function parseSentryLinkHeader(linkHeader: string | null | undefined): { nextUrl?: string; hasMore: boolean } {
  if (!linkHeader) return { hasMore: false };
  // Sentry Link header format:
  // <url>; rel="previous"; results="false"; cursor="...", <url>; rel="next"; results="true"; cursor="..."
  const parts = linkHeader.split(',');
  for (const part of parts) {
    const isNext = /rel="?next"?/i.test(part);
    const hasResults = /results="?true"?/i.test(part);
    if (isNext && hasResults) {
      const match = part.match(/<([^>]+)>/);
      if (match && match[1]) {
        return { nextUrl: match[1], hasMore: true };
      }
    }
  }
  return { hasMore: false };
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

  private async rawRequest(pathOrUrl: string): Promise<Response> {
    const url = pathOrUrl.startsWith('http://') || pathOrUrl.startsWith('https://')
      ? pathOrUrl
      : `${this.baseUrl}${pathOrUrl.startsWith('/') ? pathOrUrl : `/${pathOrUrl}`}`;
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
        throw new Error(`Sentry API error: ${response.status} ${response.statusText} at ${pathOrUrl}`);
      }

      return response;
    } catch (err: any) {
      throw new Error(redactString(`Failed Sentry request: ${err?.message || String(err)}`));
    }
  }

  private async request<T>(path: string): Promise<T> {
    const response = await this.rawRequest(path);
    return (await response.json()) as T;
  }

  async fetchIssues(
    projectSlug: string,
    query = 'is:unresolved',
    lookbackHours?: number,
    maxPages = 5
  ): Promise<SentryIssue[]> {
    let queryParam = query;
    if (lookbackHours && lookbackHours > 0) {
      queryParam = `${query} last_seen:-${lookbackHours}h`;
    }
    let nextUrl: string | undefined = `/api/0/projects/${encodeURIComponent(this.org)}/${encodeURIComponent(projectSlug)}/issues/?query=${encodeURIComponent(queryParam)}`;
    const allIssues: SentryIssue[] = [];
    let page = 0;

    while (nextUrl && page < maxPages) {
      page++;
      const response = await this.rawRequest(nextUrl);
      const issues = (await response.json()) as SentryIssue[];

      if (!Array.isArray(issues) || issues.length === 0) {
        break;
      }

      allIssues.push(...issues);

      const linkHeader = response.headers?.get('link');
      const { hasMore, nextUrl: linkNextUrl } = parseSentryLinkHeader(linkHeader);
      if (hasMore && linkNextUrl) {
        nextUrl = linkNextUrl;
      } else {
        nextUrl = undefined;
      }
    }

    return allIssues;
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
