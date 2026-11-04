<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { ApiError } from "../api/errors";
import {
  feedsApi,
  feedLabel,
  OPERATOR_STATES,
  type BatchPlan,
  type BatchPlanItem,
  type Discovery,
  type Feed,
} from "../api/feeds";
import { opmlApi, OPML_EXPORT_PATH, type ImportResult } from "../api/opml";
import { useFeedsStore } from "../stores/feeds";
import { ago } from "../utils/time";

const feeds = useFeedsStore();

const operatorStates: readonly string[] = OPERATOR_STATES;

const url = ref("");
const group = ref("");
const discovery = ref<Discovery | undefined>(undefined);
const chosen = ref("");
const adding = ref(false);
const message = ref("");
const failed = ref(false);
const importResult = ref<ImportResult | undefined>(undefined);
/** Which row is waiting on "立即抓取", so only that row's button reads as busy. */
const fetching = ref<number | undefined>(undefined);

/** A Problem Details code is the part an operator can paste into an issue, so it goes in front of the message. */
function describe(error: unknown): string {
  return error instanceof ApiError ? `${error.code}: ${error.message}` : String(error);
}

function fail(text: string): string {
  failed.value = true;
  return text;
}

function pass(text: string): string {
  failed.value = false;
  return text;
}

onMounted(() => {
  void feeds.load();
  void feeds.loadPolicy();
});

/**
 * Everything the last probe said. The add button reads `chosen`, so a probe that failed silently left the previous
 * address ready to subscribe — which is how a page you meant to parse twice ends up subscribing the wrong feed.
 */
function resetDiscovery(): void {
  discovery.value = undefined;
  chosen.value = "";
  message.value = "";
  failed.value = false;
}

/** A new address invalidates the old parse, so the stale candidate list goes with it. */
watch(url, resetDiscovery);

/** Discovery is its own round trip so a failed one never looks like a failed write (03 §4.4). */
async function probe() {
  adding.value = true;
  resetDiscovery();
  try {
    const found = await feedsApi.discover(url.value);
    discovery.value = found;
    chosen.value = found.feedUrl ?? found.candidates[0]?.url ?? "";
    message.value = found.feedUrl
      ? pass(`找到 ${found.feedUrl}${found.candidates.length > 1 ? "（这一步给了候选，可以换或点候选后面的添加）" : ""}`)
      : fail(`这个地址上没有 feed${found.note ? `（${found.note}）` : ""}`);
  } catch (error) {
    discovery.value = undefined;
    chosen.value = "";
    message.value = fail(describe(error));
  } finally {
    adding.value = false;
  }
}

async function add(target?: string) {
  const address = target || chosen.value || url.value;
  if (!address) return;
  adding.value = true;
  try {
    const feed = await feeds.add(address, group.value.trim() || undefined);
    message.value = pass(`已订阅：${feedLabel(feed)}，正在抓取`);
    url.value = "";
    group.value = "";
    resetDiscovery();
  } catch (error) {
    message.value = fail(describe(error));
  } finally {
    adding.value = false;
  }
}

/** Reads a change event's value so the template needs no cast at each call site. */
function value(event: Event): string {
  return (event.target as HTMLInputElement | HTMLSelectElement).value;
}

/** One entry point for every row mutation: the store performs the call, this only reports how it went. */
async function act(done: string, run: () => Promise<unknown>): Promise<void> {
  try {
    await run();
    message.value = pass(done);
  } catch (error) {
    message.value = fail(describe(error));
  }
}

/**
 * An empty box means "let the site name this feed again", which the patch spells as an empty string — sending
 * `undefined` would leave the override in place.
 */
function saveTitle(feed: Feed, event: Event): Promise<void> {
  return act("标题已保存", () => feeds.update(feed.id, { title: value(event).trim() }));
}

/** The number the box holds, or `0` for an empty box — which is the patch that drops the override. */
function saveInterval(feed: Feed, event: Event): Promise<void> {
  const raw = value(event).trim();
  const minutes = raw === "" ? 0 : Number(raw);
  return act(
    raw === "" ? "抓取间隔已改回跟随站点" : `抓取间隔已设为 ${minutes} 分钟`,
    () => feeds.update(feed.id, { intervalMinutes: minutes }),
  );
}

/**
 * The refresh call only says the queue took the job; the exchange happens on the dispatcher's worker. Waiting for
 * the row to report a newer `lastFetchedAt` is what lets this button tell the truth about whether it fetched.
 */
