import { ApiError } from "./errors";

const CSRF_COOKIE = "XSRF-TOKEN";
const CSRF_HEADER = "X-XSRF-TOKEN";

/**
 * Reads the CSRF cookie the backend deliberately leaves non-http-only. Cookie values can be duplicated by a
 * stale path-scoped entry, so the last one wins — that is the one the browser will actually send back.
 */
function csrfToken(): string | undefined {
  const matches = document.cookie
    .split("; ")
    .filter((pair) => pair.startsWith(`${CSRF_COOKIE}=`))
    .map((pair) => decodeURIComponent(pair.slice(CSRF_COOKIE.length + 1)))
    .filter((value) => value.length > 0);
  return matches.at(-1);
}

interface RequestOptions {
  method?: "GET" | "POST" | "PATCH" | "PUT" | "DELETE";
  /** Sent as `application/x-www-form-urlencoded`; Spring Security's login and logout filters read params, not JSON. */
  form?: Record<string, string>;
  /** Serialised to JSON. */
  body?: unknown;
  /**
   * Sent verbatim with this Content-Type. OPML import is a document, not a JSON envelope, and the endpoint
   * consumes `application/xml | text/xml | text/plain | application/octet-stream`.
   */
  raw?: { contentType: string; body: string };
  signal?: AbortSignal;
}

/**
 * All same-origin traffic goes through here so auth and CSRF handling can never be forgotten by a caller.
 * `credentials` is implicit (`same-origin` is the default), which is what keeps `RSSHUB_SESSION` attached.
 */
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const method = options.method ?? "GET";
  const headers = new Headers({ Accept: "application/json" });
  let body: string | undefined;

  if (options.form !== undefined) {
    headers.set("Content-Type", "application/x-www-form-urlencoded");
    body = new URLSearchParams(options.form).toString();
  } else if (options.raw !== undefined) {
    headers.set("Content-Type", options.raw.contentType);
    body = options.raw.body;
  } else if (options.body !== undefined) {
    headers.set("Content-Type", "application/json");
    body = JSON.stringify(options.body);
  }

  // GET is exempt from Spring's CSRF filter, so the token is only needed — and only present — for the rest.
  if (method !== "GET") {
    const token = csrfToken();
    if (token === undefined) {
      throw new ApiError(0, "csrf_missing", "CSRF token cookie is not set; reload and sign in again.");
    }
    headers.set(CSRF_HEADER, token);
  }

  const response = await fetch(path, { method, headers, body, signal: options.signal });
  return unwrap<T>(response);
}

async function unwrap<T>(response: Response): Promise<T> {
  if (response.status === 204) {
    return undefined as T;
  }

  const text = await response.text();
  const payload: unknown = text.length === 0 ? undefined : parseJson(text);

  if (!response.ok) {
    throw toApiError(response, payload);
  }
  return payload as T;
}

function parseJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    // A proxy or filter can answer with HTML even when the app would have answered with JSON.
    return { detail: text.slice(0, 200) };
  }
}

function toApiError(response: Response, payload: unknown): ApiError {
  const problem = (payload ?? {}) as Record<string, unknown>;
  // Problem type is `urn:rsshub:<code>`; anything else (a proxy error page, for instance) keeps no code.
  const type = typeof problem.type === "string" ? problem.type : "";
  const code = type.startsWith("urn:rsshub:") ? type.slice("urn:rsshub:".length) : `http_${response.status}`;
  const detail = typeof problem.detail === "string" ? problem.detail : response.statusText;
  return new ApiError(response.status, code, detail);
}

export const http = {
  get: <T>(path: string, signal?: AbortSignal) => request<T>(path, { signal }),
  post: <T>(path: string, body?: unknown) => request<T>(path, { method: "POST", body }),
  postForm: <T>(path: string, form: Record<string, string>) => request<T>(path, { method: "POST", form }),
  postRaw: <T>(path: string, contentType: string, rawBody: string) =>
    request<T>(path, { method: "POST", raw: { contentType, body: rawBody } }),
  put: <T>(path: string, body: unknown) => request<T>(path, { method: "PUT", body }),
  patch: <T>(path: string, body: unknown) => request<T>(path, { method: "PATCH", body }),
  del: <T>(path: string) => request<T>(path, { method: "DELETE" }),
};
