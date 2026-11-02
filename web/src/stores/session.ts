import { ref } from "vue";
import { defineStore } from "pinia";
import { authApi } from "../api/auth";
import { ApiError } from "../api/errors";

/**
 * `unknown` is the state before the first `/api/auth/me` round trip, and the router guard treats it as "go ask".
 * Keeping it distinct from `anonymous` is what stops a cold start on a deep link from bouncing through the login
 * page twice.
 */
export const useSessionStore = defineStore("session", () => {
  const status = ref<"unknown" | "authenticated" | "anonymous">("unknown");
  const username = ref("");
  /** Drives the login form's 动态口令 box; only `/api/auth/me` is authoritative about it. */
  const totpEnabled = ref(false);

  async function refresh(): Promise<void> {
    try {
      const me = await authApi.me();
      status.value = me.authenticated ? "authenticated" : "anonymous";
      username.value = me.username ?? "";
      totpEnabled.value = me.totpEnabled === true;
    } catch (error) {
      // A 401 here means the session died mid-use; anything else is a network/server fault we surface as
      // anonymous so the guard shows the login screen rather than a blank page.
      status.value = "anonymous";
      username.value = "";
      totpEnabled.value = false;
      if (!(error instanceof ApiError)) {
        console.warn("session check failed", error);
      }
    }
  }

  async function login(name: string, password: string, code?: string): Promise<void> {
    const result = await authApi.login(name, password, code);
    status.value = "authenticated";
    username.value = result.username;
  }

  async function logout(): Promise<void> {
    try {
      await authApi.logout();
    } finally {
      // The server dropped the session even if the request failed, so the UI must not stay logged in.
      status.value = "anonymous";
      username.value = "";
    }
  }

  return { status, username, totpEnabled, refresh, login, logout };
});
