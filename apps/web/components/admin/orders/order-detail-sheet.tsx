"use client"

import { Fragment, type ReactNode } from "react"
import { Copy, Loader2 } from "lucide-react"
import { toast } from "sonner"

import { OrderStatusBadge } from "@/components/shared/order-status-badge"
import { getPaymentLabel, PaymentIcon } from "@/components/shared/payment-icon"
import { Button } from "@/components/ui/button"
import { useLocale } from "@/lib/context"
import { formatAdminOrderCustomer } from "@/lib/admin-order-display"
import { formatCardContent, formatCardContents } from "@/lib/card-display"
import { formatDateTime, formatYuan, stripInvisible } from "@/lib/utils"
import type { TranslationKey } from "@/lib/i18n"
import type { AdminOrderItem, AdminOrderItemDetail, OrderCardKey, OrderItemDetail } from "@/types"

interface OrderDetailSheetProps {
  order: AdminOrderItem | null
  detailLoading?: boolean
  detailError?: boolean
  onRetryDetail?: () => void
  detailCardKeys: OrderCardKey[]
  detailCardKeysLoading?: boolean
  detailCardKeysError?: boolean
  onRetryCardKeys?: () => void
  completingManualItem?: string | null
  onCompleteManualItem?: (orderId: string, orderItemId: string) => void
}

type Translator = (key: TranslationKey) => string

function fulfillmentLabel(item: OrderItemDetail, t: Translator) {
  if (item.fulfillment_status === "WAITING_MANUAL") return t("admin.fulfillmentWaitingManual")
  if (item.fulfillment_status === "DELIVERED") return t("admin.fulfillmentDelivered")
  if (item.fulfillment_status === "CANCELLED") return t("admin.fulfillmentCancelled")
  return t("admin.fulfillmentPending")
}

function pricingSourceLabel(source: AdminOrderItemDetail["pricing_source"], t: Translator) {
  if (source === "LEVEL_OVERRIDE") return t("admin.pricingSourceOverride")
  if (source === "LEVEL_MARKUP") return t("admin.pricingSourceMarkup")
  return t("admin.pricingSourceRetail")
}

function stockModeLabel(mode: OrderItemDetail["stock_mode"], t: Translator) {
  if (mode === "CARD_KEY") return t("admin.cardKeyStock")
  if (mode === "MANUAL") return t("admin.manualStock")
  if (mode === "UNLIMITED") return t("admin.unlimitedStock")
  return mode || t("admin.systemNoData")
}

function uniqueValues(values: (string | null | undefined)[]) {
  return [...new Set(values.filter((value): value is string => Boolean(value)))]
}

function formatMoney(value: number | null | undefined, empty: string) {
  return value === null || value === undefined ? empty : formatYuan(value)
}

function CopyButton({ value, label, onCopy, className = "" }: { value: string; label: string; onCopy: (value: string) => void; className?: string }) {
  return (
    <button
      type="button"
      className={`shrink-0 rounded p-1 text-muted-foreground transition-colors hover:bg-surface hover:text-foreground ${className}`}
      title={`复制${label}`}
      aria-label={`复制${label}`}
      onClick={() => onCopy(value)}
    >
      <Copy className="size-3.5" aria-hidden="true" />
    </button>
  )
}

function DefinitionRow({ label, children, mono = false }: { label: string; children: ReactNode; mono?: boolean }) {
  return (
    <div className="flex min-w-0 items-start justify-between gap-4 py-2">
      <dt className="shrink-0 text-xs text-muted-foreground">{label}</dt>
      <dd className={`min-w-0 break-all text-right text-sm text-foreground ${mono ? "font-mono" : ""}`}>{children}</dd>
    </div>
  )
}

function CopyDefinitionRow({ label, value, onCopy, empty, mono = false }: { label: string; value: unknown; onCopy: (value: string) => void; empty: string; mono?: boolean }) {
  const hasValue = value !== null && value !== undefined && String(value) !== ""
  const display = hasValue ? String(value) : empty
  return (
    <div className="flex min-w-0 items-start justify-between gap-4 py-2">
      <dt className="shrink-0 text-xs text-muted-foreground">{label}</dt>
      <dd className={`flex min-w-0 items-start justify-end gap-1 text-right text-sm text-foreground ${mono ? "font-mono" : ""}`}>
        <span className="min-w-0 break-all">{display}</span>
        {hasValue && <CopyButton value={String(value)} label={label} onCopy={onCopy} />}
      </dd>
    </div>
  )
}

