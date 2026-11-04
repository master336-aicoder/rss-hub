import { http } from "./http";

/**
 * Mirrors `com.rsshub.feedstore.FeedRepository.AdminFeed`. Fields the backend can leave null are optional here,
 * because `spring.jackson.default-property-inclusion: non_null` drops the key rather than sending `null`.
 */
export interface Feed {
  id: number;
  publicId: string;
  canonicalUrl: string;
  requestedUrl: string;
  selfUrl?: string;
  siteUrl?: string;
  title?: string;
  /** The operator's own label; wins over `title` for display and is the only title a rename writes. */
  customTitle?: string;
  description?: string;
  language?: string;
  format?: string;
  groupName?: string;
  state: string;
  failCount: number;
  notfoundStreak: number;
  lastHttpStatus?: number;
  lastError?: string;
  lastFetchedAt?: string;
  lastChangeAt?: string;
  avgFetchMs?: number;
  ttlMinutes?: number;
  /** The operator's override of the cadence; absent means "follow the site, or this install's default". */
  fetchIntervalMinutes?: number;
  createdAt?: string;
  nextFetchAt?: string;
  unreadCount: number;
  entryCount: number;
}

/** Mirrors `com.rsshub.feedfetch.FeedDiscoverer.Candidate`. */
export interface DiscoveryCandidate {
  url: string;
  type: string;
  title?: string;
}

/** Mirrors `FeedDiscoverer.Discovery`: `feedUrl` is absent when nothing was found, `note` says why. */
export interface Discovery {
  requestedUrl: string;
  resolvedUrl: string;
  feedUrl?: string;
  candidates: DiscoveryCandidate[];
  note?: string;
}

/** The states the PATCH endpoint accepts; `error` and `gone` are the fetcher's, not the operator's. */
export const OPERATOR_STATES = ["active", "muted", "paused"] as const;
export type OperatorState = (typeof OPERATOR_STATES)[number];

/**
 * What a subscription will accept, and nothing else — the backend refuses any other key, so the address cannot be
 * quietly dropped by an edit that reported success. Two of these fields use a value to mean "stop overriding":
 * an empty `title` falls back to the site's own title, and `intervalMinutes: 0` falls back to the site's TTL.
 */
export interface FeedEdit {
  title?: string;
  group?: string;
  state?: string;
  intervalMinutes?: number;
}

/** Mirrors `FeedController.PolicyView` — the cadence this install falls back to, for the hint next to the field. */
export interface FetchPolicy {
  defaultIntervalMinutes: number;
  minIntervalMinutes: number;
}

/**
 * What one pasted line became. Mirrors `SubscriptionService.PlanItem`.
 *
 * The four read-only statuses come from the plan endpoint and the fifth (`added`) only from the write, because a line
 * can stop being ready between the two calls — a duplicate that appeared in between is reported, not an error.
 * `reason` is the problem code (`already_subscribed`, `private_target:10.0.0.8`, `scheme_not_allowed`), which is what
 * an operator pastes into an issue; the text is deliberately the backend's, not a translation of it.
 */
export interface BatchPlanItem {
  lineNo: number;
  /** The name typed in front of the address; becomes the subscription's own title. */
  label?: string;
  /** Exactly what the splitter read, before the `https://` defaulting — so a misread line is visible as typed. */
  address: string;
  /** Absent when the line never became an address. */
  url?: string;
  status: "ready" | "duplicate" | "refused" | "invalid" | "added";
  reason?: string;
}

/** The preview: what the paste would do, and how many rows the write would create. */
export interface BatchPlan {
  items: BatchPlanItem[];
  ready: number;
}

/** The write: the same lines, each now carrying the verdict that was actually applied. */
export interface BatchResult {
  added: number;
  items: BatchPlanItem[];
}

/**
 * The name the list shows. The server resolves the same precedence in SQL for its own ordering, but it sends the
 * two titles separately so the edit box can show the site's title and the override apart.
 */
export function feedLabel(feed: Feed): string {
  return feed.customTitle || feed.title || feed.canonicalUrl;
}

export const feedsApi = {
  list: () => http.get<Feed[]>("/api/feeds"),
  get: (id: number) => http.get<Feed>(`/api/feeds/${id}`),
  policy: () => http.get<FetchPolicy>("/api/feeds/policy"),
  groups: () => http.get<string[]>("/api/feeds/groups"),
  discover: (url: string) => http.post<Discovery>("/api/feeds/discover", { url }),
  add: (url: string, group?: string) => http.post<Feed>("/api/feeds", { url, group }),
  /** Reads a paste and reports every line. Sends no group: the preview decides nothing about where the rows land. */
  batchPlan: (text: string) => http.post<BatchPlan>("/api/feeds/batch/plan", { text }),
  /** The write re-splits the text itself, so a preview cannot be turned into an instruction it never checked. */
  batchAdd: (text: string, group?: string) => http.post<BatchResult>("/api/feeds/batch", { text, group }),
  update: (id: number, body: FeedEdit) => http.patch<Feed>(`/api/feeds/${id}`, body),
  remove: (id: number) => http.del<void>(`/api/feeds/${id}`),
  refresh: (id: number) => http.post<{ queued: boolean; id: number }>(`/api/feeds/${id}/refresh`),
  markRead: (id: number) => http.post<{ marked: number }>(`/api/feeds/${id}/read`),
};
