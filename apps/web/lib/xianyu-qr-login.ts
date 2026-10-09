import type { XianyuQrLoginStatusResponse } from "@/services/api"

export interface XianyuQrLoginUiState {
  status: "INITIALIZING" | XianyuQrLoginStatusResponse["status"]
  mode: "ADD_ACCOUNT" | "REAUTHENTICATE_EXISTING_ACCOUNT"
  loginIntentId: string
  sessionId?: string
  targetAccountRowId?: string
  targetProviderAccountId?: string
  qrCodeDataUrl?: string
  verificationMessage?: string
  verificationQrCodeDataUrl?: string
  errorCode?: string
}

export function buildQrLoginPollCommand(
  login: XianyuQrLoginUiState,
  requestId: string,
) {
  if (!login.loginIntentId || !login.sessionId) throw new Error("QR_LOGIN_INTENT_REQUIRED")
  if (login.mode === "REAUTHENTICATE_EXISTING_ACCOUNT" && !login.targetAccountRowId) {
    throw new Error("QR_LOGIN_TARGET_REQUIRED")
  }
  return {
    mode: login.mode,
    loginIntentId: login.loginIntentId,
    loginSessionId: login.sessionId,
    targetAccountRowId: login.targetAccountRowId,
    requestId,
  }
}

export function applyQrLoginStatus(
  login: XianyuQrLoginUiState,
  response: XianyuQrLoginStatusResponse,
): XianyuQrLoginUiState {
  if (response.loginIntentId !== login.loginIntentId || response.loginSessionId !== login.sessionId) {
    return { ...login, status: "FAILED", errorCode: "LOGIN_INTENT_IDENTITY_CONFLICT" }
  }
  if (
    login.mode === "REAUTHENTICATE_EXISTING_ACCOUNT"
    && response.accountId
    && response.accountId !== login.targetProviderAccountId
  ) {
    return { ...login, status: "FAILED", errorCode: "LOGIN_INTENT_TARGET_MISMATCH" }
  }
  return {
    ...login,
    status: response.status,
    errorCode: response.errorCode || undefined,
    verificationMessage: response.verificationMessage || undefined,
    verificationQrCodeDataUrl: response.verificationQrCodeDataUrl || login.verificationQrCodeDataUrl,
  }
}

export function isQrLoginTerminal(status: XianyuQrLoginStatusResponse["status"]): boolean {
  return new Set<XianyuQrLoginStatusResponse["status"]>([
    "PROVIDER_AUTH_VERIFIED",
    "PROVIDER_AUTH_VERIFICATION_FAILED",
    "EXPIRED",
    "CANCELLED",
    "FAILED",
  ]).has(status)
}

export function isQrLoginComplete(status: XianyuQrLoginStatusResponse["status"]): boolean {
  return status === "PROVIDER_AUTH_VERIFIED"
}
