<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import { ApiError } from "../api/errors";
import {
  DEFAULT_SUPPRESSION,
  RULE_CHANNELS,
  SUPPRESSION_KEYS,
  type Rule,
  type RuleAction,
  type RuleChannel,
  type RuleDraft,
  type RuleMatch,
  type SuppressionKey,
} from "../api/rules";
import { feedLabel } from "../api/feeds";
import { useFeedsStore } from "../stores/feeds";
import { useRulesStore } from "../stores/rules";

/** `rule` absent means "create"; present means "edit this one", and the form starts from its stored columns. */
const props = defineProps<{ rule?: Rule }>();
const emit = defineEmits<{ saved: [Rule]; cancel: [] }>();

const feeds = useFeedsStore();
const rules = useRulesStore();

/** List conditions are textareas in the UI and arrays on the wire: commas and newlines both separate. */
function toList(value: string): string[] {
  return value
    .split(/[,\n]/)
    .map((part) => part.trim())
    .filter((part) => part.length > 0);
}

function fromList(values?: string[]): string {
  return (values ?? []).join("\n");
}

const form = reactive({
  name: "",
  includeAny: "",
  includeAll: "",
  excludeAny: "",
  regex: "",
  wholeWord: false,
  caseSensitive: false,
  feedIds: [] as number[],
  groups: [] as string[],
  tags: "",
  windowMinutes: 0,
  actions: [{ channel: "webhook", target: "", secretRef: "" }] as RuleAction[],
  suppressionKey: DEFAULT_SUPPRESSION.key as SuppressionKey,
  suppressionHours: DEFAULT_SUPPRESSION.hours as number,
  quietOn: false,
  quietFrom: "23:00",
  quietTo: "07:00",
  quietTz: "Asia/Shanghai",
  enabled: true,
});

watch(
  () => props.rule,
  (rule) => {
    const match: RuleMatch = rule?.match ?? {};
    const suppression = rule?.suppression ?? DEFAULT_SUPPRESSION;
    const quiet = rule?.quietHours;
    form.name = rule?.name ?? "";
    form.includeAny = fromList(match.includeAny);
    form.includeAll = fromList(match.includeAll);
    form.excludeAny = fromList(match.excludeAny);
    form.regex = (match.regex ?? []).join("\n");
    form.wholeWord = match.wholeWord ?? false;
    form.caseSensitive = match.caseSensitive ?? false;
    form.feedIds = [...(match.feedIds ?? [])];
    form.groups = [...(match.groups ?? [])];
    form.tags = fromList(match.tags);
    form.windowMinutes = match.windowMinutes ?? 0;
    form.actions = (rule?.actions ?? [{ channel: "webhook", target: "", secretRef: "" }]).map((action) => ({
      channel: action.channel,
      target: action.target ?? "",
      secretRef: action.secretRef ?? "",
    }));
    form.suppressionKey = suppression.key ?? "perFeed";
    form.suppressionHours = suppression.hours ?? 6;
    form.quietOn = quiet !== undefined;
    form.quietFrom = quiet?.from ?? "23:00";
    form.quietTo = quiet?.to ?? "07:00";
    form.quietTz = quiet?.tz ?? "Asia/Shanghai";
    form.enabled = rule?.enabled ?? true;
    error.value = "";
  },
  { immediate: true },
);

const isEdit = computed(() => props.rule !== undefined);
const error = ref("");
const saving = ref(false);

/** `markOnly` is the only channel with no target, so the URL box is optional exactly once. */
function needsTarget(channel: RuleChannel): boolean {
  return channel !== "markOnly";
}

function build(): RuleDraft {
  const match: RuleMatch = {};
  const includeAny = toList(form.includeAny);
  const includeAll = toList(form.includeAll);
  const excludeAny = toList(form.excludeAny);
  const regex = form.regex
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => line.length > 0);
  const tags = toList(form.tags);
  if (includeAny.length) match.includeAny = includeAny;
  if (includeAll.length) match.includeAll = includeAll;
  if (excludeAny.length) match.excludeAny = excludeAny;
  if (regex.length) match.regex = regex;
  if (form.wholeWord) match.wholeWord = true;
  if (form.caseSensitive) match.caseSensitive = true;
  if (form.feedIds.length) match.feedIds = [...form.feedIds];
  if (form.groups.length) match.groups = [...form.groups];
  if (tags.length) match.tags = tags;
  if (form.windowMinutes > 0) match.windowMinutes = form.windowMinutes;

  // Blank boxes send nothing: `target_required` and a scheme error are the backend's message, not a trailing space.
  const actions: RuleAction[] = form.actions.map((action) => ({
    channel: action.channel,
    ...(action.target?.trim() ? { target: action.target.trim() } : {}),
    ...(action.secretRef?.trim() ? { secretRef: action.secretRef.trim() } : {}),
  }));

  const draft: RuleDraft = {
    name: form.name.trim(),
    match,
    actions,
    suppression: { key: form.suppressionKey, hours: form.suppressionHours },
  };
  if (form.quietOn) {
    draft.quietHours = { from: form.quietFrom, to: form.quietTo, tz: form.quietTz.trim() };
  } else if (props.rule?.quietHours) {
    // An empty object is the only thing that clears a stored window: an absent column means "keep it", so the
    // switch has to say "none" out loud rather than stay quiet.
    draft.quietHours = {};
  }
  // Only a new rule carries its starting flag. On an edit the breaker's decision is not a side effect of pressing
  // save, so `enabled` is left out and the rules page's switch is the one route that changes it.
  if (!isEdit.value) draft.enabled = form.enabled;
  return draft;
}

