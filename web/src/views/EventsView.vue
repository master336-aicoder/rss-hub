<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import {
  INGEST_PATH,
  TOKEN_SCOPES,
  eventsApi,
  tokensApi,
  type ApiToken,
  type CreatedToken,
  type EventStream,
  type TokenScope,
  type Visibility,
} from "../api/events";
import { ApiError } from "../api/errors";
import { ago } from "../utils/time";

const streams = ref<EventStream[]>([]);
const tokens = ref<ApiToken[]>([]);
const error = ref("");
const notice = ref("");
const loading = ref(false);

/** `slug` empty means create; a saved slug means PATCH, which cannot rename the slug (it is a public URL). */
const streamForm = reactive({ slug: "", title: "", description: "", editing: false });
const tokenForm = reactive({ name: "", scope: "ingest" as TokenScope });
/** The plaintext a created token returns, held only until the operator dismisses it — the row keeps a hash. */
const revealed = ref<CreatedToken | null>(null);

const origin = computed(() => window.location.origin);

function note(cause: unknown) {
  error.value = cause instanceof ApiError ? `${cause.code}: ${cause.message}` : String(cause);
}

onMounted(load);

async function load() {
  loading.value = true;
  error.value = "";
  try {
    const [streamRows, tokenRows] = await Promise.all([eventsApi.list(), tokensApi.list()]);
    streams.value = streamRows;
    tokens.value = tokenRows;
  } catch (cause) {
    note(cause);
  } finally {
    loading.value = false;
  }
}

function startCreate() {
  Object.assign(streamForm, { slug: "", title: "", description: "", editing: false });
}

function startEdit(stream: EventStream) {
  Object.assign(streamForm, {
    slug: stream.slug,
    title: stream.title,
    description: stream.description ?? "",
    editing: true,
  });
}

async function saveStream() {
  error.value = "";
  notice.value = "";
  try {
    if (streamForm.editing) {
      const updated = await eventsApi.update(streamForm.slug, {
        title: streamForm.title.trim(),
        description: streamForm.description.trim(),
      });
      streams.value = streams.value.map((row) => (row.slug === updated.slug ? updated : row));
      notice.value = `已更新「${updated.slug}」。`;
    } else {
      const created = await eventsApi.create({
        slug: streamForm.slug.trim(),
        title: streamForm.title.trim(),
        description: streamForm.description.trim() || undefined,
      });
      streams.value = [created, ...streams.value];
      notice.value = `已创建「${created.slug}」。机器现在可以往 ${INGEST_PATH}/${created.slug} 写事件。`;
    }
    startCreate();
  } catch (cause) {
    note(cause);
  }
}

/** FR-3.3: turning the inbox on backfills history, turning it off drops the virtual feed and its read state. */
async function toggleVisibility(stream: EventStream) {
  const wanted: Visibility = stream.visibility === "virtual_feed" ? "internal" : "virtual_feed";
  if (wanted === "internal" && !window.confirm(`「${stream.slug}」将从收件箱移除，已读状态一起删除。继续吗？`)) {
    return;
  }
  error.value = "";
  try {
    const updated = await eventsApi.setVisibility(stream.slug, wanted);
    streams.value = streams.value.map((row) => (row.slug === updated.slug ? updated : row));
  } catch (cause) {
    note(cause);
  }
}

async function removeStream(stream: EventStream) {
  if (!window.confirm(`删除事件流「${stream.slug}」及其 ${stream.itemCount} 条事件？`)) return;
  error.value = "";
  try {
    await eventsApi.remove(stream.slug);
    streams.value = streams.value.filter((row) => row.slug !== stream.slug);
    if (streamForm.slug === stream.slug) startCreate();
  } catch (cause) {
    note(cause);
  }
}

/** The secret exists only in this response; after a page reload it is gone, so show it big and once. */
async function createToken() {
  error.value = "";
  notice.value = "";
  try {
    const created = await tokensApi.create({ name: tokenForm.name.trim(), scope: tokenForm.scope });
    revealed.value = created;
    tokens.value = [created, ...tokens.value].map((row) =>
      row.id === created.id ? { id: row.id, name: row.name, scope: row.scope } : row,
    );
    tokenForm.name = "";
  } catch (cause) {
    note(cause);
  }
}

