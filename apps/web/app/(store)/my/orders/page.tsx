"use client"

import { useEffect, useState } from "react"
import Image from "next/image"
import Link from "next/link"
import { Package } from "lucide-react"
import { useLocale } from "@/lib/context"
import { useRequireAuth } from "@/lib/hooks"
import { userApi, requestOrThrow } from "@/services/api"
import { OrderStatusBadge } from "@/components/shared/order-status-badge"
import type { OrderBrief, OrderStatus } from "@/types"
import type { TranslationKey } from "@/lib/i18n"
import { cn, formatDateTime, formatYuan } from "@/lib/utils"
import { buildOrderPageHref } from "@/lib/payment-entry"

const STATUS_FILTERS: (OrderStatus | "ALL")[] = ["ALL", "PENDING", "PAID", "DELIVERED", "REFUNDED", "EXPIRED"]

export default function MyOrdersPage() {
  const { t, locale } = useLocale()
  const user = useRequireAuth()
  const [statusFilter, setStatusFilter] = useState<OrderStatus | "ALL">("ALL")
  const [orders, setOrders] = useState<OrderBrief[]>([])
  const [loading, setLoading] = useState(true)
  const [page, setPage] = useState(1)
  const [total, setTotal] = useState(0)
  const pageSize = 20

  useEffect(() => {
    if (!user) return
    let cancelled = false
    async function fetchOrders() {
      setLoading(true)
      try {
        const params: { page: number; page_size: number; status?: string } = { page, page_size: pageSize }
        if (statusFilter !== "ALL") params.status = statusFilter
        const data = await requestOrThrow(() => userApi.getOrders(params))
        if (!cancelled) {
          setOrders(data.list)
          setTotal(data.pagination.total)
        }
      } catch {
        if (!cancelled) {
          setOrders([])
          setTotal(0)
        }
      } finally {
        if (!cancelled) setLoading(false)
      }
    }
    fetchOrders()
    return () => { cancelled = true }
  }, [user, statusFilter, page])

  const statusKeys: Record<OrderStatus, TranslationKey> = {
    PENDING: "status.PENDING",
    PAID: "status.PAID_FILTER",
    DELIVERED: "status.DELIVERED",
    EXPIRED: "status.EXPIRED",
    REFUNDED: "status.REFUNDED",
  }

  const getFilterLabel = (s: OrderStatus | "ALL") => {
    if (s === "ALL") return t("myOrders.all")
    return t(statusKeys[s])
  }

  if (!user) return null

  return (
    <div className="mx-auto w-full max-w-3xl space-y-6">
      <div>
        <p className="mb-2 text-xs font-semibold uppercase tracking-[0.16em] text-primary">MyShop</p>
        <h1 className="text-2xl font-semibold tracking-tight text-foreground">{t("myOrders.title")}</h1>
      </div>

      <div className="flex flex-wrap gap-2">
        {STATUS_FILTERS.map((s) => (
          <button
            key={s}
            onClick={() => { setStatusFilter(s); setPage(1) }}
            className={cn(
              "rounded-control px-3 py-2 text-sm font-medium transition-colors duration-180",
              statusFilter === s
                ? "bg-primary text-primary-foreground"
                : "bg-surface-muted text-muted-foreground hover:bg-surface hover:text-foreground"
            )}
          >
            {getFilterLabel(s)}
          </button>
        ))}
      </div>

      {loading ? (
        <div className="flex items-center justify-center py-24">
          <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
        </div>
      ) : orders.length > 0 ? (
        <div className="flex flex-col gap-3">
          {orders.map((order) => (
            <article
              key={order.id}
              className="surface p-3 transition-[transform,border-color,box-shadow] duration-180 hover:-translate-y-0.5 hover:border-border-strong hover:shadow-sm sm:p-4"
            >
              <header className="flex min-w-0 flex-wrap items-start justify-between gap-x-3 gap-y-2">
                <span className="min-w-0 flex-1 break-all font-mono text-xs font-medium text-foreground">
                  {t("order.orderNo")} {order.order_no}
                </span>
                <div className="flex max-w-full shrink-0 flex-wrap items-center justify-end gap-x-3 gap-y-1">
                  <span className="text-xs text-muted-foreground">{formatDateTime(order.created_at, locale)}</span>
                  <OrderStatusBadge status={order.status} items={order.items} />
                </div>
              </header>

              <div className="mt-3 min-w-0 space-y-3">
                {order.items.map((item) => (
                  <div key={item.id} className="flex min-w-0 items-center gap-3">
                    <OrderCover coverUrl={item.product_cover_url} title={item.product_title} />
                    <div className="min-w-0 flex-1">
                      <p className="break-words text-sm font-semibold text-foreground">{item.product_title}</p>
                      {item.spec_name && <p className="mt-0.5 break-words text-xs text-muted-foreground">{item.spec_name}</p>}
                      <div className="mt-1 flex flex-wrap gap-x-3 gap-y-1 text-xs text-muted-foreground">
                        <span>{t("order.quantity")}: {item.quantity}</span>
                        <span>{t("order.unitPrice")}: {formatYuan(item.unit_price)}</span>
                      </div>
                    </div>
                  </div>
                ))}
              </div>

              <footer className="mt-3 flex min-w-0 flex-col gap-3 border-t border-border-subtle pt-3 sm:flex-row sm:items-center sm:justify-between">
                <span className="shrink-0 text-sm text-muted-foreground">
                  {t("order.paidAmount")}: <span className="font-semibold text-foreground">{formatYuan(order.actual_amount)}</span>
                </span>
                <div className="flex w-full min-w-0 flex-wrap gap-2 sm:w-auto sm:justify-end">
                  {(order.status === "PAID" || order.status === "DELIVERED") && (
                    <button
                      type="button"
                      disabled
                      title={t("order.afterSalesUnavailable")}
                      aria-label={`${t("order.applyAfterSales")}: ${t("order.afterSalesUnavailable")}`}
                      className="inline-flex min-h-9 min-w-0 flex-1 flex-col items-center justify-center rounded-control border border-border-subtle bg-surface-muted px-3 py-1.5 text-center text-xs font-medium leading-4 text-muted-foreground opacity-70 sm:flex-none"
                    >
                      <span>{t("order.applyAfterSales")}</span>
                      <span className="text-[10px] font-normal">{t("order.afterSalesUnavailable")}</span>
                    </button>
                  )}
                  <Link
                    href={buildOrderPageHref(order.id)}
                    className="inline-flex min-h-9 min-w-0 flex-1 items-center justify-center rounded-control border border-border-strong bg-surface px-3 py-2 text-center text-xs font-medium text-foreground transition-colors hover:bg-surface-muted sm:flex-none"
                  >
                    {t("order.orderDetail")}
                  </Link>
                  {order.status === "PENDING" && (
                    <Link
                      href={buildOrderPageHref(order.id)}
                      className="inline-flex min-h-9 min-w-0 flex-1 items-center justify-center rounded-control bg-primary px-3 py-2 text-center text-xs font-medium text-primary-foreground shadow-sm transition-[background-color,transform] duration-180 hover:bg-primary/90 active:scale-[.98] sm:flex-none"
                    >
                      {t("payment.continuePay")}
                    </Link>
                  )}
                </div>
              </footer>
            </article>
          ))}

          {total > pageSize && (
            <div className="flex justify-center gap-2 pt-4">
              <button
                onClick={() => setPage(p => Math.max(1, p - 1))}
                disabled={page <= 1}
                className="rounded-md border border-border px-3 py-1.5 text-sm text-foreground disabled:opacity-50"
              >
                {t("common.prev")}
              </button>
              <span className="flex items-center px-3 text-sm text-muted-foreground">
                {page} / {Math.ceil(total / pageSize)}
              </span>
              <button
                onClick={() => setPage(p => Math.min(Math.ceil(total / pageSize), p + 1))}
                disabled={page >= Math.ceil(total / pageSize)}
                className="rounded-md border border-border px-3 py-1.5 text-sm text-foreground disabled:opacity-50"
              >
                {t("common.next")}
              </button>
            </div>
          )}
        </div>
      ) : (
        <div className="flex flex-col items-center py-16">
          <Package className="mb-3 h-10 w-10 text-muted-foreground/30" />
          <p className="text-sm text-muted-foreground">{t("common.noData")}</p>
        </div>
      )}
    </div>
  )
}

function OrderCover({ coverUrl, title }: { coverUrl: string | null; title: string }) {
  const [imageFailed, setImageFailed] = useState(false)
  const normalizedCoverUrl = coverUrl?.trim()

  return (
    <div className="relative flex size-14 shrink-0 items-center justify-center overflow-hidden rounded-control border border-border-subtle bg-surface-muted" aria-label={title}>
      {normalizedCoverUrl && !imageFailed ? (
        <Image
          src={normalizedCoverUrl}
          alt=""
          fill
          unoptimized
          sizes="56px"
          className="object-contain"
          onError={() => setImageFailed(true)}
        />
      ) : (
        <Package className="size-6 text-muted-foreground/35" aria-hidden="true" />
      )}
    </div>
  )
}
