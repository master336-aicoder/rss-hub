import { http } from "./http";

/** Mirrors `com.rsshub.adminapi.EntryController.EntryView` — a list row, so it carries no body HTML. */
export interface EntryListItem {
  id: number;
  feedId: number;
  feedTitle: string;
  groupName?: string;
  url?: string;
  title?: string;
  summary?: string;
  authors: string[];
  categories: string[];
  publishedAt?: string;
  updatedAtSrc?: string;
  fetchedAt?: string;
  readState: string;
  starred: boolean;
  archived: boolean;
  baseline: boolean;
  revision: number;
  /**
   * FR-5.3's inbox mark: the names of the rules that already fired about this entry. The backend always sends the
   * list (`EntryController.EntryView.alertedBy`), empty when nothing hit; it can hold several because rules overlap.
   * It is a list-row field — the reader pane's `EntryViewFull` has no equivalent.
   */
  alertedBy?: string[];
}

export interface Author {
  name?: string;
  email?: string;
  uri?: string;
}

export interface Enclosure {
  url: string;
  mimeType?: string;
  sizeBytes?: number;
  durationSeconds?: number;
}

export interface Revision {
  revision: number;
  title?: string;
  contentHash?: string;
  updatedAtSrc?: string;
  recordedAt?: string;
}

/** Mirrors `EntryController.EntryViewFull`. */
export interface EntryDetail {
  id: number;
  feedId: number;
  feedTitle: string;
  feedSiteUrl?: string;
  url?: string;
  title?: string;
  summary?: string;
  contentHtml?: string;
  authors: Author[];
  categories: string[];
  enclosures: Enclosure[];
  publishedAt?: string;
  updatedAtSrc?: string;
  fetchedAt?: string;
  readState: string;
  starred: boolean;
  archived: boolean;
  revision: number;
  lang?: string;
  guidSource?: string;
  stableId: boolean;
  revisions: Revision[];
}

export interface Counters {
  total: number;
  unread: number;
  starred: number;
  archived: number;
}

export interface EntryPage {
  items: EntryListItem[];
  nextCursor?: string;
  counters: Counters;
}

/** The inbox tabs (FR-2.4). `all` means "not archived", which is what an operator expects from the word. */
export const ENTRY_STATES = ["all", "unread", "read", "dismissed", "starred", "archived"] as const;
export type EntryState = (typeof ENTRY_STATES)[number];

export interface EntryQuery {
  state: EntryState;
  feed?: number;
  group?: string;
  q?: string;
  /** FR-2.4's tag filter — one whole category, matched server-side against the stored JSON array. */
  category?: string;
  /** FR-2.4's "only what the rules fired on". `undefined` asks for no narrowing; the backend has a third value. */
  alerted?: boolean;
  cursor?: string;
  limit?: number;
}

/** Builds the query string; the backend owns the cursor's meaning, so the UI only ever resends one it received. */
function toQuery(query: EntryQuery): string {
  const params = new URLSearchParams({ state: query.state });
  if (query.feed !== undefined) params.set("feed", String(query.feed));
  if (query.group) params.set("group", query.group);
  if (query.q) params.set("q", query.q);
  if (query.category) params.set("category", query.category);
  if (query.alerted !== undefined) params.set("alerted", String(query.alerted));
  if (query.cursor) params.set("cursor", query.cursor);
  if (query.limit !== undefined) params.set("limit", String(query.limit));
  return params.toString();
}

export interface StateChange {
  ids: number[];
  readState?: "unread" | "read" | "dismissed";
  starred?: boolean;
  archived?: boolean;
}

export const entriesApi = {
  list: (query: EntryQuery, signal?: AbortSignal) =>
    http.get<EntryPage>(`/api/entries?${toQuery(query)}`, signal),
  counters: () => http.get<Counters>("/api/entries/counters"),
  detail: (id: number, signal?: AbortSignal) => http.get<EntryDetail>(`/api/entries/${id}`, signal),
  setState: (change: StateChange) => http.post<{ applied: number }>("/api/entries/state", change),
};
