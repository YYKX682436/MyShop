import type { TranslationKey } from "./i18n"

const keys: Record<string, TranslationKey> = {
  ADMIN_ADJUSTMENT: "admin.balanceAdminAdjustment",
  RECHARGE: "admin.balanceRecharge",
  ORDER_PAYMENT: "admin.balanceOrderPayment",
  REFUND: "admin.balanceRefund",
  SUBSITE_OPENING: "admin.balanceSubsiteOpening",
  SUBSITE_PROFIT: "admin.balanceSubsiteProfit",
  SUBSITE_PROFIT_REVERSAL: "admin.balanceSubsiteProfitReversal",
}

export function balanceTransactionLabel(type: string, translate: (key: TranslationKey) => string): string {
  const key = keys[type]
  return key ? translate(key) : type
}
