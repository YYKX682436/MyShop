"use client"

import { Copy } from "lucide-react"
import { toast } from "sonner"

import { OrderStatusBadge } from "@/components/shared/order-status-badge"
import { useLocale } from "@/lib/context"
import { formatAdminOrderCustomer, formatAdminOrderNumber, formatAdminOrderProductMeta } from "@/lib/admin-order-display"
import { formatDateTime, formatYuan } from "@/lib/utils"
import type { AdminOrderItem } from "@/types"

export function RecentOrders({ orders }: { orders: AdminOrderItem[] }) {
  const { locale, t } = useLocale()

  const copyToClipboard = (text: string) => {
    const fallbackCopy = () => {
      const textarea = document.createElement("textarea")
      textarea.value = text
      textarea.style.position = "fixed"
      textarea.style.opacity = "0"
      document.body.appendChild(textarea)
      textarea.select()
      document.execCommand("copy")
      document.body.removeChild(textarea)
      toast.success(t("order.copied"))
    }
    if (navigator.clipboard?.writeText) {
      navigator.clipboard.writeText(text).then(() => toast.success(t("order.copied")), fallbackCopy)
    } else {
      fallbackCopy()
    }
  }

  return (
    <section className="min-w-0 rounded-surface border border-border-subtle bg-surface p-4 shadow-sm sm:p-5">
      <h2 className="mb-4 font-semibold text-foreground">{t("admin.recentOrders")}</h2>
      {orders.length === 0 ? (
        <p className="py-8 text-center text-sm text-muted-foreground">{t("admin.noOrderData")}</p>
      ) : (
        <>
          <div className="hidden overflow-x-auto md:block">
            <table className="w-full min-w-[700px] table-fixed text-sm">
              <colgroup>
                <col className="w-[18%]" />
                <col className="w-[24%]" />
                <col className="w-[18%]" />
                <col className="w-[12%]" />
                <col className="w-[12%]" />
                <col className="w-[16%]" />
              </colgroup>
              <thead>
                <tr className="border-b border-border-subtle text-left">
                  <th className="pb-3 font-medium text-muted-foreground">{t("admin.orderNo")}</th>
                  <th className="pb-3 font-medium text-muted-foreground">{t("admin.product")}</th>
                  <th className="pb-3 font-medium text-muted-foreground">{t("admin.user")}</th>
                  <th className="pb-3 font-medium text-muted-foreground">{t("admin.amount")}</th>
                  <th className="pb-3 font-medium text-muted-foreground">{t("admin.statusLabel")}</th>
                  <th className="pb-3 font-medium text-muted-foreground">{t("admin.time")}</th>
                </tr>
              </thead>
              <tbody>
                {orders.map((order) => (
                  <tr key={order.id} className="border-b border-border-subtle last:border-0">
                    <td className="py-3 font-mono text-sm text-foreground">
                      <CopyOrderButton orderNo={order.order_no} onCopy={copyToClipboard} />
                    </td>
                    <td className="py-3 text-sm text-foreground">
                      <ProductSummary order={order} />
                    </td>
                    <td className="truncate py-3 text-foreground" title={formatAdminOrderCustomer(order, t("admin.guest")).primary}>
                      <CustomerIdentity order={order} guestLabel={t("admin.guest")} />
                    </td>
                    <td className="py-3 font-medium text-foreground">{formatYuan(order.actual_amount)}</td>
                    <td className="py-3"><OrderStatusBadge status={order.status} /></td>
                    <td className="py-3 text-muted-foreground">{formatDateTime(order.created_at, locale)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div className="space-y-2 md:hidden">
            {orders.map((order) => (
              <article key={order.id} className="min-w-0 rounded-control border border-border-subtle bg-surface-muted/45 p-3">
                <div className="flex min-w-0 items-start justify-between gap-3">
                  <CopyOrderButton orderNo={order.order_no} onCopy={copyToClipboard} />
                  <OrderStatusBadge status={order.status} />
                </div>
                <div className="mt-3 grid grid-cols-2 gap-x-4 gap-y-2 text-sm">
                  <div className="min-w-0 col-span-2">
                    <p className="text-xs text-muted-foreground">{t("admin.product")}</p>
                    <ProductSummary order={order} />
                  </div>
                  <div className="min-w-0">
                    <p className="text-xs text-muted-foreground">{t("admin.user")}</p>
                    <p className="truncate text-foreground" title={formatAdminOrderCustomer(order, t("admin.guest")).primary}><CustomerIdentity order={order} guestLabel={t("admin.guest")} /></p>
                  </div>
                  <div>
                    <p className="text-xs text-muted-foreground">{t("admin.amount")}</p>
                    <p className="font-medium text-foreground">{formatYuan(order.actual_amount)}</p>
                  </div>
                  <time className="col-span-2 text-xs text-muted-foreground" dateTime={order.created_at}>
                    {formatDateTime(order.created_at, locale)}
                  </time>
                </div>
              </article>
            ))}
          </div>
        </>
      )}
    </section>
  )
}

function CopyOrderButton({ orderNo, onCopy }: { orderNo: number; onCopy: (value: string) => void }) {
  const { t } = useLocale()
  const value = formatAdminOrderNumber(orderNo)
  return (
    <button
      type="button"
      className="group inline-flex max-w-full items-center gap-1 rounded px-0.5 text-left font-mono text-sm text-foreground underline-offset-4 hover:text-primary hover:underline"
      title={value}
      aria-label={`${t("admin.copyOrder")}: ${value}`}
      onClick={() => onCopy(value)}
    >
      <span className="truncate">{value}</span>
      <Copy className="h-3.5 w-3.5 shrink-0 text-muted-foreground group-hover:text-primary" aria-hidden="true" />
    </button>
  )
}

function ProductSummary({ order }: { order: AdminOrderItem }) {
  const { t } = useLocale()
  const firstItem = order.items?.[0]
  if (!firstItem) return <span className="text-muted-foreground">{t("admin.systemNoData")}</span>
  const more = (order.items?.length ?? 0) > 1 ? ` ${t("admin.moreItems").replace("{count}", String(order.items.length))}` : ""
  return (
    <div className="min-w-0" title={`${firstItem.product_title}${firstItem.spec_name ? ` - ${firstItem.spec_name}` : ""}${more}`}>
      <span className="block truncate">{firstItem.product_title}</span>
      <span className="block truncate text-xs text-muted-foreground">{formatAdminOrderProductMeta(firstItem)}</span>
      {more && <span className="text-xs text-muted-foreground">{more}</span>}
    </div>
  )
}

function CustomerIdentity({ order, guestLabel }: { order: AdminOrderItem; guestLabel: string }) {
  const identity = formatAdminOrderCustomer(order, guestLabel)
  return (
    <span className="inline-flex min-w-0 flex-col align-top">
      <span className="truncate">{identity.primary}</span>
      {identity.secondary && <span className="truncate text-xs text-muted-foreground">{identity.secondary}</span>}
    </span>
  )
}
