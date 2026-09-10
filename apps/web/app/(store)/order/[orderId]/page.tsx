"use client"

import { use, useCallback, useEffect, useMemo, useRef, useState } from "react"
import Link from "next/link"
import { Clock, Copy, Download, KeyRound, RefreshCw, XCircle } from "lucide-react"
import { toast } from "sonner"
import { useSearchParams } from "next/navigation"
import { useLocale, useSiteConfig } from "@/lib/context"
import { getApiErrorMessage, orderApi } from "@/services/api"
import type { OrderDeliveryGroup, OrderView } from "@/types"
import { cn, detectPaymentDevice, formatDateTime, formatYuan } from "@/lib/utils"
import { clearPaymentAction, getPaymentActionEnvironment, readPaymentAction, resolvePaymentEntry, savePaymentAction, shouldSeedPaymentAction } from "@/lib/payment-entry"
import { applyPaymentStatus, applyPaymentStatusError, createPaymentPageActionState, type PaymentStatusRequestKind } from "@/lib/payment-page-state"
import { PaymentActionRenderer } from "@/components/shared/payment-action-renderer"
import { PaymentIcon, getPaymentBrandColor, getPaymentLabel, getPaymentScanHint } from "@/components/shared/payment-icon"
import { OrderStatusBadge } from "@/components/shared/order-status-badge"
import { ContactSupport } from "@/components/shared/contact-methods"
import { MarkdownContent } from "@/components/shared/markdown-content"
import { isManualFulfillmentItem } from "@/lib/order-status"
import { getOrderStatusPollInterval, shouldPollOrderStatus } from "@/lib/order-polling"
import type { TranslationKey } from "@/lib/i18n"
import type { OrderItemDetail } from "@/types"

const PAYMENT_RETURN_TIMEOUT = 20000
const MANUAL_REFRESH_COOLDOWN = 10

