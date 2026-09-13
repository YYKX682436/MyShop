"use client"

import { use, useCallback, useEffect, useMemo, useRef, useState } from "react"
import type { ReactNode } from "react"
import Image from "next/image"
import Link from "next/link"
import { ChevronDown, Clock, Copy, Download, KeyRound, Package, RefreshCw } from "lucide-react"
import { toast } from "sonner"
import { useSearchParams } from "next/navigation"
import { useLocale, useSiteConfig } from "@/lib/context"
import { getApiErrorMessage, orderApi, productApi } from "@/services/api"
import type { OrderDeliveryGroup, OrderItemDetail, OrderView, ProductDetail } from "@/types"
import { cn, detectPaymentDevice, formatDateTime, formatYuan } from "@/lib/utils"
import { formatCardContent, formatCardContents } from "@/lib/card-display"
import { clearPaymentAction, getPaymentActionEnvironment, readPaymentAction, resolvePaymentEntry, savePaymentAction, shouldSeedPaymentAction } from "@/lib/payment-entry"
import { applyPaymentStatus, applyPaymentStatusError, createPaymentPageActionState, type PaymentStatusRequestKind } from "@/lib/payment-page-state"
import { PaymentActionRenderer } from "@/components/shared/payment-action-renderer"
import { PaymentIcon, getPaymentBrandColor, getPaymentLabel, getPaymentScanHint } from "@/components/shared/payment-icon"
import { OrderStatusBadge } from "@/components/shared/order-status-badge"
import { AfterSaleHistoryCard } from "@/components/shared/after-sale-history-card"
import { AfterSaleRequestDialog } from "@/components/shared/after-sale-request-dialog"
import { ContactSupport } from "@/components/shared/contact-methods"
import { CardContentParts } from "@/components/shared/card-content-parts"
import { MarkdownContent } from "@/components/shared/markdown-content"
import { ProductDescription } from "@/components/shared/product-description"
import { isManualFulfillmentItem } from "@/lib/order-status"
import { getOrderStatusPollInterval, shouldPollOrderStatus } from "@/lib/order-polling"

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
  const [productDetails, setProductDetails] = useState<Record<string, ProductDetail>>({})
  const [timeLeft, setTimeLeft] = useState(-1)
  const [refreshCooldown, setRefreshCooldown] = useState(0)
  const [refreshing, setRefreshing] = useState(false)
  const [retrying, setRetrying] = useState(false)
  const [afterSaleOpen, setAfterSaleOpen] = useState(false)
  const [checkingReturn, setCheckingReturn] = useState(paymentReturn)
  const [returnTimedOut, setReturnTimedOut] = useState(false)
  const [paymentState, setPaymentState] = useState(() => createPaymentPageActionState(initialAction))
  const [pageVisible, setPageVisible] = useState(() => typeof document === "undefined" || document.visibilityState === "visible")
  const requestInFlight = useRef(false)
  const viewRef = useRef<OrderView | null>(null)
  const requestedProductIds = useRef(new Set<string>())
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

  const orderItems = useMemo(() => view?.order.items ?? [], [view?.order.items])
  const productIds = useMemo(
    () => Array.from(new Set(orderItems.map(item => item.product_id).filter((id): id is string => Boolean(id)))),
    [orderItems],
  )
  const productIdKey = productIds.join("|")

  useEffect(() => {
    if (!productIdKey) return
    const idsToFetch = productIdKey.split("|").filter(productId => !requestedProductIds.current.has(productId))
    if (idsToFetch.length === 0) return

    idsToFetch.forEach(productId => requestedProductIds.current.add(productId))
    let cancelled = false
    void Promise.all(idsToFetch.map(async productId => {
      try {
        return { productId, product: await productApi.getDetail(productId) }
      } catch {
        // The order snapshot remains authoritative when a product was removed,
        // unpublished, or its public detail request temporarily failed.
        return null
      }
    })).then(results => {
      if (cancelled) return
      setProductDetails(current => {
        const next = { ...current }
        for (const result of results) {
          if (result) next[result.productId] = result.product
        }
        return next
      })
    })

    return () => { cancelled = true }
  }, [productIdKey])

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
  const deliveredGroupsWithNumbers = withDeliveryStartNumbers(deliveredGroups)
  const deliveredCardCount = deliveredGroups.reduce((count, group) => count + group.delivery_items.length, 0)
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
  const itemsWithCustomerInputs = orderItems.filter(item => item.customer_inputs.length > 0)
  const hasCards = deliveredCardCount > 0
  const purchasedProducts = productIds
    .map(productId => productDetails[productId])
    .filter((product): product is ProductDetail => Boolean(product))
  const paymentInfoCode = view?.order.order_type === "ADMIN_PICKUP"
    ? "ADMIN_PICKUP"
    : view?.payment?.payment_type || view?.order.payment_method || null
  const paymentInfoLabel = paymentInfoCode ? getPaymentLabel(paymentInfoCode, t) : null
  const refreshButtonLabel = refreshing
    ? t("order.refreshing")
    : refreshCooldown > 0
      ? `${refreshCooldown}${t("payment.refreshLimit")}`
      : t("order.refresh")
  const retryButton = <button type="button" onClick={() => void retryPayment()} disabled={retrying} className="inline-flex h-11 items-center gap-2 rounded-control bg-primary px-5 text-sm font-medium text-primary-foreground shadow-sm transition-[background-color,transform] duration-180 hover:bg-primary/90 active:scale-[.99] disabled:opacity-50"><RefreshCw className={cn("h-4 w-4", retrying && "animate-spin")} />{t("order.retryPayment")}</button>
  const retryStatusButton = <button type="button" onClick={() => void manualRefresh()} disabled={refreshing} className="inline-flex h-10 items-center gap-2 rounded-control border border-border-strong bg-surface px-4 text-sm font-medium text-foreground transition-colors hover:bg-surface-muted disabled:opacity-50"><RefreshCw className={cn("h-4 w-4", refreshing && "animate-spin")} />{t("payment.retryLoad")}</button>

  if (!view) {
    return <div className="surface mx-auto flex max-w-lg flex-col items-center gap-3 p-8 text-center" role="status"><RefreshCw className="h-8 w-8 animate-spin text-primary" /><p className="text-sm text-muted-foreground">{paymentState.errorMessage || t("common.loading")}</p>{paymentState.errorMessage && retryStatusButton}</div>
  }

  return <div className="mx-auto flex w-full max-w-4xl flex-col gap-5 py-2 sm:gap-6">
    <header className="flex flex-wrap items-center justify-between gap-3">
      <div className="min-w-0">
        <p className="mb-1 text-xs font-semibold uppercase tracking-[0.16em] text-primary">MyShop</p>
        <h1 className="text-2xl font-semibold tracking-tight text-foreground">{t("order.lifecycleTitle")}</h1>
      </div>
      <div className="flex shrink-0 items-center gap-2">
        <OrderStatusBadge status={status} items={orderItems} />
        {status !== "REFUNDED" && status !== "EXPIRED" && (
          <button
            type="button"
            onClick={() => void manualRefresh()}
            disabled={refreshCooldown > 0 || refreshing}
            aria-label={refreshButtonLabel}
            title={refreshButtonLabel}
            className="inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-control border border-border-strong bg-surface text-foreground transition-colors hover:bg-surface-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50"
          >
            <RefreshCw className={cn("h-4 w-4", refreshing && "animate-spin")} aria-hidden="true" />
          </button>
        )}
      </div>
    </header>

    <section className="surface space-y-4 p-4 sm:p-5" aria-labelledby="order-purchase-summary-title">
      <div className="flex flex-wrap items-end justify-between gap-2">
        <div>
          <h2 id="order-purchase-summary-title" className="text-base font-semibold text-foreground">{t("order.purchaseSummary")}</h2>
          <p className="mt-1 text-xs text-muted-foreground">{t("order.itemsCount").replace("{count}", String(orderItems.length))}</p>
        </div>
        {(status === "PAID" || status === "DELIVERED") && (
          <button
            type="button"
            onClick={() => setAfterSaleOpen(true)}
            className="inline-flex min-h-10 items-center justify-center rounded-control border border-primary/30 bg-primary/5 px-3 text-xs font-medium text-primary transition-colors hover:bg-primary/10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
          >
            {t("order.applyAfterSales")}
          </button>
        )}
      </div>
      <div className="divide-y divide-border-subtle">
        {orderItems.map(item => {
          return (
            <div key={item.id} className="flex min-w-0 items-center gap-3 py-3 first:pt-0 last:pb-0">
              <Link href={`/product/${item.product_id}`} className="shrink-0 rounded-control focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2" aria-label={item.product_title}>
                <ProductCover coverUrl={item.product_cover_url} title={item.product_title} />
              </Link>
              <div className="min-w-0 flex-1">
                <Link href={`/product/${item.product_id}`} className="block break-words text-sm font-semibold text-foreground transition-colors hover:text-primary">
                  {item.product_title}
                </Link>
                {item.spec_name && <p className="mt-0.5 break-words text-xs text-muted-foreground">{item.spec_name}</p>}
                <div className="mt-1 flex flex-wrap gap-x-3 gap-y-1 text-xs text-muted-foreground">
                  <span>{t("order.quantity")}: {item.quantity}</span>
                  <span>{t("order.unitPrice")}: {formatYuan(item.unit_price)}</span>
                </div>
              </div>
              <span className="shrink-0 text-right text-sm font-semibold text-foreground">{formatYuan(item.subtotal)}</span>
            </div>
          )
        })}
      </div>
      <div className="flex items-center justify-between gap-3 border-t border-border-subtle pt-3">
        <span className="text-xs text-muted-foreground">{t("order.paidAmount")}</span>
        <span className="text-base font-semibold tracking-tight text-foreground">{formatYuan(view.order.actual_amount ?? view.order.total_amount)}</span>
      </div>
    </section>

    <details className="surface overflow-hidden">
      <summary aria-controls="order-information-panel" className="group flex min-h-12 cursor-pointer list-none items-center gap-3 px-4 py-3 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset [&::-webkit-details-marker]:hidden">
        <span className="min-w-0 flex-1 truncate text-sm font-semibold text-foreground">{t("order.orderInformation")}</span>
        <span className="min-w-0 max-w-[55%] truncate font-mono text-xs text-muted-foreground">{view.order.order_no}</span>
        <ChevronDown className="size-4 shrink-0 text-muted-foreground transition-transform duration-200 group-open:rotate-180" aria-hidden="true" />
      </summary>
      <div id="order-information-panel" className="border-t border-border-subtle px-4 py-3 sm:px-5 sm:py-4">
        <dl className="grid min-w-0 gap-x-6 gap-y-3 sm:grid-cols-2">
          <OrderInfoField label={t("order.orderNo")}><span className="break-all font-mono text-xs font-medium">{view.order.order_no}</span></OrderInfoField>
          <OrderInfoField label={t("order.createdAt")}>{formatDateTime(view.order.created_at, locale)}</OrderInfoField>
          {view.order.paid_at && <OrderInfoField label={t("order.paidAt")}>{formatDateTime(view.order.paid_at, locale)}</OrderInfoField>}
          {view.order.delivered_at && <OrderInfoField label={t("order.deliveredAt")}>{formatDateTime(view.order.delivered_at, locale)}</OrderInfoField>}
          {paymentInfoLabel && <OrderInfoField label={t("order.paymentMethod")}>
            <span className="flex min-w-0 items-center gap-2">
              <PaymentIcon method={paymentInfoCode!} className="h-5 w-5" />
              <span className="min-w-0 break-words">{paymentInfoLabel}{view.order.payment_channel_code && ` (${view.order.payment_channel_code})`}</span>
            </span>
          </OrderInfoField>}
        </dl>
      </div>
    </details>

    <AfterSaleHistoryCard request={view.order.after_sale} history={view.order.after_sale_history ?? view.after_sale_history} />

    {itemsWithCustomerInputs.length > 0 && (
      <details className="surface overflow-hidden">
        <summary aria-controls="customer-inputs-panel" className="group flex min-h-12 cursor-pointer list-none items-center gap-3 px-4 py-3 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset [&::-webkit-details-marker]:hidden">
          <span className="min-w-0 flex-1 truncate text-sm font-semibold text-foreground">{t("order.customerInputs")}</span>
          <ChevronDown className="size-4 shrink-0 text-muted-foreground transition-transform duration-200 group-open:rotate-180" aria-hidden="true" />
        </summary>
        <div id="customer-inputs-panel" className="min-w-0 space-y-4 border-t border-border-subtle px-4 py-3 sm:px-5 sm:py-4">
          {itemsWithCustomerInputs.map(item => (
            <article key={item.id} className="min-w-0 overflow-hidden rounded-card border border-border-subtle bg-surface-muted/40 p-3 sm:p-4">
              <div className="min-w-0">
                <p className="break-words text-sm font-semibold text-foreground">{item.product_title}</p>
                {item.spec_name && <p className="mt-0.5 break-words text-xs text-muted-foreground">{item.spec_name}</p>}
              </div>
              <dl className="mt-3 min-w-0 space-y-3">
                {item.customer_inputs.map(input => (
                  <div key={`${item.id}-${input.id}`} className="grid min-w-0 gap-1 sm:grid-cols-[minmax(0,0.75fr)_minmax(0,1.25fr)] sm:gap-3">
                    <dt className="min-w-0 break-words text-xs text-muted-foreground">{input.label}</dt>
                    <dd className="min-w-0 break-words whitespace-pre-wrap text-sm text-foreground [overflow-wrap:anywhere]">{input.value}</dd>
                  </div>
                ))}
              </dl>
            </article>
          ))}
        </div>
      </details>
    )}

    {status === "PENDING" && (
      <section className="surface space-y-4 p-4 sm:p-5" aria-labelledby="order-payment-title">
        <h2 id="order-payment-title" className="text-base font-semibold text-foreground">{t("payment.title")}</h2>
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
      </section>
    )}

    {(status === "PAID" || status === "DELIVERED" || status === "AFTER_SALE" || status === "REFUNDED") && (
      <section className="surface-raised space-y-4 p-4 sm:p-5" aria-labelledby="order-delivery-result-title">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0">
            <h2 id="order-delivery-result-title" className="flex items-center gap-2 text-base font-semibold text-foreground"><KeyRound className="h-4 w-4 text-primary" />{t("order.deliveryResult")}</h2>
            <p className="mt-1 text-xs text-muted-foreground">{t("order.deliveredCount").replace("{count}", String(deliveredCardCount))}</p>
          </div>
          {hasCards && <div className="flex w-full flex-wrap gap-2 sm:w-auto sm:justify-end">
            <button type="button" onClick={() => copy(allCardText(deliveredGroups))} className="inline-flex min-h-10 flex-1 items-center justify-center gap-1.5 rounded-control border border-border-strong bg-surface px-3 text-xs font-medium text-foreground transition-colors hover:bg-surface-muted sm:flex-none"><Copy className="h-3.5 w-3.5" />{t("order.copyAll")}</button>
            <button type="button" onClick={() => void exportKeys()} className="inline-flex min-h-10 flex-1 items-center justify-center gap-1.5 rounded-control border border-border-strong bg-surface px-3 text-xs font-medium text-foreground transition-colors hover:bg-surface-muted sm:flex-none"><Download className="h-3.5 w-3.5" />{t("order.exportKeys")}</button>
          </div>}
        </div>

        {status === "REFUNDED" && (
          <div className="rounded-control border border-violet-300/50 bg-violet-50/70 px-4 py-3 text-sm dark:border-violet-500/30 dark:bg-violet-950/20" role="status">
            <p className="font-medium text-foreground">{t("order.refunded")}</p>
            <p className="mt-1 text-muted-foreground">{t("order.refundedDescription")}</p>
            <div className="mt-3 grid gap-2 text-xs text-muted-foreground sm:grid-cols-3">
              <span>{t("order.refundAmount")}: <strong className="font-semibold text-foreground">{formatYuan(view.order.actual_amount ?? view.order.total_amount)}</strong></span>
              <span>{t("order.refundDestination")}: <strong className="font-semibold text-foreground">{t("order.refundToBalance")}</strong></span>
              {view.order.refunded_at && <span>{t("order.refundedAt")}: <strong className="font-semibold text-foreground">{formatDateTime(view.order.refunded_at, locale)}</strong></span>}
            </div>
            {(view.order.reclaimed_card_key_count ?? 0) > 0
              ? <p className="mt-3 rounded-control border border-emerald-300/50 bg-emerald-50 px-3 py-2 text-xs text-emerald-800 dark:border-emerald-500/20 dark:bg-emerald-950/20 dark:text-emerald-200">{t("order.reclaimedCardKeys").replace("{count}", String(view.order.reclaimed_card_key_count))}</p>
              : (hasCards || deliveredManualItems.length > 0) && <p className="mt-3 rounded-control border border-amber-300/50 bg-amber-50 px-3 py-2 text-xs text-amber-800 dark:border-amber-500/30 dark:bg-amber-950/20 dark:text-amber-200">{t("order.deliveredRefundNotice")}</p>}
          </div>
        )}

        {status === "AFTER_SALE" && <div className="rounded-control border border-orange-300/50 bg-orange-50 px-4 py-3 text-sm text-orange-800 dark:border-orange-500/30 dark:bg-orange-950/20 dark:text-orange-200"><p className="font-medium">{t("order.afterSaleDeliveryPaused")}</p></div>}
        {status !== "AFTER_SALE" && waitingManualItems.length > 0 && <div className="rounded-control border border-amber-300/50 bg-amber-50 px-4 py-3 text-sm text-amber-800 dark:border-amber-500/30 dark:bg-amber-950/20 dark:text-amber-200"><p className="font-medium">{t("order.deliveryWaitingManual")}</p></div>}
        {status !== "AFTER_SALE" && pendingAutomaticItems.length > 0 && <div className="rounded-control bg-surface-muted px-4 py-3 text-sm text-muted-foreground">{t("order.deliveryPreparing")}</div>}
        {hasCards && <div className="space-y-3">{deliveredGroupsWithNumbers.map(({ group, startNumber }, index) => <DeliveryGroup key={`${group.product_no}-${group.spec_name || "no-spec"}-${index}`} group={group} startNumber={startNumber} onCopy={copy} copyLabel={t("order.copyKey")} countLabel={t("order.deliveredCount").replace("{count}", String(group.delivery_items.length))} />)}</div>}
        {deliveredManualItems.length > 0 && <div className="space-y-3">{deliveredManualItems.map(item => <ManualDeliveryItem key={item.id} item={item} statusLabel={t("status.DELIVERED")} emptyLabel={t("order.manualDeliveryEmpty")} contentTitle={t("order.manualDeliveryContentTitle")} />)}</div>}
        {status !== "AFTER_SALE" && status !== "REFUNDED" && !hasCards && waitingManualItems.length === 0 && deliveredManualItems.length === 0 && pendingAutomaticItems.length === 0 && <p className="text-sm text-muted-foreground">{t("order.deliveryEmpty")}</p>}
      </section>
    )}

    {purchasedProducts.map(product => <ProductDescription key={product.id} product={product} />)}

    <ContactSupport title={t("order.needHelp")} email={config?.contact_email} items={config?.contact_items} emailLabel={t("payment.contactSupport")} />

    <AfterSaleRequestDialog
      open={afterSaleOpen}
      orderId={orderId}
      orderNo={view.order.order_no}
      productSummary={orderItems.map(item => `${item.product_title}${item.spec_name ? ` · ${item.spec_name}` : ""}`).join("、")}
      onOpenChange={setAfterSaleOpen}
      onSubmitted={() => { void fetchView("manual", true) }}
    />
  </div>
}

