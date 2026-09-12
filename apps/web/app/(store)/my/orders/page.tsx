"use client"

import { useState, useEffect } from "react"
import Link from "next/link"
import { Package, ExternalLink } from "lucide-react"
import { PaymentIcon, getPaymentLabel } from "@/components/shared/payment-icon"
import { useLocale } from "@/lib/context"
import { useRequireAuth } from "@/lib/hooks"
import { userApi, requestOrThrow } from "@/services/api"
import { OrderStatusBadge } from "@/components/shared/order-status-badge"
import type { OrderBrief, OrderStatus } from "@/types"
import type { TranslationKey } from "@/lib/i18n"
import { cn, formatYuan } from "@/lib/utils"
import { buildOrderPageHref } from "@/lib/payment-entry"
import { isManualFulfillmentItem } from "@/lib/order-status"

const STATUS_FILTERS: (OrderStatus | "ALL")[] = ["ALL", "PENDING", "PAID", "DELIVERED", "REFUNDED", "EXPIRED"]

export default function MyOrdersPage() {
  const { t } = useLocale()
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

      {/* Status Filter */}
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

      {/* Orders List */}
      {loading ? (
        <div className="flex items-center justify-center py-24">
          <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
        </div>
      ) : orders.length > 0 ? (
        <div className="flex flex-col gap-3">
          {orders.map((order) => (
            <div
              key={order.id}
              className="surface p-4 transition-[transform,border-color,box-shadow] duration-180 hover:-translate-y-0.5 hover:border-border-strong hover:shadow-sm"
            >
              {/* Order Header */}
              <div className="mb-3 flex items-center justify-between">
                <div className="flex items-center gap-3">
                  <OrderStatusBadge status={order.status} items={order.items} />
                  <span className="font-mono text-xs font-medium text-foreground">{t("order.orderNo")} {order.order_no}</span>
                </div>
                <span className="text-xs text-muted-foreground">
                  {new Date(order.created_at).toLocaleString()}
                </span>
              </div>

              {/* Order Summary */}
              <div className="mb-3 flex items-center gap-3 text-sm text-muted-foreground">
                <span className="inline-flex items-center gap-1">
                  <PaymentIcon method={order.order_type === "ADMIN_PICKUP" ? "ADMIN_PICKUP" : order.payment_method || ""} className="h-4 w-4" />
                  {order.order_type === "ADMIN_PICKUP" ? getPaymentLabel("ADMIN_PICKUP", t) : order.payment_method ? <>{getPaymentLabel(order.payment_method, t)}{order.payment_method === "ONLINE" && order.payment_channel_code ? `（${order.payment_channel_code}）` : null}</> : ""}
                </span>
              </div>

              {order.items?.some(item => item.customer_inputs.length > 0) && (
                <div className="mb-3 rounded-card border border-border-subtle bg-surface-muted p-3 text-xs">
                  <p className="mb-2 font-medium text-muted-foreground">{t("order.customerInputs")}</p>
                  <div className="space-y-2">
                    {order.items.filter(item => item.customer_inputs.length > 0).map(item => (
                      <div key={item.id}>
                        <p className="font-medium text-foreground">{item.product_title}</p>
                        <div className="mt-1 grid gap-1 sm:grid-cols-2">
                          {item.customer_inputs.map(input => <span key={input.id} className="text-muted-foreground">{input.label}：<span className="text-foreground">{input.value}</span></span>)}
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {order.status === "REFUNDED" && (
                <div className="mb-3 rounded-card border border-violet-300/50 bg-violet-50/70 p-3 text-sm dark:border-violet-500/30 dark:bg-violet-950/20">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <p className="font-medium text-foreground">{t("order.refunded")}</p>
                    {order.refunded_at && <span className="text-xs text-muted-foreground">{new Date(order.refunded_at).toLocaleString()}</span>}
                  </div>
                  <p className="mt-1 text-xs text-muted-foreground">{t("order.refundToBalance")}</p>
                  <p className="mt-1 text-xs text-muted-foreground">{t("order.refundAmount")}: <span className="font-medium text-foreground">{formatYuan(order.actual_amount)}</span></p>
                  {(order.reclaimed_card_key_count ?? 0) > 0 && <p className="mt-1 text-xs font-medium text-emerald-700 dark:text-emerald-300">{t("order.reclaimedCardKeys").replace("{count}", String(order.reclaimed_card_key_count))}</p>}
                </div>
              )}

              {/* Footer */}
              <div className="flex items-center justify-between border-t border-border-subtle pt-3">
                <span className="text-sm text-muted-foreground">
                  {t("order.amount")}:{" "}
                  <span className="font-semibold text-foreground">
                    {formatYuan(order.actual_amount)}
                  </span>
                </span>
                <div className="flex gap-2">
                  {order.status === "PENDING" && order.order_type === "NORMAL" && order.payment_method === "ONLINE" && order.payment_channel_code && (
                    <Link
                      href={buildOrderPageHref(order.id)}
                      className="inline-flex h-9 items-center gap-1 rounded-control bg-primary px-3 text-xs font-medium text-primary-foreground shadow-sm transition-[background-color,transform] duration-180 hover:bg-primary/90 active:scale-[.98]"
                    >
                      {t("payment.continuePay")}
                    </Link>
                  )}
                  {(order.status === "PAID" || order.status === "DELIVERED" || order.status === "REFUNDED") && (
                    <Link
                      href={buildOrderPageHref(order.id)}
                      className="inline-flex h-9 items-center gap-1 rounded-control border border-border-strong bg-surface px-3 text-xs font-medium text-foreground transition-colors hover:bg-surface-muted"
                    >
                      <ExternalLink className="h-3 w-3" />
                      {order.status === "REFUNDED"
                        ? t("order.refunded")
                        : order.items?.some(item => !isManualFulfillmentItem(item) && item.stock_mode === "CARD_KEY")
                        ? t("order.cardKeys")
                        : t("order.viewDelivery")}
                    </Link>
                  )}
                </div>
              </div>
            </div>
          ))}

          {/* Pagination */}
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
