export function isValidUnlimitedStockQuantity(value: number | null): boolean {
  return value === null || (Number.isInteger(value) && value >= 0)
}

export function isValidStockCapPerSku(value: number | null): boolean {
  return value === null || (Number.isInteger(value) && value >= 0 && value <= 999999)
}