export default function OrderLifecyclePage({ params }: { params: Promise<{ orderId: string }> }) {
  const { orderId } = use(params)
  const { t, locale } = useLocale()
  const { config } = useSiteConfig()
  const searchParams = useSearchParams()
  const paymentReturn = searchParams.get("paymentReturn") === "1"
  const initialAction = useMemo(() => readPaymentAction(orderId), [orderId])
  const [view, setView] = useState<OrderView | null>(null)
  const [timeLeft, setTimeLeft] = useState(-1)
  const [refreshCooldown, setRefreshCooldown] = useState(0)
  const [refreshing, setRefreshing] = useState(false)
  const [retrying, setRetrying] = useState(false)
  const [checkingReturn, setCheckingReturn] = useState(paymentReturn)
  const [returnTimedOut, setReturnTimedOut] = useState(false)
  const [paymentState, setPaymentState] = useState(() => createPaymentPageActionState(initialAction))
  const [pageVisible, setPageVisible] = useState(() => typeof document === "undefined" || document.visibilityState === "visible")
  const requestInFlight = useRef(false)
  const viewRef = useRef<OrderView | null>(null)
  const returnDeadline = useRef<number | null>(paymentReturn ? Date.now() + PAYMENT_RETURN_TIMEOUT : null)

  const fetchView = useCallback(async (requestKind: PaymentStatusRequestKind, forceRefresh = false): Promise<string | null> => {
    if (requestInFlight.current) return null
    requestInFlight.current = true
    try {
      const result = forceRefresh ? await orderApi.refresh(orderId) : await orderApi.get(orderId)
      viewRef.current = result
      setView(result)
      setTimeLeft(result.payment?.remaining_seconds ?? 0)
      setPaymentState(current => applyPaymentStatus(current, result.order.status, result.payment?.next_action))
      if (result.order.status !== "PENDING") {
        clearPaymentAction(orderId)
        setCheckingReturn(false)
      } else if (result.payment?.next_action
        && shouldSeedPaymentAction(resolvePaymentEntry(result.payment.next_action, getPaymentActionEnvironment()))) {
        savePaymentAction(orderId, result.payment.next_action)
      } else if (!initialAction) {
        clearPaymentAction(orderId)
      }
      return null
    } catch (error) {
      const message = getApiErrorMessage(error, t)
      setPaymentState(current => applyPaymentStatusError(current, message, requestKind))
      return message
    } finally {
      requestInFlight.current = false
    }
  }, [initialAction, orderId, t])

  useEffect(() => {
    void fetchView("initial", paymentReturn)
  }, [fetchView, paymentReturn])

  useEffect(() => {
    if (!view || !pageVisible || !shouldPollOrderStatus(view.order.status, view.order.items)) return
    const interval = getOrderStatusPollInterval(view.order.status, checkingReturn)
    if (interval === null) return
    const timer = window.setInterval(() => {
      if (document.visibilityState !== "visible") return
      if (checkingReturn && returnDeadline.current !== null && Date.now() >= returnDeadline.current) {
        setCheckingReturn(false)
        setReturnTimedOut(true)
        return
      }
      void fetchView("poll")
    }, interval)
    return () => window.clearInterval(timer)
  }, [checkingReturn, fetchView, pageVisible, view])

  useEffect(() => {
    const refreshIfOrderCanChange = () => {
      if (document.visibilityState !== "visible") return
      const currentView = viewRef.current
      if (!currentView || !shouldPollOrderStatus(currentView.order.status, currentView.order.items)) return
      void fetchView("poll")
    }
    const handleVisibilityChange = () => {
      const visible = document.visibilityState === "visible"
      setPageVisible(visible)
      if (visible) refreshIfOrderCanChange()
    }

    document.addEventListener("visibilitychange", handleVisibilityChange)
    window.addEventListener("focus", refreshIfOrderCanChange)
    return () => {
      document.removeEventListener("visibilitychange", handleVisibilityChange)
      window.removeEventListener("focus", refreshIfOrderCanChange)
    }
  }, [fetchView])

  useEffect(() => {
    if (view?.order.status !== "PENDING" || timeLeft < 0) return
    const timer = window.setInterval(() => setTimeLeft(value => Math.max(0, value - 1)), 1000)
    return () => window.clearInterval(timer)
  }, [timeLeft, view?.order.status])

  useEffect(() => {
    if (refreshCooldown <= 0) return
    const timer = window.setInterval(() => setRefreshCooldown(value => Math.max(0, value - 1)), 1000)
    return () => window.clearInterval(timer)
  }, [refreshCooldown])

  const manualRefresh = async () => {
    if (refreshCooldown > 0 || refreshing) return
    setRefreshing(true)
    const errorMessage = await fetchView("manual", true)
    if (errorMessage) toast.error(errorMessage)
    else toast.info(t("payment.statusRefreshed"))
    setRefreshing(false)
    setRefreshCooldown(MANUAL_REFRESH_COOLDOWN)
  }

  const retryPayment = async () => {
    if (retrying) return
    setRetrying(true)
    try {
      const result = await orderApi.repay(orderId, detectPaymentDevice())
      const action = result.next_action ?? null
      setPaymentState({ phase: "ready", nextAction: action, errorMessage: null })
      if (action && shouldSeedPaymentAction(resolvePaymentEntry(action, getPaymentActionEnvironment()))) {
        savePaymentAction(orderId, action)
      } else {
        clearPaymentAction(orderId)
      }
      await fetchView("poll")
      toast.success(t("payment.statusRefreshed"))
    } catch (error) {
      toast.error(getApiErrorMessage(error, t))
    } finally {
      setRetrying(false)
    }
  }

  const copy = (value: string) => {
    if (!navigator.clipboard?.writeText) {
      toast.error(t("payment.copyFailed"))
      return
    }
    void navigator.clipboard.writeText(value)
      .then(() => toast.success(t("order.copied")))
      .catch(() => toast.error(t("payment.copyFailed")))
  }

  const exportKeys = async () => {
    try {
      const content = await orderApi.exportKeys(orderId)
      const url = URL.createObjectURL(new Blob([content], { type: "text/plain;charset=utf-8" }))
      const anchor = document.createElement("a")
      anchor.href = url
      anchor.download = "myshop-card-keys.txt"
      anchor.click()
      URL.revokeObjectURL(url)
    } catch (error) {
      toast.error(getApiErrorMessage(error, t))
    }
  }

  const formatTime = (seconds: number) => `${String(Math.floor(seconds / 60)).padStart(2, "0")}:${String(seconds % 60).padStart(2, "0")}`
  const status = view?.order.status ?? "PENDING"
  const paymentMethod = view?.payment?.payment_type || view?.payment?.payment_method || "online"
  const methodName = getPaymentLabel(paymentMethod, t)
  const brandColor = getPaymentBrandColor(paymentMethod)
  const deliveredGroups = view?.delivery.groups?.filter(group => group.delivery_type?.toUpperCase() === "AUTO") ?? []
  const orderItems = view?.order.items ?? []
  const automaticCardItems = orderItems.filter(item =>
    !isManualFulfillmentItem(item) && item.stock_mode === "CARD_KEY"
  )
  const waitingManualItems = orderItems.filter(item =>
    isManualFulfillmentItem(item) && item.fulfillment_status === "WAITING_MANUAL"
  )
  const deliveredManualItems = orderItems.filter(item =>
    isManualFulfillmentItem(item) && item.fulfillment_status === "DELIVERED"
  )
  const pendingAutomaticItems = orderItems.filter(item =>
    !isManualFulfillmentItem(item) && item.stock_mode === "CARD_KEY"
    && item.fulfillment_status !== "DELIVERED"
    && item.fulfillment_status !== "CANCELLED"
  )
  const hasCards = deliveredGroups.length > 0
  const retryButton = <button type="button" onClick={() => void retryPayment()} disabled={retrying} className="inline-flex h-11 items-center gap-2 rounded-control bg-primary px-5 text-sm font-medium text-primary-foreground shadow-sm transition-[background-color,transform] duration-180 hover:bg-primary/90 active:scale-[.99] disabled:opacity-50"><RefreshCw className={cn("h-4 w-4", retrying && "animate-spin")} />{t("order.retryPayment")}</button>
  const retryStatusButton = <button type="button" onClick={() => void manualRefresh()} disabled={refreshing} className="inline-flex h-10 items-center gap-2 rounded-control border border-border-strong bg-surface px-4 text-sm font-medium text-foreground transition-colors hover:bg-surface-muted disabled:opacity-50"><RefreshCw className={cn("h-4 w-4", refreshing && "animate-spin")} />{t("payment.retryLoad")}</button>

  if (!view) {
    return <div className="surface mx-auto flex max-w-lg flex-col items-center gap-3 p-8 text-center" role="status"><RefreshCw className="h-8 w-8 animate-spin text-primary" /><p className="text-sm text-muted-foreground">{paymentState.errorMessage || t("common.loading")}</p>{paymentState.errorMessage && retryStatusButton}</div>
  }

  if (status === "EXPIRED") {
    return <div className="surface mx-auto flex max-w-lg flex-col items-center gap-4 p-8 text-center"><XCircle className="h-12 w-12 text-muted-foreground" /><h1 className="text-xl font-semibold">{t("payment.expired")}</h1><p className="text-sm text-muted-foreground">{t("payment.expiredDesc")}</p><Link href="/" className="inline-flex h-11 items-center rounded-control bg-primary px-5 text-sm font-medium text-primary-foreground shadow-sm transition-[background-color,transform] duration-180 hover:bg-primary/90 active:scale-[.99]">{t("order.backHome")}</Link></div>
  }

  return <div className="mx-auto flex w-full max-w-3xl flex-col gap-6 py-2">
    <div className="flex flex-wrap items-center justify-between gap-3">
      <div>
        <p className="mb-1 text-xs font-semibold uppercase tracking-[0.16em] text-primary">MyShop</p>
        <h1 className="text-2xl font-semibold tracking-tight text-foreground">{t("order.lifecycleTitle")}</h1>
      </div>
      <OrderStatusBadge status={status} items={orderItems} />
    </div>

    <section className="surface space-y-5 p-5 sm:p-7">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0">
          <p className="text-sm text-muted-foreground">{view.order.items[0]?.product_title || t("order.lifecycleTitle")}</p>
          <p className="mt-1 font-mono text-sm font-medium text-foreground">{t("order.orderNo")} {view.order.order_no}</p>
          {view.order.items.length > 1 && <p className="mt-1 text-xs text-muted-foreground">{t("order.itemsCount").replace("{count}", String(view.order.items.length))}</p>}
          <p className="mt-3 text-3xl font-semibold tracking-tight text-foreground">{formatYuan(view.order.actual_amount ?? view.order.total_amount)}</p>
        </div>
        <div className="text-right text-xs text-muted-foreground">
          <p>{t("order.createdAt")}</p>
          <p className="mt-1">{formatDateTime(view.order.created_at, locale)}</p>
          {view.order.paid_at && <><p className="mt-3">{t("order.paidAt")}</p><p className="mt-1">{formatDateTime(view.order.paid_at, locale)}</p></>}
        </div>
      </div>

      {status === "PENDING" && (
        <div className="space-y-4 border-t border-border-subtle pt-5">
          {checkingReturn && <div className="rounded-control border border-primary/20 bg-primary/5 px-4 py-3 text-sm text-primary" role="status">{t("order.paymentReturn")}</div>}
          {returnTimedOut && <div className="rounded-control border border-amber-300/50 bg-amber-50 px-4 py-3 text-sm text-amber-800 dark:border-amber-500/30 dark:bg-amber-950/20 dark:text-amber-200" role="status">{t("order.paymentReturnTimeout")}</div>}
          <div className="flex items-center justify-between gap-3 text-sm"><span className="text-muted-foreground">{t("payment.remaining")}</span><span className={cn("inline-flex items-center gap-1 font-mono text-lg font-bold", timeLeft >= 0 && timeLeft < 120 && "text-destructive")}><Clock className="h-4 w-4" />{timeLeft < 0 ? "--:--" : formatTime(timeLeft)}</span></div>
          {view.order.payment_method === "ONLINE" && (
            <div className="mx-auto flex w-full max-w-sm flex-col items-center gap-4 rounded-surface px-6 pb-8 pt-6" style={{ backgroundColor: brandColor || "#374151" }}>
              <div className="flex items-center gap-2.5"><PaymentIcon method={paymentMethod} className="h-10 w-10" variant="plain" /><span className="text-xl font-bold text-white">{methodName}</span></div>
              <p className="text-sm font-medium text-white/90">{getPaymentScanHint(paymentMethod, t)}</p>
              <PaymentActionRenderer action={paymentState.nextAction} loadState={paymentState.phase} statusError={paymentState.errorMessage} retryAction={retryButton} retryStatusAction={retryStatusButton} />
            </div>
          )}
          {view.order.payment_method !== "ONLINE" && automaticCardItems.length > 0 && <p className="rounded-control bg-surface-muted px-4 py-3 text-sm text-muted-foreground">{t("order.deliveryPreparing")}</p>}
        </div>
      )}

      {(status === "PAID" || status === "DELIVERED") && (
        <div className="space-y-4 border-t border-border-subtle pt-5">
          {waitingManualItems.length > 0 && <div className="rounded-control border border-amber-300/50 bg-amber-50 px-4 py-3 text-sm text-amber-800 dark:border-amber-500/30 dark:bg-amber-950/20 dark:text-amber-200"><p className="font-medium">{t("order.deliveryWaitingManual")}</p></div>}
          {pendingAutomaticItems.length > 0 && <div className="rounded-control bg-surface-muted px-4 py-3 text-sm text-muted-foreground">{t("order.deliveryPreparing")}</div>}
          {hasCards && <div className="rounded-control border border-emerald-300/50 bg-emerald-50 px-4 py-3 text-sm text-emerald-800 dark:border-emerald-500/20 dark:bg-emerald-950/20 dark:text-emerald-200">{t("order.deliveryReady")}</div>}
          {hasCards && <div className="flex flex-wrap justify-end gap-2"><button type="button" onClick={() => copy(allCardText(deliveredGroups))} className="inline-flex h-9 items-center gap-1.5 rounded-control border border-border-strong bg-surface px-3 text-xs font-medium text-foreground transition-colors hover:bg-surface-muted"><Copy className="h-3.5 w-3.5" />{t("order.copyAll")}</button><button type="button" onClick={() => void exportKeys()} className="inline-flex h-9 items-center gap-1.5 rounded-control border border-border-strong bg-surface px-3 text-xs font-medium text-foreground transition-colors hover:bg-surface-muted"><Download className="h-3.5 w-3.5" />{t("order.exportKeys")}</button></div>}
          <div className="space-y-3">{deliveredGroups.map((group, index) => <DeliveryGroup key={`${group.product_no}-${index}`} group={group} index={index} onCopy={copy} copyLabel={t("order.copyKey")} countLabel={t("order.deliveredCount").replace("{count}", String(group.delivery_items.length))} />)}</div>
          <div className="space-y-3">{deliveredManualItems.map((item, index) => <ManualDeliveryItem key={item.id} item={item} index={index} statusLabel={t("status.DELIVERED")} emptyLabel={t("order.manualDeliveryEmpty")} contentTitle={t("order.manualDeliveryContentTitle")} />)}</div>
          {!hasCards && waitingManualItems.length === 0 && deliveredManualItems.length === 0 && pendingAutomaticItems.length === 0 && <p className="text-sm text-muted-foreground">{t("order.deliveryEmpty")}</p>}
        </div>
      )}
    </section>

    <section className="surface space-y-3 p-5 sm:p-7">
      <h2 className="flex items-center gap-2 text-sm font-semibold text-foreground"><KeyRound className="h-4 w-4 text-primary" />{t("order.orderItems")}</h2>
      <div className="divide-y divide-border-subtle">{view.order.items.map(item => <div key={item.id} className="flex flex-wrap items-start justify-between gap-3 py-3 first:pt-0 last:pb-0"><div><p className="text-sm font-medium text-foreground">{item.product_title}{item.spec_name ? ` · ${item.spec_name}` : ""}</p><p className="mt-1 text-xs text-muted-foreground">×{item.quantity} · {isManualFulfillmentItem(item) ? t("product.deliveryManual") : t("product.deliveryAuto")}</p><p className="mt-1 text-xs text-muted-foreground">{itemFulfillmentLabel(item, t)}</p></div><span className="text-sm font-semibold text-foreground">{formatYuan(item.subtotal)}</span></div>)}</div>
    </section>

    <div className="flex flex-wrap items-center justify-center gap-3">
      <button type="button" onClick={() => void manualRefresh()} disabled={refreshCooldown > 0 || refreshing} className="inline-flex h-11 items-center gap-2 rounded-control border border-border-strong bg-surface px-5 text-sm font-medium text-foreground transition-colors hover:bg-surface-muted disabled:opacity-50"><RefreshCw className={cn("h-4 w-4", refreshing && "animate-spin")} />{refreshing ? t("order.refreshing") : refreshCooldown > 0 ? `${refreshCooldown}${t("payment.refreshLimit")}` : t("order.refresh")}</button>
      <ContactSupport title={t("order.needHelp")} email={config?.contact_email} items={config?.contact_items} emailLabel={t("payment.contactSupport")} />
    </div>
  </div>
}

