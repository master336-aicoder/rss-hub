<script setup lang="ts">
import { computed } from "vue";
import { useEntriesStore } from "../stores/entries";
import { ago, formatAt } from "../utils/time";

const entries = useEntriesStore();

const detail = computed(() => entries.detail);
const authorNames = computed(() =>
  (detail.value?.authors ?? [])
    .map((author) => author.name ?? author.email ?? "")
    .filter((name) => name.length > 0)
    .join("、"),
);
const audio = computed(() =>
  (detail.value?.enclosures ?? []).filter((item) => (item.mimeType ?? "").startsWith("audio/")),
);
const attachments = computed(() =>
  (detail.value?.enclosures ?? []).filter((item) => !(item.mimeType ?? "").startsWith("audio/")),
);

/** A list row can leave the current filter when its flags change, so every action re-reads from the server. */
async function act(change: { readState?: "unread" | "read" | "dismissed"; starred?: boolean; archived?: boolean }) {
  const id = detail.value?.id;
  if (id === undefined) return;
  await entries.apply({ ids: [id], ...change }).catch(() => undefined);
}
</script>

<template>
  <aside class="reader pane-scroll bg-body">
    <p v-if="entries.detailLoading" class="text-body-secondary p-3">
      <span class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>加载中…
    </p>
    <p v-else-if="entries.detailError" class="alert alert-danger py-2 small m-3 mb-0">{{ entries.detailError }}</p>
    <div v-else-if="!detail" class="text-body-secondary d-flex flex-column align-items-center justify-content-center h-100 text-center p-4">
      <i class="bi bi-file-earmark-text fs-1 opacity-25 mb-2" aria-hidden="true"></i>
      选择左侧的一条查看正文。
    </div>

    <template v-else>
      <header class="pb-2 border-bottom mb-3">
        <h2 class="h5 mb-1 lh-sm">
          <a v-if="detail.url" :href="detail.url" target="_blank" rel="noreferrer noopener"
            class="text-decoration-none">{{ detail.title ?? "(无标题)" }}</a>
          <span v-else>{{ detail.title ?? "(无标题)" }}</span>
        </h2>
        <p class="small text-body-secondary mb-1">
          <button type="button" class="btn btn-link btn-sm p-0 align-baseline text-body-secondary text-decoration-none"
            @click="entries.setFeed(detail.feedId)">{{ detail.feedTitle }}</button>
          <span> · {{ ago(detail.publishedAt ?? detail.fetchedAt) }}</span>
          <span v-if="authorNames"> · {{ authorNames }}</span>
        </p>
        <!-- FR-2.4: a tag is a filter you can reach, so it is a button. The value sent is the one the source wrote,
             which is why the backend matches it exactly instead of folding case or cutting it into words. -->
        <p v-if="detail.categories.length" class="d-flex flex-wrap gap-1 mb-0">
          <button v-for="tag in detail.categories" :key="tag" type="button" class="badge rounded-pill border-0"
            :class="entries.category === tag ? 'text-bg-primary' : 'text-bg-secondary'"
            @click="entries.setCategory(entries.category === tag ? undefined : tag)">
            {{ tag }}
          </button>
        </p>
      </header>

      <div class="d-flex flex-wrap gap-2 mb-3">
        <button v-if="detail.readState === 'unread'" type="button" class="btn btn-sm btn-primary"
          @click="act({ readState: 'read' })">
          <i class="bi bi-check2 me-1" aria-hidden="true"></i>标记已读
        </button>
        <button v-else type="button" class="btn btn-sm btn-outline-secondary" @click="act({ readState: 'unread' })">
          <i class="bi bi-envelope me-1" aria-hidden="true"></i>标为未读
        </button>
        <button type="button" class="btn btn-sm" :class="detail.starred ? 'btn-warning' : 'btn-outline-secondary'"
          @click="act({ starred: !detail.starred })">
          <i class="bi" :class="detail.starred ? 'bi-star-fill' : 'bi-star'" aria-hidden="true"></i>
          {{ detail.starred ? "取消星标" : "加星标" }}
        </button>
        <button type="button" class="btn btn-sm btn-outline-secondary" @click="act({ readState: 'dismissed' })">
          <i class="bi bi-eye-slash me-1" aria-hidden="true"></i>忽略
        </button>
        <button type="button" class="btn btn-sm" :class="detail.archived ? 'btn-secondary' : 'btn-outline-secondary'"
          @click="act({ archived: !detail.archived })">
          <i class="bi bi-archive me-1" aria-hidden="true"></i>{{ detail.archived ? "移出归档" : "归档" }}
        </button>
      </div>

      <audio v-for="clip in audio" :key="clip.url" controls :src="clip.url" class="w-100 mb-2" />

      <!-- content_html is the sanitized result feedparse wrote at ingestion time (03 §5.2), which is why v-html
           is safe here; the raw document is only ever in raw_response, never in this field. -->
      <article class="article-body mb-3" v-html="detail.contentHtml ?? detail.summary ?? '<p>（无正文）</p>'" />

      <ul v-if="attachments.length" class="list-unstyled small mb-3">
        <li v-for="item in attachments" :key="item.url" class="mb-1">
          <i class="bi bi-paperclip me-1" aria-hidden="true"></i>
          <a :href="item.url" target="_blank" rel="noreferrer noopener">{{ item.mimeType ?? "附件" }}</a>
          <span v-if="item.sizeBytes" class="text-body-secondary"> · {{ Math.round(item.sizeBytes / 1024) }} KB</span>
        </li>
      </ul>

      <details v-if="detail.revisions.length > 1" class="border rounded bg-body-tertiary p-2 small mb-3">
        <summary class="text-body-secondary">
          来源改动过 {{ detail.revisions.length }} 次（当前第 {{ detail.revision }} 次）
        </summary>
        <ol class="list-unstyled mb-0 mt-2">
          <li v-for="rev in detail.revisions" :key="rev.revision" class="mb-1">
            第 {{ rev.revision }} 次 · {{ rev.title ?? "(无标题)" }} · {{ formatAt(rev.recordedAt) }}
            <code v-if="rev.contentHash" class="text-body-secondary ms-1">{{ rev.contentHash.slice(0, 12) }}</code>
          </li>
        </ol>
      </details>

      <p class="small mb-4">
        <span v-if="!detail.stableId" class="text-warning-emphasis d-block mb-1">
          <i class="bi bi-exclamation-triangle me-1" aria-hidden="true"></i>
          这条的 id 不稳定（{{ detail.guidSource ?? "无 id" }}），来源改动可能被当成新文章。
        </span>
        <span v-if="detail.lang" class="text-body-secondary">语言：{{ detail.lang }}</span>
      </p>
    </template>
  </aside>
</template>

<style scoped>
/* The reader is the column that absorbs whatever the two fixed columns leave, and it scrolls on its own. */
.reader {
  flex: 1 1 auto;
  min-width: 0;
  border-left: 1px solid var(--bs-border-color);
  padding: 1.25rem 1.5rem;
}

.article-body {
  font-size: 1rem;
  line-height: 1.7;
  word-break: break-word;
}

/* Feed HTML arrives with tables, figures and images of unknown width; the pane must never be wider than itself. */
.article-body :deep(img),
.article-body :deep(video),
.article-body :deep(table),
.article-body :deep(iframe) {
  max-width: 100%;
  height: auto;
}

.article-body :deep(h1),
.article-body :deep(h2),
.article-body :deep(h3) {
  font-size: 1.15rem;
  margin: 1.2rem 0 .5rem;
}

.article-body :deep(p) {
  margin-bottom: .8rem;
}

.article-body :deep(pre) {
  background: var(--bs-tertiary-bg);
  border-radius: var(--bs-border-radius-sm);
  padding: .6rem .75rem;
  overflow-x: auto;
}
</style>
