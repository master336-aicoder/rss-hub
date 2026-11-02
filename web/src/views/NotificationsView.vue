<script setup lang="ts">
import { onMounted, ref } from "vue";
import { NOTIFICATION_STATES, notificationsApi, type NotificationEntry, type NotificationState } from "../api/notifications";
import { ApiError } from "../api/errors";
import { useRulesStore } from "../stores/rules";
import { formatAt } from "../utils/time";

const rules = useRulesStore();

const rows = ref<NotificationEntry[]>([]);
const state = ref<NotificationState | "">("");
const ruleId = ref<number | "">("");
const error = ref("");
const notice = ref("");
const loading = ref(false);

/**
 * Same vocabulary as the wall's 规则告警 rail: "已发送" and "已抑制" mean opposite things to the operator reading
 * this log, so they must not share a colour. An unknown state lands on secondary rather than inheriting green.
 */
const STATE_TONES: Record<string, string> = {
  sent: "text-bg-success",
  failed: "text-bg-danger",
  pending: "text-bg-secondary",
  suppressed: "text-bg-warning",
  skipped_quiet: "text-bg-info",
};

function tone(value: string): string {
  return STATE_TONES[value] ?? "text-bg-secondary";
}

onMounted(async () => {
  // The rule list is only here to name the filter; a deleted rule still has rows in the log and they stay visible
  // with an empty filter.
  await rules.load();
  await load();
});

async function load() {
  loading.value = true;
  error.value = "";
  try {
    rows.value = await notificationsApi.list({
      state: state.value === "" ? undefined : state.value,
      ruleId: ruleId.value === "" ? undefined : ruleId.value,
    });
  } catch (cause) {
    error.value = cause instanceof ApiError ? `${cause.code}: ${cause.message}` : String(cause);
    rows.value = [];
  } finally {
    loading.value = false;
  }
}

/** 03 §8.3: after the ladder gives up, the only retry left is the operator pressing this. */
async function replay(row: NotificationEntry) {
  error.value = "";
  notice.value = "";
  try {
    const again = await notificationsApi.replay(row.id);
    rows.value = rows.value.map((item) => (item.id === again.id ? again : item));
    notice.value = `#${again.id} 已按当初冻结的正文重新排队。`;
  } catch (cause) {
    error.value = cause instanceof ApiError ? `${cause.code}: ${cause.message}` : String(cause);
  }
}

function replayable(row: NotificationEntry): boolean {
  return row.state === "failed";
}
</script>

<template>
  <section>
    <div class="d-flex flex-wrap align-items-end justify-content-between gap-2 mb-3">
      <h1 class="h4 mb-0 d-flex align-items-center gap-2">
        <i class="bi bi-send text-primary" aria-hidden="true"></i>投递记录
      </h1>
      <div class="d-flex flex-wrap align-items-end gap-2">
        <div>
          <label class="form-label small text-body-secondary mb-1" for="filter-state">状态</label>
          <select id="filter-state" v-model="state" class="form-select form-select-sm" @change="load">
            <option value="">全部</option>
            <option v-for="value in NOTIFICATION_STATES" :key="value" :value="value">{{ value }}</option>
          </select>
        </div>
        <div>
          <label class="form-label small text-body-secondary mb-1" for="filter-rule">规则</label>
          <select id="filter-rule" v-model="ruleId" class="form-select form-select-sm" @change="load">
            <option value="">全部</option>
            <option v-for="rule in rules.rules" :key="rule.id" :value="rule.id">{{ rule.name }}</option>
          </select>
        </div>
        <button type="button" class="btn btn-sm btn-outline-primary" :disabled="loading" @click="load">
          <span :class="loading ? 'spinner-border spinner-border-sm me-2' : 'bi bi-arrow-repeat me-1'"
            aria-hidden="true"></span>{{ loading ? "读取中…" : "重新读取" }}
        </button>
        <span class="small text-body-secondary text-nowrap">{{ rows.length }} 条 · 默认最多 100 条</span>
      </div>
    </div>

    <div v-if="error" class="alert alert-danger py-2 small">{{ error }}</div>
    <div v-if="notice" class="alert alert-success py-2 small">{{ notice }}</div>
    <div v-if="rows.length === 0 && !loading" class="card">
      <div class="card-body text-center text-body-secondary py-5">
        <i class="bi bi-envelope-open fs-1 d-block opacity-25 mb-2" aria-hidden="true"></i>
        没有投递记录。规则命中后才会写这里；「跳过静默时段」与「被抑制」的行同样出现在列表中。
      </div>
    </div>
    <div v-else-if="rows.length" class="card">
      <div class="table-responsive">
        <table class="table table-hover align-middle mb-0">
          <thead>
            <tr>
              <th>#</th>
              <th>规则</th>
              <th>渠道</th>
              <th>状态</th>
              <th>尝试</th>
              <th>HTTP</th>
              <th>错误</th>
              <th>条目</th>
              <th>时间</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id" :class="row.state === 'failed' ? 'table-danger' : ''">
              <td>{{ row.id }}</td>
              <td>{{ row.ruleName }}</td>
              <td><code>{{ row.channel }}</code></td>
              <td><span class="badge" :class="tone(row.state)">{{ row.state }}</span></td>
              <td>{{ row.attempts }}</td>
              <td>{{ row.httpStatus ?? "—" }}</td>
              <td class="reason text-danger-emphasis small">{{ row.error ?? "" }}</td>
              <td>{{ row.entryId ?? "—" }}</td>
              <td class="text-nowrap">{{ formatAt(row.createdAt) }}</td>
              <td>
                <button v-if="replayable(row)" type="button" class="btn btn-sm btn-outline-primary"
                  @click="replay(row)">重发</button>
                <span v-else class="text-body-tertiary">—</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </section>
</template>

<style scoped>
.reason {
  max-width: 18rem;
}
</style>