function Panel({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="rounded-surface border border-border-subtle bg-surface p-4 shadow-sm sm:p-5">
      <h2 className="mb-3 text-sm font-semibold text-foreground">{title}</h2>
      <dl className="divide-y divide-border-subtle/70">{children}</dl>
    </section>
  )
}

function CardRow({ card, index, onCopy }: { card: OrderCardKey; index: number; onCopy: (value: string) => void }) {
  const parts = card.content_parts ?? []
  return (
    <div className="flex min-w-0 items-start gap-3 border-b border-border-subtle/70 py-3 last:border-b-0">
      <span className="w-14 shrink-0 pt-1 text-xs text-muted-foreground">卡券 {index + 1}</span>
      <div className="min-w-0 flex-1 overflow-x-auto">
        <div className="flex min-w-max items-center gap-1.5 font-mono text-sm">
          {parts.length === 0 ? <span className="text-muted-foreground">—</span> : parts.map((part, partIndex) => (
            <Fragment key={`${card.card_key_id}-${partIndex}`}>
              {partIndex > 0 && <span className="px-0.5 text-muted-foreground" aria-hidden="true">⇥</span>}
              <span className="inline-flex max-w-[26rem] items-start gap-1 rounded-control bg-surface-muted px-2 py-1">
                <span className="break-all whitespace-pre-wrap">{part}</span>
                <CopyButton value={part} label={`卡券 ${index + 1} 字段 ${partIndex + 1}`} onCopy={onCopy} />
              </span>
            </Fragment>
          ))}
        </div>
      </div>
      <CopyButton value={formatCardContent(parts)} label={`卡券 ${index + 1}`} onCopy={onCopy} />
    </div>
  )
}

