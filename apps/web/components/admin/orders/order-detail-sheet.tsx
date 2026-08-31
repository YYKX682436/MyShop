"use client"

import { CheckCircle, Loader2 } from "lucide-react"
import { toast } from "sonner"

import { DataTableShell } from "@/components/admin/shared"
import { CardContentParts } from "@/components/shared/card-content-parts"
import { OrderStatusBadge } from "@/components/shared/order-status-badge"
import { getPaymentLabel, PaymentIcon } from "@/components/shared/payment-icon"
import { Button } from "@/components/ui/button"
import { Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle } from "@/components/ui/sheet"
import { useLocale } from "@/lib/context"
import { formatDateTime, formatYuan, stripInvisible } from "@/lib/utils"
import type { TranslationKey } from "@/lib/i18n"
import type { AdminOrderItem, AdminOrderItemDetail, OrderCardKey, OrderItemDetail } from "@/types"

interface OrderDetailSheetProps {
  order: AdminOrderItem | null
  onOpenChange: (open: boolean) => void
  detailLoading?: boolean
  detailError?: boolean
  onRetryDetail?: () => void
  detailCardKeys: OrderCardKey[]
  detailCardKeysLoading?: boolean
  detailCardKeysError?: boolean
  onRetryCardKeys?: () => void
  completingManualItem?: string | null
  onCompleteManualItem?: (orderId: string, orderItemId: string) => void
  onMarkPaid?: (orderId: string) => void
}

function fulfillmentLabel(item: OrderItemDetail, t: (key: TranslationKey) => string) {
  if (item.fulfillment_status === "WAITING_MANUAL") return t("admin.fulfillmentWaitingManual")
  if (item.fulfillment_status === "DELIVERED") return t("admin.fulfillmentDelivered")
  if (item.fulfillment_status === "CANCELLED") return t("admin.fulfillmentCancelled")
  return t("admin.fulfillmentPending")
}

function pricingSourceLabel(source: AdminOrderItemDetail["pricing_source"], t: (key: TranslationKey) => string) {
  if (source === "LEVEL_OVERRIDE") return t("admin.pricingSourceOverride")
  if (source === "LEVEL_MARKUP") return t("admin.pricingSourceMarkup")
  return t("admin.pricingSourceRetail")
}

