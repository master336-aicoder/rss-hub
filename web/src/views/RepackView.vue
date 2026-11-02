<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import {
  OUTLET_SUFFIXES,
  outletUrl,
  repacksApi,
  type RepackAuditRow,
  type RepackDetail,
  type RepackMember,
  type RepackSummary,
} from "../api/repacks";
import { feedsApi, feedLabel, type Feed } from "../api/feeds";
import { ApiError } from "../api/errors";
import { ago } from "../utils/time";

const summaries = ref<RepackSummary[]>([]);
const feeds = ref<Feed[]>([]);
const detail = ref<RepackDetail | null>(null);
const audit = ref<RepackAuditRow[]>([]);
const error = ref("");
const notice = ref("");
const loading = ref(false);

/** `slug` empty means create; a saved slug means PATCH, which cannot rename it — the slug is the public URL. */
const repackForm = reactive({ slug: "", title: "", description: "", editing: false });
const addForm = reactive({ feedId: 0 });

/** A feed already in the collection cannot be added twice, so it is not offered. */
const memberIds = computed(() => new Set((detail.value?.members ?? []).map((member) => member.feedId)));
const pickable = computed(() => feeds.value.filter((feed) => !memberIds.value.has(feed.id)));
const names = computed(() => new Map(feeds.value.map((feed) => [feed.id, feedLabel(feed)])));

const ACTION_LABELS: Record<string, string> = {
  add: "加入集合",
  remove: "移出集合",
  full_content_on: "开启全文",
  full_content_off: "关闭全文",
};

function labelOf(member: RepackMember): string {
  return member.title || member.canonicalUrl;
}

function note(cause: unknown) {
  error.value = cause instanceof ApiError ? `${cause.code}: ${cause.message}` : String(cause);
}

onMounted(load);

async function load() {
  loading.value = true;
  error.value = "";
  try {
    const [rows, feedRows] = await Promise.all([repacksApi.list(), feedsApi.list()]);
    summaries.value = rows;
    feeds.value = feedRows;
    if (detail.value) await open(detail.value.slug);
  } catch (cause) {
    note(cause);
  } finally {
    loading.value = false;
  }
}

async function open(slug: string) {
  error.value = "";
  try {
    const [row, trail] = await Promise.all([repacksApi.get(slug), repacksApi.audit(slug)]);
    detail.value = row;
    audit.value = trail;
  } catch (cause) {
    note(cause);
  }
}

function close() {
  detail.value = null;
  audit.value = [];
}

/**
 * A member write already returns the whole collection, so only the two derived numbers are re-read. Reloading them
 * here is what keeps the list's member count and the audit trail honest after a flag the backend may have ignored.
 */
async function apply(updated: RepackDetail) {
  detail.value = updated;
  audit.value = await repacksApi.audit(updated.slug);
  summaries.value = await repacksApi.list();
}

function startCreate() {
  Object.assign(repackForm, { slug: "", title: "", description: "", editing: false });
}

function startEdit(row: RepackSummary) {
  Object.assign(repackForm, {
    slug: row.slug,
    title: row.title,
    description: row.description ?? "",
    editing: true,
  });
}

async function saveRepack() {
  error.value = "";
  notice.value = "";
  try {
    if (repackForm.editing) {
      const updated = await repacksApi.update(repackForm.slug, {
        title: repackForm.title.trim(),
        description: repackForm.description.trim(),
      });
      await apply(updated);
      notice.value = `已更新「${updated.slug}」。`;
    } else {
      const created = await repacksApi.create({
        slug: repackForm.slug.trim(),
        title: repackForm.title.trim(),
        description: repackForm.description.trim() || undefined,
      });
      summaries.value = await repacksApi.list();
      detail.value = created;
      audit.value = [];
      notice.value = `已创建「${created.slug}」，现在可以往里加订阅源了。`;
    }
    startCreate();
  } catch (cause) {
    note(cause);
  }
}

async function removeRepack(row: RepackSummary) {
  if (!window.confirm(`删除聚合「${row.slug}」？它的 ${row.memberCount} 个成员关系一起删除，公开出口随即消失。`)) {
    return;
  }
  error.value = "";
  notice.value = "";
  try {
    await repacksApi.remove(row.slug);
    if (detail.value?.slug === row.slug) close();
    notice.value = `已删除「${row.slug}」。`;
    await load();
    if (summaries.value.length > 0) {
      const first = summaries.value[0];
      await open(first.slug);
    }
  } catch (cause) {
    note(cause);
  }
}

async function addSource() {
  if (!detail.value || addForm.feedId <= 0) {
    error.value = "请先选择一个已订阅的源。";
    return;
  }
  error.value = "";
  notice.value = "";
  try {
    const updated = await repacksApi.addSource(detail.value.slug, addForm.feedId);
    addForm.feedId = 0;
    await apply(updated);
    notice.value = "已加入，默认只转发摘要。";
  } catch (cause) {
    note(cause);
  }
}

