/**
 * One error type for everything the API layer rejects: a non-2xx Problem Details response, or a request we
 * refuse to send because the CSRF cookie never arrived. `status` is 0 for the client-side cases.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string, detail: string) {
    super(detail);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
  }
}
