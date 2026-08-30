/**
 * Money helpers for user-center inputs and previews.
 *
 * Amounts are accepted as decimal strings and converted to integer cents only
 * after the complete string has passed the strict decimal grammar. This keeps
 * values such as 0.29 and 2.55 independent of binary floating-point math.
 */
export const MAX_BALANCE_AMOUNT = "9999999999.99"
export const MAX_BALANCE_CENTS = 999_999_999_999

const MONEY_PATTERN = /^(?:0|[1-9]\d*)(?:\.\d{1,2})?$/

export function parseMoneyToCents(raw: string): number | null {
  if (!MONEY_PATTERN.test(raw)) return null

  const [wholePart, fractionPart = ""] = raw.split(".")
  const wholeCents = Number.parseInt(wholePart, 10) * 100
  const fractionCents = fractionPart
    ? Number.parseInt(fractionPart.padEnd(2, "0"), 10)
    : 0
  const cents = wholeCents + fractionCents

  if (!Number.isSafeInteger(cents) || cents > MAX_BALANCE_CENTS) return null
  return cents
}

export function parsePositiveMoneyToCents(raw: string): number | null {
  const cents = parseMoneyToCents(raw)
  return cents !== null && cents > 0 ? cents : null
}

/** Convert an API money value to cents without doing arithmetic on the value. */
export function parseMoneyValueToCents(value: number | string | null | undefined): number | null {
  if (typeof value === "number") {
    if (!Number.isFinite(value)) return null
    return parseSignedMoneyStringToCents(String(value))
  }
  if (typeof value === "string") return parseSignedMoneyStringToCents(value)
  return null
}

function parseSignedMoneyStringToCents(raw: string): number | null {
  if (raw.startsWith("-")) {
    const cents = parseMoneyToCents(raw.slice(1))
    return cents === null ? null : -cents
  }
  return parseMoneyToCents(raw)
}

export function formatCents(cents: number): string {
  if (!Number.isSafeInteger(cents)) return "0.00"
  const sign = cents < 0 ? "-" : ""
  const absolute = Math.abs(cents)
  const whole = Math.floor(absolute / 100)
  const fraction = String(absolute % 100).padStart(2, "0")
  return `${sign}${whole}.${fraction}`
}

export function formatYuanFromCents(cents: number): string {
  return `¥${formatCents(cents)}`
}

export function formatYuanValue(value: number | string | null | undefined): string {
  const cents = parseMoneyValueToCents(value)
  return `¥${formatCents(cents ?? 0)}`
}
