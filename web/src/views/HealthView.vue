<script setup lang="ts">
import { onMounted, ref } from "vue";
import { RouterLink } from "vue-router";
import { healthApi, type HealthView } from "../api/health";
import { ApiError } from "../api/errors";
import { useFeedsStore } from "../stores/feeds";
import { ago, formatAt, span } from "../utils/time";

const feeds = useFeedsStore();
const view = ref<HealthView | undefined>(undefined);
const error = ref("");
const loading = ref(true);

onMounted(async () => {
  await load();
  // The rows are the same columns the fetcher maintains, so one refresh is enough — no polling loop here.
  await feeds.load();
});

async function load() {
  loading.value = true;
  try {
    view.value = await healthApi.feeds();
    error.value = "";
  } catch (cause) {
    error.value = cause instanceof ApiError ? `${cause.code}: ${cause.message}` : String(cause);
  } finally {
    loading.value = false;
  }
}

/** The state summary is a map, so the badge tone has to be looked up rather than carried by the data. */
function stateTone(state: string): string {
  return state === "active"
    ? "text-bg-success"
    : state === "paused"
      ? "text-bg-secondary"
      : state === "gone"
        ? "text-bg-dark"
        : "text-bg-danger";
}
</script>

<template>
  <section>
    <div class="d-flex flex-wrap align-items-center justify-content-between gap-2 mb-3">
      <h1 class="h4 mb-0 d-flex align-items-center gap-2">
        <i class="bi bi-heart-pulse text-primary" aria-hidden="true"></i>来源健康度
      </h1>
      <div class="d-flex align-items-center gap-2 flex-wrap">
        <button type="button" class="btn btn-sm btn-outline-primary" :disabled="loading" @click="load">
          <span :class="loading ? 'spinner-border spinner-border-sm me-2' : 'bi bi-arrow-repeat me-1'"
            aria-hidden="true"></span>{{ loading ? "读取中…" : "重新读取" }}
        </button>
        <span v-if="view" class="small text-body-secondary">
          {{ view.summary.total }} 个订阅 · {{ view.summary.failing }} 个有问题 · {{ view.summary.quiet }} 个超过三天没内容
          <template v-if="view.summary.slowestFetchMs !== undefined">
            · 最慢平均 {{ view.summary.slowestFetchMs }}ms
          </template>
        </span>
        <span v-if="view" class="d-flex gap-1">
          <span v-for="(count, state) in view.summary.byState" :key="state" class="badge rounded-pill"
            :class="stateTone(String(state))">{{ state }} {{ count }}</span>
        </span>
      </div>
    </div>

    <div v-if="error" class="alert alert-danger py-2 small">{{ error }}</div>
    <RouterLink v-if="view && view.rulesDisabled > 0" :to="{ name: 'rules' }"
      class="alert alert-warning py-2 small d-block text-decoration-none mb-3">
      <i class="bi bi-exclamation-triangle-fill me-1" aria-hidden="true"></i>{{ view.rulesDisabled }}
      条规则被投递熔断自动关闭：连续 5 次发送失败后它会自己停下。去「规则」页看原因，处理完再显式启用。
    </RouterLink>
    <p v-else-if="loading && !view" class="text-body-secondary">
      <span class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>加载中…
    </p>
    <div v-else-if="view && view.feeds.length === 0" class="card">
      <div class="card-body text-center text-body-secondary py-5">
        <i class="bi bi-inbox fs-1 d-block opacity-25 mb-2" aria-hidden="true"></i>
        还没有订阅，先去「订阅管理」加一个。
      </div>
    </div>

    <div v-else-if="view" class="card">
      <div class="table-responsive">
        <table class="table table-hover align-middle mb-0">
          <thead>
            <tr>
              <th>来源</th>
              <th>状态</th>
              <th>失败</th>
              <th>上次 HTTP</th>
              <th>错误</th>
              <th>上次有内容</th>
              <th>安静时长</th>
              <th>平均耗时</th>
              <th>下次抓取</th>
              <th>条目</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in view.feeds" :key="row.id" :class="row.problem ? 'table-warning' : ''">
              <td class="source-cell">
                <strong>{{ row.title ?? "(还没有标题)" }}</strong>
                <a :href="row.canonicalUrl" target="_blank" rel="noreferrer noopener"
                  class="d-block small text-break muted">{{ row.canonicalUrl }}</a>
                <span v-if="row.groupName" class="badge rounded-pill text-bg-light border">{{ row.groupName }}</span>
              </td>
              <td class="text-nowrap">
                <span class="badge" :class="stateTone(row.state)">{{ row.state }}</span>
                <span v-if="row.format" class="small muted d-block">{{ row.format }}</span>
              </td>
              <td>
                {{ row.failCount }}
                <span v-if="row.notfoundStreak > 0" class="d-block small text-warning-emphasis">连续 404
                  {{ row.notfoundStreak }} 次</span>
              </td>
              <td>{{ row.lastHttpStatus ?? "—" }}</td>
              <td class="reason text-danger-emphasis small">{{ row.lastError ?? "" }}</td>
              <td class="text-nowrap">{{ ago(row.lastChangeAt) }}<span v-if="!row.lastChangeAt" class="muted">
                  从未</span></td>
              <td class="text-nowrap">{{ span(row.quietForSeconds) }}</td>
              <td>{{ row.avgFetchMs !== undefined ? `${row.avgFetchMs}ms` : "—" }}</td>
              <td class="text-nowrap">{{ row.nextFetchAt ? formatAt(row.nextFetchAt) : "不在队列" }}</td>
              <td class="text-nowrap">{{ row.unreadCount }} / {{ row.entryCount }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </section>
</template>

<style scoped>
/* Bootstrap wraps the table but not the cell widths: the url is the long thing on this screen and it must not
   push the ten numeric columns out of the viewport. */
.source-cell {
  max-width: 24rem;
}
.reason {
  max-width: 16rem;
}
</style>
