import { http } from "./http";

/** Mirrors `DashboardController.CardEntryView`; the wall shows titles, not bodies. */
export interface CardEntry {
  id: number;
  title?: string;
  url?: string;
  publishedAt?: string;
  readState: string;
}

/**
 * Mirrors `DashboardController.FeedCardView`. The failure fields are what let the wall mark a source that stopped
 * working without the operator opening the health page; `nextFetchAt` is absent when nothing is queued for it.
 */
export interface FeedCard {
  id: number;
  title: string;
  groupName?: string;
  state: string;
  unread: number;
  failCount: number;
  lastError?: string;
  lastFetchedAt?: string;
  lastChangeAt?: string;
  nextFetchAt?: string;
  entries: CardEntry[];
}

/** Mirrors `StatsView`; every `…Today` number is over the rolling 24 hours, not the calendar day. */
export interface DashboardStats {
  feeds: number;
  entries: number;
  unread: number;
  publishedPosts: number;
  arrivedToday: number;
  hitsToday: number;
  deliveredToday: number;
  failedToday: number;
  problemFeeds: number;
  quietFeeds: number;
}

/** Mirrors `HourBucketView`; the payload always carries 24 of these, oldest first. */
export interface HourBucket {
  hour: string;
  arrivals: number;
  hits: number;
}

/** Mirrors `GroupView`; an empty `name` is the pile of subscriptions nobody filed under a group. */
export interface GroupTile {
  name: string;
  feeds: number;
  unread: number;
  arrivedToday: number;
}

/** Mirrors `AlertView` — one rule hit, and the entry it was about if that row still exists. */
export interface AlertRow {
  id: number;
  ruleName: string;
  state: string;
  channel: string;
  createdAt: string;
  entryId?: number;
  entryTitle?: string;
}

/** Mirrors `PostView` — this site's own published article. */
export interface PostRow {
  id: number;
  slug: string;
  title: string;
  publishedAt?: string;
}

/** Mirrors `ChangeView` — the newest row of the changelog trail. */
export interface ChangeRow {
  id: number;
  title: string;
  url: string;
  action: string;
  changedAt: string;
}

export interface DashboardView {
  entriesPerFeed: number;
  refreshSeconds: number;
  generatedAt: string;
  lastFetchedAt?: string;
  stats: DashboardStats;
  hourly: HourBucket[];
  groups: GroupTile[];
  feeds: FeedCard[];
  alerts: AlertRow[];
  posts: PostRow[];
  changes: ChangeRow[];
}

export const dashboardApi = {
  get: (signal?: AbortSignal) => http.get<DashboardView>("/api/dashboard", signal),
};
