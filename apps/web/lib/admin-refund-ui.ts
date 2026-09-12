export type RefundCardKeyAvailability = "available" | "empty" | "loading" | "error"

export interface RefundCardKeyAvailabilityInput {
  loading: boolean
  error: boolean
  count: number
}

export function getRefundCardKeyAvailability({
  loading,
  error,
  count,
}: RefundCardKeyAvailabilityInput): RefundCardKeyAvailability {
  if (loading) return "loading"
  if (error) return "error"
  return count > 0 ? "available" : "empty"
}

export function canReclaimCardKeys(input: RefundCardKeyAvailabilityInput): boolean {
  return getRefundCardKeyAvailability(input) === "available"
}

export function normalizeRefundCardKeySelection(
  selected: boolean,
  availability: RefundCardKeyAvailabilityInput,
): boolean {
  return selected && canReclaimCardKeys(availability)
}

export function buildAdminRefundPayload(reason: string, reclaimCardKeys: boolean) {
  return {
    reason,
    confirmed: true,
    reclaim_card_keys: reclaimCardKeys,
  }
}
