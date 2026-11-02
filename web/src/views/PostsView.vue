<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { CHANGE_ACTION_LABELS, changelogApi, changelogOutlets, type ChangeEntry } from "../api/changelog";
import { ApiError } from "../api/errors";
import { POST_STATUSES, type Post, type PostDraft, type PostStatus } from "../api/posts";
import { usePostsStore } from "../stores/posts";
import { formatAt } from "../utils/time";

const posts = usePostsStore();

/** The statuses the editor exposes as buttons. The fetcher's `error`/`gone` are not here because a post never has them. */
const statuses: readonly PostStatus[] = POST_STATUSES;

const selected = ref<Post | undefined>(undefined);
const isNew = ref(false);
const busy = ref(false);
const message = ref("");
const failed = ref(false);

/** FR-1.6: the article's own change history, read from the same rows the public changelog outlet publishes. */
const history = ref<ChangeEntry[]>([]);
const historyError = ref("");

/** Which article the trail belongs to, kept separate from `selected` because a deleted post's trail stays on screen. */
const historyPostId = ref<number | undefined>(undefined);

/**
 * The host the backend stamps into a permalink is the configured public origin, and that is the only host serving
 * `/feed` — the dev server proxies `/api` and `/actuator` alone. Recovering it from a post's absolute URL avoids
 * hardcoding a base URL twice in the app.
 */
const publicOrigin = computed(() => {
  try {
    return new URL(selected.value?.url ?? posts.posts[0]?.url ?? "").origin;
  } catch {
    // No post has ever been published, so there is no absolute URL to read the origin out of.
    return "";
  }
});

const draft = reactive<PostDraft>({
  title: "",
  slug: "",
  summaryMd: "",
  bodyMd: "",
  status: "draft",
  lang: "",
  publishedAt: "",
  tags: [],
  categories: [],
});

/** A comma-separated input is friendlier than a multi-select for a personal editor; the backend resolves names. */
const tagsText = ref("");
const categoriesText = ref("");

const sorted = computed(() =>
  [...posts.posts].sort((a, b) => {
    // Newest touched first — the same order the API's `list` already returns, restated so a local edit keeps it.
    const ka = a.updatedAt ?? a.publishedAt ?? "";
    const kb = b.updatedAt ?? b.publishedAt ?? "";
    return kb.localeCompare(ka);
  }),
);

/**
 * The state machine's four statuses are read at a glance down the list, so each one owns a colour. Unknown values
 * fall back to the neutral badge rather than inheriting "published" green.
 */
const STATUS_TONES: Record<string, string> = {
  published: "text-bg-success",
  scheduled: "text-bg-warning",
  archived: "text-bg-secondary",
  draft: "text-bg-light border",
};

const ACTION_TONES: Record<string, string> = {
  created: "text-bg-success",
  updated: "text-bg-info",
  status: "text-bg-warning",
  deleted: "text-bg-danger",
};

function statusTone(status: string): string {
  return STATUS_TONES[status] ?? "text-bg-light border";
}

function actionTone(action: string): string {
  return ACTION_TONES[action] ?? "text-bg-secondary";
}

function describe(error: unknown): string {
  return error instanceof ApiError ? `${error.code}: ${error.message}` : String(error);
}

function note(text: string, bad = false): void {
  message.value = text;
  failed.value = bad;
}

/** Keeps its own error line so a failing history read never overwrites the save/publish result the editor reports. */
async function loadHistory(postId?: number): Promise<void> {
  historyPostId.value = postId;
  try {
    history.value = await changelogApi.list({ postId, limit: 20 });
    historyError.value = "";
  } catch (error) {
    history.value = [];
    historyError.value = describe(error);
  }
}

function startNew(): void {
  isNew.value = true;
  selected.value = undefined;
  draft.title = "";
  draft.slug = "";
  draft.summaryMd = "";
  draft.bodyMd = "";
  draft.status = "draft";
  draft.lang = "";
  draft.publishedAt = "";
  draft.tags = [];
  draft.categories = [];
  tagsText.value = "";
  categoriesText.value = "";
  note("新文章：写好后点保存。URL slug 会在保存时按标题生成，之后不再改变。");
  // A brand-new post has no history yet, so the panel falls back to the site-wide trail.
  void loadHistory();
}

