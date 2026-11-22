"use client"

import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react"
import Link from "next/link"
import { Button } from "@/components/ui/button"
import { Textarea } from "@/components/ui/textarea"
import { notifyMessagesChanged } from "@/components/store/message-summary"
import { useVisiblePoll } from "@/hooks/use-visible-poll"
import { formatDateTime, formatYuan } from "@/lib/utils"
import { supportApi, type SupportActivity, type SupportAttachment, type SupportConversation } from "@/services/api"

type Pending = { key: string; content: string; files: File[]; afterSale: boolean; state: "sending" | "sent" | "failed"; error?: string }
type Props = { id?: string; orderId?: string; orderNo?: number; admin?: boolean; identity: string; onCreated?: (id: string) => void }

function messageKey() {
  if (typeof crypto.randomUUID === "function") return crypto.randomUUID()
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6] & 15) | 64; bytes[8] = (bytes[8] & 63) | 128
  const hex = Array.from(bytes, byte => byte.toString(16).padStart(2, "0")).join("")
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

function merge(current: SupportActivity[], incoming: SupportActivity[]) {
  const byId = new Map(current.map(item => [item.id, item]))
  incoming.forEach(item => byId.set(item.id, item))
  return [...byId.values()].sort((a, b) => BigInt(a.cursor) < BigInt(b.cursor) ? -1 : BigInt(a.cursor) > BigInt(b.cursor) ? 1 : 0)
}

