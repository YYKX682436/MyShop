import type { PaymentNextAction } from "@/types"

export type PaymentActionEnvironment = {
  isMobile: boolean
  isWechatBrowser: boolean
  isAlipayBrowser: boolean
}

export type PaymentActionDecision =
  | { kind: "qr_code"; value: string }
  | { kind: "redirect"; url: string }
  | { kind: "open_in_client"; mode: "direct" | "qr_code" | "copy"; url: string; client: "wechat" | "alipay" }
  | { kind: "deep_link"; mode: "open" | "qr_code"; url: string; client: "wechat" | "alipay" }
  | { kind: "form_post"; url: string; fields: Record<string, string> }

/**
 * Decide the browser operation from the explicit action type. URL shape and
 * mobile/desktop guesses are never used to reinterpret one action as another.
 */
export function resolvePaymentAction(
  action: PaymentNextAction,
  environment: PaymentActionEnvironment,
): PaymentActionDecision {
  switch (action.type) {
    case "qr_code":
      return { kind: "qr_code", value: action.value }
    case "redirect":
      return { kind: "redirect", url: action.url }
    case "open_in_client":
      return {
        kind: "open_in_client",
        mode: environment.isMobile && ((environment.isWechatBrowser && action.client === "wechat")
          || (environment.isAlipayBrowser && action.client === "alipay"))
          ? "direct"
          : environment.isMobile
            ? "copy"
            : "qr_code",
        url: action.url,
        client: action.client,
      }
    case "deep_link":
      return {
        kind: "deep_link",
        mode: environment.isMobile ? "open" : "qr_code",
        url: action.url,
        client: action.client,
      }
    case "form_post":
      return { kind: "form_post", url: action.url, fields: action.fields }
  }
}

export function isWechatBrowser(): boolean {
  return typeof navigator !== "undefined"
    && navigator.userAgent.toLowerCase().includes("micromessenger")
}

export function isAlipayBrowser(): boolean {
  return typeof navigator !== "undefined"
    && /alipayclient|alipay/i.test(navigator.userAgent)
}