function ProductCover({ coverUrl, title }: { coverUrl: string | null; title: string }) {
  const [imageFailed, setImageFailed] = useState(false)
  const normalizedCoverUrl = coverUrl?.trim()

  return (
    <div className="relative flex size-14 shrink-0 items-center justify-center overflow-hidden rounded-control border border-border-subtle bg-surface-muted">
      {normalizedCoverUrl && !imageFailed ? (
        <Image src={normalizedCoverUrl} alt="" fill unoptimized sizes="56px" className="object-contain" onError={() => setImageFailed(true)} />
      ) : (
        <Package className="size-6 text-muted-foreground/35" aria-hidden="true" />
      )}
      <span className="sr-only">{title}</span>
    </div>
  )
}

function OrderInfoField({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="min-w-0 space-y-1">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="min-w-0 break-words text-sm text-foreground">{children}</dd>
    </div>
  )
}

function ManualDeliveryItem({
  item,
  statusLabel,
  emptyLabel,
  contentTitle,
}: {
  item: OrderItemDetail
  statusLabel: string
  emptyLabel: string
  contentTitle: string
}) {
  return (
    <article className="overflow-hidden rounded-card border border-primary/20 bg-primary/5">
      <div className="border-b border-primary/10 px-4 py-3">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <p className="break-words text-sm font-semibold text-foreground">{item.product_title}</p>
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

function withDeliveryStartNumbers(groups: ReadonlyArray<OrderDeliveryGroup>) {
  let nextNumber = 1
  return groups.map(group => {
    const numberedGroup = { group, startNumber: nextNumber }
    nextNumber += group.delivery_items.length
    return numberedGroup
  })
}

function DeliveryGroup({ group, startNumber, onCopy, copyLabel, countLabel }: { group: OrderDeliveryGroup; startNumber: number; onCopy: (value: string) => void; copyLabel: string; countLabel: string }) {
  return (
    <article className="overflow-hidden rounded-card border border-border-subtle bg-surface">
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-border-subtle bg-surface-muted/70 px-4 py-3">
        <div className="min-w-0">
          <p className="break-words text-sm font-semibold text-foreground">{group.product_title}</p>
          {group.spec_name && <p className="mt-0.5 break-words text-xs text-muted-foreground">{group.spec_name}</p>}
        </div>
        <span className="shrink-0 text-xs text-muted-foreground">{countLabel}</span>
      </div>
      <ol className="list-none space-y-2 p-3 sm:p-4" aria-label={group.product_title}>
        {group.delivery_items.map((item, itemIndex) => {
          const value = formatCardContent(item.content_parts)
          return (
            <li key={`${startNumber + itemIndex}`} className="flex min-w-0 flex-col gap-3 rounded-control border border-border-subtle bg-surface-muted/40 p-3 sm:flex-row sm:items-start sm:gap-4">
              <div className="flex min-w-0 flex-1 items-start gap-3">
                <span className="flex size-7 shrink-0 items-center justify-center rounded-full bg-primary/10 text-xs font-semibold tabular-nums text-primary" aria-hidden="true">{startNumber + itemIndex}</span>
                <CardContentParts parts={item.content_parts} onCopy={onCopy} showPartCopy={false} showCopyAll={false} className="flex-1" />
              </div>
              <button type="button" onClick={() => onCopy(value)} className="inline-flex min-h-10 w-full shrink-0 items-center justify-center gap-1 rounded-control border border-border-subtle px-3 py-2 text-xs font-medium text-muted-foreground transition-colors hover:bg-surface hover:text-foreground sm:w-auto sm:self-start"><Copy className="h-3.5 w-3.5" />{copyLabel}</button>
            </li>
          )
        })}
      </ol>
    </article>
  )
}

function allCardText(groups: ReadonlyArray<OrderDeliveryGroup>): string {
  return formatCardContents(groups.flatMap(group => group.delivery_items))
}
