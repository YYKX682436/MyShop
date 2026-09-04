"use client"

import { CheckCircle, Copy, Loader2 } from "lucide-react"
import { toast } from "sonner"

import { CardContentParts } from "@/components/shared/card-content-parts"
import { OrderStatusBadge } from "@/components/shared/order-status-badge"
import { getPaymentLabel, PaymentIcon } from "@/components/shared/payment-icon"
import { Button } from "@/components/ui/button"
import { Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle } from "@/components/ui/sheet"
import { useLocale } from "@/lib/context"
import { formatAdminOrderProductMeta } from "@/lib/admin-order-display"
import { formatCardContents } from "@/lib/card-display"
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
  embedded?: boolean
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

function CopyField({ label, value, onCopy, empty }: { label: string; value: unknown; onCopy: (value: string) => void; empty: string }) {
  const hasValue = value !== null && value !== undefined && String(value) !== ""
  const display = hasValue ? String(value) : empty
  return (
    <div className="min-w-0 rounded-control border border-border-subtle bg-surface-muted/45 px-3 py-2">
      <p className="text-xs text-muted-foreground">{label}</p>
      <div className="mt-1 flex min-w-0 items-start justify-between gap-2">
        <span className="min-w-0 break-all text-sm text-foreground">{display}</span>
        <button type="button" disabled={!hasValue} onClick={() => onCopy(String(value))} className="shrink-0 rounded p-0.5 text-muted-foreground hover:bg-surface hover:text-foreground disabled:opacity-30" title={hasValue ? "复制" : empty} aria-label={`${label}复制`}>
          <Copy className="h-3.5 w-3.5" aria-hidden="true" />
        </button>
      </div>
    </div>
  )
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
  embedded = false,
}: OrderDetailSheetProps) {
  const { t, locale } = useLocale()
  const empty = t("admin.systemNoData")

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

  const cardGroups = new Map<string, { title: string; cards: OrderCardKey[] }>()
  detailCardKeys.forEach((card) => {
    const groupKey = card.order_item_id || `${card.product_title}\u0000${card.spec_name || ""}`
    const title = [card.product_title, card.spec_name].filter(Boolean).join(" · ") || t("admin.deliveredCardContent")
    const group = cardGroups.get(groupKey)
    if (group) group.cards.push(card)
    else cardGroups.set(groupKey, { title, cards: [card] })
  })

  const cardSection = order && (
    <section className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h3 className="text-sm font-semibold text-foreground">B. 卡密与交付</h3>
          <p className="mt-1 text-xs text-muted-foreground">按订单项分组；字段在同一卡券内保持原顺序。</p>
        </div>
        {!detailCardKeysLoading && !detailCardKeysError && detailCardKeys.length > 0 && <Button type="button" variant="outline" size="sm" onClick={() => copyToClipboard(formatCardContents(detailCardKeys))}><Copy className="mr-1.5 h-3.5 w-3.5" />复制全部</Button>}
      </div>
      {detailCardKeysLoading && <div className="flex items-center gap-2 rounded-card border border-border-subtle bg-surface-muted px-3 py-4 text-sm text-muted-foreground"><Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />正在加载卡密交付内容…</div>}
      {!detailCardKeysLoading && detailCardKeysError && <div className="flex flex-wrap items-center justify-between gap-3 rounded-card border border-destructive/30 bg-destructive/5 p-4 text-sm text-destructive"><span>卡密内容加载失败，未使用本地占位数据。</span>{onRetryCardKeys && <Button type="button" variant="outline" size="sm" onClick={onRetryCardKeys}>{t("common.retry")}</Button>}</div>}
      {!detailCardKeysLoading && !detailCardKeysError && detailCardKeys.length === 0 && <div className="rounded-card border border-border-subtle bg-surface-muted/45 p-4 text-sm text-muted-foreground">{order.status === "PAID" ? "订单已支付，卡密正在准备或等待库存补充。" : order.status === "DELIVERED" ? "订单已完成，但没有可显示的卡密内容。" : "当前订单没有可交付的卡密内容。"}</div>}
      {!detailCardKeysLoading && !detailCardKeysError && detailCardKeys.length > 0 && <div className="space-y-3">{[...cardGroups.entries()].map(([groupKey, group]) => (
        <div key={groupKey} className="rounded-card border border-border-subtle bg-surface-muted/35 p-3">
          <div className="mb-2 flex flex-wrap items-center justify-between gap-2"><p className="text-xs font-medium text-muted-foreground">{group.title}</p><Button type="button" variant="ghost" size="sm" onClick={() => copyToClipboard(formatCardContents(group.cards))}>复制本组</Button></div>
          <div className="space-y-2">{group.cards.map((card, index) => (
            <div key={card.card_key_id} className="rounded-control border border-border-subtle bg-surface p-3">
              <p className="mb-2 text-xs text-muted-foreground">卡券 {index + 1}</p>
              <CardContentParts parts={card.content_parts} onCopy={copyToClipboard} />
            </div>
          ))}</div>
        </div>
      ))}</div>}
    </section>
  )

  const detailContent = order && (
    <>
      {!embedded && <SheetHeader className="border-b border-border-subtle px-5 py-5 pr-14 sm:px-6">
        <SheetTitle>{t("admin.orderDetail")}</SheetTitle>
        <SheetDescription className="font-mono">订单号：{order.order_no}</SheetDescription>
      </SheetHeader>}

      <div className="min-h-0 flex-1 overflow-y-auto px-5 py-5 sm:px-6">
        {detailLoading && <div className="mb-4 flex items-center gap-2 rounded-control border border-border-subtle bg-surface-muted px-3 py-2 text-sm text-muted-foreground"><Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />{t("common.loading")}</div>}
        {detailError && <div className="mb-4 flex flex-wrap items-center justify-between gap-3 rounded-card border border-destructive/30 bg-destructive/5 px-3 py-3 text-sm text-destructive"><span>{t("admin.loadError")}</span>{onRetryDetail && <Button type="button" variant="outline" size="sm" onClick={onRetryDetail}>{t("common.retry")}</Button>}</div>}

        <div className="flex flex-col gap-7">
          <section className="space-y-3">
            <h3 className="text-sm font-semibold text-foreground">A. 订单摘要</h3>
            <div className="grid gap-3 sm:grid-cols-2">
              <CopyField label={t("admin.orderNo")} value={order.order_no} onCopy={copyToClipboard} empty={empty} />
              <div className="rounded-control border border-border-subtle bg-surface-muted/45 px-3 py-2"><p className="text-xs text-muted-foreground">{t("admin.statusLabel")}</p><div className="mt-1"><OrderStatusBadge status={order.status} /></div></div>
              <CopyField label="主要商品" value={order.items.length > 0 ? `${order.items[0].product_title} · ${formatAdminOrderProductMeta(order.items[0])}` : null} onCopy={copyToClipboard} empty={empty} />
              <CopyField label="客户" value={order.customer_type === "REGISTERED" ? `${order.username || empty}${order.user_no ? ` · UID ${order.user_no}` : ""}` : `访客 · ${order.email || empty}`} onCopy={copyToClipboard} empty={empty} />
              <CopyField label={t("admin.paymentAmount")} value={formatYuan(order.actual_amount)} onCopy={copyToClipboard} empty={empty} />
              <CopyField label={t("admin.createdAt")} value={formatDateTime(order.created_at, locale)} onCopy={copyToClipboard} empty={empty} />
              <div className="rounded-control border border-border-subtle bg-surface-muted/45 px-3 py-2"><p className="text-xs text-muted-foreground">风险</p><p className={`mt-1 text-sm font-medium ${order.is_risk_flagged ? "text-destructive" : "text-foreground"}`}>{order.is_risk_flagged ? t("admin.riskFlagged") : "正常"}</p></div>
            </div>
          </section>

          {cardSection}

          <section className="space-y-3">
            <h3 className="text-sm font-semibold text-foreground">C. 商品与客户填写信息</h3>
            <div className="space-y-3">
              {order.items.length === 0 && <p className="rounded-card border border-border-subtle bg-surface-muted p-4 text-sm text-muted-foreground">{empty}</p>}
              {order.items.map((item) => {
                const manual = item.delivery_type === "MANUAL"
                const canComplete = manual && order.status === "PAID" && item.fulfillment_status !== "DELIVERED" && item.fulfillment_status !== "CANCELLED" && Boolean(onCompleteManualItem)
                return <div key={item.id} className="rounded-card border border-border-subtle bg-surface p-4">
                  <div className="mb-3 flex flex-wrap items-start justify-between gap-3"><div><p className="font-medium text-foreground">{item.product_title}</p><p className="mt-1 text-xs text-muted-foreground">{item.spec_name || "无规格"} · ×{item.quantity}</p></div><div className="flex items-center gap-2"><OrderStatusBadge status={order.status} />{canComplete && <Button type="button" size="sm" disabled={completingManualItem === item.id} onClick={() => onCompleteManualItem?.(order.id, item.id)}>{completingManualItem === item.id && <Loader2 className="mr-1.5 h-3.5 w-3.5 animate-spin" aria-hidden="true" />}{completingManualItem === item.id ? t("admin.processing") : t("admin.completeDelivery")}</Button>}</div></div>
                  <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
                    <CopyField label="商品标题" value={item.product_title} onCopy={copyToClipboard} empty={empty} />
                    <CopyField label={t("admin.productNo")} value={item.product_no} onCopy={copyToClipboard} empty={empty} />
                    <CopyField label="商品 ID" value={item.product_id} onCopy={copyToClipboard} empty={empty} />
                    <CopyField label="规格名称" value={item.spec_name} onCopy={copyToClipboard} empty={empty} />
                    <CopyField label="规格 ID" value={item.spec_id} onCopy={copyToClipboard} empty={empty} />
                    <CopyField label="数量" value={item.quantity} onCopy={copyToClipboard} empty={empty} />
                    <CopyField label={t("admin.unitPrice")} value={formatYuan(item.unit_price)} onCopy={copyToClipboard} empty={empty} />
                    <CopyField label={t("admin.retailPriceSnapshot")} value={formatYuan(item.retail_price_snapshot)} onCopy={copyToClipboard} empty={empty} />
                    <CopyField label={t("admin.costPriceSnapshot")} value={formatYuan(item.cost_price_snapshot)} onCopy={copyToClipboard} empty={empty} />
                    <CopyField label="小计" value={formatYuan(item.subtotal)} onCopy={copyToClipboard} empty={empty} />
                    <CopyField label={t("admin.marginAmount")} value={item.gross_profit === null ? null : formatYuan(item.gross_profit)} onCopy={copyToClipboard} empty={empty} />
                    <CopyField label={t("admin.marginRate")} value={item.gross_margin === null ? null : `${item.gross_margin.toFixed(2)}%`} onCopy={copyToClipboard} empty={empty} />
                    <CopyField label="定价来源" value={pricingSourceLabel(item.pricing_source, t)} onCopy={copyToClipboard} empty={empty} />
                    <CopyField label="交付方式" value={manual ? t("admin.manualDeliveryLabel") : t("admin.autoDeliveryLabel")} onCopy={copyToClipboard} empty={empty} />
                    <CopyField label="库存模式" value={item.stock_mode} onCopy={copyToClipboard} empty={empty} />
                    <CopyField label="履约状态" value={fulfillmentLabel(item, t)} onCopy={copyToClipboard} empty={empty} />
                    <CopyField label="交付时间" value={item.delivered_at ? formatDateTime(item.delivered_at, locale) : null} onCopy={copyToClipboard} empty={empty} />
                  </div>
                  <div className="mt-3 rounded-control border border-border-subtle bg-surface-muted/35 p-3"><p className="mb-2 text-xs font-medium text-muted-foreground">客户填写信息</p>{item.customer_inputs.length === 0 ? <p className="text-sm text-muted-foreground">{empty}</p> : <div className="grid gap-2 sm:grid-cols-2">{item.customer_inputs.map((input) => <CopyField key={input.id} label={input.label} value={input.value} onCopy={copyToClipboard} empty={empty} />)}</div>}</div>
                </div>
              })}
            </div>
          </section>

          <section className="space-y-3">
            <h3 className="text-sm font-semibold text-foreground">D. 客户</h3>
            <div className="grid gap-2 sm:grid-cols-2">
              <CopyField label="客户类型" value={order.customer_type === "REGISTERED" ? "注册用户" : "访客"} onCopy={copyToClipboard} empty={empty} />
              <CopyField label="用户名" value={order.username} onCopy={copyToClipboard} empty={empty} />
              <CopyField label="UID" value={order.user_no} onCopy={copyToClipboard} empty={empty} />
              <CopyField label="用户 ID" value={order.user_id} onCopy={copyToClipboard} empty={empty} />
              <CopyField label="订单邮箱" value={order.order_email || order.email} onCopy={copyToClipboard} empty={empty} />
              <CopyField label="注册邮箱" value={order.registered_email} onCopy={copyToClipboard} empty={empty} />
              <CopyField label="手机号" value={order.phone} onCopy={copyToClipboard} empty={empty} />
              <CopyField label="下单时等级 ID" value={order.user_level_id_snapshot} onCopy={copyToClipboard} empty={empty} />
              <CopyField label={t("admin.userLevelSnapshot")} value={order.user_level_name_snapshot} onCopy={copyToClipboard} empty={empty} />
            </div>
          </section>

          <section className="space-y-3">
            <h3 className="text-sm font-semibold text-foreground">E. 财务</h3>
            <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
              <CopyField label="订单总额" value={formatYuan(order.total_amount)} onCopy={copyToClipboard} empty={empty} />
              <CopyField label="实际支付" value={formatYuan(order.actual_amount)} onCopy={copyToClipboard} empty={empty} />
              <CopyField label="成本合计" value={formatYuan(order.cost_total)} onCopy={copyToClipboard} empty={empty} />
              <CopyField label="毛利合计" value={formatYuan(order.gross_profit_total)} onCopy={copyToClipboard} empty={empty} />
              <CopyField label="毛利率合计" value={order.gross_margin_total === null ? null : `${order.gross_margin_total.toFixed(2)}%`} onCopy={copyToClipboard} empty={empty} />
            </div>
          </section>

          <section className="space-y-3">
            <h3 className="text-sm font-semibold text-foreground">F. 生命周期与支付</h3>
            <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
              <CopyField label={t("admin.statusLabel")} value={order.status} onCopy={copyToClipboard} empty={empty} />
              <CopyField label="订单类型" value={order.order_type} onCopy={copyToClipboard} empty={empty} />
              <CopyField label={t("admin.createdAt")} value={formatDateTime(order.created_at, locale)} onCopy={copyToClipboard} empty={empty} />
              <CopyField label="过期时间" value={order.expires_at ? formatDateTime(order.expires_at, locale) : null} onCopy={copyToClipboard} empty={empty} />
              <CopyField label={t("admin.paidAt")} value={order.paid_at ? formatDateTime(order.paid_at, locale) : null} onCopy={copyToClipboard} empty={empty} />
              <CopyField label="完成交付时间" value={order.delivered_at ? formatDateTime(order.delivered_at, locale) : null} onCopy={copyToClipboard} empty={empty} />
              <div className="rounded-control border border-border-subtle bg-surface-muted/45 px-3 py-2"><p className="text-xs text-muted-foreground">{t("admin.paymentMethod")}</p><p className="mt-1 inline-flex items-center gap-1.5 text-sm text-foreground"><PaymentIcon method={order.payment_method || ""} className="h-4 w-4" />{order.payment_method ? getPaymentLabel(order.payment_method, t) : empty}</p></div>
              <CopyField label="Epay 交易号" value={order.epay_trade_no} onCopy={copyToClipboard} empty={empty} />
            </div>
          </section>

          <section className="space-y-3 border-t border-border-subtle pt-5">
            <h3 className="text-sm font-semibold text-foreground">G. 内部与风险</h3>
            <p className="text-xs text-muted-foreground">内部订单 ID 仅用于管理员排障和复制，不是对外订单号。</p>
            <div className="grid gap-2 sm:grid-cols-2">
              <CopyField label="内部订单 ID" value={order.internal_order_id || order.id} onCopy={copyToClipboard} empty={empty} />
              <CopyField label="客户端 IP" value={order.client_ip} onCopy={copyToClipboard} empty={empty} />
              <CopyField label="风险标记" value={order.is_risk_flagged ? t("admin.riskFlagged") : "正常"} onCopy={copyToClipboard} empty={empty} />
            </div>
          </section>
        </div>
      </div>

      {!embedded && <SheetFooter className="border-t border-border-subtle bg-surface-muted/45 px-5 py-4 sm:px-6">
        {order.status === "PENDING" && onMarkPaid && <Button type="button" onClick={() => onMarkPaid(order.id)}><CheckCircle className="h-4 w-4" aria-hidden="true" />{t("admin.markPaid")}</Button>}
        <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>{t("common.close")}</Button>
      </SheetFooter>}
    </>
  )

  if (embedded) return <div className="flex min-h-full flex-col">{detailContent}</div>

  return (
    <Sheet open={order !== null} onOpenChange={onOpenChange}>
      <SheetContent className="flex w-full flex-col p-0 sm:max-w-3xl">
        {detailContent}
      </SheetContent>
    </Sheet>
  )
}
