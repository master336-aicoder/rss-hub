import { http } from "./http";

/**
 * Mirrors `com.rsshub.editorial.PostService.PostView`. `url` is derived server-side from the base URL and the slug;
 * the SPA does not concatenate its own host into it. Optional fields fall out of the payload when Jackson's
 * `non_null` inclusion drops them, so the interface mirrors that reality.
 */
export interface Post {
  id: number;
  slug: string;
  title: string;
  summaryMd?: string;
  bodyMd: string;
  bodyHtml: string;
  status: PostStatus;
  lang?: string;
  publishedAt?: string;
  updatedAt?: string;
  url: string;
  tags: string[];
  categories: string[];
}

/** The five states `post.status` CHECK allows (V2__publishing_schema.sql); the transitions between them are the
 * server's, not the client's, so an editor that sends an illegal one gets a 409, not a silent rewrite. */
export const POST_STATUSES = ["draft", "scheduled", "published", "unlisted", "archived"] as const;
export type PostStatus = (typeof POST_STATUSES)[number];

export interface Term {
  id: number;
  slug: string;
  title: string;
}

/** The body shape `PostController.CreateRequest` accepts. `slug` is only read at create; the editor sends it
 * back on update and the server ignores it, which is why the permanent URL never moves on a rename. */
export interface PostDraft {
  title: string;
  slug?: string;
  summaryMd?: string;
  bodyMd: string;
  status?: PostStatus;
  lang?: string;
  publishedAt?: string;
  tags?: string[];
  categories?: string[];
}

export const postsApi = {
  list: (status?: string) => http.get<Post[]>(`/api/posts${status ? `?status=${status}` : ""}`),
  get: (id: number) => http.get<Post>(`/api/posts/${id}`),
  create: (draft: PostDraft) => http.post<Post>("/api/posts", draft),
  update: (id: number, draft: PostDraft) => http.put<Post>(`/api/posts/${id}`, draft),
  changeStatus: (id: number, status: PostStatus, publishedAt?: string) =>
    http.patch<Post>(`/api/posts/${id}/status`, { status, publishedAt }),
  remove: (id: number) => http.del<void>(`/api/posts/${id}`),
  terms: () => http.get<{ tags: Term[]; categories: Term[] }>("/api/posts/terms"),
};
