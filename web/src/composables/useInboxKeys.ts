import { onBeforeUnmount, onMounted } from "vue";
import { useEntriesStore } from "../stores/entries";

type Inbox = ReturnType<typeof useEntriesStore>;

/** FR-2.4's reader keys. Anything else belongs to the browser or to the field being typed in. */
const READER_KEYS: Record<string, (inbox: Inbox) => Promise<void>> = {
  j: (inbox) => inbox.selectRelative(1),
  k: (inbox) => inbox.selectRelative(-1),
  n: (inbox) => inbox.selectNextUnread(),
  s: (inbox) => inbox.toggleStarred(),
  m: (inbox) => inbox.toggleRead(),
};

/** A key pressed while a text field has the caret is typing, not reading. */
function isTyping(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  const tag = target.tagName;
  return tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT" || target.isContentEditable;
}

/**
 * `j`/`k` walk the list, `n` jumps to the next unread, `s` stars and `m` reads the open row.
 *
 * The listener is on the window, not on the list, because the reader pane beside the rows takes the focus after a
 * click; a handler scoped to one column would stop working exactly when the operator starts using it.
 */
export function useInboxKeys(): void {
  const inbox = useEntriesStore();

  function onKey(event: Event): void {
    if (!(event instanceof KeyboardEvent)) return;
    if (event.defaultPrevented || event.metaKey || event.ctrlKey || event.altKey) return;
    if (isTyping(event.target)) return;
    const action = READER_KEYS[event.key.toLowerCase()];
    if (action === undefined) return;
    // preventDefault, because `n` and `m` are Firefox caret-browsing and quick-find keys; without it the page moves.
    event.preventDefault();
    void action(inbox).then(() => {
      // Coupled to the markup: `InboxView.vue` gives every row these two classes, and this is how a keyboard walk
      // keeps the picked row on screen. Rename there, rename here.
      document.querySelector(".entry-row.picked")?.scrollIntoView({ block: "nearest" });
    });
  }

  onMounted(() => window.addEventListener("keydown", onKey));
  onBeforeUnmount(() => window.removeEventListener("keydown", onKey));
}
