import { http } from "./http";

/** The states `NotificationsController.STATES` accepts as a filter — anything else is a 400, not an empty list. */
export const NOTIFICATION_STATES = ["pending", "sent", "suppressed", "failed", "skipped_quiet"] as const;
export type NotificationState = (typeof NOTIFICATION_STATES)[number];

/**
 * Mirrors `NotificationsController.Entry`.
 *
 * `ruleId` disappears once the rule is deleted (the log's `rule_id` is ON DELETE SET NULL) and `ruleName` becomes
 * `（规则已删除）`, so the row stays readable without a foreign key back to something that no longer exists.
 */
export interface NotificationEntry {
  id: number;
  ruleId?: number;
  ruleName: string;
  entryId?: number;
  channel: string;
  state: string;
  httpStatus?: number;
  error?: string;
  attempts: number;
  createdAt?: string;
}

export interface NotificationQuery {
  state?: NotificationState | "";
  ruleId?: number;
  limit?: number;
}

function toQuery(query: NotificationQuery): string {
  const params = new URLSearchParams();
  if (query.state) params.set("state", query.state);
  if (query.ruleId !== undefined) params.set("ruleId", String(query.ruleId));
  if (query.limit !== undefined) params.set("limit", String(query.limit));
  return params.toString();
}

export const notificationsApi = {
  list: (query: NotificationQuery = {}, signal?: AbortSignal) =>
    http.get<NotificationEntry[]>(`/api/notifications?${toQuery(query)}`, signal),
  /** Re-sends the envelope frozen at hit time. 03 §8.3: only `failed` rows are replayable, everything else is `not_replayable`. */
  replay: (id: number) => http.post<NotificationEntry>(`/api/notifications/${id}/replay`),
};

/** 03 §8.3's ladder: five attempts, then the row stays `failed` until the operator replays it by hand. */
export const MAX_ATTEMPTS = 5;
