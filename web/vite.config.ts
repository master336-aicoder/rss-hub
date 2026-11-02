import vue from "@vitejs/plugin-vue";
import { defineConfig, loadEnv } from "vite";

const BACKEND = "http://127.0.0.1:8080";

/**
 * Dev proxy keeps the SPA and the API on one origin, which is the only way the session cookie and the
 * `XSRF-TOKEN` cookie are first-party — `SameSite=Lax` depends on it. Production serves `dist/` from the same
 * host as `/api/**`, so the paths below stay valid in both places and no URL is ever environment-dependent.
 * The dev server itself only answers on `localhost` (not the `127.0.0.1` literal), so scripts that poke it must
 * use that name; see `web/verify-dev-proxy.sh`.
 */
export default defineConfig(({ mode }) => {
  const target = loadEnv(mode, ".").VITE_API_TARGET || BACKEND;
  return {
    plugins: [vue()],
    server: {
      port: 5173,
      proxy: {
        "/api": { target },
        "/actuator": { target },
      },
    },
    build: { outDir: "dist", sourcemap: true },
  };
});