export function SupportThread({ id, orderId, orderNo, admin = false, identity, onCreated }: Props) {
  const [conversation, setConversation] = useState<SupportConversation | null>(null)
  const [items, setItems] = useState<SupportActivity[]>([])
  const [hasOlder, setHasOlder] = useState(false)
  const [loadingOlder, setLoadingOlder] = useState(false)
  const [error, setError] = useState("")
  const [content, setContent] = useState("")
  const [files, setFiles] = useState<File[]>([])
  const [pending, setPending] = useState<Pending[]>([])
  const scroller = useRef<HTMLDivElement>(null)
  const loaded = useRef(false)
  const latest = useRef("0")
  const oldest = useRef<string | null>(null)
  const nearBottom = useRef(true)
  const scrollAfterRender = useRef(false)
  const preserveHeight = useRef<number | null>(null)
  const lastRead = useRef(BigInt(0))
  const pendingRead = useRef(BigInt(0))
  const reading = useRef(false)
  const lifecycle = useRef<AbortController | null>(null)
  const root = useRef<HTMLDivElement>(null)
  const [height, setHeight] = useState<number | undefined>()

  useEffect(() => {
    const controller = new AbortController(); lifecycle.current = controller
    return () => { controller.abort(); lifecycle.current = null }
  }, [identity, id])
  useEffect(() => {
    const viewport = window.visualViewport
    const resize = () => {
      if (!root.current) return
      const bottom = viewport ? viewport.height + viewport.offsetTop : window.innerHeight
      setHeight(Math.max(260, Math.floor(bottom - root.current.getBoundingClientRect().top - 8)))
      if (nearBottom.current) scrollAfterRender.current = true
    }
    resize(); viewport?.addEventListener("resize", resize); viewport?.addEventListener("scroll", resize)
    window.addEventListener("resize", resize)
    return () => { viewport?.removeEventListener("resize", resize); viewport?.removeEventListener("scroll", resize); window.removeEventListener("resize", resize) }
  }, [])

  const poll = useCallback(async (signal: AbortSignal) => {
    if (!id) return
    let first = !loaded.current
    let keepGoing = true
    while (keepGoing && !signal.aborted) {
      const page = await supportApi.activities(id, admin, first ? undefined : { after: latest.current }, signal)
      if (signal.aborted) return
      if (first) { setHasOlder(page.has_more); oldest.current = page.oldest_cursor; loaded.current = true; scrollAfterRender.current = true }
      else if (page.items.length && nearBottom.current) scrollAfterRender.current = true
      if (page.newest_cursor) latest.current = page.newest_cursor
      if (!oldest.current && page.oldest_cursor) oldest.current = page.oldest_cursor
      if (page.items.length) {
        const receivedKeys = new Set(page.items.map(item => item.client_key).filter(Boolean))
        setPending(previous => previous.filter(item => !receivedKeys.has(item.key)))
        setItems(previous => merge(previous, page.items))
      }
      if (first || page.items.length) {
        const next = await supportApi.detail(id, admin, signal)
        if (!signal.aborted) setConversation(next)
      }
      keepGoing = !first && page.has_more
      first = false
    }
    if (!signal.aborted) setError("")
  }, [id, admin])

  useVisiblePoll(async signal => {
    try { await poll(signal) } catch (e) {
      if (!signal.aborted) setError(e instanceof Error ? e.message : "消息加载失败")
      throw e
    }
  }, !!id && !!identity, 2500, `${identity}:${id ?? "new"}:${admin}`)

  useLayoutEffect(() => {
    const element = scroller.current
    if (!element) return
    if (preserveHeight.current !== null) {
      element.scrollTop += element.scrollHeight - preserveHeight.current
      preserveHeight.current = null
    } else if (scrollAfterRender.current && nearBottom.current) element.scrollTop = element.scrollHeight
    scrollAfterRender.current = false
  }, [items, pending, height])

  const advanceRead = useCallback(async (value: bigint) => {
    if (!id || value <= lastRead.current) return
    if (value > pendingRead.current) pendingRead.current = value
    if (reading.current) return
    reading.current = true
    try {
      while (pendingRead.current > lastRead.current && lifecycle.current && !lifecycle.current.signal.aborted) {
        const next = pendingRead.current
        await supportApi.read(id, next.toString(), admin, lifecycle.current.signal)
        lastRead.current = next
        notifyMessagesChanged()
      }
    } catch { /* The next visible observation retries. */ }
    finally { reading.current = false }
  }, [id, admin])

  useEffect(() => {
    const element = scroller.current
    if (!id || !element || !items.length) return
    const observer = new IntersectionObserver(entries => {
      if (document.visibilityState !== "visible") return
      let visible = BigInt(0)
      entries.forEach(entry => {
        const cursor = (entry.target as HTMLElement).dataset.cursor
        if (entry.isIntersecting && cursor && BigInt(cursor) > visible) visible = BigInt(cursor)
      })
      if (visible > BigInt(0)) void advanceRead(visible)
    }, { root: element, threshold: 0.01 })
    element.querySelectorAll<HTMLElement>("[data-cursor]").forEach(node => observer.observe(node))
    const resume = () => {
      if (document.visibilityState !== "visible") return
      observer.disconnect()
      element.querySelectorAll<HTMLElement>("[data-cursor]").forEach(node => observer.observe(node))
    }
    document.addEventListener("visibilitychange", resume)
    return () => { observer.disconnect(); document.removeEventListener("visibilitychange", resume) }
  }, [items, id, advanceRead])

  async function loadOlder() {
    if (!id || !oldest.current || loadingOlder) return
    const controller = lifecycle.current
    if (!controller) return
    setLoadingOlder(true)
    try {
      const page = await supportApi.activities(id, admin, { before: oldest.current }, controller.signal)
      if (controller.signal.aborted) return
      preserveHeight.current = scroller.current?.scrollHeight ?? null
      setItems(previous => merge(previous, page.items))
      setHasOlder(page.has_more)
      if (page.oldest_cursor) oldest.current = page.oldest_cursor
    } catch (e) { if (!controller.signal.aborted) setError(e instanceof Error ? e.message : "加载失败") }
    finally { setLoadingOlder(false) }
  }

  async function deliver(message: Pending) {
    const controller = lifecycle.current
    if (!controller) return
    setPending(previous => previous.map(item => item.key === message.key ? { ...item, state: "sending", error: undefined } : item))
    try {
      const result = await supportApi.send({ conversationId: id, orderId, clientKey: message.key, content: message.content, files: message.files }, admin, message.afterSale, controller.signal)
      if (controller.signal.aborted) return
      setPending(previous => previous.map(item => item.key === message.key ? { ...item, state: "sent" } : item))
      notifyMessagesChanged()
      if (!id && result?.conversation_id) onCreated?.(result.conversation_id)
    } catch (e) {
      if (!controller.signal.aborted) setPending(previous => previous.map(item => item.key === message.key ? { ...item, state: "failed", error: e instanceof Error ? e.message : "发送失败" } : item))
    }
  }

  function send(event: React.FormEvent) {
    event.preventDefault()
    const value = content.trim()
    const sale = !!id && !!conversation?.waiting_for
    if ((!value && !files.length) || (sale && !value)) return
    const message: Pending = { key: messageKey(), content: value, files, afterSale: sale, state: "sending" }
    setContent(""); setFiles([]); setPending(previous => [...previous, message])
    if (nearBottom.current) scrollAfterRender.current = true
    void deliver(message)
  }

  return <div ref={root} style={{ height }} className="flex min-h-[260px] flex-col overflow-hidden rounded-card border border-border-subtle bg-surface">
    <header className="flex min-h-14 shrink-0 items-center justify-between gap-2 border-b border-border-subtle px-4 py-2">
      <div className="min-w-0"><h2 className="truncate font-semibold">{conversation?.order_no || orderNo ? `订单 #${conversation?.order_no || orderNo}` : "主站客服"}</h2>
        {admin && <p className="truncate text-xs text-muted-foreground">{conversation?.site_name || "主站"}</p>}</div>
      {conversation?.order_id && <Link href={admin ? `/admin/orders/${conversation.order_id}` : `/order/${conversation.order_id}`} className="text-sm text-primary">{admin && conversation.waiting_for ? "处理售后" : "查看订单"}</Link>}
    </header>
    {error && <p role="alert" className="shrink-0 px-4 py-2 text-sm text-destructive">{error}</p>}
    <div ref={scroller} onScroll={e => { const node=e.currentTarget; nearBottom.current = node.scrollHeight-node.scrollTop-node.clientHeight < 96 }}
      className="min-h-0 flex-1 space-y-3 overflow-y-auto overscroll-contain px-4 py-4" aria-label="会话消息">
      {hasOlder && <div className="text-center"><Button type="button" variant="ghost" size="sm" disabled={loadingOlder} onClick={() => void loadOlder()}>加载更早消息</Button></div>}
      {items.map(item => <Activity key={item.id} item={item} admin={admin} />)}
      {pending.map(item => <div key={item.key} className="ml-auto max-w-[85%] rounded-control bg-primary/10 px-3 py-2 text-sm">
        <p className="whitespace-pre-wrap break-words">{item.content}</p>{item.files.map(file => <p className="text-xs" key={`${file.name}:${file.size}`}>{file.name}</p>)}
        <div className="mt-1 text-xs text-muted-foreground">{item.state === "sending" ? "发送中…" : item.state === "sent" ? "已发送" : <span role="alert">{item.error || "发送失败"} <button type="button" className="text-primary underline" onClick={() => void deliver(item)}>重试</button></span>}</div>
      </div>)}
    </div>
    <form onSubmit={send} className="shrink-0 space-y-2 border-t border-border-subtle p-3" style={{ paddingBottom: "max(0.75rem, env(safe-area-inset-bottom))" }}>
      {files.length > 0 && <div className="flex flex-wrap gap-2">{files.map((file, index) => <button type="button" key={`${file.name}:${index}`} className="rounded bg-muted px-2 py-1 text-xs" onClick={() => setFiles(value => value.filter((_, i) => i !== index))}>{file.name} ×</button>)}</div>}
      <Textarea aria-label="消息内容" className="min-h-11 resize-y text-base" rows={2} maxLength={1000} value={content} onChange={e => setContent(e.target.value)} placeholder="输入消息" disabled={!!id && !conversation} />
      <div className="flex items-center justify-between gap-2"><label className="cursor-pointer text-sm text-primary">添加图片<input type="file" className="sr-only" multiple accept="image/jpeg,image/png,image/gif,image/webp,image/svg+xml" onChange={e => { const selected = Array.from(e.target.files || []); setFiles(value => [...value, ...selected].slice(0, 5)); e.currentTarget.value = "" }} /></label>
        <Button type="submit" disabled={(!!id && !conversation) || (!content.trim() && (!files.length || !!conversation?.waiting_for))}>发送</Button></div>
    </form>
  </div>
}