function open(post: Post): void {
  isNew.value = false;
  selected.value = post;
  draft.title = post.title;
  draft.slug = post.slug;
  draft.summaryMd = post.summaryMd ?? "";
  draft.bodyMd = post.bodyMd;
  draft.status = post.status;
  draft.lang = post.lang ?? "";
  draft.publishedAt = post.publishedAt ?? "";
  draft.tags = [...post.tags];
  draft.categories = [...post.categories];
  tagsText.value = post.tags.join(", ");
  categoriesText.value = post.categories.join(", ");
  note("");
  void loadHistory(post.id);
}

function splitList(text: string): string[] {
  return text
    .split(",")
    .map((piece) => piece.trim())
    .filter((piece) => piece.length > 0);
}

watch(tagsText, (value) => (draft.tags = splitList(value)));
watch(categoriesText, (value) => (draft.categories = splitList(value)));

async function save(): Promise<void> {
  if (!draft.title.trim() || !draft.bodyMd.trim()) {
    note("标题和正文不能为空。", true);
    return;
  }
  busy.value = true;
  try {
    const payload: PostDraft = {
      title: draft.title.trim(),
      bodyMd: draft.bodyMd,
      summaryMd: draft.summaryMd?.trim() || undefined,
      slug: isNew.value ? draft.slug?.trim() || undefined : undefined,
      status: draft.status,
      lang: draft.lang?.trim() || undefined,
      publishedAt: draft.publishedAt || undefined,
      tags: splitList(tagsText.value),
      categories: splitList(categoriesText.value),
    };
    const saved = isNew.value ? await posts.create(payload) : await posts.update(selected.value!.id, payload);
    // The list refresh keeps the row's derived fields (URL, timestamps) in view after the write.
    open(saved);
    note(isNew.value ? `已保存：${saved.url}` : `已更新：${saved.slug}`);
    isNew.value = false;
  } catch (error) {
    note(describe(error), true);
  } finally {
    busy.value = false;
  }
}

async function publish(status: PostStatus): Promise<void> {
  if (!selected.value) return;
  busy.value = true;
  try {
    const saved = await posts.changeStatus(selected.value.id, status, draft.publishedAt || undefined);
    open(saved);
    note(`状态切换到 ${status}。`);
  } catch (error) {
    // A 409 here is the state machine refusing an illegal transition, not a network failure — the message is the point.
    note(describe(error), true);
  } finally {
    busy.value = false;
  }
}

async function remove(): Promise<void> {
  if (!selected.value) return;
  if (!window.confirm(`删除《${selected.value.title}》？这个动作不可撤销，permanent URL 会一并消失。`)) return;
  busy.value = true;
  const postId = selected.value.id;
  try {
    await posts.remove(postId);
    selected.value = undefined;
    note("已删除。");
    // `content_change` has no foreign key on purpose, so the trail — including the "deleted" row — outlives the post.
    void loadHistory(postId);
  } catch (error) {
    note(describe(error), true);
  } finally {
    busy.value = false;
  }
}

onMounted(async () => {
  await Promise.all([posts.load(), posts.loadTerms()]);
  void loadHistory();
});
</script>

