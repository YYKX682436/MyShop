import type { CreateOrderResult, OrderPaymentMethod } from "@/types"

export interface BalancePaymentAvailability {
  selectable: boolean
  reason: "available" | "login_required" | "insufficient"
}

export function getBalancePaymentAvailability(
  isLoggedIn: boolean,
  role: string | null | undefined,
  balance: number | null | undefined,
  total: number,
): BalancePaymentAvailability {
  if (!isLoggedIn || role !== "USER") {
    return { selectable: false, reason: "login_required" }
  }
  const currentBalance = Number(balance ?? 0)
  const required = Number(total)
  return {
    selectable: Number.isFinite(currentBalance) && currentBalance + 0.000001 >= required,
    reason: currentBalance + 0.000001 >= required ? "available" : "insufficient",
  }
}

export function isImmediateBalancePaymentSuccess(result: CreateOrderResult): boolean {
  return result.payment.payment_method === "BALANCE"
    && result.payment.paid === true
    && result.payment.status === "PAID"
    && result.order.status === "PAID"
    && result.payment.next_action == null
}

export function paymentMethodForRequest(
  method: OrderPaymentMethod,
  channelCode: string,
): {
  payment_method: OrderPaymentMethod
  payment_channel_code?: string
} {
  return method === "ONLINE"
    ? { payment_method: method, payment_channel_code: channelCode }
    : { payment_method: method }
}
