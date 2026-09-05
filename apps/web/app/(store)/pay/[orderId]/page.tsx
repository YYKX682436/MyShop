"use client"

import { use, useCallback, useEffect, useState } from "react"
import Link from "next/link"
import { useRouter, useSearchParams } from "next/navigation"
import { ArrowRight, Clock, Copy, Loader2, RefreshCw, XCircle } from "lucide-react"
import { QRCodeSVG } from "qrcode.react"
import { toast } from "sonner"
import { useLocale, useSiteConfig } from "@/lib/context"
import { getOrderLookupToken, orderApi } from "@/services/api"
import type { OrderStatus } from "@/types"
import { cn, detectPaymentDevice, isMobileDevice } from "@/lib/utils"
import { getPaymentQrValue, getRepayPaymentCarriers, shouldDirectProviderPayment } from "@/lib/payment-page"
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
  const [status, setStatus] = useState<OrderStatus>("PENDING")
  const [orderNo, setOrderNo] = useState(searchParams.get("orderNo") || "")
  const [lookupToken, setLookupToken] = useState(searchParams.get("lookup_token") || "")
  const [timeLeft, setTimeLeft] = useState(-1)
  const [refreshCooldown, setRefreshCooldown] = useState(0)
  const [refreshing, setRefreshing] = useState(false)
  const [retrying, setRetrying] = useState(false)
  const [qrcodeUrl, setQrcodeUrl] = useState(searchParams.get("qr")?.trim() || "")
  const [payUrl, setPayUrl] = useState(searchParams.get("payurl")?.trim() || "")
  const [statusLoaded, setStatusLoaded] = useState(false)
  const isMobile = isMobileDevice()
  const method = searchParams.get("method") || "alipay"
  const methodName = getPaymentLabel(method, t)
  const brandColor = getPaymentBrandColor(method)
  const hasCountdown = timeLeft >= 0
  const qrcodeValue = getPaymentQrValue(qrcodeUrl)
  const paymentPageAvailable = Boolean(payUrl)

  const fetchStatus = useCallback(async () => {
    try {
      const token = lookupToken || (orderNo ? getOrderLookupToken(orderNo) : undefined)
      const result = await orderApi.getStatus(orderId, token, orderNo || undefined)
      setOrderNo(String(result.order_no))
      if (token && !lookupToken) setLookupToken(token)
      setTimeLeft(result.remaining_seconds ?? 0)
      const nextQrcodeUrl = result.qrcode_url?.trim() || ""
      const nextPayUrl = result.pay_url?.trim() || (!nextQrcodeUrl ? result.payment_url?.trim() || "" : "")
      if (nextQrcodeUrl && !qrcodeUrl) setQrcodeUrl(nextQrcodeUrl)
      if (nextPayUrl && !payUrl) setPayUrl(nextPayUrl)
      if (result.status !== "PENDING") {
        setStatus(result.status)
      }
    } finally {
      setStatusLoaded(true)
    }
  }, [lookupToken, orderId, orderNo, payUrl, qrcodeUrl])

  useEffect(() => { void fetchStatus().catch(() => undefined) }, [fetchStatus])

  useEffect(() => {
    if (status !== "PENDING") return
    const timer = window.setInterval(() => {
      void fetchStatus().catch(() => undefined)
    }, POLL_INTERVAL)
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
    if (status !== "PENDING" || !shouldDirectProviderPayment({ isMobile, payUrl, qrcodeUrl })) return
    window.location.href = payUrl
  }, [isMobile, payUrl, qrcodeUrl, status])

  useEffect(() => {
    if (status !== "PAID" && status !== "DELIVERED") return
    router.replace(`/order/query?orderNo=${encodeURIComponent(orderNo)}&paymentReturn=1`)
  }, [status, orderNo, router])

  const retry = async () => {
    if (retrying) return
    setRetrying(true)
    try {
      const result = await orderApi.repay(orderId, orderNo, detectPaymentDevice(), lookupToken || undefined)
      const carriers = getRepayPaymentCarriers(result)
      setOrderNo(String(result.order_no))
      setPayUrl(carriers.payUrl)
      setQrcodeUrl(carriers.qrcodeUrl)
      setStatusLoaded(true)
      if (shouldDirectProviderPayment({
        isMobile,
        payUrl: carriers.payUrl,
        qrcodeUrl: carriers.qrcodeUrl,
      })) {
        window.location.href = carriers.payUrl
      } else toast.success(t("payment.statusRefreshed"))
    } catch (error) {
      toast.error(error instanceof Error ? error.message : t("common.error"))
    } finally { setRetrying(false) }
  }

  const manualRefresh = async () => {
    if (refreshCooldown > 0 || refreshing) return
    setRefreshing(true)
    try { await fetchStatus(); toast.info(t("payment.statusRefreshed")) }
    catch (error) { toast.error(error instanceof Error ? error.message : t("common.error")) }
    finally { setRefreshing(false); setRefreshCooldown(MANUAL_REFRESH_COOLDOWN) }
  }

  const copy = (value: string) => navigator.clipboard?.writeText(value).then(() => toast.success(t("order.copied")))
  const formatTime = (seconds: number) => `${String(Math.floor(seconds / 60)).padStart(2, "0")}:${String(seconds % 60).padStart(2, "0")}`
  const retryButton = <button onClick={retry} disabled={retrying} className="inline-flex h-11 items-center gap-2 rounded-control bg-primary px-5 text-sm font-medium text-primary-foreground shadow-sm transition-[background-color,transform] duration-180 hover:bg-primary/90 active:scale-[.99] disabled:opacity-50"><RefreshCw className={cn("h-4 w-4", retrying && "animate-spin")} />{t("payment.retryQrCode")}</button>

  if (status === "EXPIRED") return <div className="surface mx-auto flex max-w-md flex-col items-center p-8 text-center"><XCircle className="mb-4 h-12 w-12 text-muted-foreground" /><h1 className="mb-2 text-xl font-semibold">{t("payment.expired")}</h1><p className="mb-6 text-sm text-muted-foreground">订单已过期，请重新下单。</p><Link href="/" className="inline-flex h-11 items-center rounded-control bg-primary px-5 text-sm font-medium text-primary-foreground shadow-sm transition-[background-color,transform] duration-180 hover:bg-primary/90 active:scale-[.99]">{t("payment.reorder")}</Link></div>

  return <div className="mx-auto flex max-w-lg flex-col items-center gap-6 py-4">
    <div className="surface flex w-full flex-col items-center gap-5 p-6 sm:p-8">
      <h1 className="text-xl font-semibold tracking-tight">{t("payment.waiting")}</h1>
      <div className="flex items-center gap-2 text-muted-foreground"><Clock className="h-4 w-4" /><span>{t("payment.remaining")}</span><span className={cn("font-mono text-lg font-bold", timeLeft >= 0 && timeLeft < 120 && "text-destructive")}>{timeLeft < 0 ? "--:--" : formatTime(timeLeft)}</span></div>
      <>
        <div className="flex w-72 flex-col items-center gap-4 rounded-surface px-6 pb-8 pt-6" style={{ backgroundColor: brandColor || "#374151" }}>
          <div className="flex items-center gap-2.5"><PaymentIcon method={method} className="h-10 w-10" variant="plain" /><span className="text-xl font-bold text-white">{methodName}</span></div>
          <p className="text-sm font-medium text-white/90">{getPaymentScanHint(method, t)}</p>
          <div className="flex h-52 w-52 items-center justify-center rounded-xl bg-white p-3">{qrcodeValue ? <QRCodeSVG value={qrcodeValue} size={184} /> : paymentPageAvailable ? <div className="flex flex-col items-center gap-3 px-3 text-center" role="status"><p className="text-sm text-muted-foreground">{t("payment.redirectingToPay")}</p><a href={payUrl} className="rounded-control bg-primary px-4 py-2 text-sm font-medium text-primary-foreground">{t("payment.continuePay")}</a></div> : statusLoaded ? <div className="flex flex-col items-center gap-3 px-3 text-center" role="alert"><p className="text-sm text-muted-foreground">{t("payment.noQrCode")}</p>{retryButton}</div> : <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />}</div>
        </div>
        {qrcodeValue || !statusLoaded ? <p className="animate-pulse text-sm text-primary">{t("payment.detecting")}</p> : paymentPageAvailable ? <p className="text-sm text-muted-foreground">{t("payment.redirectingToPay")}</p> : null}
      </>
      <div className="flex w-full items-center justify-between border-t border-border-subtle pt-4 text-sm"><span className="text-muted-foreground">{t("payment.orderNo")}</span><button onClick={() => void copy(orderNo)} disabled={!orderNo} className="flex items-center gap-1 rounded-control px-2 py-1 font-mono text-xs transition-colors hover:bg-surface-muted disabled:opacity-50"><span>{orderNo || "加载中"}</span><Copy className="h-3.5 w-3.5" /></button></div>
    </div>
    <button onClick={() => void manualRefresh()} disabled={refreshCooldown > 0 || refreshing} className="inline-flex h-11 items-center gap-2 rounded-control border border-border-strong bg-surface px-5 text-sm font-medium transition-colors hover:bg-surface-muted disabled:opacity-50"><RefreshCw className={cn("h-4 w-4", refreshing && "animate-spin")} />{refreshing ? "刷新中" : refreshCooldown > 0 ? `${refreshCooldown}s 后可刷新` : "刷新支付状态"}</button>
    <div className="flex flex-col items-center gap-2 pt-2 text-sm">
       <Link href={`/order/query?orderNo=${orderNo}`} className="text-muted-foreground hover:text-foreground">已完成支付？查看订单与卡密 <ArrowRight className="inline h-4 w-4" /></Link>
      <ContactSupport
        title={t("payment.needHelp")}
        email={config?.contact_email}
        items={config?.contact_items}
        emailLabel={t("payment.contactSupport")}
      />
    </div>
  </div>
}
