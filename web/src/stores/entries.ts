import { computed, ref } from "vue";
import { defineStore } from "pinia";
import { ApiError } from "../api/errors";
import {
  entriesApi,
  type Counters,
  type EntryDetail,
  type EntryListItem,
  type EntryState,
  type StateChange,
} from "../api/entries";

const EMPTY_COUNTERS: Counters = { total: 0, unread: 0, starred: 0, archived: 0 };

/**
 * One inbox page and the reader pane beside it.
 *
 * Paging is append-only over a cursor the backend handed us: a fetch can land while the operator reads, and an
 * offset would then either repeat a row or skip one. Filter changes drop the cursor and start over.
 *
 * The keyboard actions live here rather than in the component because they need the list order: `j`/`k` walk the
 * rows the server returned, so a UI that kept its own idea of "the next one" would open a row no longer on screen.
 */
export const useEntriesStore = defineStore("entries", () => {
  const state = ref<EntryState>("unread");
  const feedId = ref<number | undefined>(undefined);
  const group = ref<string | undefined>(undefined);
  const query = ref("");
  /** FR-2.4's tag filter: one whole category of the source, matched by the backend against the stored array. */
  const category = ref<string | undefined>(undefined);
  /** FR-2.4's "only what the rules fired on"; undefined means the filter is not applied at all. */
  const alertedOnly = ref(false);

  const items = ref<EntryListItem[]>([]);
  const cursor = ref<string | undefined>(undefined);
  const counters = ref<Counters>({ ...EMPTY_COUNTERS });
  const loading = ref(false);
  const listError = ref("");

  const selectedId = ref<number | undefined>(undefined);
  const detail = ref<EntryDetail | undefined>(undefined);
  const detailLoading = ref(false);
  const detailError = ref("");

  let inflight: AbortController | undefined;

  function describe(cause: unknown): string {
    return cause instanceof ApiError ? `${cause.code}: ${cause.message}` : String(cause);
  }

  function filter() {
    return {
      state: state.value,
      feed: feedId.value,
      group: group.value,
      q: query.value.trim() === "" ? undefined : query.value.trim(),
      category: category.value,
      // Off sends nothing rather than `alerted=false`: "hide the hits" is a different question from "no filter",
      // and the API keeps both because the backend's parameter is tri-state.
      alerted: alertedOnly.value ? true : undefined,
    };
  }

  /** First page of the current filter. */
  async function reload(): Promise<void> {
    inflight?.abort();
    const controller = new AbortController();
    inflight = controller;
    loading.value = true;
    listError.value = "";
    try {
      // No `limit` here on purpose: the inbox's page size is the `inbox.pageSize` setting (FR-9.2 promises the
      // setting is the real read path), and an explicit number would freeze it behind the settings page.
      const page = await entriesApi.list({ ...filter() }, controller.signal);
      if (controller.signal.aborted) return;
      items.value = page.items;
      cursor.value = page.nextCursor;
      counters.value = page.counters;
    } catch (cause) {
      if (!controller.signal.aborted) listError.value = describe(cause);
    } finally {
      if (!controller.signal.aborted) loading.value = false;
    }
  }

  async function loadMore(): Promise<void> {
    if (cursor.value === undefined || loading.value) return;
    loading.value = true;
    try {
      const page = await entriesApi.list({ ...filter(), cursor: cursor.value });
      items.value = [...items.value, ...page.items];
      cursor.value = page.nextCursor;
      counters.value = page.counters;
    } catch (cause) {
      listError.value = describe(cause);
    } finally {
      loading.value = false;
    }
  }

  function setState(next: EntryState): Promise<void> {
    state.value = next;
    return reload();
  }

  function setFeed(next?: number): Promise<void> {
    feedId.value = next;
    return reload();
  }

  function setGroup(next?: string): Promise<void> {
    group.value = next;
    return reload();
  }

  function search(next: string): Promise<void> {
    query.value = next;
    return reload();
  }

  function setCategory(next?: string): Promise<void> {
    category.value = next && next.trim() !== "" ? next : undefined;
    return reload();
  }

  function setAlertedOnly(next: boolean): Promise<void> {
    alertedOnly.value = next;
    return reload();
  }

  async function open(id: number): Promise<void> {
    selectedId.value = id;
    detailLoading.value = true;
    detailError.value = "";
    try {
      detail.value = await entriesApi.detail(id);
    } catch (cause) {
      detail.value = undefined;
      detailError.value = describe(cause);
    } finally {
      detailLoading.value = false;
    }
  }

  /** Index of the open row in the list the server handed us; -1 when nothing is open or the row left the filter. */
  const selectedIndex = computed(() => items.value.findIndex((item) => item.id === selectedId.value));

  /**
   * `j`/`k`: one row down or up, opening what it lands on. A walk that has not started begins at the newest row
   * instead of skipping it, and the ends stop rather than wrap — a wrapped `k` reads as a glitch.
   */
  async function selectRelative(step: number): Promise<void> {
    if (items.value.length === 0) return;
    const from = selectedIndex.value;
    const next =
      from === -1
        ? step > 0
          ? 0
          : items.value.length - 1
        : Math.min(Math.max(from + step, 0), items.value.length - 1);
    await open(items.value[next].id);
  }

  /** `n`: the next row the unread tab still counts — baseline rows are history, not news. */
  async function selectNextUnread(): Promise<void> {
    const start = selectedIndex.value === -1 ? 0 : selectedIndex.value + 1;
    const offset = items.value.findIndex(
      (item, index) => index >= start && item.readState === "unread" && !item.baseline,
    );
    if (offset === -1) return;
    await open(items.value[offset].id);
  }

  /** `s`: the star of the open row. `apply` re-reads the list, so a starred row under `unread` leaves the tab. */
  async function toggleStarred(): Promise<void> {
    const entry = detail.value;
    if (entry === undefined) return;
    await apply({ ids: [entry.id], starred: !entry.starred });
  }

  /** `m`: done with the open row. Not-read means read, including a dismissed one; read goes back to unread. */
  async function toggleRead(): Promise<void> {
    const entry = detail.value;
    if (entry === undefined) return;
    await apply({ ids: [entry.id], readState: entry.readState === "read" ? "unread" : "read" });
  }

  /**
   * Applies a flag change and re-reads both the list and the open entry. The list is re-read rather than patched
   * locally because the row may leave the current filter (starring under `state=unread`, reading under `unread`),
   * and a UI that edits its own copy is a UI that shows a row the server no longer returns.
   */
  async function apply(change: StateChange): Promise<void> {
    await entriesApi.setState(change);
    if (change.ids.length === 0) return;
    await reload();
    if (selectedId.value !== undefined && change.ids.includes(selectedId.value)) {
      await open(selectedId.value);
    }
  }

  async function countersOnly(): Promise<void> {
    try {
      counters.value = await entriesApi.counters();
    } catch (cause) {
      listError.value = describe(cause);
    }
  }

  return {
    state,
    feedId,
    group,
    query,
    category,
    alertedOnly,
    items,
    cursor,
    counters,
    loading,
    listError,
    selectedId,
    selectedIndex,
    detail,
    detailLoading,
    detailError,
    reload,
    loadMore,
    setState,
    setFeed,
    setGroup,
    search,
    setCategory,
    setAlertedOnly,
    open,
    selectRelative,
    selectNextUnread,
    toggleStarred,
    toggleRead,
    apply,
    countersOnly,
  };
});