export function OrderDetailSheet({
  order,
  detailLoading = false,
  detailError = false,
  onRetryDetail,
  detailCardKeys,
  detailCardKeysLoading = false,
  detailCardKeysError = false,
  onRetryCardKeys,
  completingManualItem = null,
  onCompleteManualItem,
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

  if (!order) return null

  const identity = formatAdminOrderCustomer(order, t("admin.guest"))
  const mainItem = order.items[0]
  const pricingSources = uniqueValues(order.items.map(item => pricingSourceLabel(item.pricing_source, t)))
  const stockModes = uniqueValues(order.items.map(item => stockModeLabel(item.stock_mode, t)))
  const cardGroups = new Map<string, { title: string; cards: OrderCardKey[] }>()
  detailCardKeys.forEach((card) => {
    const groupKey = card.order_item_id || `${card.product_title}\u0000${card.spec_name || ""}`
    const title = [card.product_title, card.spec_name].filter(Boolean).join(" · ") || t("admin.deliveredCardContent")
    const group = cardGroups.get(groupKey)
    if (group) group.cards.push(card)
    else cardGroups.set(groupKey, { title, cards: [card] })
  })

  return (
    <div className="space-y-6">
      {detailLoading && <div className="flex items-center gap-2 rounded-control border border-border-subtle bg-surface-muted px-3 py-2 text-sm text-muted-foreground"><Loader2 className="size-4 animate-spin" aria-hidden="true" />{t("common.loading")}</div>}
      {detailError && <div className="flex flex-wrap items-center justify-between gap-3 rounded-card border border-destructive/30 bg-destructive/5 px-3 py-3 text-sm text-destructive"><span>{t("admin.loadError")}</span>{onRetryDetail && <Button type="button" variant="outline" size="sm" onClick={onRetryDetail}>{t("common.retry")}</Button>}</div>}

      <header className="rounded-surface border border-border-subtle bg-surface p-5 shadow-sm sm:p-6">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-mono text-lg font-semibold tracking-tight text-foreground">{order.order_no}</span>
              <CopyButton value={String(order.order_no)} label={t("admin.orderNo")} onCopy={copyToClipboard} />
              <OrderStatusBadge status={order.status} />
              <span className={`rounded-full px-2.5 py-1 text-xs font-medium ${order.is_risk_flagged ? "bg-destructive/10 text-destructive" : "bg-emerald-500/10 text-emerald-700 dark:text-emerald-300"}`}>
                {order.is_risk_flagged ? t("admin.riskFlagged") : "风险正常"}
              </span>
            </div>
            <p className="mt-2 text-xs text-muted-foreground">订单工作台</p>
          </div>
        </div>
        <dl className="mt-5 grid gap-4 border-t border-border-subtle pt-5 sm:grid-cols-2 xl:grid-cols-4">
          <DefinitionRow label="主要商品"><span className="font-medium">{mainItem ? `#${mainItem.product_no} ${mainItem.product_title}` : empty}</span></DefinitionRow>
          <DefinitionRow label="客户"><span className="font-medium">{identity.primary}</span>{identity.secondary && <span className="block text-xs text-muted-foreground">{identity.secondary}</span>}</DefinitionRow>
          <DefinitionRow label={t("admin.paymentAmount")}><span className="font-semibold">{formatMoney(order.actual_amount, empty)}</span></DefinitionRow>
          <DefinitionRow label={t("admin.createdAt")}><span>{formatDateTime(order.created_at, locale)}</span></DefinitionRow>
        </dl>
      </header>

      <div className="grid items-start gap-6 xl:grid-cols-[minmax(0,2fr)_minmax(15rem,1fr)]">
        <main className="min-w-0 space-y-6">
          <section className="rounded-surface border border-primary/20 bg-surface p-5 shadow-sm sm:p-6" aria-labelledby="order-card-delivery-title">
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div>
                <h2 id="order-card-delivery-title" className="text-base font-semibold text-foreground">卡券与交付</h2>
                <p className="mt-1 text-xs text-muted-foreground">一张卡券一行；同一卡券的多个字段按原顺序以 Tab 分隔。</p>
              </div>
              {!detailCardKeysLoading && !detailCardKeysError && detailCardKeys.length > 0 && <Button type="button" variant="outline" size="sm" onClick={() => copyToClipboard(formatCardContents(detailCardKeys))}><Copy className="mr-1.5 size-3.5" />复制全部</Button>}
            </div>
            {detailCardKeysLoading && <div className="mt-4 flex items-center gap-2 rounded-card border border-border-subtle bg-surface-muted px-3 py-4 text-sm text-muted-foreground"><Loader2 className="size-4 animate-spin" aria-hidden="true" />正在加载卡密交付内容…</div>}
            {!detailCardKeysLoading && detailCardKeysError && <div className="mt-4 flex flex-wrap items-center justify-between gap-3 rounded-card border border-destructive/30 bg-destructive/5 p-4 text-sm text-destructive"><span>卡密内容加载失败，未使用本地占位数据。</span>{onRetryCardKeys && <Button type="button" variant="outline" size="sm" onClick={onRetryCardKeys}>{t("common.retry")}</Button>}</div>}
            {!detailCardKeysLoading && !detailCardKeysError && detailCardKeys.length === 0 && <div className="mt-4 rounded-card bg-surface-muted/70 p-4 text-sm text-muted-foreground">{order.status === "PAID" ? "订单已支付，卡密正在准备或等待库存补充。" : order.status === "DELIVERED" ? "订单已完成，但没有可显示的卡密内容。" : "当前订单没有可交付的卡密内容。"}</div>}
            {!detailCardKeysLoading && !detailCardKeysError && detailCardKeys.length > 0 && <div className="mt-4 space-y-4">{[...cardGroups.entries()].map(([groupKey, group]) => <div key={groupKey} className="rounded-card border border-border-subtle bg-surface-muted/45 p-3 sm:p-4"><div className="mb-1 flex flex-wrap items-center justify-between gap-2"><h3 className="text-sm font-medium text-foreground">{group.title}</h3><Button type="button" variant="ghost" size="sm" onClick={() => copyToClipboard(formatCardContents(group.cards))}>复制本组</Button></div><div className="overflow-x-auto">{group.cards.map((card, index) => <CardRow key={card.card_key_id} card={card} index={index} onCopy={copyToClipboard} />)}</div></div>)}</div>}
          </section>

          <section className="rounded-surface border border-border-subtle bg-surface p-5 shadow-sm sm:p-6" aria-labelledby="order-product-inputs-title">
            <div className="mb-4">
              <h2 id="order-product-inputs-title" className="text-base font-semibold text-foreground">商品与客户填写</h2>
              <p className="mt-1 text-xs text-muted-foreground">商品快照保留成交时的标题、编号、规格和价格；客户填写内容可直接复制。</p>
            </div>
            <div className="space-y-5">
              {order.items.length === 0 && <p className="rounded-card bg-surface-muted p-4 text-sm text-muted-foreground">{empty}</p>}
              {order.items.map((item) => {
                const manual = item.delivery_type === "MANUAL"
                const canComplete = manual && order.status === "PAID" && item.fulfillment_status !== "DELIVERED" && item.fulfillment_status !== "CANCELLED" && Boolean(onCompleteManualItem)
                const inputs = item.customer_inputs ?? []
                const inputText = inputs.map(input => `${input.label}：${input.value}`).join("\n")
                return <article key={item.id} className="border-t border-border-subtle pt-5 first:border-t-0 first:pt-0">
                  <div className="flex flex-wrap items-start justify-between gap-3">
                    <div className="min-w-0"><h3 className="text-base font-medium text-foreground">{item.product_title}</h3><p className="mt-1 text-xs text-muted-foreground">商品 #{item.product_no} · {item.spec_name || "无规格"} · ×{item.quantity}</p></div>
                    <div className="flex items-center gap-2"><span className="text-xs text-muted-foreground">{fulfillmentLabel(item, t)}</span>{canComplete && <Button type="button" size="sm" disabled={completingManualItem === item.id} onClick={() => onCompleteManualItem?.(order.id, item.id)}>{completingManualItem === item.id && <Loader2 className="mr-1.5 size-3.5 animate-spin" aria-hidden="true" />}{completingManualItem === item.id ? t("admin.processing") : t("admin.completeDelivery")}</Button>}</div>
                  </div>
                  <div className="mt-4 grid gap-x-5 gap-y-3 border-y border-border-subtle/70 py-3 text-sm sm:grid-cols-2 lg:grid-cols-4">
                    <div><p className="text-xs text-muted-foreground">商品编号</p><p className="mt-1 font-mono">#{item.product_no}</p></div>
                    <div><p className="text-xs text-muted-foreground">规格</p><p className="mt-1">{item.spec_name || "无规格"}</p></div>
                    <div><p className="text-xs text-muted-foreground">数量</p><p className="mt-1">{item.quantity}</p></div>
                    <div><p className="text-xs text-muted-foreground">交付方式</p><p className="mt-1">{manual ? t("admin.manualDeliveryLabel") : t("admin.autoDeliveryLabel")}</p></div>
                  </div>
                  <dl className="grid gap-x-5 gap-y-2 border-b border-border-subtle/70 pb-3 text-sm sm:grid-cols-2 lg:grid-cols-5">
                    <DefinitionRow label={t("admin.unitPrice")}><span>{formatMoney(item.unit_price, empty)}</span></DefinitionRow>
                    <DefinitionRow label="成交小计"><span>{formatMoney(item.subtotal, empty)}</span></DefinitionRow>
                    <DefinitionRow label={t("admin.costPriceSnapshot")}><span>{formatMoney(item.cost_price_snapshot, empty)}</span></DefinitionRow>
                    <DefinitionRow label={t("admin.marginAmount")}><span>{formatMoney(item.gross_profit, empty)}</span></DefinitionRow>
                    <DefinitionRow label={t("admin.marginRate")}><span>{item.gross_margin === null ? empty : `${item.gross_margin.toFixed(2)}%`}</span></DefinitionRow>
                  </dl>
                  <div className="mt-4 rounded-card bg-surface-muted/70 p-4">
                    <div className="flex flex-wrap items-center justify-between gap-2"><h4 className="text-sm font-medium text-foreground">客户填写</h4>{inputText && <Button type="button" variant="ghost" size="sm" onClick={() => copyToClipboard(inputText)}><Copy className="mr-1.5 size-3.5" />复制全部</Button>}</div>
                    {inputs.length === 0 ? <p className="mt-2 text-sm text-muted-foreground">{empty}</p> : <div className="mt-2 divide-y divide-border-subtle/70">{inputs.map(input => <div key={input.id} className="flex min-w-0 items-start justify-between gap-3 py-2 first:pt-0 last:pb-0"><span className="shrink-0 text-xs text-muted-foreground">{input.label}</span><span className="flex min-w-0 items-start gap-1 text-right text-sm text-foreground"><span className="break-all whitespace-pre-wrap">{input.value}</span><CopyButton value={input.value} label={input.label} onCopy={copyToClipboard} /></span></div>)}</div>}
                  </div>
                </article>
              })}
            </div>
          </section>
        </main>

        <aside className="min-w-0 space-y-4 xl:sticky xl:top-4">
          <Panel title="客户">
            {order.customer_type === "REGISTERED" ? <>
              <DefinitionRow label="用户名"><span className="font-medium">{order.username || identity.primary}</span></DefinitionRow>
              <CopyDefinitionRow label={t("admin.userNumber")} value={order.user_no} onCopy={copyToClipboard} empty={empty} mono />
              <CopyDefinitionRow label="账户邮箱" value={order.registered_email} onCopy={copyToClipboard} empty={empty} />
              <CopyDefinitionRow label="手机号" value={order.phone} onCopy={copyToClipboard} empty={empty} />
              <DefinitionRow label="下单时等级"><span>{order.user_level_name_snapshot || empty}</span></DefinitionRow>
            </> : <DefinitionRow label="客户"><span className="font-medium">{t("admin.guest")}</span></DefinitionRow>}
          </Panel>

          <Panel title="金额">
            <DefinitionRow label="实付"><span className="font-semibold">{formatMoney(order.actual_amount, empty)}</span></DefinitionRow>
            <DefinitionRow label="成本"><span>{formatMoney(order.cost_total, empty)}</span></DefinitionRow>
            <DefinitionRow label="利润"><span>{formatMoney(order.gross_profit_total, empty)}</span></DefinitionRow>
            <DefinitionRow label="毛利率"><span>{order.gross_margin_total === null ? empty : `${order.gross_margin_total.toFixed(2)}%`}</span></DefinitionRow>
          </Panel>

          <Panel title="订单信息">
            <DefinitionRow label="创建"><span>{formatDateTime(order.created_at, locale)}</span></DefinitionRow>
            <DefinitionRow label="支付"><span>{order.paid_at ? formatDateTime(order.paid_at, locale) : empty}</span></DefinitionRow>
            <DefinitionRow label="完成"><span>{order.delivered_at ? formatDateTime(order.delivered_at, locale) : empty}</span></DefinitionRow>
            <DefinitionRow label={t("admin.paymentMethod")}><span className="inline-flex items-center gap-1.5"><PaymentIcon method={order.order_type === "ADMIN_PICKUP" ? "ADMIN_PICKUP" : order.payment_method || ""} className="size-4" />{order.order_type === "ADMIN_PICKUP" ? getPaymentLabel("ADMIN_PICKUP", t) : order.payment_method ? <>{getPaymentLabel(order.payment_method, t)}{order.payment_method === "ONLINE" && order.payment_channel_code ? <span className="text-xs text-muted-foreground">（{order.payment_channel_code}）</span> : null}</> : empty}</span></DefinitionRow>
            <DefinitionRow label="风险状态"><span className={order.is_risk_flagged ? "font-medium text-destructive" : "text-emerald-700 dark:text-emerald-300"}>{order.is_risk_flagged ? t("admin.riskFlagged") : "正常"}</span></DefinitionRow>
          </Panel>

          <Panel title={t("admin.orderDetails")}>
            <DefinitionRow label={t("admin.pricingSource")}><span>{pricingSources.join("、") || empty}</span></DefinitionRow>
            <DefinitionRow label={t("admin.stockMode")}><span>{stockModes.join("、") || empty}</span></DefinitionRow>
            <DefinitionRow label={t("admin.orderType")}><span>{order.order_type}</span></DefinitionRow>
            <DefinitionRow label={t("admin.expiresAt")}><span>{order.expires_at ? formatDateTime(order.expires_at, locale) : empty}</span></DefinitionRow>
          </Panel>
        </aside>
      </div>

    </div>
  )
}