function ManualDeliveryItem({
  item,
  index,
  statusLabel,
  emptyLabel,
  contentTitle,
}: {
  item: OrderItemDetail
  index: number
  statusLabel: string
  emptyLabel: string
  contentTitle: string
}) {
  return (
    <article className="overflow-hidden rounded-card border border-primary/20 bg-primary/5">
      <div className="border-b border-primary/10 px-4 py-3">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <p className="text-sm font-semibold text-foreground">{index + 1}. {item.product_title}</p>
          <span className="rounded-full bg-emerald-500/10 px-2.5 py-1 text-xs font-medium text-emerald-700 dark:text-emerald-300">{statusLabel}</span>
        </div>
        <p className="mt-0.5 text-xs text-muted-foreground">
          {item.spec_name || ""}{item.spec_name ? " · " : ""}×{item.quantity}
        </p>
      </div>
      <div className="space-y-2 p-4">
        <h3 className="text-xs font-semibold uppercase tracking-[0.08em] text-primary">{contentTitle}</h3>
        {item.customer_delivery_content?.trim()
          ? <MarkdownContent value={item.customer_delivery_content} />
          : <p className="text-sm text-muted-foreground">{emptyLabel}</p>}
      </div>
    </article>
  )
}

function itemFulfillmentLabel(
  item: OrderItemDetail,
  t: (key: TranslationKey) => string,
) {
  if (item.fulfillment_status === "WAITING_MANUAL") return t("status.WAITING_MANUAL")
  if (item.fulfillment_status === "DELIVERED") return t("status.DELIVERED")
  if (item.fulfillment_status === "CANCELLED") return t("order.itemCancelled")
  if (item.fulfillment_status === "PENDING") return t("status.PENDING")
  return isManualFulfillmentItem(item) ? t("status.WAITING_MANUAL") : t("status.WAITING_AUTO")
}

