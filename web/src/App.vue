<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { RouterLink, RouterView, useRoute, useRouter } from "vue-router";
import { useSessionStore } from "./stores/session";

const session = useSessionStore();
const route = useRoute();
const router = useRouter();

// Login is the only screen an anonymous visitor sees, and it carries its own header — a nav bar with a sign-out
// button and a feed list would be noise there.
const showChrome = computed(() => session.status === "authenticated" && route.name !== "login");

// The inbox is a three-column pane that owns the viewport; the other pages are ordinary scrolling documents.
const flush = computed(() => route.name === "inbox");

// Eleven tabs do not fit a laptop at this type size, and Bootstrap's own answer is a toggler below a breakpoint.
// No Bootstrap JavaScript is loaded, so the collapse is Vue state driving the two classes that CSS already knows.
const navOpen = ref(false);
watch(() => route.fullPath, () => {
  navOpen.value = false;
});

async function signOut() {
  await session.logout();
  await router.push({ name: "login" });
}

const tabs = [
  { name: "inbox", label: "收件箱", icon: "bi-inbox" },
  { name: "dashboard", label: "大屏", icon: "bi-grid-1x2" },
  { name: "feeds", label: "订阅管理", icon: "bi-rss" },
  { name: "posts", label: "文章", icon: "bi-file-earmark-text" },
  { name: "repacks", label: "聚合", icon: "bi-collection" },
  { name: "events", label: "事件源", icon: "bi-lightning-charge" },
  { name: "rules", label: "规则", icon: "bi-funnel" },
  { name: "notifications", label: "投递", icon: "bi-send" },
  { name: "health", label: "健康度", icon: "bi-heart-pulse" },
  { name: "security", label: "安全", icon: "bi-shield-lock" },
  { name: "settings", label: "设置", icon: "bi-sliders" },
];
</script>

<template>
  <div class="d-flex flex-column min-vh-100">
    <nav v-if="showChrome" class="navbar navbar-expand-xxl app-navbar sticky-top">
      <div class="container-fluid">
        <RouterLink class="navbar-brand d-flex align-items-center gap-2 fw-semibold" :to="{ name: 'inbox' }">
          <i class="bi bi-rss-fill text-primary" aria-hidden="true"></i>
          RSS Hub
        </RouterLink>
        <button class="navbar-toggler" type="button" :aria-expanded="navOpen" aria-controls="appNav"
          aria-label="展开导航" @click="navOpen = !navOpen">
          <span class="navbar-toggler-icon"></span>
        </button>
        <div id="appNav" class="navbar-collapse" :class="navOpen ? 'collapse show' : 'collapse'">
          <ul class="navbar-nav flex-row flex-wrap gap-1 align-items-center me-auto">
            <li v-for="tab in tabs" :key="tab.name" class="nav-item">
              <RouterLink class="nav-link d-flex align-items-center gap-1" :to="{ name: tab.name }">
                <i class="bi" :class="tab.icon" aria-hidden="true"></i>{{ tab.label }}
              </RouterLink>
            </li>
          </ul>
          <div class="d-flex align-items-center gap-2 ms-lg-3 ms-0 py-1 ps-lg-2">
            <span class="d-inline-flex align-items-center gap-1 quiet">
              <i class="bi bi-person-circle" aria-hidden="true"></i>{{ session.username }}
            </span>
            <button type="button" class="btn btn-sm btn-outline-secondary" @click="signOut">退出</button>
          </div>
        </div>
      </div>
    </nav>
    <main :class="flush ? 'flex-grow-1 d-flex pane-host' : 'flex-grow-1 p-3 p-lg-4'">
      <RouterView />
    </main>
  </div>
</template>
