export function canApplyForAfterSale(status: string, guest: boolean | undefined): boolean {
  return guest === false && (status === "PAID" || status === "DELIVERED")
}
