import { http } from "./http";

/**
 * Mirrors `TotpController.enroll`'s map. The secret is returned once and only reaches the operator's authenticator
 * app (by typing or by the `otpauthUri`); the row keeps the `SecretCipher`-encrypted value after `activate`.
 */
export interface TotpEnrollment {
  secret: string;
  otpauthUri: string;
  digits: number;
  periodSeconds: number;
}

/**
 * `POST /api/auth/totp/*` (03 §9.1). `activate` and `deactivate` answer 204, so there is nothing to read back —
 * `GET /api/auth/me`'s `totpEnabled` is what tells the UI the state changed.
 *
 * <p>Enrolment is deliberately two calls: storing the secret at `enroll` would lock the operator out of an account
 * whose phone never produced a code.
 */
export const securityApi = {
  enroll: () => http.post<TotpEnrollment>("/api/auth/totp/enroll"),
  activate: (secret: string, code: string) => http.post<void>("/api/auth/totp/activate", { secret, code }),
  deactivate: (password: string) => http.post<void>("/api/auth/totp/deactivate", { password }),
};
