import { computed, ref } from "vue";
import { defineStore } from "pinia";
import { ApiError } from "../api/errors";
import { postsApi, type Post, type PostDraft, type PostStatus, type Term } from "../api/posts";

/**
 * The editor's own working set. The list carries full bodies because the preview pane renders one of them without
 * a second round trip; at self-hosted volumes this stays inside a page's worth of rows.
 */
export const usePostsStore = defineStore("posts", () => {
  const posts = ref<Post[]>([]);
  const terms = ref<{ tags: Term[]; categories: Term[] }>({ tags: [], categories: [] });
  const loading = ref(false);
  const error = ref("");

  const byId = computed(() => new Map(posts.value.map((post) => [post.id, post])));

  function describe(cause: unknown): string {
    return cause instanceof ApiError ? `${cause.code}: ${cause.message}` : String(cause);
  }

  async function load(): Promise<void> {
    loading.value = true;
    error.value = "";
    try {
      posts.value = await postsApi.list();
    } catch (cause) {
      error.value = describe(cause);
    } finally {
      loading.value = false;
    }
  }

  async function loadTerms(): Promise<void> {
    try {
      terms.value = await postsApi.terms();
    } catch (cause) {
      error.value = describe(cause);
    }
  }

  /** Create is a full write, so the response replaces the row list rather than a merge — the server owns the slug. */
  async function create(draft: PostDraft): Promise<Post> {
    const created = await postsApi.create(draft);
    await load();
    return created;
  }

  async function update(id: number, draft: PostDraft): Promise<Post> {
    const saved = await postsApi.update(id, draft);
    posts.value = posts.value.map((post) => (post.id === id ? saved : post));
    return saved;
  }

  async function changeStatus(id: number, status: PostStatus, publishedAt?: string): Promise<Post> {
    const saved = await postsApi.changeStatus(id, status, publishedAt);
    posts.value = posts.value.map((post) => (post.id === id ? saved : post));
    return saved;
  }

  async function remove(id: number): Promise<void> {
    await postsApi.remove(id);
    posts.value = posts.value.filter((post) => post.id !== id);
  }

  return { posts, terms, byId, loading, error, load, loadTerms, create, update, changeStatus, remove };
});
