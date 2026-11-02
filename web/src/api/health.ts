import { http } from "./http";

/** Mirrors `com.rsshub.adminapi.HealthController.FeedHealth`. */
export interface FeedHealth {
  id: number;
  title?: string;
  canonicalUrl: string;
  groupName?: string;
  format?: string;
  state: string;
  failCount: number;
  notfoundStreak: number;
  lastHttpStatus?: number;
  lastError?: string;
  lastFetchedAt?: string;
  lastChangeAt?: string;
  quietForSeconds?: number;
  avgFetchMs?: number;
  nextFetchAt?: string;
  unreadCount: number;
  entryCount: number;
  problem: boolean;
}

/** Mirrors `HealthController.Summary`. `quiet` is the backend's three-day threshold, documented as an estimate. */
export interface HealthSummary {
  total: number;
  byState: Record<string, number>;
  failing: number;
  quiet: number;
  slowestFetchMs?: number;
}

export interface HealthView {
  feeds: FeedHealth[];
  summary: HealthSummary;
  /** 03 §8.2: how many rules the delivery circuit breaker stopped. Above zero means an alert pipeline went quiet by itself. */
  rulesDisabled: number;
}

export const healthApi = {
  feeds: () => http.get<HealthView>("/api/health/feeds"),
};
