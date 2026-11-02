<script setup lang="ts">
import { onMounted, ref } from "vue";
import ReaderPane from "../components/ReaderPane.vue";
import { useEntriesStore } from "../stores/entries";
import { useFeedsStore } from "../stores/feeds";
import { useInboxKeys } from "../composables/useInboxKeys";
import { useUnreadAlerts } from "../composables/useUnreadAlerts";
import { ApiError } from "../api/errors";
import { feedLabel } from "../api/feeds";
import type { EntryState } from "../api/entries";
import { ago } from "../utils/time";

const entries = useEntriesStore();
const feeds = useFeedsStore();

useInboxKeys();
const alerts = useUnreadAlerts();

const tabs: { key: EntryState; label: string; icon: string; count?: () => number }[] = [
  { key: "unread", label: "未读", icon: "bi-envelope", count: () => entries.counters.unread },
  { key: "all", label: "全部", icon: "bi-inboxes", count: () => entries.counters.total },
  { key: "starred", label: "星标", icon: "bi-star", count: () => entries.counters.starred },
  { key: "archived", label: "归档", icon: "bi-archive", count: () => entries.counters.archived },
];

const term = ref(entries.query);
const busy = ref("");

function note(cause: unknown) {
  busy.value = cause instanceof ApiError ? `${cause.code}: ${cause.message}` : String(cause);
}

onMounted(async () => {
  await Promise.all([feeds.load(), entries.reload()]);
});

async function pick(state: EntryState, feedId?: number) {
  entries.feedId = feedId;
  await entries.setState(state).catch(note);
}

async function pickGroup(name: string) {
  const clear = entries.group === name;
  entries.feedId = undefined;
  await entries.setGroup(clear ? undefined : name).catch(note);
}

async function toggleAlerted() {
  await entries.setAlertedOnly(!entries.alertedOnly).catch(note);
}

async function clearCategory() {
  await entries.setCategory(undefined).catch(note);
}

async function markFeedRead() {
  if (entries.feedId === undefined) return;
  await feeds.markFeedRead(entries.feedId).then(() => entries.reload()).catch(note);
}
</script>

