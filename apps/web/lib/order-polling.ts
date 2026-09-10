import type { OrderStatus } from "@/types"
import type { OrderStatusItem } from "@/lib/order-status"

export const PAYMENT_RETURN_POLL_INTERVAL = 1000
export const PAYMENT_STATUS_POLL_INTERVAL = 3000
export const FULFILLMENT_STATUS_POLL_INTERVAL = 10000

export function isTerminalFulfillmentStatus(status: OrderStatusItem["fulfillment_status"]): boolean {
  return status === "DELIVERED" || status === "CANCELLED"
}

/**
 * Payment and fulfillment are both external to the already-mounted order page.
 * Keep polling while either one can still change, but stop once the aggregate
 * is expired/delivered or every paid-order item is terminal.
 */
export function shouldPollOrderStatus(
  orderStatus: OrderStatus,
  items: ReadonlyArray<Pick<OrderStatusItem, "fulfillment_status">> = [],
): boolean {
  if (orderStatus === "PENDING") return true
  if (orderStatus !== "PAID") return false
  return items.some((item) => !isTerminalFulfillmentStatus(item.fulfillment_status))
}

export function getOrderStatusPollInterval(
  orderStatus: OrderStatus,
  checkingPaymentReturn: boolean,
): number | null {
  if (orderStatus === "PENDING") {
    return checkingPaymentReturn
      ? PAYMENT_RETURN_POLL_INTERVAL
      : PAYMENT_STATUS_POLL_INTERVAL
  }
  if (orderStatus === "PAID") return FULFILLMENT_STATUS_POLL_INTERVAL
  return null
}