<template>
  <div class="row g-3 align-items-start">
    <aside class="col-12 col-xl-4 col-xxl-3">
      <section class="card">
        <header class="card-header d-flex justify-content-between align-items-center py-2">
          <span class="small text-body-secondary">{{ posts.posts.length }} 篇</span>
          <button type="button" class="btn btn-sm btn-primary" @click="startNew">
            <i class="bi bi-plus-lg me-1" aria-hidden="true"></i>新文章
          </button>
        </header>
        <p v-if="posts.loading" class="small text-body-secondary p-3 mb-0">
          <span class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>加载中…
        </p>
        <p v-else-if="posts.error" class="small text-danger-emphasis p-3 mb-0">{{ posts.error }}</p>
        <div v-else-if="!sorted.length" class="small text-body-secondary p-3 mb-0">
          还没有文章，点上方"新文章"开始。
        </div>
        <div v-else class="list-group list-group-flush post-list">
          <button v-for="post in sorted" :key="post.id" type="button" class="list-group-item list-group-item-action"
            :class="{ on: selected?.id === post.id }" @click="open(post)">
            <div class="d-flex w-100 justify-content-between align-items-start gap-2">
              <span class="post-title">{{ post.title }}</span>
              <span class="badge rounded-pill flex-none" :class="statusTone(post.status)">{{ post.status }}</span>
            </div>
            <span class="small text-body-tertiary">{{ formatAt(post.publishedAt ?? post.updatedAt) }}</span>
          </button>
        </div>
      </section>
    </aside>

    <section class="col-12 col-xl-8 col-xxl-9">
      <div class="card">
        <div class="card-body">
          <p v-if="!selected && !isNew" class="text-body-secondary mb-0">选左侧的一篇，或点"新文章"。</p>
          <form v-else class="editor" @submit.prevent="save">
            <p v-if="selected" class="small text-break mb-0">
              <a :href="selected.url" target="_blank" rel="noreferrer">{{ selected.url }}</a>
              <span class="text-body-tertiary">（slug 一旦创建就不能改，编辑标题不会挪动 URL）</span>
            </p>

            <div>
              <label class="form-label small" for="post-title">标题</label>
              <input id="post-title" v-model="draft.title" class="form-control" required maxlength="512" />
            </div>
            <div v-if="isNew">
              <label class="form-label small" for="post-slug">
                Slug（可选，留空按标题生成；只允许字母、数字、`-` 和中文）
              </label>
              <input id="post-slug" v-model="draft.slug" class="form-control font-monospace" placeholder="example-slug" />
            </div>
            <div class="row g-2">
              <div class="col-12 col-md-4">
                <label class="form-label small" for="post-lang">语言</label>
                <input id="post-lang" v-model="draft.lang" class="form-control" placeholder="zh-CN" maxlength="32" />
              </div>
              <div class="col-12 col-md-8">
                <label class="form-label small" for="post-published">发布时间（仅 scheduled 状态必填，ISO 8601）</label>
                <input id="post-published" v-model="draft.publishedAt" class="form-control font-monospace"
                  placeholder="2026-11-01T00:00:00Z" />
              </div>
            </div>

            <div>
              <label class="form-label small" for="post-summary">摘要 Markdown（可选，feed 的 description 优先取它）</label>
              <textarea id="post-summary" v-model="draft.summaryMd" class="form-control font-monospace"
                rows="2"></textarea>
            </div>

            <div>
              <label class="form-label small" for="post-tags">标签（英文逗号分隔）</label>
              <input id="post-tags" v-model="tagsText" class="form-control" placeholder="ebpf, kernel" />
              <div v-if="posts.terms.tags.length" class="term-cloud">
                <span class="small text-body-tertiary me-1">已用：</span>
                <span v-for="term in posts.terms.tags.slice(0, 12)" :key="term.id"
                  class="badge rounded-pill text-bg-light border">{{ term.title }}</span>
              </div>
            </div>

            <div>
              <label class="form-label small" for="post-categories">分类（英文逗号分隔）</label>
              <input id="post-categories" v-model="categoriesText" class="form-control" placeholder="Deep dives" />
              <div v-if="posts.terms.categories.length" class="term-cloud">
                <span class="small text-body-tertiary me-1">已用：</span>
                <span v-for="term in posts.terms.categories.slice(0, 12)" :key="term.id"
                  class="badge rounded-pill text-bg-light border">{{ term.title }}</span>
              </div>
            </div>

            <div>
              <label class="form-label small" for="post-body">正文 Markdown</label>
              <textarea id="post-body" v-model="draft.bodyMd" class="form-control font-monospace" rows="20"
                required></textarea>
            </div>

            <div class="d-flex flex-wrap gap-2">
              <button type="submit" class="btn btn-primary" :disabled="busy">
                <span v-if="busy" class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>保存
              </button>
              <button v-for="status in statuses" :key="status" type="button" class="btn btn-outline-primary"
                :disabled="busy || !selected || selected.status === status" @click="publish(status)">
                {{ status }}
              </button>
              <button v-if="selected" type="button" class="btn btn-outline-danger" :disabled="busy"
                @click="remove">删除</button>
            </div>

            <div v-if="message" class="alert py-2 small mb-0" :class="failed ? 'alert-danger' : 'alert-success'">
              {{ message }}
            </div>

            <div v-if="selected" class="preview">
              <h3 class="h6">预览（服务端渲染后的 body_html）</h3>
              <!-- The server sanitised this on the way in, so the browser does not need to re-clean it. -->
              <div class="rendered" v-html="selected.bodyHtml"></div>
            </div>
          </form>

          <div class="history">
            <div class="d-flex flex-wrap justify-content-between align-items-baseline gap-2">
              <h3 class="h6 mb-0">变更历史 · {{ historyPostId === undefined ? "全站最新" : "本篇" }}</h3>
              <span v-if="publicOrigin" class="d-flex flex-wrap gap-1">
                <a v-for="outlet in changelogOutlets(publicOrigin)" :key="outlet.suffix" :href="outlet.href"
                  target="_blank" rel="noreferrer" class="badge rounded-pill text-bg-light border text-decoration-none">{{
                    outlet.suffix }}</a>
              </span>
            </div>
            <p v-if="historyError" class="small text-danger-emphasis mt-2">{{ historyError }}</p>
            <p v-else-if="!history.length" class="small text-body-secondary mt-2">
              只有公开过的变化才会留痕：草稿的增删改不写入公告，发布、改内容、状态移动和撤稿才会。
            </p>
            <div v-else class="list-group list-group-flush">
              <div v-for="entry in history" :key="entry.publicId"
                class="list-group-item d-flex flex-wrap align-items-baseline gap-2 px-0">
                <span class="badge flex-none" :class="actionTone(entry.action)">
                  {{ CHANGE_ACTION_LABELS[entry.action] }}
                </span>
                <span class="small text-body-tertiary flex-none">{{ formatAt(entry.changedAt) }}</span>
                <!-- A deleted post's permalink no longer resolves, so its row is deliberately not a link. -->
                <span v-if="entry.action === 'deleted'" class="text-body-tertiary text-break">{{ entry.title }}</span>
                <a v-else :href="entry.url" target="_blank" rel="noreferrer" class="text-break">{{ entry.title }}</a>
                <span v-if="entry.fromStatus || entry.toStatus" class="small text-body-tertiary flex-none">
                  {{ entry.fromStatus || "—" }} → {{ entry.toStatus || "—" }}
                </span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
