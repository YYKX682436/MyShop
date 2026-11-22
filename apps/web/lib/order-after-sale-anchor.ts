/** Resolve legacy order fragments to the authorized order conversation entry. */
export function getAfterSaleAnchorDestination(hash: string, orderId: string): string | null {
  return hash === "#after-sale" ? `/messages/support/new?order=${encodeURIComponent(orderId)}` : null
}
