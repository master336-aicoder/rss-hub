import { http } from "./http";

export interface SessionInfo {
  authenticated: boolean;
  username?: string;
  /** `AuthController.me` reports whether the account has a second factor, so the login form can ask for a code. */
  totpEnabled?: boolean;
}

/** Login and logout are answered by the security filters, not by a controller, so the body shapes are fixed here. */
export const authApi = {
  me: () => http.get<SessionInfo>("/api/auth/me"),
  /**
   * `code` is the TOTP step (03 §9.1): `OperatorAuthenticationProvider.LoginDetailsSource` reads the `code`
   * parameter, and an enrolled account is refused without it. An absent code is simply not sent, so a
   * second-factor-free login looks like the ordinary one to the filter.
   */
  login: (username: string, password: string, code?: string) =>
    http.postForm<{ username: string }>("/api/auth/login", {
      username,
      password,
      ...(code && code.trim() ? { code: code.trim() } : {}),
    }),
  logout: () => http.post<void>("/api/auth/logout"),
};
