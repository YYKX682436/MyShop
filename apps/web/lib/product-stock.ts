import type { TranslationKey } from "@/lib/i18n"

export function hasPurchasableStock(stock: number | null | undefined, unlimited: boolean, status?: string | null): boolean {
  if (unlimited || status === "UNLIMITED") return true
  if (status) return status === "AVAILABLE" && (stock ?? 0) > 0
  return (stock ?? 0) > 0
}

export function stockNoticeKey(status?: string | null): TranslationKey {
  if (["SYNC_FAILED", "STALE", "ACCOUNT_UNAVAILABLE", "INVALID_DATA", "UNMAPPED", "UNKNOWN"].includes(status ?? "")) {
    return "product.stockCheckPending"
  }
  if (["BELOW_MINIMUM", "MULTIPLE_SHORTAGE"].includes(status ?? "")) return "product.quantityUnavailable"
  if (["LOCAL_UNAVAILABLE", "UPSTREAM_UNAVAILABLE", "INPUT_UNSUPPORTED"].includes(status ?? "")) {
    return "product.temporarilyUnavailable"
  }
  return "product.soldOut"
}

export function purchaseQuantityBounds(
  stock: number | null | undefined,
  minQuantity: number | null | undefined,
  maxQuantity: number | null | undefined,
  unlimited: boolean,
) {
  const minimum = Math.max(1, Number.isSafeInteger(minQuantity) ? Number(minQuantity) : 1)
  const configuredMaximum = maxQuantity != null && maxQuantity > 0 ? maxQuantity : Number.POSITIVE_INFINITY
  const stockMaximum = unlimited ? Number.POSITIVE_INFINITY : Math.max(0, Math.floor(stock ?? 0))
  const selectable = configuredMaximum >= minimum && stockMaximum >= minimum
  const maximum = selectable ? Math.min(configuredMaximum, stockMaximum) : minimum
  return { minimum, maximum, selectable }
}

export function clampPurchaseQuantity(value: number, minimum: number, maximum: number): number {
  return Math.min(maximum, Math.max(minimum, Number.isFinite(value) ? Math.trunc(value) : minimum))
}