async function revokeToken(token: ApiToken) {
  if (!window.confirm(`吊销「${token.name}」？用它的任务会立刻开始收到 401。`)) return;
  error.value = "";
  try {
    await tokensApi.revoke(token.id);
    tokens.value = tokens.value.filter((row) => row.id !== token.id);
    if (revealed.value?.id === token.id) revealed.value = null;
  } catch (cause) {
    note(cause);
  }
}
</script>

<template>
  <section>
    <div class="d-flex flex-wrap align-items-center justify-content-between gap-2 mb-2">
      <h1 class="h4 mb-0 d-flex align-items-center gap-2">
        <i class="bi bi-lightning-charge text-primary" aria-hidden="true"></i>事件源与机器凭证
      </h1>
      <button type="button" class="btn btn-sm btn-outline-primary" :disabled="loading" @click="load">
        <span :class="loading ? 'spinner-border spinner-border-sm me-2' : 'bi bi-arrow-repeat me-1'"
          aria-hidden="true"></span>{{ loading ? "读取中…" : "重新读取" }}
      </button>
    </div>
    <p class="small text-body-secondary">
      机器用 <code>ingest</code> 作用域的 Bearer 令牌写事件，走的是不带 Cookie 的
      <code>POST {{ INGEST_PATH }}</code>；这个页面用的是登录会话，两边只共用同一个 slug。
    </p>
    <div v-if="error" class="alert alert-danger py-2 small">{{ error }}</div>
    <div v-if="notice" class="alert alert-success py-2 small">{{ notice }}</div>

    <div class="row g-3 align-items-start">
      <div class="col-12 col-xxl-7">
        <section class="card mb-3">
          <div class="card-header bg-body py-2 d-flex align-items-center justify-content-between">
            <h2 class="h6 mb-0">事件流（{{ streams.length }}）</h2>
          </div>
          <div class="table-responsive">
            <table class="table table-hover align-middle mb-0">
              <thead>
                <tr>
                  <th>slug</th>
                  <th>标题</th>
                  <th>收件箱</th>
                  <th>事件</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="stream in streams" :key="stream.slug">
                  <td><code>{{ stream.slug }}</code></td>
                  <td>
                    {{ stream.title }}
                    <span v-if="stream.description" class="d-block small text-body-secondary">{{ stream.description
                      }}</span>
                  </td>
                  <td class="text-nowrap">
                    <button type="button" class="btn btn-sm rounded-pill"
                      :class="stream.visibility === 'virtual_feed' ? 'btn-primary' : 'btn-outline-secondary'"
                      @click="toggleVisibility(stream)">
                      {{ stream.visibility === "virtual_feed" ? "进收件箱" : "仅内部" }}
                    </button>
                  </td>
                  <td>{{ stream.itemCount }}</td>
                  <td class="text-end text-nowrap">
                    <button type="button" class="btn btn-sm btn-outline-primary me-1"
                      @click="startEdit(stream)">编辑</button>
                    <button type="button" class="btn btn-sm btn-outline-danger"
                      @click="removeStream(stream)">删除</button>
                  </td>
                </tr>
                <tr v-if="!streams.length && !loading">
                  <td colspan="5" class="text-body-secondary">还没有事件流。先建一个，再把 CI 指过来。</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <form class="card mb-3" @submit.prevent="saveStream">
          <div class="card-body">
            <h3 class="h6 mb-3">{{ streamForm.editing ? `编辑「${streamForm.slug}」` : "新建事件流" }}</h3>
            <div class="mb-2">
              <label class="form-label small" for="stream-slug">
                slug<span v-if="streamForm.editing" class="text-body-secondary">（创建后不可改，它出现在公开 URL 里）</span>
              </label>
              <input id="stream-slug" v-model="streamForm.slug" class="form-control" :disabled="streamForm.editing"
                maxlength="64" required pattern="[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?" placeholder="ci-builds" />
            </div>
            <div class="mb-2">
              <label class="form-label small" for="stream-title">标题</label>
              <input id="stream-title" v-model="streamForm.title" class="form-control" maxlength="512" required />
            </div>
            <div class="mb-3">
              <label class="form-label small" for="stream-desc">说明（可选）</label>
              <textarea id="stream-desc" v-model="streamForm.description" class="form-control" rows="2"
                maxlength="2048"></textarea>
            </div>
            <div class="d-flex gap-2">
              <button type="submit" class="btn btn-sm btn-primary">{{ streamForm.editing ? "保存" : "创建" }}</button>
              <button v-if="streamForm.editing" type="button" class="btn btn-sm btn-outline-secondary"
                @click="startCreate">取消</button>
            </div>
          </div>
        </form>

        <pre class="sample">{{ `# CI 侧
curl -X POST ${origin}${INGEST_PATH} \\
  -H "Authorization: Bearer $RSSHUB_TOKEN" \\
  -H "Content-Type: application/json" \\
  -d '{"title":"build #42 failed","url":"${origin}/jobs/42","tags":["ci"],"stream":"ci-builds"}'` }}</pre>
      </div>

      <div class="col-12 col-xxl-5">
        <section class="card mb-3">
          <div class="card-header bg-body py-2">
            <h2 class="h6 mb-0">机器凭证（{{ tokens.length }}）</h2>
          </div>
          <div class="table-responsive">
            <table class="table table-hover align-middle mb-0">
              <thead>
                <tr>
                  <th>名称</th>
                  <th>作用域</th>
                  <th>创建</th>
                  <th>最近使用</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="token in tokens" :key="token.id">
                  <td>{{ token.name }}</td>
                  <td><code>{{ token.scope }}</code></td>
                  <td class="text-nowrap">{{ ago(token.createdAt) }}</td>
                  <td class="text-nowrap">{{ token.lastUsedAt ? ago(token.lastUsedAt) : "从未" }}</td>
                  <td class="text-end">
                    <button type="button" class="btn btn-sm btn-outline-danger"
                      @click="revokeToken(token)">吊销</button>
                  </td>
                </tr>
                <tr v-if="!tokens.length && !loading">
                  <td colspan="5" class="text-body-secondary">还没有令牌。</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <form class="card mb-3" @submit.prevent="createToken">
          <div class="card-body">
            <h3 class="h6 mb-3">新建令牌</h3>
            <div class="mb-2">
              <label class="form-label small" for="token-name">名称</label>
              <input id="token-name" v-model="tokenForm.name" class="form-control" maxlength="64" required
                placeholder="jenkins" />
            </div>
            <div class="mb-3">
              <label class="form-label small" for="token-scope">作用域</label>
              <select id="token-scope" v-model="tokenForm.scope" class="form-select">
                <option v-for="scope in TOKEN_SCOPES" :key="scope" :value="scope">{{ scope }}</option>
              </select>
              <div class="form-text">写事件只需要 <code>ingest</code>。</div>
            </div>
            <button type="submit" class="btn btn-sm btn-primary">创建</button>
          </div>
        </form>

        <div v-if="revealed" class="alert alert-warning">
          <p class="mb-2">
            <i class="bi bi-key-fill me-1" aria-hidden="true"></i>这是唯一一次显示。<strong>{{ revealed.name }}</strong>（{{
              revealed.scope }}）：库里只存 SHA-256，刷新后就再也读不到。
          </p>
          <code class="secret-value d-block mb-2">{{ revealed.secret }}</code>
          <button type="button" class="btn btn-sm btn-outline-dark" @click="revealed = null">我已经存好了</button>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
/* Two shapes Bootstrap does not ship: a code block that scrolls sideways instead of wrapping a curl line, and a
   secret that must stay readable as one unbroken token you can select. */
.sample {
  background: var(--bs-secondary-bg);
  border: 1px solid var(--bs-border-color);
  border-radius: var(--bs-border-radius);
  font-size: .82rem;
  margin: 0;
  overflow-x: auto;
  padding: .75rem .9rem;
  white-space: pre;
}
.secret-value {
  font-size: .95rem;
  letter-spacing: .04em;
  word-break: break-all;
}
</style>
