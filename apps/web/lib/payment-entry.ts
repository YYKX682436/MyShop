import { isAlipayBrowser, isWechatBrowser, resolvePaymentAction, type PaymentActionDecision, type PaymentActionEnvironment } from "./payment-action.ts"
import { isMobileDevice } from "./utils.ts"
import type { PaymentNextAction } from "../types"

const PAYMENT_ACTION_STORAGE_PREFIX = "myshop:payment-action:"

export type PaymentEntry = {
  mode: "execute" | "payment_page"
  action: PaymentNextAction
  decision: PaymentActionDecision
}

/**
 * Build the browser environment used by the provider-neutral action policy.
 * The policy only sees the explicit PaymentNextAction and browser capabilities;
 * it never inspects a provider id or guesses from a URL.
 */
export function getPaymentActionEnvironment(): PaymentActionEnvironment {
  return {
    isMobile: isMobileDevice(),
    isWechatBrowser: isWechatBrowser(),
    isAlipayBrowser: isAlipayBrowser(),
  }
}

/**
 * Decide whether an action can leave the current page immediately or needs
 * MyShop's PaymentPage to render a user-facing action.
 */
export function resolvePaymentEntry(
  action: PaymentNextAction,
  environment: PaymentActionEnvironment,
): PaymentEntry {
  const decision = resolvePaymentAction(action, environment)
  const mode = action.type === "redirect"
    || action.type === "form_post"
    || (action.type === "open_in_client" && decision.kind === "open_in_client" && decision.mode === "direct")
    || (action.type === "deep_link" && decision.kind === "deep_link" && decision.mode === "open")
    ? "execute"
    : "payment_page"

  return { mode, action, decision }
}

export function shouldSeedPaymentAction(entry: Pick<PaymentEntry, "mode">): boolean {
  return entry.mode === "payment_page"
}

export function buildPaymentPageHref(orderId: string, orderNo: number | string, method: string): string {
  const query = new URLSearchParams({
    orderNo: String(orderNo),
    method,
  })
  return `/pay/${encodeURIComponent(orderId)}?${query.toString()}`
}

export function paymentActionStorageKey(orderId: string): string {
  return `${PAYMENT_ACTION_STORAGE_PREFIX}${orderId}`
}

function getPaymentActionStorage(): Storage | null {
  if (typeof window === "undefined") return null
  try {
    return window.sessionStorage
  } catch {
    return null
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null
}

function isPaymentNextAction(value: unknown): value is PaymentNextAction {
  if (!isRecord(value) || typeof value.type !== "string") return false
  switch (value.type) {
    case "qr_code":
      return typeof value.value === "string"
    case "redirect":
      return typeof value.url === "string"
    case "open_in_client":
    case "deep_link":
      return typeof value.url === "string" && (value.client === "wechat" || value.client === "alipay")
    case "form_post":
      return typeof value.url === "string"
        && isRecord(value.fields)
        && Object.values(value.fields).every((field) => typeof field === "string")
    default:
      return false
  }
}

/** Save only the public PaymentNextAction as the first-render seed. */
export function savePaymentAction(orderId: string, action: PaymentNextAction | null | undefined): void {
  const storage = getPaymentActionStorage()
  if (!storage) return
  const key = paymentActionStorageKey(orderId)
  try {
    if (!action) {
      storage.removeItem(key)
      return
    }
    if (!shouldSeedPaymentAction(resolvePaymentEntry(action, getPaymentActionEnvironment()))) {
      storage.removeItem(key)
      return
    }
    storage.setItem(key, JSON.stringify(action))
  } catch {
    // sessionStorage is an optimization; server status remains authoritative.
  }
}

export function readPaymentAction(orderId: string): PaymentNextAction | null {
  const storage = getPaymentActionStorage()
  if (!storage) return null
  const key = paymentActionStorageKey(orderId)
  try {
    const raw = storage.getItem(key)
    if (!raw) return null
    const parsed: unknown = JSON.parse(raw)
    if (isPaymentNextAction(parsed)
      && shouldSeedPaymentAction(resolvePaymentEntry(parsed, getPaymentActionEnvironment()))) {
      return parsed
    }
    storage.removeItem(key)
  } catch {
    try {
      storage.removeItem(key)
    } catch {
      // Ignore an unavailable storage implementation.
    }
  }
  return null
}

export function clearPaymentAction(orderId: string): void {
  const storage = getPaymentActionStorage()
  if (!storage) return
  try {
    storage.removeItem(paymentActionStorageKey(orderId))
  } catch {
    // sessionStorage is best-effort and never the source of payment truth.
  }
}
