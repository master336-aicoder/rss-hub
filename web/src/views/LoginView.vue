<script setup lang="ts">
import { reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ApiError } from "../api/errors";
import { useSessionStore } from "../stores/session";

const session = useSessionStore();
const router = useRouter();
const route = useRoute();

const form = reactive({ username: "", password: "", code: "" });
const error = ref("");
const busy = ref(false);

async function submit() {
  error.value = "";
  busy.value = true;
  try {
    await session.login(form.username, form.password, form.code);
    const next = typeof route.query.next === "string" ? route.query.next : "/";
    await router.replace(next.startsWith("/") ? next : "/");
  } catch (cause) {
    // The login response is a ProblemDetail on failure, so its code is the only reason we can show something
    // more useful than "HTTP 401".
    error.value =
      cause instanceof ApiError
        ? cause.code === "invalid_credentials"
          ? "用户名、密码或动态码不正确。"
          : cause.code === "account_locked"
            ? "失败次数过多，账号已临时锁定（5 次锁 15 分钟）。稍后再试。"
            : cause.code === "csrf_missing"
              ? "缺少 CSRF Cookie，请刷新页面后重试。"
              : cause.message
        : "无法连接服务，请确认后端已启动。";
    form.password = "";
    form.code = "";
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <div class="d-flex align-items-center justify-content-center flex-grow-1 py-5">
    <form class="card login-card" @submit.prevent="submit">
      <div class="card-body d-flex flex-column gap-3">
        <h1 class="h5 mb-0 d-flex align-items-center gap-2">
          <i class="bi bi-rss-fill text-primary" aria-hidden="true"></i>登录 RSS Hub
        </h1>
        <p class="text-secondary small mb-0">单机自托管，账号由环境变量在首次启动时创建。</p>

        <div>
          <label class="form-label" for="login-username">用户名</label>
          <input id="login-username" v-model.trim="form.username" class="form-control" name="username"
            autocomplete="username" required>
        </div>
        <div>
          <label class="form-label" for="login-password">密码</label>
          <input id="login-password" v-model="form.password" class="form-control" name="password" type="password"
            autocomplete="current-password" required>
        </div>
        <div>
          <label class="form-label" for="login-code">
            动态口令
            <span class="text-secondary fw-normal">（启用二次验证后必填）</span>
          </label>
          <input id="login-code" v-model="form.code" class="form-control" name="code" inputmode="numeric"
            autocomplete="one-time-code" maxlength="10" placeholder="6 位数字">
        </div>

        <div v-if="error" class="alert alert-danger py-2 mb-0 small" role="alert">
          <i class="bi bi-exclamation-triangle-fill me-1" aria-hidden="true"></i>{{ error }}
        </div>

        <button type="submit" class="btn btn-primary" :disabled="busy">
          <span v-if="busy" class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>
          {{ busy ? "登录中…" : "登录" }}
        </button>
      </div>
    </form>
  </div>
</template>

<style scoped>
/* Bootstrap has no max-width utility; the login card is the one place in the console that has a fixed measure. */
.login-card {
  width: 100%;
  max-width: 21rem;
}
</style>