async function submit() {
  saving.value = true;
  error.value = "";
  try {
    const rule = isEdit.value
      ? await rules.update(props.rule!.id, build())
      : await rules.create(build());
    emit("saved", rule);
  } catch (cause) {
    error.value = cause instanceof ApiError ? `${cause.code}: ${cause.message}` : String(cause);
  } finally {
    saving.value = false;
  }
}

function addAction() {
  form.actions.push({ channel: "webhook", target: "", secretRef: "" });
}

function removeAction(index: number) {
  form.actions.splice(index, 1);
}
</script>

<template>
  <form class="card editor" @submit.prevent="submit">
    <div class="card-body">
      <h2 class="h6 mb-3">{{ isEdit ? `编辑「${props.rule?.name}」` : "新建规则" }}</h2>
      <div v-if="props.rule && !props.rule.enabled" class="alert alert-danger py-2 small">
        这条规则目前是关闭的{{ props.rule.disabledReason ? `：${props.rule.disabledReason}` : "" }}。保存编辑不会把它打开，
        请用列表里的开关显式恢复。
      </div>

      <div class="mb-3">
        <label class="form-label small" for="rule-name">名称</label>
        <input id="rule-name" v-model="form.name" class="form-control" maxlength="255" required placeholder="例如：OOM 告警" />
      </div>

      <fieldset class="block mb-3">
        <legend>匹配条件</legend>
        <div class="mb-2">
          <label class="form-label small" for="rule-include-any">命中词（任一命中即算）</label>
          <textarea id="rule-include-any" v-model="form.includeAny" class="form-control" rows="2"
            placeholder="每行一个，或用逗号分隔"></textarea>
        </div>
        <div class="mb-2">
          <label class="form-label small" for="rule-include-all">必须全部包含</label>
          <textarea id="rule-include-all" v-model="form.includeAll" class="form-control" rows="2"></textarea>
        </div>
        <div class="mb-2">
          <label class="form-label small" for="rule-exclude-any">排除词（只收窄，不能单独构成规则）</label>
          <textarea id="rule-exclude-any" v-model="form.excludeAny" class="form-control" rows="2"></textarea>
        </div>
        <div class="mb-2">
          <label class="form-label small" for="rule-regex">正则（每行一条，200 字符上限）</label>
          <textarea id="rule-regex" v-model="form.regex" class="form-control" rows="2"
            placeholder="OOM|out of memory"></textarea>
        </div>
        <div class="d-flex flex-wrap gap-3 mb-2">
          <div class="form-check">
            <input id="rule-whole-word" v-model="form.wholeWord" class="form-check-input" type="checkbox" />
            <label class="form-check-label small" for="rule-whole-word">整词匹配</label>
          </div>
          <div class="form-check">
            <input id="rule-case-sensitive" v-model="form.caseSensitive" class="form-check-input" type="checkbox" />
            <label class="form-check-label small" for="rule-case-sensitive">区分大小写</label>
          </div>
        </div>
        <div class="mb-2">
          <label class="form-label small" for="rule-feeds">只看这些订阅</label>
          <select id="rule-feeds" v-model="form.feedIds" class="form-select" multiple size="5">
            <option v-for="feed in feeds.feeds" :key="feed.id" :value="feed.id">
              {{ feedLabel(feed) }}
            </option>
          </select>
          <div class="form-text">按住 Ctrl 多选；留空表示不限订阅。</div>
        </div>
        <div class="mb-2">
          <label class="form-label small" for="rule-groups">只看这些分组</label>
          <select id="rule-groups" v-model="form.groups" class="form-select" multiple size="4">
            <option v-for="group in feeds.groups" :key="group" :value="group">{{ group }}</option>
          </select>
        </div>
        <div class="mb-2">
          <label class="form-label small" for="rule-tags">标签（分类词，任一命中）</label>
          <input id="rule-tags" v-model="form.tags" class="form-control" placeholder="alert, ci" />
        </div>
        <div class="mb-0">
          <label class="form-label small" for="rule-window">只看最近 N 分钟内发布的条目（0 表示不限）</label>
          <input id="rule-window" v-model.number="form.windowMinutes" class="form-control" type="number" min="0"
            max="43200" />
        </div>
      </fieldset>

      <fieldset class="block mb-3">
        <legend>命中之后</legend>
        <div v-for="(action, index) in form.actions" :key="index" class="input-group input-group-sm mb-2">
          <select v-model="action.channel" class="form-select channel" aria-label="渠道">
            <option v-for="channel in RULE_CHANNELS" :key="channel" :value="channel">{{ channel }}</option>
          </select>
          <input v-if="needsTarget(action.channel)" v-model="action.target" class="form-control"
            aria-label="目标地址" placeholder="https://hooks.example.com/…" />
          <input v-model="action.secretRef" class="form-control" aria-label="secretRef"
            placeholder="secretRef（可选，环境变量里的签名密钥名）" />
          <button v-if="form.actions.length > 1" type="button" class="btn btn-outline-danger"
            @click="removeAction(index)">删除</button>
        </div>
        <button type="button" class="btn btn-link btn-sm p-0" @click="addAction">
          <i class="bi bi-plus-circle me-1" aria-hidden="true"></i>再加一个动作
        </button>
        <p class="form-text mb-0">
          密钥本身不存在规则里：<code>secretRef</code> 只是 <code>rsshub.notify.secrets</code> 的键名，规则行会随着数据库一起导出，
          签名密钥不能。<code>dingtalk</code>／<code>wecom</code> 需要它来算签名。
        </p>
      </fieldset>

      <fieldset class="block mb-3">
        <legend>收敛与静默</legend>
        <div class="row g-2 mb-2">
          <div class="col-12 col-sm-6">
            <label class="form-label small" for="rule-suppression-key">重复命中怎么算</label>
            <select id="rule-suppression-key" v-model="form.suppressionKey" class="form-select form-select-sm">
              <option v-for="key in SUPPRESSION_KEYS" :key="key" :value="key">{{ key }}</option>
            </select>
          </div>
          <div class="col-12 col-sm-6">
            <label class="form-label small" for="rule-suppression-hours">抑制时长（小时，0 = 不抑制）</label>
            <input id="rule-suppression-hours" v-model.number="form.suppressionHours" class="form-control form-control-sm"
              type="number" min="0" max="720" />
          </div>
        </div>
        <div class="form-check mb-2">
          <input id="rule-quiet-on" v-model="form.quietOn" class="form-check-input" type="checkbox" />
          <label class="form-check-label small" for="rule-quiet-on">启用静默时段（时段内仍记录命中，只是不发）</label>
        </div>
        <div v-if="form.quietOn" class="row g-2 mb-2">
          <div class="col-4">
            <label class="form-label small" for="rule-quiet-from">开始</label>
            <input id="rule-quiet-from" v-model="form.quietFrom" class="form-control form-control-sm" type="time"
              required />
          </div>
          <div class="col-4">
            <label class="form-label small" for="rule-quiet-to">结束</label>
            <input id="rule-quiet-to" v-model="form.quietTo" class="form-control form-control-sm" type="time" required />
          </div>
          <div class="col-4">
            <label class="form-label small" for="rule-quiet-tz">时区</label>
            <input id="rule-quiet-tz" v-model="form.quietTz" class="form-control form-control-sm"
              placeholder="Asia/Shanghai" required />
          </div>
        </div>
        <div v-if="!isEdit" class="form-check mb-0">
          <input id="rule-enabled" v-model="form.enabled" class="form-check-input" type="checkbox" />
          <label class="form-check-label small" for="rule-enabled">创建后立即启用</label>
        </div>
      </fieldset>

      <div v-if="error" class="alert alert-danger py-2 small">{{ error }}</div>

      <div class="d-flex gap-2">
        <button type="submit" class="btn btn-primary" :disabled="saving || form.actions.length === 0">
          <span v-if="saving" class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>
          {{ saving ? "保存中…" : "保存" }}
        </button>
        <button type="button" class="btn btn-outline-secondary" @click="emit('cancel')">取消</button>
      </div>
    </div>
  </form>
</template>

<style scoped>
/* Bootstrap resets `fieldset` and `legend` to nothing but size; the console groups a rule's four concerns with a
   hairline box, which is what makes the rail scannable at 14px. */
.block {
  border: 1px solid var(--bs-border-color);
  border-radius: var(--bs-border-radius);
  margin: 0;
  padding: .75rem;
}
.block legend {
  color: var(--bs-secondary-color);
  font-size: .82rem;
  padding: 0 .4rem;
  width: auto;
}
.editor textarea {
  resize: vertical;
}
/* The channel box only needs nine characters; without a width the input group gives it half the row. */
.channel {
  flex: 0 0 9.5rem;
}
</style>