/** FR-7.2: full text is a per-site decision the operator states out loud, and the audit log keeps the statement. */
async function toggleFullContent(member: RepackMember) {
  const wanted = !member.fullContent;
  if (
    wanted &&
    !window.confirm(
      `转发「${labelOf(member)}」的全文会把该站点的正文原样再发布一次，转载授权由你确认。` +
        "这一步会写进审计日志。继续吗？",
    )
  ) {
    return;
  }
  error.value = "";
  notice.value = "";
  try {
    const updated = await repacksApi.setFullContent(detail.value?.slug ?? "", member.feedId, wanted);
    await apply(updated);
    notice.value = wanted ? "已改为转发全文。" : "已退回只转发摘要。";
  } catch (cause) {
    note(cause);
  }
}

async function removeSource(member: RepackMember) {
  if (!detail.value) return;
  if (!window.confirm(`把「${labelOf(member)}」移出「${detail.value.slug}」？`)) return;
  error.value = "";
  try {
    const updated = await repacksApi.removeSource(detail.value.slug, member.feedId);
    await apply(updated);
  } catch (cause) {
    note(cause);
  }
}
</script>

<template>
  <section>
    <div class="d-flex flex-wrap align-items-center justify-content-between gap-2 mb-2">
      <h1 class="h4 mb-0 d-flex align-items-center gap-2">
        <i class="bi bi-collection text-primary" aria-hidden="true"></i>聚合再分发
      </h1>
      <button type="button" class="btn btn-sm btn-outline-primary" :disabled="loading" @click="load">
        <span :class="loading ? 'spinner-border spinner-border-sm me-2' : 'bi bi-arrow-repeat me-1'"
          aria-hidden="true"></span>{{ loading ? "读取中…" : "重新读取" }}
      </button>
    </div>
    <p class="small text-body-secondary">
      把若干已订阅的源映射成一个公开出口（FR-7.1）。每条条目都带 <code>&lt;source&gt;</code> 原出处、
      <code>link</code> 指向原文；默认只转发摘要，全文要按站点逐个开启（FR-7.2）。
    </p>
    <div v-if="error" class="alert alert-danger py-2 small">{{ error }}</div>
    <div v-if="notice" class="alert alert-success py-2 small">{{ notice }}</div>

    <div class="row g-3 align-items-start">
      <div class="col-12 col-xxl-6">
        <section class="card mb-3">
          <div class="card-header bg-body py-2">
            <h2 class="h6 mb-0">聚合集合（{{ summaries.length }}）</h2>
          </div>
          <div class="table-responsive">
            <table class="table table-hover align-middle mb-0">
              <thead>
                <tr>
                  <th>slug</th>
                  <th>标题</th>
                  <th>成员</th>
                  <th>出口</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in summaries" :key="row.slug">
                  <td>
                    <button type="button" class="btn btn-link btn-sm p-0" @click="open(row.slug)">
                      <code>{{ row.slug }}</code>
                    </button>
                  </td>
                  <td>
                    {{ row.title }}
                    <span v-if="row.description" class="d-block small text-body-secondary">{{ row.description }}</span>
                  </td>
                  <td><span class="badge rounded-pill text-bg-secondary">{{ row.memberCount }}</span></td>
                  <td class="text-nowrap">
                    <a v-for="suffix in OUTLET_SUFFIXES" :key="suffix" :href="outletUrl(row.publicUrl, suffix)"
                      target="_blank" rel="noreferrer" class="btn btn-sm btn-outline-primary rounded-pill me-1">{{
                      suffix }}</a>
                  </td>
                  <td class="text-end text-nowrap">
                    <button type="button" class="btn btn-sm btn-outline-primary me-1"
                      @click="startEdit(row)">编辑</button>
                    <button type="button" class="btn btn-sm btn-outline-danger"
                      @click="removeRepack(row)">删除</button>
                  </td>
                </tr>
                <tr v-if="!summaries.length && !loading">
                  <td colspan="5" class="text-body-secondary">还没有聚合集合。先建一个，再往里加已订阅的源。</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <form class="card" @submit.prevent="saveRepack">
          <div class="card-body">
            <h3 class="h6 mb-3">{{ repackForm.editing ? `编辑「${repackForm.slug}」` : "新建聚合集合" }}</h3>
            <div class="mb-2">
              <label class="form-label small" for="repack-slug">
                slug<span v-if="repackForm.editing" class="text-body-secondary">（创建后不可改，它出现在公开 URL 里）</span>
              </label>
              <input id="repack-slug" v-model="repackForm.slug" class="form-control" :disabled="repackForm.editing"
                maxlength="64" required pattern="[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?" placeholder="weekly-picks" />
            </div>
            <div class="mb-2">
              <label class="form-label small" for="repack-title">标题</label>
              <input id="repack-title" v-model="repackForm.title" class="form-control" maxlength="512" required />
            </div>
            <div class="mb-3">
              <label class="form-label small" for="repack-desc">
                说明（可选，RSS 2.0 的频道描述必填，留空时后端会生成一句）
              </label>
              <textarea id="repack-desc" v-model="repackForm.description" class="form-control" rows="2"
                maxlength="2048"></textarea>
            </div>
            <div class="d-flex gap-2">
              <button type="submit" class="btn btn-sm btn-primary">{{ repackForm.editing ? "保存" : "创建" }}</button>
              <button v-if="repackForm.editing" type="button" class="btn btn-sm btn-outline-secondary"
                @click="startCreate">取消</button>
            </div>
          </div>
        </form>
      </div>

      <div class="col-12 col-xxl-6">
        <div v-if="!detail" class="card">
          <div class="card-body text-center text-body-secondary py-5">
            <i class="bi bi-collection fs-1 d-block opacity-25 mb-2" aria-hidden="true"></i>
            未选择集合：点左侧的 slug 查看成员与全文开关的审计日志。
          </div>
        </div>

        <template v-else>
          <section class="card mb-3">
            <div class="card-header bg-body py-2">
              <h2 class="h6 mb-0">「{{ detail.slug }}」的成员（{{ detail.members.length }}）</h2>
              <p class="small text-body-secondary mb-0">
                公开地址：<a :href="detail.publicUrl" target="_blank" rel="noreferrer">{{ detail.publicUrl }}</a>
              </p>
            </div>
            <div class="table-responsive">
              <table class="table table-hover align-middle mb-0">
                <thead>
                  <tr>
                    <th>源</th>
                    <th>条目</th>
                    <th>再分发范围</th>
                    <th>决定时间</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="member in detail.members" :key="member.feedId">
                    <td class="member-cell">
                      {{ labelOf(member) }}
                      <span v-if="member.groupName" class="badge rounded-pill text-bg-light border ms-1">{{
                        member.groupName }}</span>
                      <span class="d-block small text-break muted">{{ member.canonicalUrl }}</span>
                    </td>
                    <td>{{ member.entryCount }}</td>
                    <td class="text-nowrap">
                      <button type="button" class="btn btn-sm rounded-pill"
                        :class="member.fullContent ? 'btn-warning' : 'btn-outline-secondary'"
                        @click="toggleFullContent(member)">
                        {{ member.fullContent ? "转发全文" : "仅摘要" }}
                      </button>
                      <span v-if="member.state !== 'active'" class="small text-body-secondary ms-1">{{ member.state
                        }}</span>
                    </td>
                    <td class="text-nowrap small text-body-secondary">{{ ago(member.fullContentChangedAt) }}</td>
                    <td class="text-end">
                      <button type="button" class="btn btn-sm btn-outline-danger"
                        @click="removeSource(member)">移出</button>
                    </td>
                  </tr>
                  <tr v-if="!detail.members.length">
                    <td colspan="5" class="text-body-secondary">还没有成员；空集合也是一个合法的出口。</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>

          <form class="card mb-3" @submit.prevent="addSource">
            <div class="card-body">
              <h3 class="h6 mb-2">加入订阅源</h3>
              <div class="mb-2">
                <label class="form-label small" for="repack-member">
                  选择源（只列出尚未在这个集合里的 {{ pickable.length }} 个订阅）
                </label>
                <select id="repack-member" v-model.number="addForm.feedId" class="form-select">
                  <option :value="0">— 请选择 —</option>
                  <option v-for="feed in pickable" :key="feed.id" :value="feed.id">
                    {{ feedLabel(feed) }}（{{ feed.entryCount }} 条）
                  </option>
                </select>
                <div class="form-text">新加入的源一律只转发摘要。</div>
              </div>
              <button type="submit" class="btn btn-sm btn-primary">加入集合</button>
            </div>
          </form>

          <section class="card">
            <div class="card-header bg-body py-2">
              <h2 class="h6 mb-0">全文开关的审计（{{ audit.length }}）</h2>
            </div>
            <div class="table-responsive">
              <table class="table table-sm align-middle mb-0">
                <thead>
                  <tr>
                    <th>动作</th>
                    <th>源</th>
                    <th>之后</th>
                    <th>时间</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="row in audit" :key="row.id">
                    <td>{{ ACTION_LABELS[row.action] ?? row.action }}</td>
                    <td>{{ names.get(row.feedId) ?? `#${row.feedId}` }}</td>
                    <td>
                      <span class="badge rounded-pill" :class="row.fullContent ? 'text-bg-warning' : 'text-bg-secondary'">
                        {{ row.fullContent ? "全文" : "摘要" }}
                      </span>
                    </td>
                    <td class="text-nowrap small text-body-secondary">{{ ago(row.createdAt) }}</td>
                  </tr>
                  <tr v-if="!audit.length">
                    <td colspan="4" class="text-body-secondary">还没有决定可记录。</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <div class="card-footer bg-body py-2 small text-body-secondary">
              移出集合或删除聚合都不会抹掉这张表：正文再分发过的决定要能事后说清（04 §M5 的门禁）。
            </div>
          </section>
        </template>
      </div>
    </div>
  </section>
</template>

<style scoped>
/* Bootstrap lets a table cell grow to whatever its content wants; a feed url in the first column would push the
   toggle and the 移出 button off the visible width. */
.member-cell {
  max-width: 20rem;
}
</style>
