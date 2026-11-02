import { http } from "./http";

/**
 * Mirrors `EventStreamService.View`. `visibility` is FR-3.3's switch: `internal` keeps the stream out of the inbox,
 * `virtual_feed` backfills it in; `feedUrlBase` is the configured public origin, used only to print event permalinks.
 */
export interface EventStream {
  id: number;
  slug: string;
  title: string;
  description?: string;
  visibility: string;
  itemCount: number;
  feedUrlBase?: string;
}

export const VISIBILITIES = ["internal", "virtual_feed"] as const;
export type Visibility = (typeof VISIBILITIES)[number];

/** Mirrors `EventStreamController` — slug-keyed, because the slug is what appears in a public URL. */
export const eventsApi = {
  list: () => http.get<EventStream[]>("/api/events"),
  get: (slug: string) => http.get<EventStream>(`/api/events/${encodeURIComponent(slug)}`),
  create: (body: { slug: string; title: string; description?: string }) => http.post<EventStream>("/api/events", body),
  update: (slug: string, body: { title?: string; description?: string }) =>
    http.patch<EventStream>(`/api/events/${encodeURIComponent(slug)}`, body),
  setVisibility: (slug: string, visibility: Visibility) =>
    http.patch<EventStream>(`/api/events/${encodeURIComponent(slug)}/visibility`, { visibility }),
  remove: (slug: string) => http.del<void>(`/api/events/${encodeURIComponent(slug)}`),
};

/** Mirrors `ApiTokenService.Token`: enough to recognise and revoke a credential, never enough to use one. */
export interface ApiToken {
  id: number;
  name: string;
  scope: string;
  createdAt?: string;
  lastUsedAt?: string;
}

/** Mirrors `TokenController.CreatedView` — `secret` exists exactly once, in the response to POST. */
export interface CreatedToken {
  id: number;
  name: string;
  scope: string;
  secret: string;
}

/** Mirrors `ApiTokenService.SCOPES`. `ingest` is the only one a CI job needs. */
export const TOKEN_SCOPES = ["ingest", "read", "admin"] as const;
export type TokenScope = (typeof TOKEN_SCOPES)[number];

export const tokensApi = {
  list: () => http.get<ApiToken[]>("/api/tokens"),
  create: (body: { name: string; scope: TokenScope }) => http.post<CreatedToken>("/api/tokens", body),
  revoke: (id: number) => http.del<void>(`/api/tokens/${id}`),
};

/**
 * The machine-facing call this page documents. It is authenticated by a bearer token and carries no cookie, so it is
 * outside CSRF and outside this client — the SPA never calls it, it only shows the operator how to point at it.
 */
export const INGEST_PATH = "/api/ingest/events";
