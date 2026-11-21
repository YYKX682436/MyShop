"use client"

import { type ReactNode, useId, useState } from "react"
import { Copy, Loader2 } from "lucide-react"
import { toast } from "sonner"

import { OrderStatusBadge } from "@/components/shared/order-status-badge"
import { StatusBadge } from "@/components/admin/shared"
import { AfterSaleHistoryCard } from "@/components/shared/after-sale-history-card"
import { SupplierAftercarePanel } from "@/components/admin/orders/supplier-aftercare-panel"
import { MarkdownContent } from "@/components/shared/markdown-content"
import { CardContentParts } from "@/components/shared/card-content-parts"
import { getPaymentLabel, PaymentIcon } from "@/components/shared/payment-icon"
import { Button } from "@/components/ui/button"
import { useLocale } from "@/lib/context"
import { formatAdminOrderCustomer } from "@/lib/admin-order-display"
import { formatCardContent, formatCardContents } from "@/lib/card-display"
import { formatDateTime, formatYuan, stripInvisible, cn } from "@/lib/utils"
import { isManualFulfillmentItem } from "@/lib/order-status"
import { supplierErrorLabel, supplierStatusLabel } from "@/lib/supplier-order"
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
  onReplyAfterSale?: (content: string, attachments: File[]) => Promise<void>
  afterSaleReplyLoading?: boolean
  onRefreshSupplier?: (purchaseId: string) => void
  onReviewSupplier?: (purchaseId: string, outcome: string) => Promise<void>
  onReviewPayment?: (attemptId: string, note: string) => Promise<void>
  onReloadSupplier?: () => Promise<void>
  refreshingSupplier?: string | null
}

type Translator = (key: TranslationKey) => string

const reviewLabels: Record<string, string> = {
  CONTACTED_SUPPLIER: "已联系供应商，等待核实",
  ORDER_NOT_FOUND_UNCONFIRMED: "暂未查到订单，扣款情况未确认",
  ORDER_FOUND_VERIFY_BY_QUERY: "已核实存在订单，需查询确认",
  MANUAL_HANDLING: "已转人工处理",
}

