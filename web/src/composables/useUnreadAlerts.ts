import { onBeforeUnmount, onMounted, ref, type Ref } from "vue";
import { useEntriesStore } from "../stores/entries";

/** One minute. A fetch round is an hour apart, so checking faster than that buys nothing but requests. */
const POLL_MS = 60_000;

export interface UnreadAlerts {
  supported: boolean;
  permission: Ref<string>;
  /** What the operator sees when a desktop popup is not available or not allowed. */
  note: Ref<string>;
  ask: () => Promise<void>;
}

/**
 * FR-2.4's optional desktop notification: watch the unread badge and say so out loud when it grows.
 *
 * The popup body is a count and nothing else, because an entry title is whatever the source wrote and a desktop
 * notification is the one surface this app cannot style, sanitize or click through safely.
 */
export function useUnreadAlerts(): UnreadAlerts {
  const inbox = useEntriesStore();
  const supported = typeof window !== "undefined" && "Notification" in window;
  const permission = ref<string>(supported ? Notification.permission : "unsupported");
  const note = ref("");

  let seen: number | undefined;
  let timer: ReturnType<typeof setInterval> | undefined;

  async function ask(): Promise<void> {
    if (!supported) {
      note.value = "这个浏览器没有桌面通知接口；未读数仍然会在左侧标签上更新。";
      return;
    }
    try {
      permission.value = await Notification.requestPermission();
    } catch (cause) {
      permission.value = "denied";
      note.value = String(cause);
      return;
    }
    if (permission.value !== "granted") {
      note.value = "系统没有允许桌面通知；未读数仍然会在左侧标签上更新。";
    }
  }

  async function poll(): Promise<void> {
    const before = seen;
    await inbox.countersOnly().catch(() => undefined);
    const unread = inbox.counters.unread;
    seen = unread;
    if (before === undefined || unread <= before) return;
    const message = `${unread - before} 条新未读（共 ${unread} 条）`;
    if (permission.value === "granted") new Notification("RSS Hub 收件箱", { body: message, tag: "rsshub-unread" });
    else note.value = message;
  }

  onMounted(() => {
    if (!supported || permission.value === "denied") return;
    void poll();
    timer = setInterval(() => void poll(), POLL_MS);
  });

  onBeforeUnmount(() => {
    if (timer !== undefined) clearInterval(timer);
  });

  return { supported, permission, note, ask };
}
