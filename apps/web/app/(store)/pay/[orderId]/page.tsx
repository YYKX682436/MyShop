"use client"

import { use, useCallback, useEffect, useMemo, useRef, useState } from "react"
import Link from "next/link"
import { useRouter, useSearchParams } from "next/navigation"
import { ArrowRight, Clock, Copy, RefreshCw, XCircle } from "lucide-react"
import { toast } from "sonner"
import { useLocale, useSiteConfig } from "@/lib/context"
import { getApiErrorMessage, getOrderLookupToken, orderApi } from "@/services/api"
import type { OrderStatus } from "@/types"
import { cn, detectPaymentDevice } from "@/lib/utils"
import { clearPaymentAction, getPaymentActionEnvironment, readPaymentAction, resolvePaymentEntry, savePaymentAction, shouldSeedPaymentAction } from "@/lib/payment-entry"
import { applyPaymentStatus, applyPaymentStatusError, createPaymentPageActionState, type PaymentStatusRequestKind } from "@/lib/payment-page-state"
import { PaymentActionRenderer } from "@/components/shared/payment-action-renderer"
import { PaymentIcon, getPaymentBrandColor, getPaymentLabel, getPaymentScanHint } from "@/components/shared/payment-icon"
import { ContactSupport } from "@/components/shared/contact-methods"

const POLL_INTERVAL = 3000
const MANUAL_REFRESH_COOLDOWN = 10

