import { computed, ref } from "vue";
import { defineStore } from "pinia";
import { ApiError } from "../api/errors";
import { rulesApi, type Rule, type RuleDraft } from "../api/rules";

/**
 * The rule list, shared by the rules page (which edits it) and the delivery log (which filters by rule). Two views
 * asking the same question separately would disagree about which rules the breaker had stopped.
 */
export const useRulesStore = defineStore("rules", () => {
  const rules = ref<Rule[]>([]);
  const loading = ref(false);
  const error = ref("");

  /** The delivery log's dropdown needs `id -> name`, and a deleted rule's name is not in this list any more. */
  const namesById = computed(() => new Map(rules.value.map((rule) => [rule.id, rule.name])));

  const autoDisabled = computed(() =>
    rules.value.filter((rule) => !rule.enabled && (rule.disabledReason ?? "").startsWith("delivery failed")),
  );

  function describe(cause: unknown): string {
    return cause instanceof ApiError ? `${cause.code}: ${cause.message}` : String(cause);
  }

  async function load(): Promise<void> {
    loading.value = true;
    error.value = "";
    try {
      rules.value = await rulesApi.list();
    } catch (cause) {
      error.value = describe(cause);
    } finally {
      loading.value = false;
    }
  }

  /** Every write re-reads from the server response, so `hitCount` and the breaker's reason stay the backend's. */
  async function create(draft: RuleDraft): Promise<Rule> {
    const rule = await rulesApi.create(draft);
    rules.value = [rule, ...rules.value];
    return rule;
  }

  async function update(id: number, draft: RuleDraft): Promise<Rule> {
    const rule = await rulesApi.update(id, draft);
    rules.value = rules.value.map((row) => (row.id === id ? rule : row));
    return rule;
  }

  async function setEnabled(id: number, enabled: boolean): Promise<Rule> {
    const rule = await rulesApi.setEnabled(id, enabled);
    rules.value = rules.value.map((row) => (row.id === id ? rule : row));
    return rule;
  }

  async function remove(id: number): Promise<void> {
    await rulesApi.remove(id);
    rules.value = rules.value.filter((row) => row.id !== id);
  }

  return { rules, loading, error, namesById, autoDisabled, load, create, update, setEnabled, remove, describe };
});
