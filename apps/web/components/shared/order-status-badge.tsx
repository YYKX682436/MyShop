"use client"

import { cn } from "@/lib/utils"
import type { OrderStatus } from "@/types"
import { useLocale } from "@/lib/context"
import type { TranslationKey } from "@/lib/i18n"
import { deriveOrderDisplayStatus, type OrderDisplayStatus, type OrderStatusItem } from "@/lib/order-status"

const statusStyles: Record<OrderDisplayStatus, string> = {
  PENDING: "bg-amber-100 text-amber-800 dark:bg-amber-900/30 dark:text-amber-400",
  WAITING_MANUAL: "bg-amber-100 text-amber-800 dark:bg-amber-900/30 dark:text-amber-400",
  WAITING_AUTO: "bg-blue-100 text-blue-800 dark:bg-blue-900/30 dark:text-blue-400",
  PROCESSING: "bg-indigo-100 text-indigo-800 dark:bg-indigo-900/30 dark:text-indigo-400",
  DELIVERED: "bg-emerald-100 text-emerald-800 dark:bg-emerald-900/30 dark:text-emerald-400",
  EXPIRED: "bg-zinc-100 text-zinc-600 dark:bg-zinc-800 dark:text-zinc-400",
  REFUNDED: "bg-violet-100 text-violet-800 dark:bg-violet-900/30 dark:text-violet-300",
  AFTER_SALE: "bg-orange-100 text-orange-800 dark:bg-orange-900/30 dark:text-orange-300",
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