function Activity({ item, admin }: { item: SupportActivity; admin: boolean }) {
  const system = item.sender_type === "SYSTEM"
  const mine = !system && (admin ? item.sender_type === "ADMIN" : item.sender_type === "CUSTOMER")
  const label = item.source_type === "AFTER_SALE_APPLY" ? `第 ${item.round ?? "—"} 次售后申请`
    : item.source_type === "AFTER_SALE_REJECT" ? `第 ${item.round ?? "—"} 次售后已拒绝`
    : item.source_type === "ORDER_REFUND" ? "退款成功" : item.source_type === "SUPPLIER_UPDATE" ? `${item.round ? `第 ${item.round} 次售后 · ` : ""}上游处理更新`
    : item.sender_type === "ADMIN" ? "主站客服" : "顾客"
  return <div data-cursor={item.cursor} className={`${system ? "mx-auto max-w-[90%] bg-muted/60" : mine ? "ml-auto max-w-[85%] bg-primary/10" : "mr-auto max-w-[85%] bg-muted/60"} rounded-control px-3 py-2 text-sm`}>
    <div className="flex flex-wrap justify-between gap-2 text-xs text-muted-foreground"><span>{label}</span>{item.created_at && <time>{formatDateTime(item.created_at)}</time>}</div>
    {item.content && <p className="mt-1 whitespace-pre-wrap break-words">{item.content}</p>}
    {item.source_type === "ORDER_REFUND" && <p className="mt-1">{item.refund_amount == null ? "金额未知" : formatYuan(item.refund_amount)} · {item.refund_destination === "NO_PAYMENT" ? "零金额订单" : item.refund_destination === "BALANCE" ? "退至余额" : "去向未知"} · {item.order_status === "REFUNDED" ? "已退款" : item.order_status}</p>}
    {item.source_type === "SUPPLIER_UPDATE" && <p className="mt-1 text-xs">{({1:"等待处理",2:"已受理",3:"处理完成",4:"审核终止"} as Record<number,string>)[item.supplier_status ?? 0] || "处理中"}</p>}
    {item.attachments.length > 0 && <div className="mt-2 flex flex-wrap gap-2">{item.attachments.map(attachment => <PrivateAttachment key={attachment.id} attachment={attachment} />)}</div>}
  </div>
}

