import { http } from "./http";

/** Mirrors `com.rsshub.adminapi.SubscriptionService.ImportResult`. */
export interface ImportResult {
  imported: number;
  skipped: number;
}

/**
 * The endpoint sets `Content-Disposition: attachment` with a timestamped filename, so a plain same-origin link is
 * the whole download implementation — a GET needs no CSRF header, and the browser names the file for us.
 */
export const OPML_EXPORT_PATH = "/api/opml";

export const opmlApi = {
  import: (xml: string) => http.postRaw<ImportResult>("/api/opml", "application/xml", xml),
};
