import { createRouter, createWebHistory } from "vue-router";
import { useSessionStore } from "../stores/session";
import DashboardView from "../views/DashboardView.vue";
import EventsView from "../views/EventsView.vue";
import FeedsView from "../views/FeedsView.vue";
import HealthView from "../views/HealthView.vue";
import InboxView from "../views/InboxView.vue";
import LoginView from "../views/LoginView.vue";
import NotificationsView from "../views/NotificationsView.vue";
import PostsView from "../views/PostsView.vue";
import RepackView from "../views/RepackView.vue";
import RulesView from "../views/RulesView.vue";
import SecurityView from "../views/SecurityView.vue";
import SettingsView from "../views/SettingsView.vue";

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/", name: "inbox", component: InboxView },
    { path: "/dashboard", name: "dashboard", component: DashboardView },
    { path: "/feeds", name: "feeds", component: FeedsView },
    { path: "/posts", name: "posts", component: PostsView },
    { path: "/repacks", name: "repacks", component: RepackView },
    { path: "/rules", name: "rules", component: RulesView },
    { path: "/notifications", name: "notifications", component: NotificationsView },
    { path: "/events", name: "events", component: EventsView },
    { path: "/security", name: "security", component: SecurityView },
    { path: "/health", name: "health", component: HealthView },
    { path: "/settings", name: "settings", component: SettingsView },
    { path: "/login", name: "login", component: LoginView, meta: { public: true } },
  ],
});

/**
 * The guard asks the server rather than trusting a client-side flag, because the session cookie is http-only and
 * can expire while the tab stays open. `/api/auth/me` is anonymous-readable by design, so this costs one cheap
 * request per cold start and per sign-in, not per navigation.
 */
router.beforeEach(async (to) => {
  const session = useSessionStore();
  if (session.status === "unknown") {
    await session.refresh();
  }

  if (to.meta.public) {
    return session.status === "authenticated" ? { name: "inbox" } : true;
  }
  if (session.status === "anonymous") {
    return { name: "login", query: { next: to.fullPath } };
  }
  return true;
});

export default router;
