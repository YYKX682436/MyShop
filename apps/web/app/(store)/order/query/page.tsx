"use client"

import { useCallback, useEffect, useRef, useState } from "react"
import { useSearchParams } from "next/navigation"
import Link from "next/link"
import { ClipboardList, Copy, Download, KeyRound, Loader2, Search, ShieldCheck } from "lucide-react"
import { toast } from "sonner"
import { getApiErrorMessage, getOrderLookupToken, orderApi, saveOrderLookupToken } from "@/services/api"
import { OrderStatusBadge } from "@/components/shared/order-status-badge"
import { getPaymentLabel } from "@/components/shared/payment-icon"
import { useAuth } from "@/lib/context"
import { formatCardContent, formatCardContents } from "@/lib/card-display"
import { formatYuan } from "@/lib/utils"
import type { DeliverResult, OrderBrief } from "@/types"

const RETURN_POLL_INTERVAL_MS = 1000
const RETURN_POLL_MAX_MS = 20000

interface SearchOptions {
  preserveOnError?: boolean
  silent?: boolean
}

interface SearchOutcome {
  terminal: boolean
}

type ReturnPollCallback = (requested: string, token: string, deadline: number) => void

export default function OrderQueryPage() {
  const searchParams = useSearchParams()
  const { user, authLoaded } = useAuth()
  const [orderNo, setOrderNo] = useState("")
  const [lookupToken, setLookupToken] = useState("")
  const [orders, setOrders] = useState<OrderBrief[]>([])
  const [deliveries, setDeliveries] = useState<DeliverResult[]>([])
  const [loading, setLoading] = useState(false)
  const [returnChecking, setReturnChecking] = useState(false)
  const [returnTimedOut, setReturnTimedOut] = useState(false)
  const initializedFromUrl = useRef(false)
  const searchInFlight = useRef(false)
  const returnPolling = useRef(false)
  const returnPollTimer = useRef<number | undefined>(undefined)
  const returnPollCallback = useRef<ReturnPollCallback | undefined>(undefined)
  const paymentReturn = searchParams.get("paymentReturn") === "1"

  const search = useCallback(async (
    requestedOrderNo?: string,
    requestedToken?: string,
    options: SearchOptions = {},
  ): Promise<SearchOutcome | null> => {
    const requested = (requestedOrderNo ?? orderNo).trim()
    const token = (requestedToken ?? lookupToken).trim()
    if (!requested) {
      if (!options.silent) toast.error("请输入订单号")
      return { terminal: true }
    }
    if (!authLoaded) return { terminal: false }
    if (!user && !token) {
      if (!options.silent) toast.error("游客查询需要订单查询凭证")
      return { terminal: true }
    }
    if (searchInFlight.current) return null
    searchInFlight.current = true
    setLoading(true)
    let found: OrderBrief[] = []
    try {
      if (token) saveOrderLookupToken(requested, token)
      const credential = token ? { order_no: requested, lookup_token: token } : { order_no: requested }
      found = await orderApi.query({ orders: [credential] })
      const deliverable = found.filter(order => order.status === "PAID" || order.status === "DELIVERED")
      const delivered = deliverable.length
        ? await orderApi.deliver({ orders: deliverable.map(order => token ? { order_no: order.order_no, lookup_token: token } : { order_no: order.order_no }) })
        : []
      setOrders(found.map(order => {
        const result = delivered.find(item => item.order_no === order.order_no)
        return result && result.status !== order.status ? { ...order, status: result.status } : order
      }))
      setDeliveries(delivered)
      const terminalOrder = found.find(order => order.status === "EXPIRED" || order.status === "DELIVERED")
      const deliveryCompleted = delivered.some(item => item.status === "DELIVERED" || Boolean(item.groups?.length))
      return {
        terminal: Boolean(terminalOrder || deliveryCompleted),
      }
    } catch (error) {
      if (options.preserveOnError) {
        if (found.length > 0) setOrders(found)
      } else {
        setOrders([])
        setDeliveries([])
      }
      if (!options.silent) toast.error(getApiErrorMessage(error, key => key))
      return { terminal: false }
    } finally {
      searchInFlight.current = false
      setLoading(false)
    }
  }, [authLoaded, lookupToken, orderNo, user])

  const stopReturnPolling = useCallback((timedOut: boolean) => {
    returnPolling.current = false
    if (returnPollTimer.current !== undefined) {
      window.clearTimeout(returnPollTimer.current)
      returnPollTimer.current = undefined
    }
    setReturnChecking(false)
    if (timedOut) setReturnTimedOut(true)
  }, [])

  const pollPaymentReturn = useCallback(async (requested: string, token: string, deadline: number) => {
    if (!returnPolling.current) return
    if (Date.now() >= deadline) {
      stopReturnPolling(true)
      return
    }
    const outcome = await search(requested, token, { preserveOnError: true, silent: true })
    if (!returnPolling.current) return
    if (outcome?.terminal) {
      stopReturnPolling(false)
      return
    }
    const remaining = deadline - Date.now()
    if (remaining <= 0) {
      stopReturnPolling(true)
      return
    }
    returnPollTimer.current = window.setTimeout(() => {
      returnPollTimer.current = undefined
      returnPollCallback.current?.(requested, token, deadline)
    }, Math.min(RETURN_POLL_INTERVAL_MS, remaining))
  }, [search, stopReturnPolling])

  useEffect(() => {
    returnPollCallback.current = pollPaymentReturn
  }, [pollPaymentReturn])

  const confirmPaymentReturn = useCallback(async (requested: string, token: string) => {
    const deadline = Date.now() + RETURN_POLL_MAX_MS
    returnPolling.current = true
    setReturnChecking(true)
    setReturnTimedOut(false)
    const initial = await search(requested, token, { preserveOnError: true, silent: true })
    if (!returnPolling.current) return
    if (initial?.terminal) {
      stopReturnPolling(false)
      return
    }
    const remaining = deadline - Date.now()
    if (remaining <= 0) {
      stopReturnPolling(true)
      return
    }
    returnPollTimer.current = window.setTimeout(() => {
      returnPollTimer.current = undefined
      returnPollCallback.current?.(requested, token, deadline)
    }, Math.min(RETURN_POLL_INTERVAL_MS, remaining))
  }, [search, stopReturnPolling])

  useEffect(() => {
    const requested = searchParams.get("orderNo") || ""
    if (!requested || !authLoaded || initializedFromUrl.current) return
    initializedFromUrl.current = true
    const tokenFromUrl = searchParams.get("lookup_token") || ""
    const storedToken = requested ? tokenFromUrl || getOrderLookupToken(requested) || "" : ""
    setOrderNo(requested)
    if (storedToken) {
      setLookupToken(storedToken)
    }
    if (storedToken || user) {
      if (paymentReturn) void confirmPaymentReturn(requested, storedToken)
      else void search(requested, storedToken)
    }
  }, [authLoaded, confirmPaymentReturn, paymentReturn, search, searchParams, user])

  useEffect(() => () => {
    returnPolling.current = false
    if (returnPollTimer.current !== undefined) window.clearTimeout(returnPollTimer.current)
  }, [])

  const manualSearch = () => {
    setReturnTimedOut(false)
    void search()
  }

  const copy = async (value: string) => {
    await navigator.clipboard.writeText(value)
    toast.success("已复制")
  }

  const download = async () => {
    const order = orders[0]
    if (!order) return
    try {
      const content = await orderApi.exportKeys(order.id, order.order_no, lookupToken)
      const url = URL.createObjectURL(new Blob([content], { type: "text/plain;charset=utf-8" }))
      const anchor = document.createElement("a")
      anchor.href = url
      anchor.download = `myshop-card-keys-${order.order_no}.txt`
      anchor.click()
      URL.revokeObjectURL(url)
    } catch (error) {
      toast.error(getApiErrorMessage(error, key => key))
    }
  }

  return (
    <div className="mx-auto w-full max-w-3xl space-y-7">
      <div>
        <div className="mb-2 flex items-center gap-3">
          <span className="flex size-9 items-center justify-center rounded-control bg-primary/10 text-primary"><ClipboardList className="size-5" aria-hidden="true" /></span>
          <h1 className="text-2xl font-semibold tracking-tight text-foreground">查询订单与卡密</h1>
        </div>
        <p className="text-sm text-muted-foreground">订单号本身不能访问游客订单；游客需要同时输入下单后得到的查询凭证，登录用户查询自己的订单可留空。服务器会在支付成功后自动尝试发货。</p>
      </div>

      {paymentReturn && returnChecking && <div className="rounded-card border border-primary/20 bg-primary/[0.06] px-4 py-3 text-sm text-primary" role="status">正在确认支付结果 / 正在准备卡密</div>}
      {paymentReturn && returnTimedOut && !returnChecking && <div className="rounded-card border border-border-subtle bg-surface-muted px-4 py-3 text-sm text-muted-foreground" role="status">暂未完成支付确认，请点击“查询”重试。系统尚未判定支付失败。</div>}

      <div className="surface p-5 sm:p-6">
        <div className="grid gap-4 sm:grid-cols-[1fr_1fr_auto] sm:items-end">
          <label className="text-sm font-medium text-foreground">
            订单号
            <input inputMode="numeric" value={orderNo} onChange={event => setOrderNo(event.target.value)} placeholder="10 位数字订单号" className="mt-1.5 h-11 w-full rounded-control border border-input bg-surface-muted px-3 font-mono text-sm" />
          </label>
          <label className="text-sm font-medium text-foreground">
            订单查询凭证（游客必填）
            <input value={lookupToken} onChange={event => setLookupToken(event.target.value)} placeholder="游客下单结果中显示一次；登录用户可留空" className="mt-1.5 h-11 w-full rounded-control border border-input bg-surface-muted px-3 font-mono text-sm" />
          </label>
          <button onClick={manualSearch} disabled={loading || !authLoaded} className="inline-flex h-11 items-center justify-center gap-2 rounded-control bg-primary px-5 text-sm font-semibold text-primary-foreground shadow-sm transition-[background-color,transform] duration-180 hover:bg-primary/90 active:scale-[.99] disabled:opacity-50">
            {loading ? <Loader2 className="h-4 w-4 animate-spin" /> : <Search className="h-4 w-4" />} 查询
          </button>
        </div>
        <div className="mt-4 flex items-start gap-2 rounded-card border border-border-subtle bg-surface-muted p-3.5 text-xs text-muted-foreground">
          <ShieldCheck className="mt-0.5 h-4 w-4 shrink-0 text-primary" />
          <span>游客查询凭证只在本机订单记录中保存；登录用户查询自己的订单不依赖本机凭证。服务端日志不会记录明文凭证，数据库中的 guest token 字段属于敏感凭证。</span>
        </div>
      </div>

      {orders.map(order => {
        const delivery = deliveries.find(item => item.order_no === order.order_no)
        return <section key={order.order_no} className="surface p-5 sm:p-6">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="flex items-center gap-3"><span className="font-mono text-sm">{order.order_no}</span><OrderStatusBadge status={order.status} /></div>
            <span className="text-sm font-semibold">{formatYuan(order.actual_amount)}</span>
          </div>
          <div className="mt-3 flex flex-wrap gap-4 text-xs text-muted-foreground">
            <span>创建于 {new Date(order.created_at).toLocaleString()}</span>
            <span>支付方式：{getPaymentLabel(order.payment_method)}</span>
          </div>
          {order.items?.some(item => item.customer_inputs.length > 0) && (
            <div className="mt-5 rounded-card border border-border-subtle bg-surface-muted p-3.5">
              <p className="mb-2 text-xs font-medium text-muted-foreground">购买填写信息</p>
              <div className="space-y-3">
                {order.items.filter(item => item.customer_inputs.length > 0).map(item => (
                  <div key={item.id} className="text-sm">
                    <p className="font-medium text-foreground">{item.product_title}{item.spec_name ? ` · ${item.spec_name}` : ""}</p>
                    <div className="mt-1 grid gap-1 sm:grid-cols-2">
                      {item.customer_inputs.map(input => (
                        <div key={input.id} className="flex gap-2 text-xs text-muted-foreground">
                          <span>{input.label}：</span><span className="break-all text-foreground">{input.value}</span>
                        </div>
                      ))}
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}
          {order.status === "PENDING" && <Link href={`/pay/${order.id}?orderNo=${order.order_no}&method=${encodeURIComponent(order.payment_method)}`} className="mt-4 inline-flex rounded-control bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground shadow-sm transition-[background-color,transform] duration-180 hover:bg-primary/90 active:scale-[.98]">继续支付</Link>}
          {(order.items?.some(item => item.fulfillment_status === "WAITING_MANUAL")
            || delivery?.item_statuses?.some(item => item.fulfillment_status === "WAITING_MANUAL")) && (
            <div className="mt-5 rounded-card border border-amber-500/20 bg-amber-500/[0.07] p-3 text-sm text-amber-800 dark:text-amber-200">
              订单中包含人工发货商品，商家完成处理后会更新订单状态。请保留订单查询凭证。
            </div>
          )}
          {delivery?.groups?.length ? <div className="mt-5 space-y-4">
            <div className="flex flex-wrap items-center justify-between gap-2"><h2 className="flex items-center gap-2 text-base font-semibold"><KeyRound className="h-4 w-4 text-primary" />卡密已发货</h2><button onClick={() => void download()} className="inline-flex items-center gap-1.5 rounded-lg border border-border px-3 py-1.5 text-xs font-medium"><Download className="h-3.5 w-3.5" />导出</button></div>
            {delivery.groups.map((group, index) => {
              const deliveryItems = group.delivery_items ?? []
              const allCards = formatCardContents(deliveryItems)
              return <div key={`${group.product_title}-${index}`} className="rounded-card border border-border-subtle bg-surface-muted/45 p-3.5">
                <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
                  <div className="text-sm font-medium">{group.product_title}{group.spec_name ? ` · ${group.spec_name}` : ""}</div>
                  <button onClick={() => void copy(allCards)} className="text-xs text-muted-foreground hover:text-foreground">复制全部</button>
                </div>
                <div className="space-y-3">
                  {deliveryItems.map((deliveryItem, itemIndex) => (
                    <div key={`${group.product_title}-${index}-${itemIndex}`} className="rounded-control border border-border-subtle bg-surface p-2">
                      <div className="mb-1 text-xs text-muted-foreground">卡券 {itemIndex + 1}</div>
                      <div className="space-y-1.5">
                        <div className="flex items-start justify-between gap-3 rounded-control border border-border-subtle bg-surface-muted px-3 py-2 font-mono text-xs">
                          <span className="break-all whitespace-pre-wrap">{formatCardContent(deliveryItem.content_parts)}</span>
                          <button onClick={() => void copy(formatCardContent(deliveryItem.content_parts))} aria-label="复制内容" className="shrink-0 text-muted-foreground hover:text-foreground"><Copy className="h-3.5 w-3.5" /></button>
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            })}
          </div> : null}
        </section>
      })}
    </div>
  )
}