<template>
  <div class="d-flex flex-grow-1 overflow-hidden">
    <nav class="inbox-side pane-scroll border-end bg-body-tertiary p-2">
      <ul class="nav nav-pills flex-column gap-1 mb-3">
        <li v-for="tab in tabs" :key="tab.key" class="nav-item">
          <button type="button" class="nav-link w-100 d-flex align-items-center gap-2"
            :class="{ active: entries.state === tab.key && entries.feedId === undefined }" @click="pick(tab.key)">
            <i class="bi" :class="tab.icon" aria-hidden="true"></i>
            <span class="flex-grow-1 text-start">{{ tab.label }}</span>
            <span class="badge rounded-pill"
              :class="tab.key === 'unread' && (tab.count?.() ?? 0) > 0 ? 'text-bg-danger' : 'text-bg-secondary'">
              {{ tab.count?.() ?? 0 }}
            </span>
          </button>
        </li>
      </ul>

      <form class="input-group input-group-sm mb-3" @submit.prevent="entries.search(term).catch(note)">
        <span class="input-group-text bg-body"><i class="bi bi-search" aria-hidden="true"></i></span>
        <input v-model="term" class="form-control" type="search" placeholder="搜索标题与摘要" aria-label="搜索条目">
      </form>

      <template v-if="feeds.groups.length">
        <p class="small fw-semibold text-body-secondary text-uppercase mb-1 ps-1">分组</p>
        <div class="list-group list-group-flush mb-2">
          <button v-for="name in feeds.groups" :key="name" type="button"
            class="list-group-item list-group-item-action text-truncate"
            :class="{ active: entries.group === name && entries.feedId === undefined }" @click="pickGroup(name)">
            <i class="bi bi-folder2 me-1 opacity-75" aria-hidden="true"></i>{{ name }}
          </button>
        </div>
      </template>

      <p class="small fw-semibold text-body-secondary text-uppercase mb-1 ps-1">
        订阅（{{ feeds.feeds.length }}）
      </p>
      <div class="list-group list-group-flush">
        <button v-for="feed in feeds.feeds" :key="feed.id" type="button"
          class="list-group-item list-group-item-action d-flex align-items-center gap-2"
          :class="{ active: entries.feedId === feed.id }" :title="feed.canonicalUrl"
          @click="pick(entries.state === 'all' ? 'all' : 'unread', feed.id)">
          <span class="flex-grow-1 text-truncate">{{ feedLabel(feed) }}</span>
          <span v-if="feed.unreadCount" class="badge rounded-pill text-bg-danger flex-shrink-0">
            {{ feed.unreadCount }}
          </span>
          <span v-else-if="feed.state !== 'active'" class="badge rounded-pill text-bg-warning flex-shrink-0">
            {{ feed.state }}
          </span>
        </button>
      </div>
      <p v-if="feeds.error" class="small text-danger-emphasis mb-0 mt-2">{{ feeds.error }}</p>

      <p class="small fw-semibold text-body-secondary text-uppercase mb-1 mt-4 ps-1">提醒</p>
      <p class="small mb-1">
        <button v-if="alerts.permission.value !== 'granted'" type="button"
          class="btn btn-sm btn-outline-primary py-0" @click="alerts.ask()">开启桌面通知</button>
        <span v-else class="text-success-emphasis"><i class="bi bi-check2-circle me-1" aria-hidden="true"></i>桌面通知已开启</span>
        <span v-if="alerts.note.value" class="text-body-secondary"> {{ alerts.note.value }}</span>
      </p>
      <p class="small text-body-secondary mb-4">
        键盘：<kbd>j</kbd> / <kbd>k</kbd> 上下 · <kbd>n</kbd> 下一条未读 · <kbd>s</kbd> 星标 · <kbd>m</kbd> 已读
      </p>
    </nav>

    <section class="inbox-list pane-scroll d-flex flex-column border-end">
      <header class="sticky-page-head d-flex align-items-center justify-content-between gap-2 p-2 border-bottom">
        <span class="small text-body-secondary text-uppercase">
          {{ entries.state }}<template v-if="entries.feedId"> · 单个订阅</template>
        </span>
        <div class="d-flex flex-wrap gap-1 justify-content-end">
          <button type="button" class="btn btn-sm py-0"
            :class="entries.alertedOnly ? 'btn-warning' : 'btn-outline-secondary'" @click="toggleAlerted">
            <i class="bi bi-funnel me-1" aria-hidden="true"></i>只看命中
          </button>
          <button v-if="entries.category" type="button"
            class="btn btn-sm btn-primary rounded-pill py-0" @click="clearCategory">
            标签：{{ entries.category }} <i class="bi bi-x-lg ms-1" aria-hidden="true"></i>
          </button>
          <button v-if="entries.feedId !== undefined" type="button" class="btn btn-sm btn-outline-secondary py-0"
            @click="markFeedRead">此订阅全部已读</button>
        </div>
      </header>

      <div v-if="busy" class="alert alert-danger py-2 small m-2 mb-0" role="alert">{{ busy }}</div>
      <p v-if="entries.loading && entries.items.length === 0" class="text-body-secondary p-3 mb-0">
        <span class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>加载中…
      </p>
      <p v-else-if="entries.listError" class="alert alert-danger py-2 small m-2 mb-0">{{ entries.listError }}</p>
      <p v-else-if="entries.items.length === 0" class="text-body-secondary p-3 mb-0">
        <i class="bi bi-inbox d-block mb-2 opacity-50 fs-3" aria-hidden="true"></i>
        {{ entries.state === "unread" ? "没有未读。抓取按每个来源的节奏，默认一小时一轮。" : "这里没有条目。" }}
      </p>
      <div v-else class="pane-scroll flex-grow-1">
        <button v-for="item in entries.items" :key="item.id" type="button"
          class="entry-row w-100 text-start"
          :class="{ unread: item.readState === 'unread', picked: entries.selectedId === item.id }"
          @click="entries.open(item.id)">
          <span class="headline d-block lh-sm">
            <i v-if="item.starred" class="bi bi-star-fill text-warning me-1" aria-hidden="true"></i>
            {{ item.title ?? "(无标题)" }}
          </span>
          <span v-if="item.alertedBy?.length" class="d-flex flex-wrap gap-1 mt-1">
            <span v-for="name in item.alertedBy" :key="name" class="badge rounded-pill text-bg-danger">
              规则 · {{ name }}
            </span>
          </span>
          <span class="d-block small text-body-secondary mt-1">
            {{ item.feedTitle }} · {{ ago(item.publishedAt ?? item.fetchedAt) }}
            <template v-if="item.revision > 1"> · 第 {{ item.revision }} 次</template>
          </span>
          <span v-if="item.summary" class="d-block small text-body-tertiary mt-1">{{ item.summary.slice(0, 140) }}</span>
        </button>
      </div>
      <button v-if="entries.cursor" type="button" class="btn btn-outline-secondary btn-sm m-2 align-self-start"
        :disabled="entries.loading" @click="entries.loadMore()">
        <span v-if="entries.loading" class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>
        {{ entries.loading ? "加载中…" : "加载更多" }}
      </button>
    </section>

    <ReaderPane />
  </div>
</template>

<style scoped>
/* The three columns own the viewport, so their widths are the one thing Bootstrap's grid cannot say for them:
   a grid row scrolls the page, and this layout must scroll inside each column instead. */
.inbox-side {
  flex: 0 0 16rem;
}

.inbox-list {
  flex: 0 0 clamp(19rem, 28vw, 25rem);
}

/* `entry-row` + `picked` is read by composables/useInboxKeys.ts to keep the keyboard walk on screen. */
.entry-row {
  border: 0;
  border-bottom: 1px solid var(--bs-border-color-translucent);
  background: var(--bs-body-bg);
  cursor: pointer;
  font: inherit;
  padding: .7rem .8rem;
  transition: background 120ms var(--ease);
}

.entry-row:hover {
  background: var(--bs-tertiary-bg);
}

.entry-row.unread .headline {
  font-weight: 600;
  color: var(--bs-primary-text-emphasis);
}

.entry-row.picked {
  background: var(--accent-wash);
  box-shadow: inset 3px 0 0 var(--accent);
}
</style>
