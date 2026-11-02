import { http } from "./http";

/**
 * Mirrors `RepackService.Summary`. `publicUrl` is absolute on purpose: a forwarding feed is the one outlet you hand
 * to somebody else, so the address has to be usable outside this app.
 */
export interface RepackSummary {
  id: number;
  slug: string;
  title: string;
  description?: string;
  memberCount: number;
  publicUrl: string;
}

/** Mirrors `RepackService.MemberView` — one subscribed feed inside a collection, with its own full-content flag. */
export interface RepackMember {
  feedId: number;
  title?: string;
  canonicalUrl: string;
  groupName?: string;
  state: string;
  fullContent: boolean;
  fullContentChangedAt?: string;
  entryCount: number;
}

/** Mirrors `RepackService.Detail`. Every member write returns this, so the screen never re-reads after a PATCH. */
export interface RepackDetail {
  id: number;
  slug: string;
  title: string;
  description?: string;
  publicUrl: string;
  members: RepackMember[];
}

/** Mirrors `RepackService.AuditView`: FR-7.2's trail, and these rows outlive the member they describe. */
export interface RepackAuditRow {
  id: number;
  feedId: number;
  action: string;
  fullContent: boolean;
  createdAt?: string;
}

export const repacksApi = {
  list: () => http.get<RepackSummary[]>("/api/repacks"),
  get: (slug: string) => http.get<RepackDetail>(`/api/repacks/${encodeURIComponent(slug)}`),
  create: (body: { slug: string; title: string; description?: string }) =>
    http.post<RepackDetail>("/api/repacks", body),
  update: (slug: string, body: { title?: string; description?: string }) =>
    http.patch<RepackDetail>(`/api/repacks/${encodeURIComponent(slug)}`, body),
  remove: (slug: string) => http.del<void>(`/api/repacks/${encodeURIComponent(slug)}`),
  audit: (slug: string) => http.get<RepackAuditRow[]>(`/api/repacks/${encodeURIComponent(slug)}/audit`),
  addSource: (slug: string, feedId: number) =>
    http.post<RepackDetail>(`/api/repacks/${encodeURIComponent(slug)}/feeds`, { feedId }),
  setFullContent: (slug: string, feedId: number, fullContent: boolean) =>
    http.patch<RepackDetail>(`/api/repacks/${encodeURIComponent(slug)}/feeds/${feedId}`, { fullContent }),
  removeSource: (slug: string, feedId: number) =>
    http.del<RepackDetail>(`/api/repacks/${encodeURIComponent(slug)}/feeds/${feedId}`),
};

/** The suffixes 03 §6.2 gives every scope: three documents, one path stem. */
export const OUTLET_SUFFIXES = ["rss", "atom", "json"] as const;
export type OutletSuffix = (typeof OUTLET_SUFFIXES)[number];

/** The `.rss` address the backend returns is the same outlet as its other two formats, so the rest are derived. */
export function outletUrl(publicUrl: string, suffix: OutletSuffix): string {
  return publicUrl.replace(/\.rss$/, `.${suffix}`);
}
