<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { RouterLink } from "vue-router";
import { dashboardApi, type DashboardView, type FeedCard } from "../api/dashboard";
import { ApiError } from "../api/errors";
import { ago, formatAt } from "../utils/time";

const view = ref<DashboardView | undefined>(undefined);
const error = ref("");
const loading = ref(true);

/**
 * The refresh cadence is the operator's setting, not a constant: a wall that hangs on someone's screen must not
 * need a reload to pick the interval up. 0 means "manual only" — the same setting value that disables the timer
 * in the payload disables it here.
 */
let timer: ReturnType<typeof setInterval> | undefined;

onMounted(load);
onBeforeUnmount(stop);

watch(
  () => view.value?.refreshSeconds,
  () => restart(),
);

async function load() {
  loading.value = true;
  try {
    view.value = await dashboardApi.get();
    error.value = "";
  } catch (cause) {
    error.value = cause instanceof ApiError ? `${cause.code}: ${cause.message}` : String(cause);
  } finally {
    loading.value = false;
  }
}

function restart() {
  stop();
  const seconds = view.value?.refreshSeconds ?? 0;
  if (seconds > 0) {
    timer = setInterval(load, seconds * 1000);
  }
}

function stop() {
  if (timer !== undefined) {
    clearInterval(timer);
    timer = undefined;
  }
}

const caption = computed(() => {
  if (!view.value) return "";
  const refresh = view.value.refreshSeconds > 0 ? `每 ${view.value.refreshSeconds} 秒自动刷新` : "自动刷新已关闭";
  return `每源最新 ${view.value.entriesPerFeed} 条 · ${refresh} · 最近抓取 ${ago(view.value.lastFetchedAt)}`;
});

/** The KPI strip. `hint` is the line that tells the operator what the number is actually counting. */
const cards = computed(() => {
  const stats = view.value?.stats;
  if (!stats) return [];
  return [
    { label: "未读条目", value: stats.unread, hint: `库内共 ${stats.entries} 条`, tone: "info" },
    { label: "24 小时到达", value: stats.arrivedToday, hint: "新抓进来的条目", tone: "info" },
    { label: "24 小时命中", value: stats.hitsToday, hint: `其中投递成功 ${stats.deliveredToday}`, tone: "info" },
    { label: "投递失败", value: stats.failedToday, hint: "需要去「投递」页处理", tone: stats.failedToday > 0 ? "bad" : "ok" },
    { label: "问题订阅", value: stats.problemFeeds, hint: "失败或带错误记录", tone: stats.problemFeeds > 0 ? "bad" : "ok" },
    { label: "安静订阅", value: stats.quietFeeds, hint: `24 小时内没抓取记录 · 共 ${stats.feeds} 源`, tone: stats.quietFeeds > 0 ? "warn" : "ok" },
    { label: "站内文章", value: stats.publishedPosts, hint: "本站发布出去的内容", tone: "info" },
  ];
});

/**
 * The histogram's scale. One max across both series, so a hit is never drawn taller than the arrivals that
 * caused it — the two bars are meant to be compared by eye.
 */
const peak = computed(() => {
  const hours = view.value?.hourly ?? [];
  return Math.max(1, ...hours.map((bucket) => Math.max(bucket.arrivals, bucket.hits)));
});

function height(count: number): string {
  return `${Math.round((count / peak.value) * 100)}%`;
}

/** Hour ticks every six buckets, plus the newest one, so the axis stays readable without crowding. */
const axis = computed(() => {
  const hours = view.value?.hourly ?? [];
  return hours.map((bucket, index) => ({
    label: index % 6 === 0 || index === hours.length - 1 ? hourLabel(bucket.hour) : "",
    hour: bucket.hour,
  }));
});

function hourLabel(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "" : `${String(date.getHours()).padStart(2, "0")}时`;
}

/** A card is "loud" when the last round left an error: the wall should show it before the operator asks. */
function isProblem(feed: FeedCard): boolean {
  return feed.failCount > 0 || Boolean(feed.lastError) || feed.state === "error" || feed.state === "gone";
}

