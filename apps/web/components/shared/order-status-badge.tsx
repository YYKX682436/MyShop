"use client"

import { cn } from "@/lib/utils"
import type { OrderStatus } from "@/types"
import { useLocale } from "@/lib/context"
import type { TranslationKey } from "@/lib/i18n"
import { deriveOrderDisplayStatus, type OrderDisplayStatus, type OrderStatusItem } from "@/lib/order-status"

const statusStyles: Record<OrderDisplayStatus, string> = {
  PENDING: "border border-amber-600/20 bg-amber-500/10 text-amber-900 dark:border-amber-500/30 dark:bg-amber-500/15 dark:text-amber-200",
  WAITING_MANUAL: "border border-amber-600/25 bg-amber-500/10 text-amber-900 dark:border-amber-500/30 dark:bg-amber-500/15 dark:text-amber-200",
  WAITING_AUTO: "border border-blue-500/20 bg-blue-500/10 text-blue-800 dark:border-blue-500/30 dark:bg-blue-500/15 dark:text-blue-300",
  PROCESSING: "border border-sky-500/20 bg-sky-500/10 text-sky-800 dark:border-sky-500/30 dark:bg-sky-500/15 dark:text-sky-300",
  DELIVERED: "border border-emerald-500/20 bg-emerald-500/10 text-emerald-800 dark:border-emerald-500/30 dark:bg-emerald-500/15 dark:text-emerald-300",
  EXPIRED: "border border-border-subtle bg-surface-muted text-muted-foreground",
  REFUNDED: "border border-slate-300/80 bg-slate-500/10 text-slate-700 dark:border-slate-700/80 dark:bg-slate-500/15 dark:text-slate-300",
  AFTER_SALE: "border border-amber-600/25 bg-amber-500/10 text-amber-900 dark:border-amber-500/30 dark:bg-amber-500/15 dark:text-amber-200",
}

const statusKeys: Record<OrderDisplayStatus, TranslationKey> = {
  PENDING: "status.PENDING",
  WAITING_MANUAL: "status.WAITING_MANUAL",
  WAITING_AUTO: "status.WAITING_AUTO",
  PROCESSING: "status.PROCESSING",
  DELIVERED: "status.DELIVERED",
  EXPIRED: "status.EXPIRED",
  REFUNDED: "status.REFUNDED",
  AFTER_SALE: "status.AFTER_SALE",
}

export function OrderStatusBadge({
  status,
  items,
}: {
  status: OrderStatus
  items?: ReadonlyArray<OrderStatusItem>
}) {
  const { t } = useLocale()
  const displayStatus = deriveOrderDisplayStatus(status, items)
  return (
    <span
      className={cn(
        "inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium",
        statusStyles[displayStatus]
      )}
    >
      {t(statusKeys[displayStatus])}
    </span>
  )
}