/* The list column scrolls on its own so the editor never drifts down; Bootstrap gives no height budget for a
   `list-group` inside a card, so the card owns it. */
.post-list {
  max-height: calc(100vh - 12rem);
  overflow: auto;
}
/* Bootstrap's `.active` paints the whole row in the accent, which swallows the timestamp and the status badge in
   this list; the editor's rail uses the same soft accent wash as the inbox's picked row instead. */
.post-list .list-group-item.on {
  background: var(--accent-wash);
  box-shadow: inset 3px 0 0 var(--accent);
}
.post-title {
  font-weight: 500;
  line-height: 1.35;
}
.term-cloud {
  margin-top: 4px;
}
.term-cloud .badge {
  margin: 2px 4px 0 0;
  font-weight: 400;
}
.editor {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.editor textarea {
  resize: vertical;
}
.preview,
.history {
  border-top: 1px dashed var(--bs-border-color);
  padding-top: 12px;
}
.preview {
  margin-top: 8px;
}
.history {
  margin-top: 16px;
}
/* The rendered article is server-produced HTML, so its elements carry no classes: these rules are the only thing
   that keeps a wide table or code block inside the editor column. */
.rendered :deep(h1),
.rendered :deep(h2),
.rendered :deep(h3) {
  margin: 12px 0 6px;
}
.rendered :deep(pre) {
  background: var(--bs-secondary-bg);
  padding: 8px 12px;
  border-radius: var(--bs-border-radius-sm);
  overflow-x: auto;
}
.rendered :deep(table) {
  border-collapse: collapse;
  max-width: 100%;
}
.rendered :deep(th),
.rendered :deep(td) {
  border: 1px solid var(--bs-border-color);
  padding: 4px 8px;
}
.rendered :deep(img) {
  max-width: 100%;
  height: auto;
}
</style>
