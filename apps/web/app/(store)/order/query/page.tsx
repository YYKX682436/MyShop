"use client"

import { useCallback, useEffect, useRef, useState } from "react"
import { useSearchParams } from "next/navigation"
import Link from "next/link"
import { ClipboardList, Copy, Download, KeyRound, Loader2, Search, ShieldCheck } from "lucide-react"
import { toast } from "sonner"
import { getApiErrorMessage, getOrderLookupToken, orderApi, saveOrderLookupToken } from "@/services/api"
import { OrderStatusBadge } from "@/components/shared/order-status-badge"
import { getPaymentLabel } from "@/components/shared/payment-icon"
import { formatYuan } from "@/lib/utils"
import type { DeliverResult, OrderBrief } from "@/types"

export default function OrderQueryPage() {
  const searchParams = useSearchParams()
  const [orderId, setOrderId] = useState("")
  const [lookupToken, setLookupToken] = useState("")
  const [orders, setOrders] = useState<OrderBrief[]>([])
  const [deliveries, setDeliveries] = useState<DeliverResult[]>([])
  const [loading, setLoading] = useState(false)
  const initializedFromUrl = useRef(false)

  const search = useCallback(async (requestedId?: string, requestedToken?: string) => {
    const id = (requestedId || orderId).trim()
    const token = (requestedToken || lookupToken).trim()
    if (!id || !token) {
      toast.error("请输入订单号和订单查询凭证")
      return
    }
    setLoading(true)
    try {
      saveOrderLookupToken(id, token)
      const found = await orderApi.query({ orders: [{ order_id: id, lookup_token: token }] })
      const deliverable = found.filter(order => order.status === "PAID" || order.status === "DELIVERED")
      const delivered = deliverable.length
        ? await orderApi.deliver({ orders: deliverable.map(order => ({ order_id: order.id, lookup_token: token })) })
        : []
      setOrders(found.map(order => {
        const result = delivered.find(item => item.order_id === order.id)
        return result && result.status !== order.status ? { ...order, status: result.status } : order
      }))
      setDeliveries(delivered)
    } catch (error) {
      setOrders([])
      setDeliveries([])
      toast.error(getApiErrorMessage(error, key => key))
    } finally {
      setLoading(false)
    }
  }, [lookupToken, orderId])

  useEffect(() => {
    const id = searchParams.get("orderId") || ""
    if (initializedFromUrl.current) return
    initializedFromUrl.current = true
    const tokenFromUrl = searchParams.get("lookup_token") || ""
    const storedToken = id ? tokenFromUrl || getOrderLookupToken(id) || "" : ""
    if (id) setOrderId(id)
    if (storedToken) {
      setLookupToken(storedToken)
      void search(id, storedToken)
    }
  }, [searchParams, search])

  const copy = async (value: string) => {
    await navigator.clipboard.writeText(value)
    toast.success("已复制")
  }

  const download = async () => {
    if (!orderId) return
    try {
      const content = await orderApi.exportKeys(orderId, lookupToken)
      const url = URL.createObjectURL(new Blob([content], { type: "text/plain;charset=utf-8" }))
      const anchor = document.createElement("a")
      anchor.href = url
      anchor.download = `myshop-card-keys-${orderId}.txt`
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
        <p className="text-sm text-muted-foreground">订单号本身不能访问订单；请同时输入下单后得到的查询凭证。服务器会在支付成功后自动尝试发货。</p>
      </div>

      <div className="surface p-5 sm:p-6">
        <div className="grid gap-4 sm:grid-cols-[1fr_1fr_auto] sm:items-end">
          <label className="text-sm font-medium text-foreground">
            订单号
            <input value={orderId} onChange={event => setOrderId(event.target.value)} placeholder="UUID" className="mt-1.5 h-11 w-full rounded-control border border-input bg-surface-muted px-3 font-mono text-sm" />
          </label>
          <label className="text-sm font-medium text-foreground">
            订单查询凭证
            <input value={lookupToken} onChange={event => setLookupToken(event.target.value)} placeholder="仅在下单结果中显示一次" className="mt-1.5 h-11 w-full rounded-control border border-input bg-surface-muted px-3 font-mono text-sm" />
          </label>
          <button onClick={() => void search()} disabled={loading} className="inline-flex h-11 items-center justify-center gap-2 rounded-control bg-primary px-5 text-sm font-semibold text-primary-foreground shadow-sm transition-[background-color,transform] duration-180 hover:bg-primary/90 active:scale-[.99] disabled:opacity-50">
            {loading ? <Loader2 className="h-4 w-4 animate-spin" /> : <Search className="h-4 w-4" />} 查询
          </button>
        </div>
        <div className="mt-4 flex items-start gap-2 rounded-card border border-border-subtle bg-surface-muted p-3.5 text-xs text-muted-foreground">
          <ShieldCheck className="mt-0.5 h-4 w-4 shrink-0 text-primary" />
          <span>查询凭证只在本机订单记录中保存；服务端日志不会记录明文凭证。为支持站长恢复，数据库中的 guest token 字段属于敏感凭证。</span>
        </div>
      </div>

      {orders.map(order => {
        const delivery = deliveries.find(item => item.order_id === order.id)
        return <section key={order.id} className="surface p-5 sm:p-6">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="flex items-center gap-3"><span className="font-mono text-sm">{order.id}</span><OrderStatusBadge status={order.status} /></div>
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
          {order.status === "PENDING" && <Link href={`/pay/${order.id}?method=${encodeURIComponent(order.payment_method)}`} className="mt-4 inline-flex rounded-control bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground shadow-sm transition-[background-color,transform] duration-180 hover:bg-primary/90 active:scale-[.98]">继续支付</Link>}
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
              const allParts = deliveryItems.flatMap(item => item.content_parts)
              return <div key={`${group.product_title}-${index}`} className="rounded-card border border-border-subtle bg-surface-muted/45 p-3.5">
                <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
                  <div className="text-sm font-medium">{group.product_title}{group.spec_name ? ` · ${group.spec_name}` : ""}</div>
                  <button onClick={() => void copy(allParts.join("\n"))} className="text-xs text-muted-foreground hover:text-foreground">复制全部</button>
                </div>
                <div className="space-y-3">
                  {deliveryItems.map((deliveryItem, itemIndex) => (
                    <div key={`${group.product_title}-${index}-${itemIndex}`} className="rounded-control border border-border-subtle bg-surface p-2">
                      <div className="mb-1 text-xs text-muted-foreground">卡券 {itemIndex + 1}</div>
                      <div className="space-y-1.5">
                        {deliveryItem.content_parts.map((part, partIndex) => (
                          <div key={`${itemIndex}-${partIndex}`} className="flex items-center justify-between gap-3 rounded-control border border-border-subtle bg-surface-muted px-3 py-2 font-mono text-xs">
                            <span className="break-all">卡券内容 {partIndex + 1}：{part}</span>
                            <button onClick={() => void copy(part)} aria-label="复制内容" className="shrink-0 text-muted-foreground hover:text-foreground"><Copy className="h-3.5 w-3.5" /></button>
                          </div>
                        ))}
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
