"use client"

import { useEffect, useState } from "react"
import Image from "next/image"
import Link from "next/link"
import { Package } from "lucide-react"
import { useLocale } from "@/lib/context"
import { useRequireAuth } from "@/lib/hooks"
import { userApi, requestOrThrow } from "@/services/api"
import { OrderStatusBadge } from "@/components/shared/order-status-badge"
import { AfterSaleRequestDialog } from "@/components/shared/after-sale-request-dialog"
import type { OrderBrief, OrderStatus } from "@/types"
import type { TranslationKey } from "@/lib/i18n"
import { getAfterSaleWaitingLabelKey } from "@/lib/after-sale-waiting"
import { cn, formatDateTime, formatYuan } from "@/lib/utils"
import { buildOrderPageHref } from "@/lib/payment-entry"

const STATUS_FILTERS: (OrderStatus | "ALL")[] = ["ALL", "PENDING", "PAID", "DELIVERED", "AFTER_SALE", "REFUNDED", "EXPIRED"]

export default function MyOrdersPage() {
  const { t, locale } = useLocale()
  const user = useRequireAuth()
  const [statusFilter, setStatusFilter] = useState<OrderStatus | "ALL">("ALL")
  const [orders, setOrders] = useState<OrderBrief[]>([])
  const [loading, setLoading] = useState(true)
  const [page, setPage] = useState(1)
  const [total, setTotal] = useState(0)
  const [refreshNonce, setRefreshNonce] = useState(0)
  const [afterSaleOrder, setAfterSaleOrder] = useState<OrderBrief | null>(null)
  const [afterSaleOpen, setAfterSaleOpen] = useState(false)
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
  }, [user, statusFilter, page, refreshNonce])

  const statusKeys: Record<OrderStatus, TranslationKey> = {
    PENDING: "status.PENDING",
    PAID: "status.PAID_FILTER",
    DELIVERED: "status.DELIVERED",
    EXPIRED: "status.EXPIRED",
    REFUNDED: "status.REFUNDED",
    AFTER_SALE: "status.AFTER_SALE",
  }

  const getFilterLabel = (s: OrderStatus | "ALL") => {
    if (s === "ALL") return t("myOrders.all")
    return t(statusKeys[s])
  }

  if (!user) return null

  return (
    <div className="order-history-page space-y-6">
      <div>
        <p className="mb-2 text-xs font-semibold uppercase tracking-[0.16em] text-primary">MyShop</p>
        <h1 className="text-2xl font-semibold tracking-tight text-foreground">{t("myOrders.title")}</h1>
      </div>

      <nav className="order-filter-bar segmented-control w-full" role="group" aria-label={t("myOrders.title")}>
        {STATUS_FILTERS.map((s) => (
          <button
            key={s}
            type="button"
            onClick={() => { setStatusFilter(s); setPage(1) }}
            aria-pressed={statusFilter === s}
            data-active={statusFilter === s}
            className={cn(
              "segmented-control-item",
              statusFilter === s
                ? "bg-primary text-primary-foreground"
                : "bg-surface-muted text-muted-foreground hover:bg-surface hover:text-foreground"
            )}
          >
            {getFilterLabel(s)}
          </button>
        ))}
      </nav>

      {loading ? (
        <div className="flex items-center justify-center py-24">
          <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
        </div>
      ) : orders.length > 0 ? (
        <div className="order-list">
          {orders.map((order) => (
            <article
              key={order.id}
              className="order-list-item"
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
                      onClick={() => { setAfterSaleOrder(order); setAfterSaleOpen(true) }}
                      aria-label={t("order.applyAfterSales")}
                      className="inline-flex min-h-9 min-w-0 flex-1 items-center justify-center rounded-control border border-primary/30 bg-primary/5 px-3 py-2 text-center text-xs font-medium text-primary transition-colors hover:bg-primary/10 sm:flex-none"
                    >
                      {t("order.applyAfterSales")}
                    </button>
                  )}
                  {order.status === "AFTER_SALE" && (
                    <Link
                      href={`${buildOrderPageHref(order.id)}#after-sale`}
                      className="inline-flex min-h-9 min-w-0 flex-1 items-center justify-center gap-1.5 rounded-control border border-orange-300/50 bg-orange-50 px-3 py-2 text-center text-xs font-medium text-orange-800 dark:border-orange-500/30 dark:bg-orange-950/20 dark:text-orange-200 sm:flex-none"
                      aria-label={t("status.AFTER_SALE")}
                    >
                      {order.after_sale?.waiting_for === "CUSTOMER" && <span className="size-1.5 rounded-full bg-destructive" aria-label={t(getAfterSaleWaitingLabelKey("customer", order.after_sale.waiting_for))} />}
                      {t("status.AFTER_SALE")}
                    </Link>
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
      {afterSaleOrder && (
        <AfterSaleRequestDialog
          open={afterSaleOpen}
          orderId={afterSaleOrder.id}
          orderNo={afterSaleOrder.order_no}
          productSummary={afterSaleOrder.items.map(item => `${item.product_title}${item.spec_name ? ` · ${item.spec_name}` : ""}`).join("、")}
          onOpenChange={open => { setAfterSaleOpen(open); if (!open) setAfterSaleOrder(null) }}
          onSubmitted={() => setRefreshNonce(value => value + 1)}
        />
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
