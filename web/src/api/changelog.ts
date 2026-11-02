import { http } from "./http";
import { OUTLET_SUFFIXES, outletUrl } from "./repacks";

/**
 * Mirrors `ChangelogController.Entry`, which is the same list FR-1.6's public outlet publishes. `url` is the post's
 * permalink as it stood at the time, so a deleted post still shows where it used to live.
 */
export interface ChangeEntry {
  id: number;
  publicId: string;
  postId: number;
  slug: string;
  title: string;
  url: string;
  action: ChangeAction;
  fromStatus?: string;
  toStatus?: string;
  changedAt: string;
}

/** The four things that can happen to a post, i.e. `content_change.action`'s CHECK list (V5__changelog_schema.sql). */
export const CHANGE_ACTIONS = ["created", "updated", "status", "deleted"] as const;
export type ChangeAction = (typeof CHANGE_ACTIONS)[number];

export const CHANGE_ACTION_LABELS: Record<ChangeAction, string> = {
  created: "新建",
  updated: "编辑",
  status: "状态",
  deleted: "删除",
};

/**
 * The outlet path 03 §6.2 fixes for FR-1.6. `origin` must be the configured public origin — the one the backend puts
 * inside a post's permalink — because the dev server's own port does not proxy `/feed`.
 */
export const CHANGELOG_OUTLET = "/feed/changelog.rss";

export const changelogApi = {
  list: (params: { postId?: number; limit?: number } = {}) => {
    const query = new URLSearchParams();
    if (params.postId !== undefined) query.set("postId", String(params.postId));
    if (params.limit !== undefined) query.set("limit", String(params.limit));
    const tail = query.toString();
    return http.get<ChangeEntry[]>(`/api/changelog${tail ? `?${tail}` : ""}`);
  },
};

/** One absolute address per format, derived from the `.rss` one — the three are the same document. */
export function changelogOutlets(origin: string): { suffix: string; href: string }[] {
  const self = `${origin}${CHANGELOG_OUTLET}`;
  return OUTLET_SUFFIXES.map((suffix) => ({ suffix, href: outletUrl(self, suffix) }));
}
