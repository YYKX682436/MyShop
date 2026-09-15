export type AfterSaleAnchorAction = "ignore" | "wait" | "scroll"

/**
 * Data loads after the order page mounts. Keep the fragment decision pure so
 * the component can wait for the after-sale section without coupling scrolling
 * to every polling response.
 */
export function getAfterSaleAnchorAction(
  hash: string,
  hasAfterSaleData: boolean,
  handledHash: string | null,
): AfterSaleAnchorAction {
  if (hash !== "#after-sale" || handledHash === hash) return "ignore"
  return hasAfterSaleData ? "scroll" : "wait"
}
