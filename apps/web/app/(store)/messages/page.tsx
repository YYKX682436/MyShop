"use client"

import { useEffect, useState } from "react"
import Link from "next/link"
import { useRouter, useSearchParams } from "next/navigation"
import { Bell } from "lucide-react"
import { SupportThread } from "@/components/shared/support-thread"
import { useMessageSummary } from "@/components/store/message-summary"
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

export default function MessagesPage() {
  const identity = useSupportIdentity()
  const search = useSearchParams()
  const router = useRouter()
  const orderId = search.get("order") || undefined
  const requestId = search.get("request_id") || undefined
  const round = search.get("round") ? Number(search.get("round")) : undefined
  const noticeId = search.get("notice") || undefined
  const [context, setContext] = useState<Context | null>(null)
  const [error, setError] = useState("")
  const { announcements } = useMessageSummary()

  useEffect(() => {
    if (!identity) return
    const controller = new AbortController()
    setError("")
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
  function replaceQuery(params: URLSearchParams) {
    router.replace("/messages" + (params.size ? "?" + params.toString() : ""), { scroll: false })
  }
  function clearOrder() {
    setContext(previous => previous?.identity === identity ? { ...previous, orderId: undefined, orderNo: undefined, requestId: undefined, round: undefined } : previous)
    const params = new URLSearchParams(search.toString())
    params.delete("order"); params.delete("request_id"); params.delete("round")
    replaceQuery(params)
  }
  function clearAfterSale() {
    setContext(previous => previous?.identity === identity ? { ...previous, requestId: undefined, round: undefined } : previous)
    const params = new URLSearchParams(search.toString())
    params.delete("request_id"); params.delete("round")
    replaceQuery(params)
  }
  function clearNotice() {
    setContext(previous => previous?.identity === identity ? { ...previous, noticeId: undefined, noticeTitle: undefined } : previous)
    const params = new URLSearchParams(search.toString())
    params.delete("notice")
    replaceQuery(params)
  }

  return <section className="mx-auto flex h-full min-h-0 w-full max-w-3xl flex-1 flex-col">
    <header className="z-10 flex min-h-14 shrink-0 items-center justify-between gap-3 border-b border-border-subtle bg-background px-1">
      <h1 className="text-base font-semibold">商城客服</h1>
      <Link href="/messages/announcements" className="inline-flex min-h-11 items-center gap-2 rounded-full px-3 text-sm text-foreground hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
        <span className="relative inline-flex"><Bell className="size-4" aria-hidden="true" />
          {announcements.unread > 0 && <span className="absolute -right-2 -top-2 inline-flex min-h-4 min-w-4 items-center justify-center rounded-full bg-destructive px-1 text-[10px] leading-4 text-white" aria-label={announcements.unread + " 条通知未读"}>{announcements.unread > 99 ? "99+" : announcements.unread}</span>}
        </span>商城通知
      </Link>
    </header>
    {error && <p role="status" className="shrink-0 px-1 py-1 text-xs text-muted-foreground">{error}</p>}
    <div className="flex min-h-0 flex-1 flex-col">
      {current ? <SupportThread id={current.conversationId} orderId={current.orderId} orderNo={current.orderNo}
        afterSaleRequestId={current.requestId} afterSaleRound={current.round} noticeId={current.noticeId} noticeTitle={current.noticeTitle}
        onClearOrder={clearOrder} onClearAfterSale={clearAfterSale} onClearNotice={clearNotice} identity={identity} showHeader={false} />
        : !error && <div role="status" aria-label="正在打开客服" className="flex min-h-0 flex-1 flex-col gap-3 py-3"><div className="h-12 w-3/5 animate-pulse rounded-2xl bg-muted" /><div className="h-12 w-2/3 animate-pulse self-end rounded-2xl bg-muted" /><div className="flex-1" /></div>}
    </div>
  </section>
}
