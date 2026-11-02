import { http } from "./http";

/**
 * Mirrors `com.rsshub.rules.MatchSpec`. Every condition is optional and an absent one means "not used" — the empty
 * spec is refused on save (`match_too_broad`), because a rule that alerts on the whole inbox gets switched off.
 */
export interface RuleMatch {
  includeAny?: string[];
  includeAll?: string[];
  excludeAny?: string[];
  regex?: string[];
  wholeWord?: boolean;
  caseSensitive?: boolean;
  feedIds?: number[];
  groups?: string[];
  tags?: string[];
  windowMinutes?: number;
}

/** `Action.CHANNELS`. `markOnly` needs no target: the hit is the record, nothing leaves the box. */
export const RULE_CHANNELS = ["webhook", "dingtalk", "wecom", "markOnly"] as const;
export type RuleChannel = (typeof RULE_CHANNELS)[number];

/** Mirrors `com.rsshub.rules.Action`. `secretRef` names a key of `rsshub.notify.secrets`; the value never travels. */
export interface RuleAction {
  channel: RuleChannel;
  target?: string;
  secretRef?: string;
}

/** Mirrors `com.rsshub.rules.Suppression`. `hours: 0` means "do not suppress", not "suppress forever". */
export const SUPPRESSION_KEYS = ["perFeed", "perContentHash", "perTitleNorm"] as const;
export type SuppressionKey = (typeof SUPPRESSION_KEYS)[number];

export interface RuleSuppression {
  key?: SuppressionKey;
  hours?: number;
}

/** Mirrors `com.rsshub.rules.QuietHours`: all three fields or none, so the editor sends `{}` to take a window off. */
export interface RuleQuietHours {
  from?: string;
  to?: string;
  tz?: string;
}

/**
 * Mirrors `RuleService.View`. The four rule columns arrive already decoded, so no JSON.parse in the UI. Fields the
 * backend can leave null are optional here (`non_null` inclusion drops the key): `quietHours`, `disabledReason`,
 * and — on a rule whose columns predate a migration — the columns themselves.
 */
export interface Rule {
  id: number;
  name: string;
  enabled: boolean;
  match: RuleMatch;
  actions: RuleAction[];
  suppression?: RuleSuppression;
  quietHours?: RuleQuietHours;
  hitCount: number;
  disabledReason?: string;
  createdAt?: string;
  updatedAt?: string;
}

/** Mirrors `RuleService.Hit` — note `keywords`, not the webhook body's `matchedKeywords`. */
export interface RuleHit {
  entryId: number;
  title?: string;
  feedTitle?: string;
  publishedAt?: string;
  keywords: string[];
}

/** Mirrors `RuleService.Preview`: what the last entries would have done, with nothing written or sent. */
export interface RulePreview {
  scanned: number;
  matched: number;
  hits: RuleHit[];
}

/**
 * Mirrors `RuleService.Request`.
 *
 * `enabled` is only ever sent on create. A PATCH that carries it changes the flag through the ordinary save path,
 * and FR-5.3 wants re-arming a breaker-stopped rule to be a decision on its own route (`PATCH /{id}/enabled`),
 * so the editor omits the field on update even when the checkbox is visible.
 */
export interface RuleDraft {
  name: string;
  match: RuleMatch;
  actions: RuleAction[];
  suppression?: RuleSuppression;
  quietHours?: RuleQuietHours | Record<string, never>;
  enabled?: boolean;
}

export const rulesApi = {
  list: () => http.get<Rule[]>("/api/rules"),
  get: (id: number) => http.get<Rule>(`/api/rules/${id}`),
  create: (draft: RuleDraft) => http.post<Rule>("/api/rules", draft),
  update: (id: number, draft: RuleDraft) => http.patch<Rule>(`/api/rules/${id}`, draft),
  setEnabled: (id: number, enabled: boolean) => http.patch<Rule>(`/api/rules/${id}/enabled`, { enabled }),
  remove: (id: number) => http.del<void>(`/api/rules/${id}`),
  /** FR-5.4's test button. `limit` is the sample size, capped at 200 by the backend. */
  test: (id: number, limit = 200) => http.post<RulePreview>(`/api/rules/${id}/test?limit=${limit}`),
};

/** The DDL default (03 §8.2), which is also what a rule with no suppression column behaves like. */
export const DEFAULT_SUPPRESSION: RuleSuppression = { key: "perFeed", hours: 6 };
