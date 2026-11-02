import { computed, ref } from "vue";
import { defineStore } from "pinia";
import { ApiError } from "../api/errors";
import { feedsApi, type Feed, type FeedEdit, type FetchPolicy } from "../api/feeds";

/**
 * The subscription list, shared by the inbox sidebar, the management page and the health page. Keeping it in one
 * store is what stops the three views from showing three different answers to "how many unread".
 */
export const useFeedsStore = defineStore("feeds", () => {
  const feeds = ref<Feed[]>([]);
  const loading = ref(false);
  const error = ref("");
  /** The cadence this install falls back to, so the edit box can state the default instead of guessing it. */
  const policy = ref<FetchPolicy | undefined>(undefined);

  const groups = computed(() => {
    const names = new Set<string>();
    for (const feed of feeds.value) {
      if (feed.groupName) names.add(feed.groupName);
    }
    return [...names].sort();
  });

  const unreadTotal = computed(() => feeds.value.reduce((sum, feed) => sum + feed.unreadCount, 0));

  const byId = computed(() => new Map(feeds.value.map((feed) => [feed.id, feed])));

  function describe(cause: unknown): string {
    return cause instanceof ApiError ? `${cause.code}: ${cause.message}` : String(cause);
  }

  async function load(): Promise<void> {
    loading.value = true;
    error.value = "";
    try {
      feeds.value = await feedsApi.list();
    } catch (cause) {
      error.value = describe(cause);
    } finally {
      loading.value = false;
    }
  }

  /** Read once per visit; a failed hint must not stop the list itself from rendering. */
  async function loadPolicy(): Promise<void> {
    try {
      policy.value = await feedsApi.policy();
    } catch {
      policy.value = undefined;
    }
  }

  /** Every mutation re-reads the row the backend returned, so the UI never keeps a guess about state or counts. */
  async function add(url: string, group?: string): Promise<Feed> {
    const feed = await feedsApi.add(url, group);
    await load();
    return feed;
  }

  async function update(id: number, body: FeedEdit): Promise<void> {
    const updated = await feedsApi.update(id, body);
    feeds.value = feeds.value.map((feed) => (feed.id === id ? updated : feed));
  }

  async function remove(id: number): Promise<void> {
    await feedsApi.remove(id);
    feeds.value = feeds.value.filter((feed) => feed.id !== id);
  }

  /**
   * "Fetch now" arms the queue and wakes the dispatcher, but the exchange itself runs on the dispatcher's worker,
   * so the caller hands over the `lastFetchedAt` it last saw and waits for the row to report a newer one. Polling
   * the single row keeps the list's `loading` flag untouched — a full `load()` would blank the table mid-wait.
   * Resolves with the refreshed feed, or `undefined` when the fetch had not landed within `timeoutMs`.
   */
  async function waitFetched(id: number, before: string | undefined, timeoutMs = 15000): Promise<Feed | undefined> {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      try {
        const feed = await feedsApi.get(id);
        if (feed.lastFetchedAt !== before) {
          feeds.value = feeds.value.map((row) => (row.id === id ? feed : row));
          return feed;
        }
      } catch {
        // A row that vanished mid-fetch is the caller's problem to report; keep polling until the deadline.
      }
      if (Date.now() >= deadline) {
        return undefined;
      }
      await new Promise((resolve) => setTimeout(resolve, 400));
    }
  }

  async function markFeedRead(id: number): Promise<void> {
    await feedsApi.markRead(id);
    await load();
  }

  return {
    feeds,
    groups,
    unreadTotal,
    byId,
    loading,
    error,
    policy,
    load,
    loadPolicy,
    add,
    update,
    remove,
    refresh: feedsApi.refresh,
    waitFetched,
    markFeedRead,
  };
});
