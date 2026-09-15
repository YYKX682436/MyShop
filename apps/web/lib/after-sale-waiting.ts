import type { TranslationKey } from "./i18n"

export type AfterSaleViewMode = "customer" | "admin"
export type AfterSaleWaitingForValue = "ADMIN" | "CUSTOMER" | null | undefined

/**
 * Resolve the label from the viewer's perspective. The server's waiting_for
 * value is shared, but the sentence shown to a customer and an administrator
 * must not be shared because each side is waiting on the other participant.
 */
export function getAfterSaleWaitingLabelKey(
  mode: AfterSaleViewMode,
  waitingFor: AfterSaleWaitingForValue,
): TranslationKey {
  if (mode === "customer") {
    if (waitingFor === "ADMIN") return "order.afterSaleWaitingForAdmin"
    if (waitingFor === "CUSTOMER") return "order.afterSaleCustomerWaitingForReply"
    return "afterSaleStatus.PENDING"
  }
  if (waitingFor === "ADMIN") return "admin.afterSaleNeedsAttention"
  if (waitingFor === "CUSTOMER") return "admin.afterSaleWaitingForCustomer"
  return "afterSaleStatus.PENDING"
}