async function fetchNow(feed: Feed): Promise<void> {
  const before = feed.lastFetchedAt;
  fetching.value = feed.id;
  try {
    await feeds.refresh(feed.id);
    const done = await feeds.waitFetched(feed.id, before);
    if (done === undefined) {
      message.value = fail(`已排队，但这一轮还没抓完（可能正被同一主机的其他订阅占用），稍后刷新查看`);
      return;
    }
    const grew = done.entryCount > feed.entryCount ? `，新增 ${done.entryCount - feed.entryCount} 条` : "";
    message.value = done.lastError
      ? fail(`抓取完成但未成功：${done.lastError}`)
      : pass(`已抓取${grew}，下次按策略更新`);
  } catch (error) {
    message.value = fail(describe(error));
  } finally {
    fetching.value = undefined;
  }
}

/** Deleting cascades to the entries and the queued fetch, so it is the one row action that asks first. */
async function removeNow(feed: Feed): Promise<void> {
  if (!window.confirm(`删除「${feedLabel(feed)}」及其全部条目？此操作不可撤销。`)) return;
  await act("订阅已删除", () => feeds.remove(feed.id));
}

async function onImport(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  try {
    importResult.value = await opmlApi.import(await file.text());
    message.value = pass(
      `OPML 导入：新增 ${importResult.value.imported} 条，跳过 ${importResult.value.skipped} 条`,
    );
    await feeds.load();
  } catch (error) {
    message.value = fail(describe(error));
  } finally {
    input.value = "";
  }
}

/**
 * Batch subscribe (F16). Its own state and its own alert box, because the two write paths answer different
 * questions: the single add asks "is this the right feed?" and the batch asks "how did you read these twenty lines?".
 */
const batchOpen = ref(false);
const batchText = ref("");
const batchGroup = ref("");
const batchPlan = ref<BatchPlan | undefined>(undefined);
const batchBusy = ref(false);
const batchMessage = ref("");
const batchFailed = ref(false);

/** Mirrors {@code SubscriptionService.MAX_BATCH_ITEMS}, so the box states its own limit before the server has to. */
const BATCH_LIMIT = 200;

const batchLines = computed(() => batchText.value.split(/\r\n|\r|\n/).filter((line) => line.trim() !== "").length);

/** The five verdicts the backend sends, in the words the table shows. The raw `reason` stays next to them. */
const BATCH_STATUS: Record<string, { label: string; pill: string }> = {
  ready: { label: "可添加", pill: "text-bg-primary" },
  added: { label: "已添加", pill: "text-bg-success" },
  duplicate: { label: "重复", pill: "text-bg-secondary" },
  refused: { label: "被拒绝", pill: "text-bg-warning" },
  invalid: { label: "读不懂", pill: "text-bg-danger" },
};

function batchStatus(item: BatchPlanItem): { label: string; pill: string } {
  return BATCH_STATUS[item.status] ?? { label: item.status, pill: "text-bg-secondary" };
}

function batchPass(text: string): string {
  batchFailed.value = false;
  return text;
}

function batchFail(text: string): string {
  batchFailed.value = true;
  return text;
}

/** The F10 rule, applied to the paste box: a new text invalidates the last reading of the old one. */
function resetBatchPlan(): void {
  batchPlan.value = undefined;
  batchMessage.value = "";
  batchFailed.value = false;
}

watch(batchText, resetBatchPlan);

const batchReady = computed(() => batchPlan.value?.ready ?? 0);

/** Reads the paste without writing it, so an operator sees the split before committing to twenty subscriptions. */
async function parseBatch() {
  batchBusy.value = true;
  resetBatchPlan();
  try {
    const found = await feedsApi.batchPlan(batchText.value);
    batchPlan.value = found;
    const skipped = found.items.length - found.ready;
    batchMessage.value = found.ready
      ? batchPass(
          `读到 ${found.items.length} 条，可添加 ${found.ready} 条` +
            (skipped ? `，${skipped} 条不会写入（下表说明了原因）` : ""),
        )
      : batchFail(`读到 ${found.items.length} 条，没有一条可以添加（下表说明了原因）`);
  } catch (error) {
    batchMessage.value = batchFail(describe(error));
  } finally {
    batchBusy.value = false;
  }
}

/**
 * Writes the paste. The backend re-reads the text rather than trusting the table above, so what lands is what the
 * lines say now — including lines that became duplicates since the preview. A paste that added nothing keeps its text
 * in the box, because the next thing to do is fix the lines the table says were refused, not type the list again.
 */
