<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { securityApi, type TotpEnrollment } from "../api/security";
import { ApiError } from "../api/errors";
import { useSessionStore } from "../stores/session";

const session = useSessionStore();

const enrolling = ref(false);
const enrollment = ref<TotpEnrollment | null>(null);
const error = ref("");
const notice = ref("");
const busy = ref(false);
const code = reactive({ activate: "", password: "" });

function note(cause: unknown) {
  error.value = cause instanceof ApiError ? `${cause.code}: ${cause.message}` : String(cause);
}

onMounted(async () => {
  // The account's own state is only ever the server's answer, so the page re-asks instead of trusting the flag the
  // router guard left behind.
  await session.refresh();
});

async function enroll() {
  error.value = "";
  notice.value = "";
  busy.value = true;
  try {
    enrollment.value = await securityApi.enroll();
    enrolling.value = true;
  } catch (cause) {
    note(cause);
  } finally {
    busy.value = false;
  }
}

async function activate() {
  if (!enrollment.value) return;
  error.value = "";
  busy.value = true;
  try {
    await securityApi.activate(enrollment.value.secret, code.activate.trim());
    enrollment.value = null;
    enrolling.value = false;
    code.activate = "";
    notice.value = "二次验证已启用，下次登录要填 6 位动态码。";
    await session.refresh();
  } catch (cause) {
    note(cause);
  } finally {
    busy.value = false;
  }
}

async function deactivate() {
  error.value = "";
  busy.value = true;
  try {
    await securityApi.deactivate(code.password);
    code.password = "";
    notice.value = "二次验证已关闭。丢手机时这是唯一的出口，所以它要口令。";
    await session.refresh();
  } catch (cause) {
    note(cause);
  } finally {
    busy.value = false;
  }
}

function cancel() {
  enrolling.value = false;
  enrollment.value = null;
  code.activate = "";
}
</script>

<template>
  <section class="page-narrow">
    <h1 class="h4 mb-3 d-flex align-items-center gap-2">
      <i class="bi bi-shield-lock text-primary" aria-hidden="true"></i>账号安全
    </h1>

    <div v-if="error" class="alert alert-danger py-2 small">{{ error }}</div>
    <div v-if="notice" class="alert alert-success py-2 small">{{ notice }}</div>

    <div class="card mb-3">
      <div class="card-body">
        <h2 class="h6 mb-2">登录二次验证（TOTP）</h2>
        <p class="small mb-3">
          当前状态：<strong>{{ session.totpEnabled ? "已启用" : "未启用" }}</strong>
          <span class="text-body-secondary">（RFC 6238，SHA1 / 6 位 / 30 秒，允许前后各一步时钟偏差）</span>
        </p>

        <template v-if="enrollment">
          <p class="small">
            把这串密钥添进验证器 App，或导入下面的 <code>otpauth</code> 链接；启用码验证通过之前，服务端还不保存它。
          </p>
          <div class="mb-2">
            <p class="form-label small text-body-secondary mb-1">Base32 密钥</p>
            <code class="value d-block">{{ enrollment.secret }}</code>
          </div>
          <div class="mb-3">
            <p class="form-label small text-body-secondary mb-1">otpauth URI</p>
            <code class="value d-block">{{ enrollment.otpauthUri }}</code>
          </div>
          <div class="mb-3 field-code">
            <label class="form-label small text-body-secondary" for="totp-code">App 里现在的 6 位码</label>
            <input id="totp-code" v-model="code.activate" class="form-control" inputmode="numeric" maxlength="10"
              placeholder="123456" />
          </div>
          <div class="d-flex gap-2">
            <button type="button" class="btn btn-sm btn-primary" :disabled="busy || code.activate.trim().length < 6"
              @click="activate">启用</button>
            <button type="button" class="btn btn-sm btn-outline-secondary" @click="cancel">放弃这次绑定</button>
          </div>
        </template>
        <template v-else-if="session.totpEnabled">
          <div class="mb-3 field-wide">
            <p class="form-label small text-body-secondary mb-1">账号口令（关闭二次验证要用它证明身份）</p>
            <input v-model="code.password" class="form-control" type="password" autocomplete="current-password"
              aria-label="账号口令" />
          </div>
          <button type="button" class="btn btn-sm btn-outline-danger" :disabled="busy || !code.password"
            @click="deactivate">关闭二次验证</button>
        </template>
        <template v-else>
          <button type="button" class="btn btn-sm btn-primary" :disabled="busy" @click="enroll">
            <span v-if="busy" class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>
            {{ busy ? "处理中…" : "开始绑定" }}
          </button>
        </template>
      </div>
    </div>

    <div class="card">
      <div class="card-body">
        <h2 class="h6 mb-2">登录失败锁定</h2>
        <p class="small text-body-secondary">
          连续 5 次失败锁 15 分钟（<code>LoginAttemptGuard</code>，内存计数器，重启即清零）。锁定时登录返回 429
          <code>account_locked</code> 并带 <code>Retry-After</code> 时间戳，不会伪装成"口令错误"。
        </p>
        <p class="small text-body-secondary mb-0">
          启用二次验证前请先确认手机能拿到时间同步的验证器：一旦启用，没有码就进不去，只能靠这个页面用口令关闭。
          绑定过程是两步的——先 enroll，再 activate，半途放弃不会把账号锁在外面。
        </p>
      </div>
    </div>
  </section>
</template>

<style scoped>
/* Bootstrap has no "one narrow reading column" utility, and a TOTP form that spans the viewport reads as a
   spreadsheet rather than a settings sheet. */
.page-narrow {
  max-width: 46rem;
}
/* Bootstrap's width utilities are percentages, so a six-digit code box and a password box need their own rem
   widths or they stretch across the whole card. */
.field-code {
  max-width: 16rem;
}
.field-wide {
  max-width: 20rem;
}
.value {
  font-size: .82rem;
  word-break: break-all;
  background: var(--bs-secondary-bg);
  border: 1px solid var(--bs-border-color);
  border-radius: var(--bs-border-radius-sm);
  padding: .4rem .55rem;
}
</style>