export function OrderDetailSheet({
  order,
  onOpenChange,
  detailLoading = false,
  detailError = false,
  onRetryDetail,
  detailCardKeys,
  detailCardKeysLoading = false,
  detailCardKeysError = false,
  onRetryCardKeys,
  completingManualItem = null,
  onCompleteManualItem,
  onMarkPaid,
}: OrderDetailSheetProps) {
  const { t, locale } = useLocale()

  const copyToClipboard = (value: string) => {
    const cleanValue = stripInvisible(value)
    const fallback = () => {
      const textarea = document.createElement("textarea")
      textarea.value = cleanValue
      textarea.style.position = "fixed"
      textarea.style.opacity = "0"
      document.body.appendChild(textarea)
      textarea.select()
      document.execCommand("copy")
      document.body.removeChild(textarea)
      toast.success(t("order.copied"))
    }
    if (navigator.clipboard?.writeText) void navigator.clipboard.writeText(cleanValue).then(() => toast.success(t("order.copied")), fallback)
    else fallback()
  }

  return (
    <Sheet open={order !== null} onOpenChange={onOpenChange}>
      <SheetContent className="flex w-full flex-col p-0 sm:max-w-2xl">
        {order && (
          <>
            <SheetHeader className="border-b border-border px-6 py-5 pr-14">
              <SheetTitle>{t("admin.orderDetail")}</SheetTitle>
              <SheetDescription className="break-all font-mono">{order.id}</SheetDescription>
            </SheetHeader>

            <div className="min-h-0 flex-1 overflow-y-auto px-6 py-5">
              {detailLoading && <div className="mb-4 flex items-center gap-2 rounded-lg border border-border bg-muted/30 px-3 py-2 text-sm text-muted-foreground"><Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />{t("common.loading")}</div>}
              {detailError && <div className="mb-4 flex flex-wrap items-center justify-between gap-3 rounded-lg border border-destructive/30 bg-destructive/5 px-3 py-3 text-sm text-destructive"><span>{t("admin.loadError")}</span>{onRetryDetail && <Button type="button" variant="outline" size="sm" onClick={onRetryDetail}>{t("common.retry")}</Button>}</div>}
              <div className="flex flex-col gap-6">
                <section>
                  <h3 className="mb-3 text-sm font-semibold text-foreground">{t("admin.orderProducts")}</h3>
                  <div className="overflow-hidden rounded-lg border border-border">
                    {order.items.length === 0 && <p className="px-3 py-4 text-sm text-muted-foreground">{t("admin.systemNoData")}</p>}
                    {order.items.map((item, index) => {
                      const manual = item.delivery_type === "MANUAL"
                      const canComplete = manual && order.status === "PAID" && item.fulfillment_status !== "DELIVERED" && item.fulfillment_status !== "CANCELLED" && Boolean(onCompleteManualItem)
                      return (
                        <div key={item.id} className={`flex flex-wrap items-start justify-between gap-4 px-3 py-4 ${index > 0 ? "border-t border-border" : ""}`}>
                          <div className="min-w-0 flex-1">
                            <p className="text-sm font-medium text-foreground">{item.product_title}{item.spec_name && <span className="text-muted-foreground"> · {item.spec_name}</span>} <span className="text-muted-foreground">×{item.quantity}</span></p>
                            {item.customer_inputs.length > 0 && <div className="mt-2 space-y-1 text-xs text-muted-foreground">{item.customer_inputs.map((input) => <p key={input.id}>{input.label}: {input.value}</p>)}</div>}
                            <div className="mt-2 flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                              <span>{manual ? t("admin.manualDeliveryLabel") : t("admin.autoDeliveryLabel")}</span>
                              <span aria-hidden="true">·</span>
                              <span>{fulfillmentLabel(item, t)}</span>
                            </div>
                            <div className="mt-3 grid grid-cols-2 gap-x-4 gap-y-2 text-xs text-muted-foreground sm:grid-cols-3">
                              <div><span className="block">{t("admin.unitPrice")}</span><span className="font-medium text-foreground">{formatYuan(item.unit_price)}</span></div>
                              <div><span className="block">{t("admin.retailPriceSnapshot")}</span><span className="font-medium text-foreground">{formatYuan(item.retail_price_snapshot)}</span></div>
                              <div><span className="block">{t("admin.costPriceSnapshot")}</span><span className="font-medium text-foreground">{formatYuan(item.cost_price_snapshot)}</span></div>
                              <div><span className="block">{t("admin.pricingSource")}</span><span className="font-medium text-foreground">{pricingSourceLabel(item.pricing_source, t)}</span></div>
                              <div><span className="block">{t("admin.marginAmount")}</span><span className="font-medium text-foreground">{item.gross_profit === null ? t("admin.systemNoData") : formatYuan(item.gross_profit)}</span></div>
                              <div><span className="block">{t("admin.marginRate")}</span><span className="font-medium text-foreground">{item.gross_margin === null ? t("admin.systemNoData") : `${item.gross_margin.toFixed(2)}%`}</span></div>
                            </div>
                          </div>
                          <div className="flex items-center gap-3">
                            <span className="whitespace-nowrap text-sm font-medium text-foreground">{formatYuan(item.subtotal)}</span>
                            {canComplete && <Button type="button" size="sm" disabled={completingManualItem === item.id} onClick={() => onCompleteManualItem?.(order.id, item.id)}>
                              {completingManualItem === item.id && <Loader2 className="h-3.5 w-3.5 animate-spin" aria-hidden="true" />}
                              {completingManualItem === item.id ? t("admin.processing") : t("admin.completeDelivery")}
                            </Button>}
                          </div>
                        </div>
                      )
                    })}
                  </div>
                </section>

                <section className="grid grid-cols-2 gap-x-5 gap-y-4 rounded-lg border border-border p-4">
                  <div><p className="text-xs text-muted-foreground">{t("admin.paymentAmount")}</p><p className="mt-1 text-sm font-medium text-foreground">{formatYuan(order.actual_amount)}</p></div>
                  <div><p className="text-xs text-muted-foreground">{t("admin.paymentMethod")}</p><p className="mt-1 inline-flex items-center gap-1.5 text-sm text-foreground"><PaymentIcon method={order.payment_method} className="h-4 w-4" />{getPaymentLabel(order.payment_method, t)}</p></div>
                  <div><p className="text-xs text-muted-foreground">{t("admin.contactEmailLabel")}</p><p className="mt-1 break-all text-sm text-foreground">{order.email}</p></div>
                  <div><p className="text-xs text-muted-foreground">{t("admin.statusLabel")}</p><div className="mt-1"><OrderStatusBadge status={order.status} /></div></div>
                  <div><p className="text-xs text-muted-foreground">{t("admin.createdAt")}</p><p className="mt-1 text-sm text-foreground">{formatDateTime(order.created_at, locale)}</p></div>
                  <div><p className="text-xs text-muted-foreground">{t("admin.paidAt")}</p><p className="mt-1 text-sm text-foreground">{order.paid_at ? formatDateTime(order.paid_at, locale) : t("admin.systemNoData")}</p></div>
                  <div><p className="text-xs text-muted-foreground">{t("admin.userLevelSnapshot")}</p><p className="mt-1 break-all text-sm text-foreground">{order.user_level_name_snapshot || t("admin.systemNoData")}</p></div>
                </section>

                {order.status === "DELIVERED" && (
                  <section>
                    <h3 className="mb-3 text-sm font-semibold text-foreground">{t("admin.deliveredCardContent")}</h3>
                    {detailCardKeysLoading && <div className="flex items-center gap-2 py-4 text-sm text-muted-foreground"><Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />{t("common.loading")}</div>}
                    {!detailCardKeysLoading && detailCardKeysError && <div className="flex flex-col items-start gap-2 rounded-lg border border-destructive/30 bg-destructive/5 p-4 text-sm text-destructive"><span>{t("admin.loadError")}</span>{onRetryCardKeys && <Button type="button" variant="outline" size="sm" onClick={onRetryCardKeys}>{t("common.retry")}</Button>}</div>}
                    {!detailCardKeysLoading && !detailCardKeysError && detailCardKeys.length === 0 && <p className="text-sm text-muted-foreground">{t("admin.systemNoData")}</p>}
                    {!detailCardKeysLoading && !detailCardKeysError && detailCardKeys.length > 0 && (() => {
                      const groups = new Map<string, { title: string; cards: OrderCardKey[] }>()
                      detailCardKeys.forEach((card) => {
                        const groupKey = card.order_item_id || `${card.product_title}\u0000${card.spec_name || ""}`
                        const title = [card.product_title, card.spec_name].filter(Boolean).join(" · ") || t("admin.deliveredCardContent")
                        const group = groups.get(groupKey)
                        if (group) group.cards.push(card)
                        else groups.set(groupKey, { title, cards: [card] })
                      })
                      return <div className="flex flex-col gap-3">{[...groups.entries()].map(([groupKey, group]) => <div key={groupKey} className="flex flex-col gap-2"><p className="text-xs font-medium text-muted-foreground">{groups.size > 1 ? group.title : t("admin.deliveredCardContent")}</p>{group.cards.map((card) => <DataTableShell key={card.card_key_id} className="rounded-lg"><div className="p-3"><CardContentParts parts={card.content_parts} onCopy={copyToClipboard} /></div></DataTableShell>)}</div>)}</div>
                    })()}
                  </section>
                )}
              </div>
            </div>

            <SheetFooter className="border-t border-border px-6 py-4">
              {order.status === "PENDING" && onMarkPaid && <Button type="button" onClick={() => onMarkPaid(order.id)}><CheckCircle className="h-4 w-4" aria-hidden="true" />{t("admin.markPaid")}</Button>}
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>{t("common.close")}</Button>
            </SheetFooter>
          </>
        )}
      </SheetContent>
    </Sheet>
  )
}
