<script setup lang="ts">
import { onMounted, ref } from "vue";
import RuleEditor from "../components/RuleEditor.vue";
import { rulesApi, type Rule, type RulePreview } from "../api/rules";
import { useRulesStore } from "../stores/rules";
import { ago } from "../utils/time";

const rules = useRulesStore();

const editing = ref<Rule | null>(null);
const creating = ref(false);
const busy = ref("");
const notice = ref("");
const previews = ref<Record<number, RulePreview>>({});

onMounted(async () => {
  await rules.load();
});

function close() {
  editing.value = null;
  creating.value = false;
}

function onSaved(rule: Rule) {
  notice.value = `已保存「${rule.name}」。`;
  close();
  busy.value = "";
}

/** FR-5.4: judging is read-only, so a rule can be tested while it is live without anything being sent. */
async function test(rule: Rule) {
  busy.value = "";
  try {
    previews.value = { ...previews.value, [rule.id]: await rulesApi.test(rule.id) };
  } catch (cause) {
    busy.value = rules.describe(cause);
  }
}

async function toggle(rule: Rule) {
  busy.value = "";
  try {
    await rules.setEnabled(rule.id, !rule.enabled);
  } catch (cause) {
    busy.value = rules.describe(cause);
  }
}

async function remove(rule: Rule) {
  if (!window.confirm(`删除规则「${rule.name}」？投递记录会留下，只是不再指向任何规则。`)) return;
  busy.value = "";
  try {
    await rules.remove(rule.id);
    if (editing.value?.id === rule.id) close();
  } catch (cause) {
    busy.value = rules.describe(cause);
  }
}
</script>

<template>
  <div class="row g-3 align-items-start">
    <section :class="creating || editing ? 'col-12 col-xl-7' : 'col-12'">
      <div class="d-flex flex-wrap align-items-center justify-content-between gap-2 mb-3">
        <h1 class="h4 mb-0 d-flex align-items-center gap-2">
          <i class="bi bi-funnel text-primary" aria-hidden="true"></i>规则与告警
        </h1>
        <div class="d-flex flex-wrap align-items-center gap-2">
          <button type="button" class="btn btn-sm btn-primary" @click="creating = true; editing = null">
            <i class="bi bi-plus-lg me-1" aria-hidden="true"></i>新建规则
          </button>
          <button type="button" class="btn btn-sm btn-outline-primary" :disabled="rules.loading"
            @click="rules.load()">{{ rules.loading ? "读取中…" : "重新读取" }}</button>
          <span class="small text-body-secondary">{{ rules.rules.length }} 条规则</span>
          <span v-if="rules.autoDisabled.length" class="badge rounded-pill text-bg-danger">
            {{ rules.autoDisabled.length }} 条被投递熔断自动关闭
          </span>
        </div>
      </div>

      <div v-if="rules.error || busy" class="alert alert-danger py-2 small">{{ rules.error || busy }}</div>
      <div v-if="notice" class="alert alert-success py-2 small">{{ notice }}</div>

      <div v-if="rules.rules.length === 0 && !rules.loading" class="card">
        <div class="card-body text-center text-body-secondary py-5">
          <i class="bi bi-funnel fs-1 d-block opacity-25 mb-2" aria-hidden="true"></i>
          还没有规则。规则只在抓取或事件写入时判断，命中后按渠道投递；先用「只标记」试一条，确认命中了再接 webhook。
        </div>
      </div>

      <div v-else class="d-flex flex-column gap-3">
        <article v-for="rule in rules.rules" :key="rule.id" class="card"
          :class="rule.enabled ? '' : 'bg-body-tertiary'">
          <div class="card-body py-3">
            <div class="d-flex flex-wrap align-items-center gap-2">
              <strong>{{ rule.name }}</strong>
              <span class="badge rounded-pill" :class="rule.enabled ? 'text-bg-success' : 'text-bg-secondary'">
                {{ rule.enabled ? "启用" : "关闭" }}
              </span>
              <span class="small text-body-secondary">命中 {{ rule.hitCount }} 次 · 更新于 {{ ago(rule.updatedAt) }}</span>
              <span class="flex-grow-1"></span>
              <button type="button" class="btn btn-sm btn-outline-secondary" @click="toggle(rule)">
                {{ rule.enabled ? "关闭" : "重新启用" }}
              </button>
              <button type="button" class="btn btn-sm btn-outline-primary" @click="test(rule)">测试</button>
              <button type="button" class="btn btn-sm btn-outline-primary"
                @click="editing = rule; creating = false">编辑</button>
              <button type="button" class="btn btn-sm btn-outline-danger" @click="remove(rule)">删除</button>
            </div>
            <p v-if="rule.disabledReason" class="text-danger-emphasis small mb-0 mt-2">{{ rule.disabledReason }}</p>
            <pre class="spec">{{ JSON.stringify(rule.match, null, 1) }}</pre>
            <pre class="spec">→ {{ JSON.stringify(rule.actions) }}</pre>
            <details v-if="previews[rule.id]" class="mt-2">
              <summary class="small">
                测试：查了 {{ previews[rule.id].scanned }} 条，命中 {{ previews[rule.id].matched }} 条
              </summary>
              <ul class="list-unstyled small mt-2 mb-0">
                <li v-for="hit in previews[rule.id].hits" :key="hit.entryId" class="py-1 border-bottom">
                  {{ hit.title ?? "(无标题)" }}
                  <span class="text-body-secondary">
                    · {{ hit.feedTitle ?? "?" }} · {{ ago(hit.publishedAt) }}
                    <template v-if="hit.keywords.length"> · {{ hit.keywords.join(", ") }}</template>
                  </span>
                </li>
              </ul>
            </details>
          </div>
        </article>
      </div>
    </section>

    <aside v-if="creating || editing" class="col-12 col-xl-5 editor-rail">
      <RuleEditor :rule="editing ?? undefined" @saved="onSaved" @cancel="close" />
    </aside>
  </div>
</template>

<style scoped>
/* The editor is a form you scroll beside a list you also scroll; Bootstrap's `sticky-top` pins to the top of the
   scroll container but has no height budget, so the rail would run off the bottom of the viewport. */
.editor-rail {
  position: sticky;
  top: 1rem;
  max-height: calc(100vh - 6rem);
  overflow-y: auto;
}
.spec {
  margin: .5rem 0 0;
  font-size: .82rem;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--bs-secondary-color);
}
</style>