function SupplierReview({ purchaseId, reviews, onReview }: {
  purchaseId: string
  reviews: { outcome: string; operator: string; created_at: string }[]
  onReview: (purchaseId: string, outcome: string) => Promise<void>
}) {
  const id = useId()
  const [busy, setBusy] = useState(false)
  return <div className="mt-3 space-y-2">
    {reviews.map((review, i) => <p key={`${review.created_at}-${i}`} className="text-xs text-muted-foreground">{reviewLabels[review.outcome] || review.outcome} · {review.operator} · {review.created_at}</p>)}
    <form aria-label="记录采购核实结果" onSubmit={async event => {
      event.preventDefault()
      const outcome = String(new FormData(event.currentTarget).get("outcome") || "")
      setBusy(true)
      try { await onReview(purchaseId, outcome); toast.success("已记录核实结果") }
      catch (error) { toast.error(error instanceof Error ? error.message : "记录失败") }
      finally { setBusy(false) }
    }} className="flex flex-wrap items-end gap-2">
      <div className="min-w-48 flex-1"><label htmlFor={id} className="mb-1 block text-xs">核实结果</label>
        <select id={id} name="outcome" required defaultValue="" className="h-10 w-full rounded-control border border-input bg-surface px-3 text-sm text-foreground">
          <option value="" disabled>请选择</option>
          {Object.entries(reviewLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select>
      </div>
      <Button type="submit" disabled={busy}>{busy ? "保存中" : "记录结果"}</Button>
    </form>
  </div>
}

function PaymentReview({ attemptId, onReview }: { attemptId: string; onReview: (attemptId: string, note: string) => Promise<void> }) {
  const id = useId()
  const [busy, setBusy] = useState(false)
  return <form aria-label="记录支付核实结果" className="mt-3 flex flex-wrap items-end gap-2" onSubmit={async event => {
    event.preventDefault()
    const form = event.currentTarget
    const note = String(new FormData(form).get("note") || "").trim()
    setBusy(true)
    try { await onReview(attemptId, note); form.reset(); toast.success("已记录核实结果") }
    catch (error) { toast.error(error instanceof Error ? error.message : "记录失败") }
    finally { setBusy(false) }
  }}>
    <div className="min-w-48 flex-1"><label htmlFor={id} className="mb-1 block text-xs">人工核实记录（勿填写密钥或卡密）</label>
      <input id={id} name="note" minLength={3} maxLength={500} required className="h-10 w-full rounded-control border border-input bg-surface px-3 text-sm text-foreground" />
    </div>
    <Button type="submit" disabled={busy}>{busy ? "保存中" : "记录"}</Button>
  </form>
}

function fulfillmentLabel(item: OrderItemDetail, t: Translator) {
  if (item.fulfillment_status === "WAITING_MANUAL") return t("admin.fulfillmentWaitingManual")
  if (item.fulfillment_status === "DELIVERED") return t("admin.fulfillmentDelivered")
  if (item.fulfillment_status === "CANCELLED") return t("admin.fulfillmentCancelled")
  return t("admin.fulfillmentPending")
}

function pricingSourceLabel(source: AdminOrderItemDetail["pricing_source"], t: Translator) {
  if (source === "SUBSITE") return t("admin.pricingSourceSubsite")
  if (source === "SALES_CHANNEL") return t("admin.pricingSourceChannel")
  if (source === "USER_SECRET") return t("admin.pricingSourceSecret")
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

function CopyButton({ value, label, copyActionLabel, onCopy, className = "" }: { value: string; label: string; copyActionLabel: string; onCopy: (value: string) => void; className?: string }) {
  return (
    <button
      type="button"
      className={`shrink-0 rounded p-1 text-muted-foreground transition-colors hover:bg-surface hover:text-foreground ${className}`}
      title={`${copyActionLabel} ${label}`}
      aria-label={`${copyActionLabel} ${label}`}
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

function CopyDefinitionRow({ label, value, copyActionLabel, onCopy, empty, mono = false }: { label: string; value: unknown; copyActionLabel: string; onCopy: (value: string) => void; empty: string; mono?: boolean }) {
  const hasValue = value !== null && value !== undefined && String(value) !== ""
  const display = hasValue ? String(value) : empty
  return (
    <div className="flex min-w-0 items-start justify-between gap-4 py-2">
      <dt className="shrink-0 text-xs text-muted-foreground">{label}</dt>
      <dd className={`flex min-w-0 items-start justify-end gap-1 text-right text-sm text-foreground ${mono ? "font-mono" : ""}`}>
        <span className="min-w-0 break-all">{display}</span>
        {hasValue && <CopyButton value={String(value)} label={label} copyActionLabel={copyActionLabel} onCopy={onCopy} />}
      </dd>
    </div>
  )
}

function Panel({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="content-surface rounded-surface p-4 sm:p-5">
      <h2 className="mb-3 text-sm font-semibold text-foreground">{title}</h2>
      <dl className="divide-y divide-border-subtle/70">{children}</dl>
    </section>
  )
}

function CardRow({ card, index, cardLabel, copyActionLabel, onCopy }: { card: OrderCardKey; index: number; cardLabel: string; copyActionLabel: string; onCopy: (value: string) => void }) {
  const parts = card.content_parts ?? []
  return (
    <div className="flex min-w-0 items-start gap-3 border-b border-border-subtle/70 py-3 last:border-b-0">
      <span className="w-14 shrink-0 pt-1 text-xs text-muted-foreground">{cardLabel} {index + 1}</span>
      {parts.length === 0
        ? <span className="min-w-0 flex-1 text-sm text-muted-foreground">—</span>
        : <CardContentParts parts={parts} onCopy={onCopy} showCopyAll={false} className="flex-1 font-mono text-sm" />}
      <CopyButton value={formatCardContent(parts)} label={`${cardLabel} ${index + 1}`} copyActionLabel={copyActionLabel} onCopy={onCopy} />
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
  onReplyAfterSale,
  afterSaleReplyLoading = false,
  onRefreshSupplier,
  onReviewSupplier,
  onReviewPayment,
  onReloadSupplier,
  refreshingSupplier,
}: OrderDetailSheetProps) {
  const { t, locale } = useLocale()
  const empty = t("admin.systemNoData")
  const copyActionLabel = t("admin.copyAction")

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
  const afterSaleRequest = order.after_sale && "reason" in order.after_sale
    ? order.after_sale
    : null
  const mainItem = order.items[0]
  const hasAutomaticCardItems = order.items.some((item) =>
    !isManualFulfillmentItem(item) && item.stock_mode === "CARD_KEY"
  )
  const pricingSources = uniqueValues(order.items.map(item => pricingSourceLabel(item.pricing_source, t)))
  const stockModes = uniqueValues(order.items.map(item => stockModeLabel(item.stock_mode, t)))
  const reclaimedCardKeyCount = order.reclaimed_card_key_count ?? 0
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

      <header className="content-surface rounded-surface p-5 sm:p-6">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-mono text-lg font-semibold tracking-tight text-foreground">{order.order_no}</span>
              <CopyButton value={String(order.order_no)} label={t("admin.orderNo")} copyActionLabel={copyActionLabel} onCopy={copyToClipboard} />
              <OrderStatusBadge status={order.status} items={order.items} />
              <span className={cn("inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium border", order.is_risk_flagged ? "border-destructive/20 bg-destructive/10 text-destructive dark:border-destructive/30 dark:bg-destructive/15 dark:text-red-300" : "border-emerald-500/20 bg-emerald-500/10 text-emerald-800 dark:border-emerald-500/30 dark:bg-emerald-500/15 dark:text-emerald-300")}>
                {order.is_risk_flagged ? t("admin.riskFlagged") : t("admin.riskNormal")}
              </span>
            </div>
            <p className="mt-2 text-xs text-muted-foreground">{t("admin.orderWorkspace")}</p>
          </div>
        </div>
        <dl className="mt-5 grid gap-4 border-t border-border-subtle pt-5 sm:grid-cols-2 xl:grid-cols-4">
          <DefinitionRow label={t("admin.primaryProduct")}><span className="font-medium">{mainItem ? `#${mainItem.product_no} ${mainItem.product_title}` : empty}</span></DefinitionRow>
          <DefinitionRow label={t("admin.customer")}><span className="font-medium">{identity.primary}</span>{identity.secondary && <span className="block text-xs text-muted-foreground">{identity.secondary}</span>}</DefinitionRow>
          <DefinitionRow label={t("admin.paymentAmount")}><span className="font-semibold">{formatMoney(order.actual_amount, empty)}</span></DefinitionRow>
          <DefinitionRow label={t("admin.createdAt")}><span>{formatDateTime(order.created_at, locale)}</span></DefinitionRow>
        </dl>
        {order.customer_type === "GUEST" && (
          <p className="admin-warning-callout mt-4 rounded-control px-3 py-2 text-sm">
            {t("admin.guestRefundUnsupported")}
          </p>
        )}
      </header>

      {((order.payment_attempts?.length ?? 0) > 0 || (order.late_payments?.length ?? 0) > 0) && (
        <section className="content-surface space-y-3 rounded-surface p-5 sm:p-6" aria-labelledby="payment-attempts-title">
          <h2 id="payment-attempts-title" className="text-base font-semibold">支付记录</h2>
          {!!order.late_payments?.length && <p className="text-sm text-destructive" role="alert">存在需核实的迟到或冲突付款记录，请人工处理，勿重复发货或自动退款。</p>}
          {order.payment_attempts?.map(attempt => <div key={attempt.id} className="rounded-control border border-border-subtle p-3 text-sm">
            <div className="flex flex-wrap justify-between gap-2"><span>{attempt.channel} · {formatYuan(attempt.amount)}</span><span>{attempt.status} / {attempt.creation_status}</span></div>
            <div className="mt-1 break-all text-xs text-muted-foreground">商户单号 {attempt.merchant_trade_no}</div>
            {attempt.provider_trade_no && <div className="break-all text-xs text-muted-foreground">网关单号 {attempt.provider_trade_no}</div>}
            {attempt.create_response_trade_no && attempt.create_response_trade_no !== attempt.provider_trade_no && <div className="break-all text-xs text-destructive">创建响应单号 {attempt.create_response_trade_no}（需核实）</div>}
            {order.late_payments?.filter(event => event.attempt_id === attempt.id).map(event => <div key={event.id} className="mt-1 break-all text-xs text-destructive">{event.process_result === "LATE_PAID_CONFLICT" ? "签名通知的网关单号冲突" : event.process_result === "LATE_PAID_UNVERIFIED" ? "签名通知等待网关确认" : "迟到付款"} · 待人工核实 · {formatDateTime(event.recorded_at, locale)} · {event.reported_trade_no || event.event_id}</div>)}
            {onReviewPayment && (attempt.creation_status === "UNKNOWN" || attempt.creation_status === "SUBMITTING" || order.late_payments?.some(event => event.attempt_id === attempt.id)) && <PaymentReview attemptId={attempt.id} onReview={onReviewPayment} />}
          </div>)}
          {order.payment_reviews?.map(review => <p key={review.id} className="break-all text-xs text-muted-foreground">{formatDateTime(review.created_at, locale)} · {review.operator} · {review.detail}</p>)}
        </section>
      )}

      {(afterSaleRequest || (order.after_sale_history?.length ?? 0) > 0) && (
        <AfterSaleHistoryCard
          request={afterSaleRequest}
          history={order.after_sale_history ?? []}
          customerLabel={[identity.primary, identity.secondary].filter(Boolean).join(" · ")}
          mode="admin"
          onReply={onReplyAfterSale}
          replyLoading={afterSaleReplyLoading}
        />
      )}

      {order.status === "REFUNDED" && (
        <section className="rounded-surface border border-slate-300/80 bg-slate-500/[0.06] p-5 shadow-sm dark:border-slate-700/80 dark:bg-slate-500/10 sm:p-6" aria-labelledby="order-refund-title">
          <div className="flex items-center gap-2">
            <h2 id="order-refund-title" className="text-base font-semibold text-foreground">{t("admin.refund")}</h2>
            <StatusBadge label={t("status.REFUNDED")} tone="refunded" />
          </div>
          <dl className="mt-3 grid gap-x-6 gap-y-2 sm:grid-cols-2 lg:grid-cols-4">
            <DefinitionRow label={t("admin.refundStatus")}><span>{t("status.REFUNDED")}</span></DefinitionRow>
            <DefinitionRow label={t("admin.refundAmount")}><span className="font-semibold">{formatMoney(order.refund_amount ?? order.actual_amount, empty)}</span></DefinitionRow>
            <DefinitionRow label={t("admin.refundDestination")}><span>{t(order.actual_amount === 0 ? "admin.zeroRefund" : "admin.refundBalance")}</span></DefinitionRow>
            <DefinitionRow label={t("admin.refundedAt")}><span>{order.refunded_at ? formatDateTime(order.refunded_at, locale) : empty}</span></DefinitionRow>
            <DefinitionRow label={t("admin.refundCardHandling")}><span>{reclaimedCardKeyCount > 0 ? t("admin.refundReclaimed").replace("{count}", String(reclaimedCardKeyCount)) : t("admin.refundNotReclaimed")}</span></DefinitionRow>
          </dl>
          {(order.refund_reason || order.refund_operator_username || order.status === "REFUNDED") && (
            <dl className="mt-3 grid gap-x-6 gap-y-2 border-t border-slate-200 dark:border-slate-800 pt-3 text-sm sm:grid-cols-2">
              <DefinitionRow label={t("admin.refundReason")}><span className="whitespace-pre-wrap">{order.refund_reason?.trim() || t("admin.refundReasonNotProvided")}</span></DefinitionRow>
              {order.refund_operator_username && <DefinitionRow label={t("admin.refundOperator")}><span>{order.refund_operator_username}</span></DefinitionRow>}
            </dl>
          )}
        </section>
      )}

      <div className="grid items-start gap-6 xl:grid-cols-[minmax(0,2fr)_minmax(15rem,1fr)]">
        <main className="min-w-0 space-y-6">
          {hasAutomaticCardItems && <section className="content-surface rounded-surface border-primary/20 p-5 sm:p-6" aria-labelledby="order-card-delivery-title">
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div>
                <h2 id="order-card-delivery-title" className="text-base font-semibold text-foreground">{t("admin.cardDelivery")}</h2>
                <p className="mt-1 text-xs text-muted-foreground">{t("admin.cardDeliveryDescription")}</p>
              </div>
              {!detailCardKeysLoading && !detailCardKeysError && detailCardKeys.length > 0 && <Button type="button" variant="outline" size="sm" onClick={() => copyToClipboard(formatCardContents(detailCardKeys))}><Copy className="mr-1.5 size-3.5" />{t("admin.copyAll")}</Button>}
            </div>
            {detailCardKeysLoading && <div className="mt-4 flex items-center gap-2 rounded-card border border-border-subtle bg-surface-muted px-3 py-4 text-sm text-muted-foreground"><Loader2 className="size-4 animate-spin" aria-hidden="true" />{t("admin.loadingCardDelivery")}</div>}
            {!detailCardKeysLoading && detailCardKeysError && <div className="mt-4 flex flex-wrap items-center justify-between gap-3 rounded-card border border-destructive/30 bg-destructive/5 p-4 text-sm text-destructive"><span>{t("admin.cardDeliveryLoadFailed")}</span>{onRetryCardKeys && <Button type="button" variant="outline" size="sm" onClick={onRetryCardKeys}>{t("common.retry")}</Button>}</div>}
            {!detailCardKeysLoading && !detailCardKeysError && detailCardKeys.length === 0 && <div className="mt-4 rounded-card bg-surface-muted/70 p-4 text-sm text-muted-foreground">{order.status === "PAID" ? t("admin.paidCardDeliveryPending") : order.status === "DELIVERED" || order.status === "REFUNDED" ? t("admin.deliveredCardContentEmpty") : t("admin.cardDeliveryEmpty")}</div>}
            {!detailCardKeysLoading && !detailCardKeysError && detailCardKeys.length > 0 && <div className="mt-4 space-y-4">{[...cardGroups.entries()].map(([groupKey, group]) => <div key={groupKey} className="rounded-card border border-border-subtle bg-surface-muted/45 p-3 sm:p-4"><div className="mb-1 flex flex-wrap items-center justify-between gap-2"><h3 className="text-sm font-medium text-foreground">{group.title}</h3><Button type="button" variant="ghost" size="sm" onClick={() => copyToClipboard(formatCardContents(group.cards))}>{t("admin.copyGroup")}</Button></div><div>{group.cards.map((card, index) => <CardRow key={card.card_key_id} card={card} index={index} cardLabel={t("admin.cardNumber")} copyActionLabel={copyActionLabel} onCopy={copyToClipboard} />)}</div></div>)}</div>}
          </section>}

          <section className="content-surface rounded-surface p-5 sm:p-6" aria-labelledby="order-product-inputs-title">
            <div className="mb-4">
              <h2 id="order-product-inputs-title" className="text-base font-semibold text-foreground">{t("admin.productAndCustomerInputs")}</h2>
              <p className="mt-1 text-xs text-muted-foreground">{t("admin.productSnapshotDescription")}</p>
            </div>
            <div className="space-y-5">
              {order.items.length === 0 && <p className="rounded-card bg-surface-muted p-4 text-sm text-muted-foreground">{empty}</p>}
              {order.items.map((item) => {
                const manual = isManualFulfillmentItem(item)
                const inputs = item.customer_inputs ?? []
                const inputText = inputs.map(input => `${input.label}：${input.value}`).join("\n")
                return <article key={item.id} className="border-t border-border-subtle pt-5 first:border-t-0 first:pt-0">
                  <div className="flex flex-wrap items-start justify-between gap-3">
                    <div className="min-w-0"><h3 className="text-base font-medium text-foreground">{item.product_title}</h3><p className="mt-1 text-xs text-muted-foreground">{t("admin.product")} #{item.product_no} · {item.spec_name || t("admin.noSpecification")} · ×{item.quantity}</p></div>
                    <div className="flex items-center gap-2"><span className="text-xs text-muted-foreground">{fulfillmentLabel(item, t)}</span></div>
                  </div>
                  <div className="mt-4 grid gap-x-5 gap-y-3 border-y border-border-subtle/70 py-3 text-sm sm:grid-cols-2 lg:grid-cols-4">
                    <div><p className="text-xs text-muted-foreground">{t("admin.productNo")}</p><p className="mt-1 font-mono">#{item.product_no}</p></div>
                    <div><p className="text-xs text-muted-foreground">{t("admin.specification")}</p><p className="mt-1">{item.spec_name || t("admin.noSpecification")}</p></div>
                    <div><p className="text-xs text-muted-foreground">{t("admin.quantity")}</p><p className="mt-1">{item.quantity}</p></div>
                    <div><p className="text-xs text-muted-foreground">{t("admin.deliveryMethod")}</p><p className="mt-1">{manual ? t("admin.manualDeliveryLabel") : t("admin.autoDeliveryLabel")}</p></div>
                  </div>
                  <dl className="grid gap-x-5 gap-y-2 border-b border-border-subtle/70 pb-3 text-sm sm:grid-cols-2 lg:grid-cols-5">
                    <DefinitionRow label={t(item.subsite_supply_price_snapshot != null ? "admin.subsiteSalePrice" : "admin.unitPrice")}><span>{formatMoney(item.unit_price, empty)}</span></DefinitionRow>
                    {item.subsite_supply_price_snapshot != null && <DefinitionRow label={t("admin.subsiteSupplyPrice")}><span>{formatMoney(item.subsite_supply_price_snapshot, empty)}</span></DefinitionRow>}
                    <DefinitionRow label={t("admin.subtotal")}><span>{formatMoney(item.subtotal, empty)}</span></DefinitionRow>
                    <DefinitionRow label={t("admin.costPriceSnapshot")}><span>{formatMoney(item.cost_price_snapshot, empty)}</span></DefinitionRow>
                    <DefinitionRow label={t(item.subsite_supply_price_snapshot != null ? "admin.subsiteMainProfit" : "admin.marginAmount")}><span>{formatMoney(item.gross_profit, empty)}</span></DefinitionRow>
                    <DefinitionRow label={t("admin.marginRate")}><span>{item.gross_margin === null ? empty : `${item.gross_margin.toFixed(2)}%`}</span></DefinitionRow>
                  </dl>
                  {item.supplier_purchase && <div className="mt-4 rounded-card border border-border-subtle p-4">
                    <div className="flex flex-wrap items-center justify-between gap-2"><h4 className="text-sm font-medium">货源采购 · {supplierStatusLabel(item.supplier_purchase.status)}</h4>
                      {onRefreshSupplier && <Button type="button" variant="outline" size="sm" disabled={!item.supplier_purchase.can_refresh || Boolean(refreshingSupplier)} onClick={() => onRefreshSupplier(item.supplier_purchase!.id)}>{refreshingSupplier === item.supplier_purchase.id ? "查询中" : "查询最新状态"}</Button>}
                    </div>
                    <dl className="mt-2 grid gap-x-5 gap-y-1 sm:grid-cols-2">
                      <DefinitionRow label="供货账号">{item.supplier_purchase.account_name}</DefinitionRow>
                      <DefinitionRow label="上游订单号">{item.supplier_purchase.upstream_order_number || "—"}</DefinitionRow>
                      <DefinitionRow label="采购单号">{item.supplier_purchase.outer_number}</DefinitionRow>
                      <DefinitionRow label="上游状态">{item.supplier_purchase.upstream_status ?? "—"}</DefinitionRow>
                      <DefinitionRow label="采购数量">{item.supplier_purchase.actual_quantity}</DefinitionRow>
                      <DefinitionRow label="采购成本">{item.supplier_purchase.actual_cost == null ? "—" : `¥${item.supplier_purchase.actual_cost}`}</DefinitionRow>
                      <DefinitionRow label="上游已退款">{item.supplier_purchase.refunded_amount == null ? "—" : `¥${item.supplier_purchase.refunded_amount}`}</DefinitionRow>
                      <DefinitionRow label="最近查询">{item.supplier_purchase.last_checked_at ? formatDateTime(item.supplier_purchase.last_checked_at, locale) : "—"}</DefinitionRow>
                    </dl>
                    {item.supplier_purchase.error_code && <p className="mt-2 text-xs text-destructive">{supplierErrorLabel(item.supplier_purchase.error_code)}</p>}
                    {onReviewSupplier && ["UNKNOWN", "SUBMITTING", "BLOCKED"].includes(item.supplier_purchase.status) && <SupplierReview purchaseId={item.supplier_purchase.id} reviews={item.supplier_purchase.reviews || []} onReview={onReviewSupplier} />}
                    {onReloadSupplier && <SupplierAftercarePanel orderId={order.id} purchase={item.supplier_purchase} sale={order.after_sale_history?.[0] ?? null} onReload={onReloadSupplier} />}
                  </div>}
                    <div className="mt-4 rounded-card bg-surface-muted/70 p-4">
                    <div className="flex flex-wrap items-center justify-between gap-2"><h4 className="text-sm font-medium text-foreground">{t("admin.customerInputs")}</h4>{inputText && <Button type="button" variant="ghost" size="sm" onClick={() => copyToClipboard(inputText)}><Copy className="mr-1.5 size-3.5" />{t("admin.copyAll")}</Button>}</div>
                    {inputs.length === 0 ? <p className="mt-2 text-sm text-muted-foreground">{empty}</p> : <div className="mt-2 divide-y divide-border-subtle/70">{inputs.map(input => <div key={input.id} className="flex min-w-0 items-start justify-between gap-3 py-2 first:pt-0 last:pb-0"><span className="shrink-0 text-xs text-muted-foreground">{input.label}</span><span className="flex min-w-0 items-start gap-1 text-right text-sm text-foreground"><span className="break-all whitespace-pre-wrap">{input.value}</span><CopyButton value={input.value} label={input.label} copyActionLabel={copyActionLabel} onCopy={copyToClipboard} /></span></div>)}</div>}
                  </div>
                  {manual && item.fulfillment_status === "DELIVERED" && (
                    <div className="mt-4 rounded-card border border-primary/20 bg-primary/5 p-4">
                      <h4 className="text-sm font-medium text-foreground">{t("admin.customerDeliveryContent")}</h4>
                      {item.customer_delivery_content?.trim()
                        ? <MarkdownContent value={item.customer_delivery_content} className="mt-2" />
                        : <p className="mt-2 text-sm text-muted-foreground">{t("admin.manualDeliveryContentEmpty")}</p>}
                    </div>
                  )}
                </article>
              })}
            </div>
          </section>
        </main>

        <aside className="min-w-0 space-y-4 xl:sticky xl:top-4">
          <Panel title={t("admin.customer")}>
            {order.customer_type === "REGISTERED" ? <>
              <DefinitionRow label={t("auth.username")}><span className="font-medium">{order.username || identity.primary}</span></DefinitionRow>
              <CopyDefinitionRow label={t("admin.userNumber")} value={order.user_no} copyActionLabel={copyActionLabel} onCopy={copyToClipboard} empty={empty} mono />
              <CopyDefinitionRow label={t("admin.accountEmail")} value={order.registered_email} copyActionLabel={copyActionLabel} onCopy={copyToClipboard} empty={empty} />
              <CopyDefinitionRow label={t("admin.phoneLabel")} value={order.phone} copyActionLabel={copyActionLabel} onCopy={copyToClipboard} empty={empty} />
              <DefinitionRow label={t("admin.userLevelSnapshot")}><span>{order.user_level_name_snapshot || empty}</span></DefinitionRow>
            </> : <DefinitionRow label={t("admin.customer")}><span className="font-medium">{t("admin.guest")}</span></DefinitionRow>}
          </Panel>

          <Panel title={t("admin.amount")}>
            <DefinitionRow label={t("admin.actualPaid")}><span className="font-semibold">{formatMoney(order.actual_amount, empty)}</span></DefinitionRow>
            <DefinitionRow label={t(order.subsite_profit != null ? "admin.subsiteCostSnapshot" : "admin.cost")}><span>{formatMoney(order.cost_total, empty)}</span></DefinitionRow>
            <DefinitionRow label={t(order.subsite_profit != null ? "admin.subsiteMainProfit" : "admin.profit")}><span>{formatMoney(order.gross_profit_total, empty)}</span></DefinitionRow>
            {order.subsite_profit != null && <DefinitionRow label={t("admin.subsiteOwnerProfit")}><span>{formatMoney(order.subsite_profit, empty)} · {t(order.subsite_profit_state === "CANCELED" ? "admin.subsiteProfitCanceled" : order.subsite_profit_state === "REVERSED" ? "admin.subsiteProfitReversed" : order.subsite_profit_state === "SETTLED" ? "admin.subsiteProfitSettled" : order.subsite_profit_state === "PENDING" ? "admin.subsiteProfitPending" : order.status === "PAID" ? "admin.subsiteProfitEstimated" : "admin.subsiteProfitUnsettled")}</span></DefinitionRow>}
            <DefinitionRow label={t("admin.marginRate")}><span>{order.gross_margin_total === null ? empty : `${order.gross_margin_total.toFixed(2)}%`}</span></DefinitionRow>
          </Panel>

          <Panel title={t("admin.orderInfo")}>
            <DefinitionRow label={t("admin.createdShort")}><span>{formatDateTime(order.created_at, locale)}</span></DefinitionRow>
            <DefinitionRow label={t("admin.paidShort")}><span>{order.paid_at ? formatDateTime(order.paid_at, locale) : empty}</span></DefinitionRow>
            <DefinitionRow label={t("admin.completedShort")}><span>{order.delivered_at ? formatDateTime(order.delivered_at, locale) : empty}</span></DefinitionRow>
            <DefinitionRow label={t("admin.paymentMethod")}><span className="inline-flex items-center gap-1.5"><PaymentIcon method={order.order_type === "ADMIN_PICKUP" ? "ADMIN_PICKUP" : order.payment_method || ""} className="size-4" />{order.order_type === "ADMIN_PICKUP" ? getPaymentLabel("ADMIN_PICKUP", t) : order.payment_method ? <>{getPaymentLabel(order.payment_method, t)}{order.payment_method === "ONLINE" && order.payment_channel_code ? <span className="text-xs text-muted-foreground">（{order.payment_channel_code}）</span> : null}</> : empty}</span></DefinitionRow>
            <DefinitionRow label={t("admin.riskStatus")}><span className={order.is_risk_flagged ? "font-medium text-destructive" : "text-emerald-700 dark:text-emerald-300"}>{order.is_risk_flagged ? t("admin.riskFlagged") : t("admin.normal")}</span></DefinitionRow>
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
