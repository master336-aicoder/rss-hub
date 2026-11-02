import { http } from "./http";

/** Mirrors `com.rsshub.settings.SettingsService.View`: resolved values, defaults already applied. */
export interface SettingsView {
  dashboardEntriesPerFeed: number;
  dashboardRefreshSeconds: number;
  inboxPageSize: number;
  notificationsPageSize: number;
}

/** The backend's own validation table (SettingsService.IntSpec); the form's hints and clamps come from here. */
export const SETTING_RANGES = {
  dashboardEntriesPerFeed: { min: 1, max: 50, label: "大屏每源条数" },
  dashboardRefreshSeconds: { min: 0, max: 600, label: "大屏刷新间隔（秒）" },
  inboxPageSize: { min: 10, max: 200, label: "收件箱每页条数" },
  notificationsPageSize: { min: 10, max: 500, label: "投递日志每页条数" },
} as const;

export type SettingKey = keyof typeof SETTING_RANGES;

export const settingsApi = {
  get: () => http.get<SettingsView>("/api/settings"),
  patch: (patch: Partial<SettingsView>) => http.patch<SettingsView>("/api/settings", patch),
};
