import type { OrderStatus } from "@/types"

export type FulfillmentStatus = "PENDING" | "WAITING_MANUAL" | "DELIVERED" | "CANCELLED"
export type OrderDisplayStatus =
  | "PENDING"
  | "WAITING_MANUAL"
  | "WAITING_AUTO"
  | "PROCESSING"
  | "DELIVERED"
  | "EXPIRED"
  | "REFUNDED"

export interface OrderStatusItem {
  delivery_type?: string | null
  stock_mode?: "CARD_KEY" | "MANUAL" | "UNLIMITED" | string | null
  fulfillment_status?: FulfillmentStatus | string | null
}

export function isManualFulfillmentItem(item: OrderStatusItem): boolean {
  return item.delivery_type?.toUpperCase() === "MANUAL"
    || item.stock_mode?.toUpperCase() === "MANUAL"
}

export function isAutomaticFulfillmentItem(item: OrderStatusItem): boolean {
  return !isManualFulfillmentItem(item)
}

/**
 * Order.status remains the payment/order aggregate. The storefront and admin
 * badge use this derived display state so PAID never gets shown as a fixed
 * "Paid" label when fulfillment is still in progress.
 */
export function deriveOrderDisplayStatus(
  orderStatus: OrderStatus,
  items?: ReadonlyArray<OrderStatusItem>,
): OrderDisplayStatus {
  if (orderStatus === "PENDING") return "PENDING"
  if (orderStatus === "DELIVERED") return "DELIVERED"
  if (orderStatus === "EXPIRED") return "EXPIRED"
  if (orderStatus === "REFUNDED") return "REFUNDED"

  const currentItems = items ?? []
  const unfinishedItems = currentItems.filter((item) =>
    item.fulfillment_status !== "DELIVERED"
    && item.fulfillment_status !== "CANCELLED"
  )
  if (unfinishedItems.length === 0) return "PROCESSING"

  if (unfinishedItems.every((item) =>
    isManualFulfillmentItem(item) && item.fulfillment_status === "WAITING_MANUAL"
  )) {
    return "WAITING_MANUAL"
  }
  if (unfinishedItems.every((item) =>
    isAutomaticFulfillmentItem(item) && item.fulfillment_status === "PENDING"
  )) {
    return "WAITING_AUTO"
  }
  return "PROCESSING"
}