function PrivateAttachment({ attachment }: { attachment: SupportAttachment }) {
  const [url, setUrl] = useState<string | null>(null)
  const [error, setError] = useState(false)
  useEffect(() => {
    const controller = new AbortController(); let objectUrl: string | null = null
    supportApi.attachment(attachment.url, controller.signal).then(blob => {
      if (controller.signal.aborted) return
      objectUrl = URL.createObjectURL(blob); setUrl(objectUrl)
    }).catch(() => { if (!controller.signal.aborted) setError(true) })
    return () => { controller.abort(); if (objectUrl) URL.revokeObjectURL(objectUrl) }
  }, [attachment.url])
  if (error) return <span className="inline-flex h-28 w-32 items-center justify-center text-xs text-destructive">图片加载失败</span>
  if (!url) return <span className="inline-flex h-28 w-32 items-center justify-center text-xs text-muted-foreground">加载图片…</span>
  if (attachment.content_type === "image/svg+xml") return <a href={url} download="附件.svg" className="text-xs text-primary underline">下载附件</a>
  // eslint-disable-next-line @next/next/no-img-element
  return <a href={url} target="_blank" rel="noreferrer"><img src={url} alt="聊天图片" width={128} height={112} className="h-28 w-32 rounded-control object-contain" /></a>
}
