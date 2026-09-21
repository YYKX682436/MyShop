export function isValidUnlimitedStockQuantity(value: number | null): boolean {
  return value === null || (Number.isInteger(value) && value >= 0)
}
