import { formatCents, parseMoneyValueToCents } from "./money.ts"

export const USER_MANAGEMENT_LIST_COLUMNS = [
  "user_no",
  "username",
  "user_level",
  "balance",
  "order_count",
  "total_spent",
  "status",
  "created_at",
  "actions",
] as const

export interface BalanceResultLike {
  delta: number | string
  balance_after: number | string
}

export interface IpLocationLike {
  country?: string | null
  province?: string | null
  city?: string | null
  display_name?: string | null
  resolved?: boolean
}

export function normalizeOptionalReason(reason: string | null | undefined): string | null {
  const normalized = reason?.trim() ?? ""
  return normalized ? normalized : null
}

export function isOptionalReasonValid(reason: string | null | undefined): boolean {
  const normalized = normalizeOptionalReason(reason)
  return normalized === null || normalized.length <= 500
}

export function formatBalanceAdjustmentResult(
  userNo: number | string | null | undefined,
  username: string,
  transaction: BalanceResultLike,
): string {
  const deltaCents = parseMoneyValueToCents(transaction.delta) ?? 0
  const balanceAfterCents = parseMoneyValueToCents(transaction.balance_after) ?? 0
  const signedDelta = `${deltaCents >= 0 ? "+" : "-"}${formatCents(Math.abs(deltaCents))}`
  return [
    `客户ID：${userNo ?? "—"}`,
    `客户账号：${username}`,
    `余额调整：${signedDelta}元`,
    `当前余额：${formatCents(balanceAfterCents)}元`,
  ].join("\n")
}

export function buildPasswordResetResultText(temporaryPassword: string): string {
  return ["登录密码已重置", temporaryPassword, "请尽快登录修改"].join("\n")
}

const MUNICIPALITIES = new Set(["北京市", "上海市", "天津市", "重庆市"])

export function normalizeIpLocationDisplay(location: IpLocationLike | null | undefined): string {
  if (!location?.resolved) return location?.display_name || "未知"
  const province = location.province?.trim() || ""
  const city = location.city?.trim() || ""
  if (province && MUNICIPALITIES.has(province)) return province
  if (province && city && province === city) return province
  if (province && city) return `${province} · ${city}`
  return location.display_name?.trim() || province || city || location.country?.trim() || "未知"
}