async function applyBatch() {
  batchBusy.value = true;
  try {
    const result = await feedsApi.batchAdd(batchText.value, batchGroup.value.trim() || undefined);
    const skipped = result.items.length - result.added;
    if (result.added > 0) {
      batchText.value = "";
      batchPlan.value = undefined;
      batchMessage.value = batchPass(
        `已订阅 ${result.added} 条` + (skipped ? `，${skipped} 条跳过` : "") + "，正在按顺序抓取",
      );
    } else {
      batchPlan.value = { items: result.items, ready: 0 };
      batchMessage.value = batchFail(`没有新订阅写入，${skipped} 条已存在或被拒绝`);
    }
    await feeds.load();
  } catch (error) {
    batchMessage.value = batchFail(describe(error));
  } finally {
    batchBusy.value = false;
  }
}
</script>

<template>
  <section>
    <div class="d-flex flex-wrap align-items-center justify-content-between gap-2 mb-3">
      <h1 class="h4 mb-0 d-flex align-items-center gap-2">
        <i class="bi bi-rss text-primary" aria-hidden="true"></i>订阅管理
      </h1>
      <div class="d-flex align-items-center gap-2">
        <a :href="OPML_EXPORT_PATH" download class="btn btn-sm btn-outline-secondary">
          <i class="bi bi-download me-1" aria-hidden="true"></i>导出 OPML
        </a>
        <label class="btn btn-sm btn-outline-secondary mb-0">
          <i class="bi bi-upload me-1" aria-hidden="true"></i>导入 OPML
          <input class="d-none" type="file" accept=".opml,.xml,text/xml,application/xml" @change="onImport">
        </label>
        <span v-if="importResult" class="small text-body-secondary">
          {{ importResult.imported }} 条新增，{{ importResult.skipped }} 条跳过（已有或被 SSRF 规则拒绝）
        </span>
      </div>
    </div>

    <div class="card mb-3">
      <div class="card-body">
        <form class="row g-2 align-items-center" @submit.prevent="add()">
          <div class="col-12 col-lg">
            <label class="form-label small text-body-secondary mb-1" for="feed-url">订阅地址</label>
            <input id="feed-url" v-model="url" class="form-control" type="url" required
              placeholder="https://example.com/feed.xml" aria-label="订阅地址">
          </div>
          <div class="col-12 col-md-4 col-lg-3">
            <label class="form-label small text-body-secondary mb-1" for="feed-group">分组</label>
            <input id="feed-group" v-model="group" class="form-control" placeholder="分组（可选）" aria-label="分组">
          </div>
          <div class="col-12 col-lg-auto d-flex gap-2">
            <button type="button" class="btn btn-outline-primary" :disabled="adding || !url" @click="probe">
              <i class="bi bi-search me-1" aria-hidden="true"></i>解析
            </button>
            <button type="submit" class="btn btn-primary" :disabled="adding || !(chosen || url)">
              <i class="bi bi-plus-lg me-1" aria-hidden="true"></i>添加
            </button>
          </div>
        </form>

        <div v-if="discovery?.candidates.length" class="border rounded bg-body-tertiary p-2 mt-3">
          <p class="small text-body-secondary mb-2">
            这个页面提供了 {{ discovery.candidates.length }} 个 feed，选一个或直接添加：
          </p>
          <div v-for="candidate in discovery.candidates" :key="candidate.url"
            class="d-flex align-items-center justify-content-between gap-3 py-1">
            <div class="form-check mb-0 flex-grow-1">
              <input v-model="chosen" class="form-check-input" type="radio" name="candidate"
                :value="candidate.url" :id="`candidate-${candidate.url}`">
              <label class="form-check-label small text-break" :for="`candidate-${candidate.url}`">
                {{ candidate.title ?? candidate.url }}
                <code class="ms-1 text-body-secondary">{{ candidate.type }}</code>
              </label>
            </div>
            <button type="button" class="btn btn-sm btn-outline-primary flex-shrink-0" :disabled="adding"
              @click="add(candidate.url)">直接添加</button>
          </div>
        </div>

        <div v-if="message" class="alert py-2 small mt-3 mb-0" :class="failed ? 'alert-danger' : 'alert-success'"
          role="status">
          <i class="bi me-1" :class="failed ? 'bi-exclamation-circle' : 'bi-check-circle'" aria-hidden="true"></i>
          {{ message }}
        </div>
      </div>
    </div>

    <div class="card mb-3">
      <div class="card-header d-flex align-items-center justify-content-between gap-2 py-2">
        <span class="small fw-semibold d-flex align-items-center gap-2">
          <i class="bi bi-clipboard2-plus text-primary" aria-hidden="true"></i>批量订阅
        </span>
        <div class="d-flex align-items-center gap-2">
          <span v-if="!batchOpen && batchText" class="small text-body-secondary text-truncate">
            已粘贴 {{ batchLines }} 行
          </span>
          <button type="button" class="btn btn-sm btn-outline-secondary" :aria-expanded="batchOpen"
            @click="batchOpen = !batchOpen">
            {{ batchOpen ? "收起" : "展开" }}
          </button>
        </div>
      </div>

      <div v-if="batchOpen" class="card-body">
        <label class="form-label small text-body-secondary mb-1" for="batch-text">一次粘贴多个地址</label>
        <textarea id="batch-text" v-model="batchText" class="form-control font-monospace" rows="5"
          placeholder="每行一个地址，也支持 , ; 、 空格 分隔&#10;地址前的名字会成为订阅标题：阮一峰: https://www.ruanyifeng.com/blog/atom.xml"></textarea>
        <p class="small text-body-secondary mb-2 mt-2">
          分隔符：逗号、分号、顿号、空格与回车；冒号只在不是协议（<code>://</code>）也不是端口（<code>:8443</code>）时拆开，
          拆开后的前半截当作这条订阅的名字。一次最多 {{ BATCH_LIMIT }} 条。批量订阅不会解析页面，粘贴网页地址会得到
          <code>not_a_feed</code>，请改用上面的“解析”。
        </p>
        <div class="row g-2 align-items-end">
          <div class="col-12 col-md-4">
            <label class="form-label small text-body-secondary mb-1" for="batch-group">这批放进的分组</label>
            <input id="batch-group" v-model="batchGroup" class="form-control" placeholder="分组（可选）"
              aria-label="批量分组">
          </div>
          <div class="col-12 col-md-auto d-flex gap-2">
            <button type="button" class="btn btn-outline-primary" :disabled="batchBusy || !batchText"
              @click="parseBatch">
              <span v-if="batchBusy" class="spinner-border spinner-border-sm me-1" aria-hidden="true"></span>
              <i v-else class="bi bi-search me-1" aria-hidden="true"></i>解析
            </button>
            <button type="button" class="btn btn-primary" :disabled="batchBusy || batchReady === 0"
              @click="applyBatch">
              添加 {{ batchReady }} 条
            </button>
            <button type="button" class="btn btn-outline-secondary" :disabled="batchBusy || !batchText"
              @click="batchText = ''">清空</button>
          </div>
        </div>

        <div v-if="batchPlan" class="table-responsive border rounded bg-body-tertiary mt-3">
          <table class="table table-sm align-middle mb-0">
            <thead>
              <tr>
                <th class="small">行</th>
                <th class="small">名称</th>
                <th class="small">地址</th>
                <th class="small">结果</th>
                <th class="small">说明</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(item, index) in batchPlan.items" :key="`${item.lineNo}-${index}`">
                <td class="small text-body-secondary">{{ item.lineNo }}</td>
                <td class="small">{{ item.label ?? "—" }}</td>
                <td class="small text-break">
                  <code>{{ item.address }}</code>
                  <span v-if="item.url && item.url !== item.address" class="d-block text-body-tertiary">
                    → {{ item.url }}
                  </span>
                </td>
                <td>
                  <span class="badge rounded-pill" :class="batchStatus(item).pill">{{ batchStatus(item).label }}</span>
                </td>
                <td class="small text-body-secondary">{{ item.reason ?? "" }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div v-if="batchMessage" class="alert py-2 small mt-3 mb-0"
          :class="batchFailed ? 'alert-danger' : 'alert-success'" role="status">
          <i class="bi me-1" :class="batchFailed ? 'bi-exclamation-circle' : 'bi-check-circle'"
            aria-hidden="true"></i>
          {{ batchMessage }}
        </div>
      </div>
    </div>

    <div class="card">
      <div class="card-body p-0">
        <p v-if="feeds.loading" class="text-body-secondary p-3 mb-0">
          <span class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>加载中…
        </p>
        <p v-else-if="feeds.error" class="alert alert-danger py-2 small m-3 mb-0">{{ feeds.error }}</p>
        <div v-else-if="feeds.feeds.length === 0" class="text-body-secondary p-4 text-center">
          <i class="bi bi-inbox fs-1 d-block opacity-25 mb-2" aria-hidden="true"></i>还没有订阅。
        </div>
        <div v-else class="table-responsive">
          <table class="table table-hover align-middle mb-0">
            <thead>
              <tr>
                <th>订阅</th>
                <th>分组</th>
                <th>状态</th>
                <th>抓取间隔</th>
                <th>未读 / 条目</th>
                <th>上次抓取</th>
                <th>下次</th>
                <th class="text-end">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="feed in feeds.feeds" :key="feed.id">
                <td class="col-wide">
                  <input class="form-control form-control-sm" :value="feed.customTitle ?? ''"
                    :placeholder="feed.title ?? feed.canonicalUrl" aria-label="订阅标题" @change="saveTitle(feed, $event)">
                  <a :href="feed.canonicalUrl" target="_blank" rel="noreferrer noopener"
                    class="d-block small text-body-secondary text-break mt-1"
                    title="订阅地址不可更改：换地址请删除后重新订阅，条目与队列都挂在它上面">{{ feed.canonicalUrl }}</a>
                  <span v-if="feed.requestedUrl !== feed.canonicalUrl" class="d-block small text-warning-emphasis">
                    <i class="bi bi-arrow-right-circle me-1" aria-hidden="true"></i>原地址 {{ feed.requestedUrl }}（来源搬过家）
                  </span>
                  <span v-if="feed.format" class="small text-body-tertiary">{{ feed.format }}</span>
                </td>
                <td>
                  <input class="form-control form-control-sm" :value="feed.groupName ?? ''" placeholder="无分组"
                    aria-label="分组" @change="act('分组已保存', () => feeds.update(feed.id, { group: value($event) }))">
                </td>
                <td>
                  <select class="form-select form-select-sm"
                    :value="operatorStates.includes(feed.state) ? feed.state : 'active'" aria-label="状态"
                    @change="act('状态已保存', () => feeds.update(feed.id, { state: value($event) }))">
                    <option v-for="name in OPERATOR_STATES" :key="name" :value="name">{{ name }}</option>
                    <option v-if="!operatorStates.includes(feed.state)" :value="feed.state" disabled>
                      {{ feed.state }}
                    </option>
                  </select>
                </td>
                <td>
                  <input class="form-control form-control-sm" type="number"
                    :min="feeds.policy?.minIntervalMinutes ?? 1" :value="feed.fetchIntervalMinutes ?? ''"
                    :placeholder="`跟随站点（默认 ${feeds.policy?.defaultIntervalMinutes ?? 60} 分钟）`"
                    aria-label="抓取间隔（分钟）" @change="saveInterval(feed, $event)">
                  <span v-if="feed.ttlMinutes" class="small text-body-tertiary">站点声明 {{ feed.ttlMinutes }} 分钟</span>
                </td>
                <td class="text-nowrap">
                  <span class="badge rounded-pill"
                    :class="feed.unreadCount > 0 ? 'text-bg-danger' : 'text-bg-secondary'">{{ feed.unreadCount }}</span>
                  <span class="ms-1">{{ feed.entryCount }}</span>
                </td>
                <td class="small">
                  {{ ago(feed.lastFetchedAt) }}
                  <span v-if="feed.failCount > 0" class="d-block text-danger-emphasis">失败 {{ feed.failCount }} 次</span>
                  <span v-if="feed.lastError" class="d-block text-danger-emphasis text-break">{{ feed.lastError }}</span>
                  <span v-if="feed.avgFetchMs" class="text-body-tertiary"> {{ feed.avgFetchMs }}ms</span>
                </td>
                <td class="small text-nowrap">{{ feed.nextFetchAt ? ago(feed.nextFetchAt) : "不在队列" }}</td>
                <td class="text-end text-nowrap">
                  <button type="button" class="btn btn-sm btn-outline-primary" :disabled="fetching === feed.id"
                    @click="fetchNow(feed)">
                    <span v-if="fetching === feed.id" class="spinner-border spinner-border-sm me-1" aria-hidden="true"></span>
                    <i v-else class="bi bi-arrow-repeat me-1" aria-hidden="true"></i>{{ fetching === feed.id ? "抓取中…" : "立即抓取" }}
                  </button>
                  <button type="button" class="btn btn-sm btn-outline-secondary ms-1"
                    @click="act('该订阅已全部标记为已读', () => feeds.markFeedRead(feed.id))">全部已读</button>
                  <button type="button" class="btn btn-sm btn-outline-danger ms-1" @click="removeNow(feed)">删除</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
/* The subscription column carries the editable title and the read-only url, so it needs the most room. */
.col-wide {
  min-width: 18rem;
}
</style>
