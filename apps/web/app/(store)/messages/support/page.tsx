"use client"

import { useEffect, useState } from "react"
import Link from "next/link"
import { useRouter, useSearchParams } from "next/navigation"
import { ArrowLeft } from "lucide-react"
import { SupportThread } from "@/components/shared/support-thread"
import { useSupportIdentity } from "@/hooks/use-support-identity"
import { announcementApi, supportApi } from "@/services/api"

type Context = {
  identity: string
  conversationId: string
  orderId?: string
  orderNo?: string
  requestId?: string
  round?: number
  noticeId?: string
  noticeTitle?: string
}

export default function SupportPage() {
  const identity = useSupportIdentity()
  const search = useSearchParams()
  const router = useRouter()
  const orderId = search.get("order") || undefined
  const requestId = search.get("request_id") || undefined
  const round = search.get("round") ? Number(search.get("round")) : undefined
  const noticeId = search.get("notice") || undefined
  const [context, setContext] = useState<Context | null>(null)
  const [error, setError] = useState("")
  const queryKey = `${identity}:${orderId ?? ""}:${requestId ?? ""}:${round ?? ""}:${noticeId ?? ""}`

  useEffect(() => {
    if (!identity) return
    const controller = new AbortController()
    setContext(null); setError("")
    const conversation = orderId ? supportApi.order(orderId, controller.signal, requestId, round) : supportApi.general(controller.signal)
    const notice = noticeId
      ? announcementApi.detail(noticeId, controller.signal).then(item => ({ item, error: "" })).catch(() => ({ item: null, error: "该通知已撤下，或当前身份不可见" }))
      : Promise.resolve({ item: null, error: "" })
    Promise.all([conversation, notice]).then(([thread, noticeResult]) => {
      if (controller.signal.aborted || !thread.conversation_id) return
      if (noticeResult.error) setError(noticeResult.error)
      setContext({
        identity,
        conversationId: thread.conversation_id,
        ...(orderId && "order_id" in thread ? { orderId: thread.order_id, orderNo: thread.order_no } : {}),
        ...(orderId && "request_id" in thread && thread.request_id ? { requestId: thread.request_id, round: thread.round } : {}),
        ...(noticeResult.item ? { noticeId: noticeResult.item.id, noticeTitle: noticeResult.item.title || "商城通知" } : {}),
      })
    }).catch(reason => { if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : "无法打开客服") })
    return () => controller.abort()
  }, [identity, orderId, requestId, round, noticeId])

  const current = context?.identity === identity ? context : null
  const clearOrder = () => {
    setContext(previous => previous?.identity === identity ? { ...previous, orderId: undefined, orderNo: undefined, requestId: undefined, round: undefined } : previous)
    const params = new URLSearchParams(search.toString()); params.delete("order"); params.delete("request_id"); params.delete("round")
    router.replace(`/messages/support${params.size ? `?${params}` : ""}`)
  }
  const clearAfterSale = () => {
    setContext(previous => previous?.identity === identity ? { ...previous, requestId: undefined, round: undefined } : previous)
    const params = new URLSearchParams(search.toString()); params.delete("request_id"); params.delete("round")
    router.replace(`/messages/support${params.size ? `?${params}` : ""}`)
  }
  const clearNotice = () => {
    setContext(previous => previous?.identity === identity ? { ...previous, noticeId: undefined, noticeTitle: undefined } : previous)
    const params = new URLSearchParams(search.toString()); params.delete("notice")
    router.replace(`/messages/support${params.size ? `?${params}` : ""}`)
  }

  return <div className="mx-auto max-w-3xl space-y-2 py-2 sm:py-5">
    <header><Link href="/messages" className="inline-flex min-h-11 items-center gap-2 text-sm text-muted-foreground"><ArrowLeft className="size-4" aria-hidden="true" />消息</Link></header>
    {error && <p role="alert" className="py-3 text-sm text-destructive">{error}</p>}
    {current ? <SupportThread key={queryKey} id={current.conversationId} orderId={current.orderId} orderNo={current.orderNo}
      afterSaleRequestId={current.requestId} afterSaleRound={current.round} noticeId={current.noticeId} noticeTitle={current.noticeTitle}
      onClearOrder={clearOrder} onClearAfterSale={clearAfterSale} onClearNotice={clearNotice} identity={identity} />
      : !error && <div role="status" aria-label="正在打开客服" className="space-y-3"><div className="h-14 animate-pulse rounded-card bg-muted" /><div className="h-[60dvh] animate-pulse rounded-card bg-muted" /></div>}
  </div>
}
