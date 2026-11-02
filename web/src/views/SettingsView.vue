<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { ApiError } from "../api/errors";
import { SETTING_RANGES, settingsApi, type SettingKey, type SettingsView } from "../api/settings";
import { systemApi, type SystemStatus } from "../api/system";
import { ago, formatAt, span } from "../utils/time";

const status = ref<SystemStatus | undefined>(undefined);
const statusError = ref("");
const statusLoading = ref(true);

const form = reactive<Record<SettingKey, number>>({
  dashboardEntriesPerFeed: 20,
  dashboardRefreshSeconds: 60,
  inboxPageSize: 50,
  notificationsPageSize: 100,
});
const saved = ref<SettingsView | undefined>(undefined);
const saving = ref(false);
const saveError = ref("");
const savedNote = ref("");

onMounted(async () => {
  await Promise.all([loadStatus(), loadSettings()]);
});

async function loadStatus() {
  statusLoading.value = true;
  try {
    status.value = await systemApi.status();
    statusError.value = "";
  } catch (cause) {
    statusError.value = cause instanceof ApiError ? `${cause.code}: ${cause.message}` : String(cause);
  } finally {
    statusLoading.value = false;
  }
}

async function loadSettings() {
  try {
    const view = await settingsApi.get();
    saved.value = view;
    for (const key of Object.keys(SETTING_RANGES) as SettingKey[]) {
      form[key] = view[key];
    }
  } catch (cause) {
    saveError.value = cause instanceof ApiError ? `${cause.code}: ${cause.message}` : String(cause);
  }
}

/**
 * The same edges the backend enforces (SettingsService.IntSpec): the client-side check is a courtesy that keeps the
 * button honest, the server's is the contract, and the two can never disagree because they read one table.
 */
function outOfRange(key: SettingKey): boolean {
  const range = SETTING_RANGES[key];
  return !Number.isInteger(form[key]) || form[key] < range.min || form[key] > range.max;
}

const hasInvalid = computed(() => (Object.keys(SETTING_RANGES) as SettingKey[]).some(outOfRange));

const changed = computed(() => {
  const patch: Partial<SettingsView> = {};
  for (const key of Object.keys(SETTING_RANGES) as SettingKey[]) {
    // Only what the operator actually moved is sent: PATCH is partial, and the neighbours' rows must stay untouched.
    if (saved.value !== undefined && form[key] !== saved.value[key]) {
      patch[key] = form[key];
    }
  }
  return patch;
});

async function save() {
  saving.value = true;
  savedNote.value = "";
  try {
    saved.value = await settingsApi.patch(changed.value);
    saveError.value = "";
    savedNote.value = "已保存，下一次读取即生效";
  } catch (cause) {
    saveError.value = cause instanceof ApiError ? `${cause.code}: ${cause.message}` : String(cause);
  } finally {
    saving.value = false;
  }
}

function bytes(value: number): string {
  if (value < 1024) return `${value} B`;
  const units = ["KB", "MB", "GB"];
  let size = value;
  let unit = -1;
  do {
    size /= 1024;
    unit += 1;
  } while (size >= 1024 && unit < units.length - 1);
  return `${size.toFixed(1)} ${units[unit]}`;
}

const heapPercent = computed(() => {
  const jvm = status.value?.jvm;
  if (jvm === undefined || jvm.heapMaxBytes <= 0) return 0;
  return Math.round((jvm.heapUsedBytes / jvm.heapMaxBytes) * 100);
});

/** A heap that is three-quarters gone is the one number on this page that should change colour before you read it. */
function barTone(percent: number): string {
  return percent >= 90 ? "bg-danger" : percent >= 70 ? "bg-warning" : "";
}
</script>