function groupLabel(name: string): string {
  return name === "" ? "未分组" : name;
}

/**
 * A delivery state is worth five distinct colours, not one amber: "已发送" and "已抑制" mean opposite things to the
 * operator reading the wall. Bootstrap's contextual badges carry that, and an unknown value lands on secondary
 * rather than inheriting a colour that would read as success.
 */
const ALERT_TONES: Record<string, string> = {
  sent: "text-bg-success",
  failed: "text-bg-danger",
  pending: "text-bg-secondary",
  suppressed: "text-bg-warning",
  skipped_quiet: "text-bg-info",
};

function alertTone(state: string): string {
  return ALERT_TONES[state] ?? "text-bg-secondary";
}

/** The KPI tone, in Bootstrap's contextual colour vocabulary. */
function metricTone(tone: string): string {
  return tone === "bad"
    ? "text-danger-emphasis"
    : tone === "warn"
      ? "text-warning-emphasis"
      : tone === "ok"
        ? "text-success-emphasis"
        : "text-body";
}
</script>

<template>
  <section>
    <div class="d-flex flex-wrap align-items-center justify-content-between gap-2 mb-3">
      <h1 class="h4 mb-0 d-flex align-items-center gap-2">
        <i class="bi bi-grid-1x2 text-primary" aria-hidden="true"></i>大屏
      </h1>
      <div class="d-flex align-items-center gap-2">
        <button type="button" class="btn btn-sm btn-outline-primary" :disabled="loading" @click="load">
          <span :class="loading ? 'spinner-border spinner-border-sm me-2' : 'bi bi-arrow-repeat me-1'"
            aria-hidden="true"></span>{{ loading ? "读取中…" : "立即刷新" }}
        </button>
        <span v-if="view" class="small text-body-secondary">{{ caption }}</span>
      </div>
    </div>

    <div v-if="error" class="alert alert-danger py-2 small">{{ error }}</div>
    <p v-else-if="loading && !view" class="text-body-secondary">
      <span class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>加载中…
    </p>
    <div v-else-if="view && view.feeds.length === 0" class="card">
      <div class="card-body text-center text-body-secondary py-5">
        <i class="bi bi-inbox fs-1 d-block opacity-25 mb-2" aria-hidden="true"></i>
        还没有订阅，先去「订阅管理」加一个，大屏会随抓取自动补上。
        <div class="mt-3">
          <RouterLink class="btn btn-sm btn-primary" :to="{ name: 'feeds' }">去添加订阅</RouterLink>
        </div>
      </div>
    </div>

    <template v-if="view">
      <div class="row row-cols-2 row-cols-md-3 row-cols-xl-4 row-cols-xxl-7 g-2 g-lg-3 mb-3">
        <div v-for="card in cards" :key="card.label" class="col">
          <article class="card h-100">
            <div class="card-body py-3">
              <p class="metric mb-0" :class="metricTone(card.tone)">{{ card.value }}</p>
              <p class="metric-label mb-0">{{ card.label }}</p>
              <p class="metric-hint mb-0">{{ card.hint }}</p>
            </div>
          </article>
        </div>
      </div>

      <div class="row g-3 mb-3">
        <div class="col-12 col-xxl-8">
          <section class="card h-100">
            <div class="card-header bg-body d-flex align-items-center justify-content-between py-2">
              <h2 class="h6 mb-0">近 24 小时</h2>
              <span class="small text-body-secondary d-flex align-items-center gap-3">
                <span><i class="swatch arrivals d-inline-block me-1"></i>到达</span>
                <span><i class="swatch hits d-inline-block me-1"></i>规则命中</span>
              </span>
            </div>
            <div class="card-body">
              <div class="bars">
                <div v-for="(bucket, index) in view.hourly" :key="bucket.hour" class="slot"
                  :title="`${hourLabel(bucket.hour)} · 到达 ${bucket.arrivals} · 命中 ${bucket.hits}`">
                  <div class="column">
                    <div class="stem arrivals" :style="{ height: height(bucket.arrivals) }"></div>
                    <div class="stem hits" :style="{ height: height(bucket.hits) }"></div>
                  </div>
                  <time class="d-block small text-body-tertiary text-center">{{ axis[index].label }}</time>
                </div>
              </div>
            </div>
          </section>
        </div>

        <div class="col-12 col-xxl-4">
          <section class="card h-100">
            <div class="card-header bg-body py-2"><h2 class="h6 mb-0">分组</h2></div>
            <p v-if="view.groups.length === 0" class="text-body-secondary small p-3 mb-0">
              还没有分组，订阅管理里给源填上分组即可。
            </p>
            <div v-else class="list-group list-group-flush">
              <div v-for="group in view.groups" :key="group.name || '(none)'"
                class="list-group-item d-flex align-items-center gap-2 py-2">
                <span class="flex-grow-1 text-truncate">{{ groupLabel(group.name) }}</span>
                <span class="badge rounded-pill text-bg-secondary">{{ group.feeds }} 源</span>
                <span class="badge rounded-pill"
                  :class="group.unread > 0 ? 'text-bg-danger' : 'text-bg-light border'">未读 {{ group.unread }}</span>
                <span class="small text-body-secondary text-nowrap">24h {{ group.arrivedToday }}</span>
              </div>
            </div>
          </section>
        </div>
      </div>

      <div class="row g-3">
        <div class="col-12 col-xxl-9">
          <div class="row row-cols-1 row-cols-md-2 row-cols-xl-3 g-3">
            <div v-for="feed in view.feeds" :key="feed.id" class="col">
              <article class="card h-100"
                :class="{ 'border-danger-subtle': isProblem(feed), 'opacity-75': feed.state !== 'active' }">
                <div class="card-header bg-body d-flex align-items-center gap-2 py-2">
                  <h3 class="h6 mb-0 flex-grow-1 text-truncate" :title="feed.title">{{ feed.title }}</h3>
                  <span v-if="feed.groupName" class="badge rounded-pill text-bg-light border flex-shrink-0">
                    {{ feed.groupName }}
                  </span>
                  <span v-if="feed.state !== 'active'" class="badge rounded-pill text-bg-secondary flex-shrink-0">
                    {{ feed.state }}
                  </span>
                  <span class="badge rounded-pill flex-shrink-0"
                    :class="feed.unread > 0 ? 'text-bg-danger' : 'text-bg-light border'">{{ feed.unread }}</span>
                </div>
                <div class="card-body py-2">
                  <p v-if="feed.lastError" class="alert alert-danger py-1 px-2 small mb-2 text-truncate"
                    :title="feed.lastError">{{ feed.lastError }}</p>
                  <p v-else-if="feed.failCount > 0" class="alert alert-warning py-1 px-2 small mb-0">
                    连续失败 {{ feed.failCount }} 次
                  </p>
                  <p v-if="feed.entries.length === 0" class="text-body-secondary small mb-0">还没有条目</p>
                  <ol v-else class="list-unstyled mb-0 small">
                    <li v-for="entry in feed.entries" :key="entry.id"
                      class="d-flex justify-content-between gap-2 py-1 entry"
                      :class="{ read: entry.readState !== 'unread' }">
                      <span class="text-truncate">
                        <a v-if="entry.url" :href="entry.url" target="_blank" rel="noreferrer noopener"
                          class="text-decoration-none">{{ entry.title ?? "(无标题)" }}</a>
                        <span v-else>{{ entry.title ?? "(无标题)" }}</span>
                      </span>
                      <time class="text-body-tertiary flex-shrink-0">{{ ago(entry.publishedAt) }}</time>
                    </li>
                  </ol>
                </div>
                <div class="card-footer bg-body-tertiary d-flex justify-content-between small text-body-secondary">
                  <span v-if="feed.nextFetchAt">下次抓取 {{ ago(feed.nextFetchAt) }}</span>
                  <span v-else>排队中</span>
                  <span v-if="feed.lastChangeAt">上次变化 {{ ago(feed.lastChangeAt) }}</span>
                </div>
              </article>
            </div>
          </div>
        </div>

        <div class="col-12 col-xxl-3">
          <div class="d-flex flex-column gap-3">
            <section class="card">
              <div class="card-header bg-body py-2"><h2 class="h6 mb-0">规则告警</h2></div>
              <p v-if="view.alerts.length === 0" class="text-body-secondary small p-3 mb-0">
                最近没有命中，去「规则」看看条件是否太窄。
              </p>
              <div v-else class="list-group list-group-flush">
                <div v-for="alert in view.alerts" :key="alert.id" class="list-group-item py-2">
                  <div class="d-flex align-items-center gap-2">
                    <RouterLink :to="{ name: 'notifications' }" class="flex-grow-1 text-truncate">{{ alert.ruleName }}</RouterLink>
                    <span class="badge rounded-pill flex-shrink-0" :class="alertTone(alert.state)">{{ alert.state }}</span>
                  </div>
                  <p v-if="alert.entryTitle" class="small mb-0 mt-1 text-truncate">{{ alert.entryTitle }}</p>
                  <p class="small text-body-secondary mb-0">{{ alert.channel }} · {{ formatAt(alert.createdAt) }}</p>
                </div>
              </div>
            </section>

            <section class="card">
              <div class="card-header bg-body py-2"><h2 class="h6 mb-0">本站发布</h2></div>
              <p v-if="view.posts.length === 0" class="text-body-secondary small p-3 mb-0">还没有已发布的文章。</p>
              <div v-else class="list-group list-group-flush">
                <div v-for="post in view.posts" :key="post.id"
                  class="list-group-item d-flex justify-content-between gap-2 align-items-center py-2">
                  <a :href="`/blog/${post.slug}`" target="_blank" rel="noreferrer noopener"
                    class="text-decoration-none text-truncate">{{ post.title }}</a>
                  <time class="small text-body-tertiary flex-shrink-0">{{ ago(post.publishedAt) }}</time>
                </div>
              </div>
            </section>

            <section class="card">
              <div class="card-header bg-body py-2"><h2 class="h6 mb-0">更新轨迹</h2></div>
              <p v-if="view.changes.length === 0" class="text-body-secondary small p-3 mb-0">还没有变化记录。</p>
              <div v-else class="list-group list-group-flush">
                <div v-for="change in view.changes" :key="change.id" class="list-group-item py-2">
                  <div class="d-flex align-items-center gap-2">
                    <a :href="change.url" target="_blank" rel="noreferrer noopener"
                      class="text-decoration-none text-truncate flex-grow-1">{{ change.title }}</a>
                    <span class="badge rounded-pill text-bg-light border flex-shrink-0">{{ change.action }}</span>
                  </div>
                  <time class="small text-body-tertiary">{{ ago(change.changedAt) }}</time>
                </div>
              </div>
            </section>
          </div>
        </div>
      </div>
    </template>
  </section>
</template>

<style scoped>
/* The histogram is the one thing on this page Bootstrap has no component for: 24 stacked pairs of bars that must
   share a single scale, so a hit is never drawn taller than the arrivals that caused it. */
.bars {
  display: flex;
  align-items: flex-end;
  gap: 2px;
  height: 9rem;
}

.slot {
  flex: 1 1 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
}

.column {
  display: flex;
  align-items: flex-end;
  gap: 1px;
  height: 8rem;
}

.stem {
  flex: 1 1 0;
  min-width: 0;
  border-radius: 2px 2px 0 0;
  min-height: 2px;
}

.stem.arrivals {
  background: var(--accent);
}

.stem.hits {
  background: var(--bs-warning);
}

.swatch {
  width: .7rem;
  height: .7rem;
  border-radius: 2px;
}

.swatch.arrivals {
  background: var(--accent);
}

.swatch.hits {
  background: var(--bs-warning);
}

/* A read entry on a wall card should recede, not disappear: the operator still needs to see what the source sent. */
.entry.read {
  opacity: .55;
}
</style>