function DeliveryGroup({ group, index, onCopy, copyLabel, countLabel }: { group: OrderDeliveryGroup; index: number; onCopy: (value: string) => void; copyLabel: string; countLabel: string }) {
  return <article className="overflow-hidden rounded-card border border-border-subtle bg-surface"><div className="flex flex-wrap items-center justify-between gap-2 border-b border-border-subtle bg-surface-muted/70 px-4 py-3"><div><p className="text-sm font-semibold text-foreground">{index + 1}. {group.product_title}</p>{group.spec_name && <p className="mt-0.5 text-xs text-muted-foreground">{group.spec_name}</p>}</div><span className="text-xs text-muted-foreground">{countLabel}</span></div><div className="space-y-2 p-4">{group.delivery_items.map((item, itemIndex) => { const value = item.content_parts.join("\t"); return <div key={`${itemIndex}-${value}`} className="flex items-start gap-2 rounded-control border border-border-subtle bg-surface-muted/40 p-3"><code className="min-w-0 flex-1 whitespace-pre-wrap break-all text-xs leading-5 text-foreground">{value}</code><button type="button" onClick={() => onCopy(value)} className="inline-flex shrink-0 items-center gap-1 rounded-control px-2 py-1 text-xs text-muted-foreground transition-colors hover:bg-surface hover:text-foreground"><Copy className="h-3.5 w-3.5" />{copyLabel}</button></div> })}</div></article>
}

function allCardText(groups: OrderDeliveryGroup[]): string {
  return groups.flatMap(group => group.delivery_items.map(item => item.content_parts.join("\t"))).join("\n")
}