export default function PaymentPage({ params }: { params: Promise<{ orderId: string }> }) {
  const { orderId } = use(params)
  const { t } = useLocale()
  const { config } = useSiteConfig()
  const searchParams = useSearchParams()
  const router = useRouter()
  const initialOrderNo = searchParams.get("orderNo") || ""
  const initialAction = useMemo(() => readPaymentAction(orderId), [orderId])
  const [status, setStatus] = useState<OrderStatus>("PENDING")
  const [orderNo, setOrderNo] = useState(initialOrderNo)
  const [timeLeft, setTimeLeft] = useState(-1)
  const [refreshCooldown, setRefreshCooldown] = useState(0)
  const [refreshing, setRefreshing] = useState(false)
  const [retrying, setRetrying] = useState(false)
  const [paymentState, setPaymentState] = useState(() => createPaymentPageActionState(initialAction))
  const orderNoRef = useRef(initialOrderNo)
  const lookupTokenRef = useRef("")
  const method = searchParams.get("method") || "alipay"
  const methodName = getPaymentLabel(method, t)
  const brandColor = getPaymentBrandColor(method)
  const hasCountdown = timeLeft >= 0

  const fetchStatus = useCallback(async (requestKind: PaymentStatusRequestKind): Promise<string | null> => {
    try {
      const requestedOrderNo = orderNoRef.current
      const token = lookupTokenRef.current || (requestedOrderNo ? getOrderLookupToken(requestedOrderNo) : undefined)
      const result = await orderApi.getStatus(orderId, token, requestedOrderNo || undefined)
      const returnedOrderNo = String(result.order_no)
      orderNoRef.current = returnedOrderNo
      setOrderNo(returnedOrderNo)
      if (token && !lookupTokenRef.current) {
        lookupTokenRef.current = token
      }
      setTimeLeft(result.remaining_seconds ?? 0)
      setPaymentState(current => applyPaymentStatus(current, result.status, result.next_action))
      if (result.status !== "PENDING") {
        clearPaymentAction(orderId)
      } else if (result.next_action
        && shouldSeedPaymentAction(resolvePaymentEntry(result.next_action, getPaymentActionEnvironment()))) {
        savePaymentAction(orderId, result.next_action)
      } else {
        clearPaymentAction(orderId)
      }
      setStatus(result.status)
      return null
    } catch (error) {
      const message = getApiErrorMessage(error, t)
      setPaymentState(current => applyPaymentStatusError(current, message, requestKind))
      return message
    }
  }, [orderId, t])

  useEffect(() => {
    void fetchStatus("initial")
  }, [fetchStatus])

  useEffect(() => {
    if (status !== "PENDING") return
    const timer = window.setInterval(() => { void fetchStatus("poll") }, POLL_INTERVAL)
    return () => window.clearInterval(timer)
  }, [status, fetchStatus])

  useEffect(() => {
    if (status !== "PENDING" || !hasCountdown) return
    const timer = window.setInterval(() => setTimeLeft(value => Math.max(0, value - 1)), 1000)
    return () => window.clearInterval(timer)
  }, [status, hasCountdown])

  useEffect(() => {
    if (refreshCooldown <= 0) return
    const timer = window.setInterval(() => setRefreshCooldown(value => Math.max(0, value - 1)), 1000)
    return () => window.clearInterval(timer)
  }, [refreshCooldown])

  useEffect(() => {
    if (status !== "PAID" && status !== "DELIVERED") return
    clearPaymentAction(orderId)
    router.replace(`/order/query?orderNo=${encodeURIComponent(orderNo)}&paymentReturn=1`)
  }, [orderId, orderNo, router, status])

  const retry = async () => {
    if (retrying) return
    setRetrying(true)
    try {
      const result = await orderApi.repay(orderId, orderNo, detectPaymentDevice(), lookupTokenRef.current || undefined)
      const returnedOrderNo = String(result.order_no)
      orderNoRef.current = returnedOrderNo
      setOrderNo(returnedOrderNo)
      const action = result.next_action ?? null
      setPaymentState({ phase: "ready", nextAction: action, errorMessage: null })
      if (action && shouldSeedPaymentAction(resolvePaymentEntry(action, getPaymentActionEnvironment()))) {
        savePaymentAction(orderId, action)
      } else {
        clearPaymentAction(orderId)
      }
      setStatus(result.status === "PAID" ? "PAID" : "PENDING")
      toast.success(t("payment.statusRefreshed"))
    } catch (error) {
      toast.error(getApiErrorMessage(error, t))
    } finally {
      setRetrying(false)
    }
  }

  const manualRefresh = async () => {
    if (refreshCooldown > 0 || refreshing) return
    setRefreshing(true)
    const errorMessage = await fetchStatus("manual")
    if (errorMessage) {
      toast.error(errorMessage)
    } else {
      toast.info(t("payment.statusRefreshed"))
    }
    setRefreshing(false)
    setRefreshCooldown(MANUAL_REFRESH_COOLDOWN)
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

  const formatTime = (seconds: number) => `${String(Math.floor(seconds / 60)).padStart(2, "0")}:${String(seconds % 60).padStart(2, "0")}`
  const retryButton = <button type="button" onClick={() => void retry()} disabled={retrying} className="inline-flex h-11 items-center gap-2 rounded-control bg-primary px-5 text-sm font-medium text-primary-foreground shadow-sm transition-[background-color,transform] duration-180 hover:bg-primary/90 active:scale-[.99] disabled:opacity-50"><RefreshCw className={cn("h-4 w-4", retrying && "animate-spin")} />{t("payment.retryPay")}</button>
  const retryStatusButton = <button type="button" onClick={() => void manualRefresh()} disabled={refreshing} className="inline-flex h-10 items-center gap-2 rounded-control border border-border-strong bg-surface px-4 text-sm font-medium text-foreground transition-colors hover:bg-surface-muted disabled:opacity-50"><RefreshCw className={cn("h-4 w-4", refreshing && "animate-spin")} />{t("payment.retryLoad")}</button>

  if (status === "EXPIRED") return <div className="surface mx-auto flex max-w-md flex-col items-center p-8 text-center"><XCircle className="mb-4 h-12 w-12 text-muted-foreground" /><h1 className="mb-2 text-xl font-semibold">{t("payment.expired")}</h1><p className="mb-6 text-sm text-muted-foreground">{t("payment.expiredDesc")}</p><Link href="/" className="inline-flex h-11 items-center rounded-control bg-primary px-5 text-sm font-medium text-primary-foreground shadow-sm transition-[background-color,transform] duration-180 hover:bg-primary/90 active:scale-[.99]">{t("payment.reorder")}</Link></div>

  return <div className="mx-auto flex max-w-lg flex-col items-center gap-6 py-4">
    <div className="surface flex w-full flex-col items-center gap-5 p-6 sm:p-8">
      <h1 className="text-xl font-semibold tracking-tight">{t("payment.waiting")}</h1>
      <div className="flex items-center gap-2 text-muted-foreground"><Clock className="h-4 w-4" /><span>{t("payment.remaining")}</span><span className={cn("font-mono text-lg font-bold", timeLeft >= 0 && timeLeft < 120 && "text-destructive")}>{timeLeft < 0 ? "--:--" : formatTime(timeLeft)}</span></div>
      {paymentState.errorMessage && paymentState.nextAction && (
        <div className="flex w-full items-center justify-between gap-3 rounded-control border border-destructive/30 bg-destructive/5 px-3 py-2 text-xs text-destructive" role="alert">
          <span>{paymentState.errorMessage}</span>
          {retryStatusButton}
        </div>
      )}
      <div className="flex w-72 flex-col items-center gap-4 rounded-surface px-6 pb-8 pt-6" style={{ backgroundColor: brandColor || "#374151" }}>
        <div className="flex items-center gap-2.5"><PaymentIcon method={method} className="h-10 w-10" variant="plain" /><span className="text-xl font-bold text-white">{methodName}</span></div>
        <p className="text-sm font-medium text-white/90">{getPaymentScanHint(method, t)}</p>
        <div className="flex min-h-52 w-52 items-center justify-center rounded-xl bg-white p-3">
          <PaymentActionRenderer
            action={paymentState.nextAction}
            loadState={paymentState.phase}
            statusError={paymentState.errorMessage}
            retryAction={retryButton}
            retryStatusAction={retryStatusButton}
          />
        </div>
      </div>
      <div className="flex w-full items-center justify-between border-t border-border-subtle pt-4 text-sm"><span className="text-muted-foreground">{t("payment.orderNo")}</span><button type="button" onClick={() => copy(orderNo)} disabled={!orderNo} className="flex items-center gap-1 rounded-control px-2 py-1 font-mono text-xs transition-colors hover:bg-surface-muted disabled:opacity-50"><span>{orderNo || "加载中"}</span><Copy className="h-3.5 w-3.5" /></button></div>
    </div>
    <button type="button" onClick={() => void manualRefresh()} disabled={refreshCooldown > 0 || refreshing} className="inline-flex h-11 items-center gap-2 rounded-control border border-border-strong bg-surface px-5 text-sm font-medium transition-colors hover:bg-surface-muted disabled:opacity-50"><RefreshCw className={cn("h-4 w-4", refreshing && "animate-spin")} />{refreshing ? t("payment.refreshing") : refreshCooldown > 0 ? `${refreshCooldown}${t("payment.refreshLimit")}` : t("payment.refresh")}</button>
    <div className="flex flex-col items-center gap-2 pt-2 text-sm">
      <Link href={`/order/query?orderNo=${orderNo}`} className="text-muted-foreground hover:text-foreground">{t("payment.completedPayment")} {t("payment.goQueryOrder")} <ArrowRight className="inline h-4 w-4" /></Link>
      <ContactSupport title={t("payment.needHelp")} email={config?.contact_email} items={config?.contact_items} emailLabel={t("payment.contactSupport")} />
    </div>
  </div>
}