<template>
  <section>
    <h1 class="h4 mb-3 d-flex align-items-center gap-2">
      <i class="bi bi-sliders text-primary" aria-hidden="true"></i>系统设置
    </h1>

    <div class="d-flex flex-wrap align-items-center justify-content-between gap-2 mb-3">
      <h2 class="h6 mb-0">运行状态</h2>
      <div class="d-flex align-items-center gap-2">
        <button type="button" class="btn btn-sm btn-outline-primary" :disabled="statusLoading" @click="loadStatus">
          <span :class="statusLoading ? 'spinner-border spinner-border-sm me-2' : 'bi bi-arrow-repeat me-1'"
            aria-hidden="true"></span>{{ statusLoading ? "读取中…" : "重新读取" }}
        </button>
        <span v-if="status" class="small text-body-secondary">采集于 {{ formatAt(status.now) }}</span>
      </div>
    </div>
    <div v-if="statusError" class="alert alert-danger py-2 small">{{ statusError }}</div>

    <div v-if="status" class="row g-3 mb-4">
      <div class="col-12 col-md-6 col-xxl-3">
        <section class="card h-100">
          <div class="card-header bg-body py-2">
            <h3 class="h6 mb-0"><i class="bi bi-pc-display me-1 text-primary" aria-hidden="true"></i>系统</h3>
          </div>
          <div class="card-body">
            <dl class="facts mb-0">
              <dt>操作系统</dt>
              <dd class="text-break">{{ status.system.osName }} {{ status.system.osVersion }} · {{
                status.system.osArch }}</dd>
              <dt>Java</dt>
              <dd>{{ status.system.javaVendor }} {{ status.system.javaVersion }}</dd>
              <dt>进程启动于</dt>
              <dd>{{ formatAt(status.system.jvmStartedAt) }}</dd>
              <dt>已运行</dt>
              <dd>{{ span(status.system.uptimeSeconds) }}</dd>
            </dl>
          </div>
        </section>
      </div>

      <div class="col-12 col-md-6 col-xxl-3">
        <section class="card h-100">
          <div class="card-header bg-body py-2">
            <h3 class="h6 mb-0"><i class="bi bi-cpu me-1 text-primary" aria-hidden="true"></i>JVM</h3>
          </div>
          <div class="card-body">
            <dl class="facts mb-0">
              <dt>堆内存</dt>
              <dd>{{ bytes(status.jvm.heapUsedBytes) }} / {{ bytes(status.jvm.heapMaxBytes) }} {{ heapPercent }}%</dd>
              <dt>已提交</dt>
              <dd>{{ bytes(status.jvm.heapCommittedBytes) }}</dd>
              <dt>活跃线程</dt>
              <dd>{{ status.jvm.liveThreads }}</dd>
              <dt>已加载类</dt>
              <dd>{{ status.jvm.loadedClasses }}</dd>
            </dl>
            <div class="progress mt-2" role="progressbar" :aria-valuenow="heapPercent" aria-valuemin="0"
              aria-valuemax="100" :aria-label="`堆内存占用 ${heapPercent}%`">
              <div class="progress-bar" :class="barTone(heapPercent)" :style="{ width: `${heapPercent}%` }">
                {{ heapPercent }}%
              </div>
            </div>
          </div>
        </section>
      </div>

      <div class="col-12 col-md-6 col-xxl-3">
        <section class="card h-100">
          <div class="card-header bg-body py-2">
            <h3 class="h6 mb-0"><i class="bi bi-database me-1 text-primary" aria-hidden="true"></i>数据库</h3>
          </div>
          <div class="card-body">
            <dl class="facts mb-0">
              <dt>连接</dt>
              <dd>
                <span class="badge rounded-pill" :class="status.database.up ? 'text-bg-success' : 'text-bg-danger'">{{
                  status.database.up ? "正常" : "不可用" }}</span>
                {{ status.database.productName }} {{ status.database.productVersion }}
              </dd>
              <dt>订阅 / 条目</dt>
              <dd>{{ status.database.feeds }} / {{ status.database.entries }}</dd>
              <dt>规则 / 通知</dt>
              <dd>{{ status.database.rules }} / {{ status.database.notifications }}</dd>
            </dl>
          </div>
        </section>
      </div>

      <div class="col-12 col-md-6 col-xxl-3">
        <section class="card h-100">
          <div class="card-header bg-body py-2">
            <h3 class="h6 mb-0"><i class="bi bi-hdd-network me-1 text-primary" aria-hidden="true"></i>服务</h3>
          </div>
          <div class="card-body">
            <dl class="facts mb-0">
              <dt>抓取调度</dt>
              <dd><span class="badge rounded-pill"
                  :class="status.services.fetchEnabled ? 'text-bg-success' : 'text-bg-warning'">{{
                    status.services.fetchEnabled ? "运行中" : "已停用" }}</span></dd>
              <dt>投递调度</dt>
              <dd><span class="badge rounded-pill"
                  :class="status.services.notifyEnabled ? 'text-bg-success' : 'text-bg-warning'">{{
                    status.services.notifyEnabled ? "运行中" : "已停用" }}</span></dd>
              <dt>任务队列</dt>
              <dd class="chips">
                <template v-if="Object.keys(status.services.taskQueueByState).length === 0">空</template>
                <span v-for="(count, state) in status.services.taskQueueByState" :key="state"
                  class="badge rounded-pill text-bg-light border">{{ state }} {{ count }}</span>
              </dd>
              <dt>投递日志</dt>
              <dd class="chips">
                <template v-if="Object.keys(status.services.notificationsByState).length === 0">空</template>
                <span v-for="(count, state) in status.services.notificationsByState" :key="state"
                  class="badge rounded-pill text-bg-light border">{{ state }} {{ count }}</span>
              </dd>
              <dt>最后抓到条目</dt>
              <dd>{{ ago(status.services.lastFetchedAt) }}</dd>
            </dl>
          </div>
        </section>
      </div>
    </div>

    <h2 class="h6 mb-1">系统参数</h2>
    <p class="small text-body-secondary mb-3">
      改动即时生效，无需重启。抓取节奏与投递超时属于部署配置（application.yml / 环境变量），改它们需要重启，所以不放在这里。
    </p>
    <div class="row g-3 mb-3 settings-form">
      <div v-for="(range, key) in SETTING_RANGES" :key="key" class="col-12 col-md-6">
        <div class="card h-100">
          <div class="card-body py-3">
            <label class="form-label" :for="key">{{ range.label }}</label>
            <input :id="key" v-model.number="form[key]" class="form-control" :class="outOfRange(key) ? 'is-invalid' : ''"
              type="number" :min="range.min" :max="range.max" step="1" />
            <div v-if="outOfRange(key)" class="invalid-feedback">必须在 {{ range.min }}–{{ range.max }} 之间</div>
            <div v-else class="form-text">范围 {{ range.min }}–{{ range.max }}</div>
          </div>
        </div>
      </div>
    </div>
    <div class="d-flex align-items-center gap-3 flex-wrap">
      <button type="button" class="btn btn-primary" :disabled="saving || hasInvalid || Object.keys(changed).length === 0"
        @click="save">
        <span v-if="saving" class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>
        {{ saving ? "保存中…" : "保存" }}
      </button>
      <span v-if="saveError" class="text-danger-emphasis small">{{ saveError }}</span>
      <span v-else-if="savedNote" class="text-success-emphasis small">{{ savedNote }}</span>
      <span v-else-if="Object.keys(changed).length === 0" class="text-body-tertiary small">没有改动要保存</span>
    </div>
  </section>
</template>

<style scoped>
/* A two-column definition list inside a card: Bootstrap's grid would put every row in its own `.row`, which is
   nine divs per panel for something that is a label and a value. */
.settings-form {
  max-width: 48rem;
}
.facts {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: .25rem .75rem;
  font-size: .88rem;
  margin: 0;
}
.facts dt {
  color: var(--bs-secondary-color);
  font-weight: 400;
}
.facts dd {
  margin: 0;
  word-break: break-word;
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: .35rem;
}
</style>
