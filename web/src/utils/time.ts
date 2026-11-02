const stamp = new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium", timeStyle: "short" });
const DAY = 86_400;

/** Every timestamp the API sends is an ISO-8601 instant; the display form is the UI's business, not the wire's. */
export function formatAt(value?: string): string {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : stamp.format(date);
}

/** Compact "how long ago", for rows where the exact minute is not the point. */
export function ago(value?: string): string {
  if (!value) return "—";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  const seconds = Math.max(0, Math.round((Date.now() - date.getTime()) / 1000));
  if (seconds < 60) return "刚刚";
  if (seconds < 3_600) return `${Math.round(seconds / 60)} 分钟前`;
  if (seconds < DAY) return `${Math.round(seconds / 3_600)} 小时前`;
  if (seconds < 30 * DAY) return `${Math.round(seconds / DAY)} 天前`;
  return stamp.format(date);
}

/** Reads as "4 天 6 小时" so the health page's quiet column is comparable at a glance. */
export function span(seconds?: number): string {
  if (seconds === undefined) return "—";
  if (seconds < 3_600) return `${Math.max(1, Math.round(seconds / 60))} 分钟`;
  if (seconds < DAY) return `${Math.round(seconds / 3_600)} 小时`;
  const days = Math.floor(seconds / DAY);
  const hours = Math.round((seconds % DAY) / 3_600);
  return hours === 0 ? `${days} 天` : `${days} 天 ${hours} 小时`;
}
