"use client"

import { Fragment, useCallback, useEffect, useLayoutEffect, useRef, useState, type FormEvent, type KeyboardEvent } from "react"
import Link from "next/link"
import { ArrowDown, Paperclip, Send, X } from "lucide-react"
import { Attachment as ChatAttachment, AttachmentActions, AttachmentContent, AttachmentMedia, AttachmentTitle, Bubble, Marker, Message, MessageGroup } from "@/components/shared/chat-primitives"
import { Button } from "@/components/ui/button"
import { Textarea } from "@/components/ui/textarea"
import { Dialog, DialogClose, DialogContent, DialogTitle } from "@/components/ui/dialog"
import { notifyMessagesChanged } from "@/components/store/message-summary"
import { useVisiblePoll } from "@/hooks/use-visible-poll"
import { createPendingSupportMessage, shouldFetchConversationDetail, SupportReadQueue, type PendingSupportMessage } from "@/lib/support-message-state"
import { formatYuan } from "@/lib/utils"
import { supportApi, type SupportActivity, type SupportAttachment, type SupportConversation } from "@/services/api"

type SaleContext = { orderId: string; orderNo: string; requestId: string; round: number }
type Pending = PendingSupportMessage
type Props = {
  id?: string
  orderId?: string
  orderNo?: string
  afterSaleRequestId?: string
  afterSaleRound?: number
  noticeId?: string
  noticeTitle?: string
  onClearOrder?: () => void
  onClearAfterSale?: () => void
  onClearNotice?: () => void
  admin?: boolean
  adminUserId?: string | null
  identity: string
  showHeader?: boolean
  className?: string
}

function messageKey() {
  if (typeof crypto.randomUUID === "function") return crypto.randomUUID()
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6] & 15) | 64; bytes[8] = (bytes[8] & 63) | 128
  const hex = Array.from(bytes, byte => byte.toString(16).padStart(2, "0")).join("")
  return hex.slice(0, 8) + "-" + hex.slice(8, 12) + "-" + hex.slice(12, 16) + "-" + hex.slice(16, 20) + "-" + hex.slice(20)
}

function merge(current: SupportActivity[], incoming: SupportActivity[]) {
  const byId = new Map(current.map(item => [item.id, item]))
  incoming.forEach(item => byId.set(item.id, item))
  return [...byId.values()].sort((a, b) => BigInt(a.cursor) < BigInt(b.cursor) ? -1 : BigInt(a.cursor) > BigInt(b.cursor) ? 1 : 0)
}

function dateKey(value: string | null) {
  if (!value) return "unknown"
  const date = new Date(value)
  return date.getFullYear() + "-" + date.getMonth() + "-" + date.getDate()
}
function dateLabel(value: string | null) {
  if (!value) return "历史记录"
  return new Date(value).toLocaleDateString(undefined, { month: "long", day: "numeric" })
}
function timeLabel(value: string | null) {
  if (!value) return ""
  return new Date(value).toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" })
}
function draftKey(identity: string, id: string) {
  return "myshop:support-draft:" + encodeURIComponent(identity) + ":" + encodeURIComponent(id)
}

export function SupportThread({ id, orderId, orderNo, afterSaleRequestId, afterSaleRound, noticeId, noticeTitle,
  onClearOrder, onClearAfterSale, onClearNotice, admin = false, adminUserId, identity, showHeader = true, className = "" }: Props) {
  const [conversation, setConversation] = useState<SupportConversation | null>(null)
  const [items, setItems] = useState<SupportActivity[]>([])
  const [hasOlder, setHasOlder] = useState(false)
  const [loadingOlder, setLoadingOlder] = useState(false)
  const [error, setError] = useState("")
  const [content, setContent] = useState("")
  const [files, setFiles] = useState<File[]>([])
  const [pending, setPending] = useState<Pending[]>([])
  const [showLatest, setShowLatest] = useState(false)
  const [adminSale, setAdminSale] = useState<SaleContext | null>(null)
  const scroller = useRef<HTMLDivElement>(null)
  const textarea = useRef<HTMLTextAreaElement>(null)
  const form = useRef<HTMLFormElement>(null)
  const activitiesLoaded = useRef(false)
  const detailLoaded = useRef(false)
  const latest = useRef("0")
  const oldest = useRef<string | null>(null)
  const firstUnread = useRef<string | null>(null)
  const nearBottom = useRef(true)
  const scrollAfterRender = useRef(false)
  const preserveAnchor = useRef<{ cursor: string; offset: number } | null>(null)
  const lifecycle = useRef<AbortController | null>(null)
  const readQueue = useRef(new SupportReadQueue())
  const sending = pending.some(item => item.state === "sending")
  const activeSale = admin ? adminSale : afterSaleRequestId && orderId && afterSaleRound
    ? { orderId, orderNo: orderNo ?? "", requestId: afterSaleRequestId, round: afterSaleRound } : null

  useEffect(() => {
    const controller = new AbortController(); lifecycle.current = controller
    activitiesLoaded.current = false; detailLoaded.current = false; latest.current = "0"
    oldest.current = null; firstUnread.current = null; nearBottom.current = true
    setConversation(null); setItems([]); setPending([]); setError(""); setShowLatest(false)
    readQueue.current.clear()
    return () => { controller.abort(); if (lifecycle.current === controller) lifecycle.current = null }
  }, [identity, id])

  useEffect(() => {
    if (!identity || !id) { setContent(""); return }
    try { setContent(window.localStorage.getItem(draftKey(identity, id)) ?? "") }
    catch { setContent("") }
  }, [identity, id])

  const flushReadReceipts = useCallback(async () => {
    const controller = lifecycle.current
    if (!id || !controller || controller.signal.aborted) return
    await readQueue.current.flush(controller.signal,
      async (batch, signal) => { await supportApi.read(id, batch, admin, signal) },
      () => { if (!controller.signal.aborted && lifecycle.current === controller) notifyMessagesChanged() })
  }, [id, admin])

  const poll = useCallback(async (signal: AbortSignal) => {
    if (!id) return
    let first = !activitiesLoaded.current
    let keepGoing = true
    while (keepGoing && !signal.aborted) {
      const page = await supportApi.activities(id, admin, first ? undefined : { after: latest.current }, signal)
      if (signal.aborted) return
      if (first) {
        setHasOlder(page.has_more); oldest.current = page.oldest_cursor
        firstUnread.current = page.first_unread_cursor ?? null
        scrollAfterRender.current = !firstUnread.current
        activitiesLoaded.current = true
      } else if (page.items.length) {
        if (nearBottom.current) scrollAfterRender.current = true
        else setShowLatest(true)
      }
      if (page.newest_cursor) latest.current = page.newest_cursor
      if (!oldest.current && page.oldest_cursor) oldest.current = page.oldest_cursor
      if (page.items.length) {
        const receivedKeys = new Set(page.items.map(item => item.client_key).filter(Boolean))
        setPending(previous => previous.filter(item => !receivedKeys.has(item.key)))
        setItems(previous => merge(previous, page.items))
      }
      const needsDetail = shouldFetchConversationDetail(detailLoaded.current, page.items.length)
      if (needsDetail) {
        const next = await supportApi.detail(id, admin, signal)
        if (signal.aborted) return
        setConversation(next); detailLoaded.current = true
      }
      keepGoing = !first && page.has_more
      first = false
    }
    if (!signal.aborted) { setError(""); void flushReadReceipts() }
  }, [id, admin, flushReadReceipts])

  useVisiblePoll(async signal => {
    try { await poll(signal) } catch (reason) {
      if (!signal.aborted) setError(reason instanceof Error ? reason.message : "消息加载失败")
      throw reason
    }
  }, !!id && !!identity, 2500, identity + ":" + (id ?? "new") + ":" + admin)

  useLayoutEffect(() => {
    const element = scroller.current
    if (!element) return
    if (preserveAnchor.current) {
      const anchor = preserveAnchor.current
      const target = [...element.querySelectorAll<HTMLElement>("[data-cursor]")]
        .find(node => node.dataset.cursor === anchor.cursor)
      if (target) element.scrollTop += target.getBoundingClientRect().top - element.getBoundingClientRect().top - anchor.offset
      preserveAnchor.current = null
    } else if (firstUnread.current) {
      const target = [...element.querySelectorAll<HTMLElement>("[data-cursor]")]
        .find(node => node.dataset.cursor === firstUnread.current)
      if (target) {
        element.scrollTop += target.getBoundingClientRect().top - element.getBoundingClientRect().top - element.clientHeight / 3
        nearBottom.current = false
      }
      firstUnread.current = null
    } else if (scrollAfterRender.current && nearBottom.current) element.scrollTop = element.scrollHeight
    scrollAfterRender.current = false
  }, [items, pending])

  useLayoutEffect(() => {
    const node = textarea.current
    if (!node) return
    node.style.height = "auto"
    node.style.height = Math.min(128, node.scrollHeight) + "px"
  }, [content])

  useEffect(() => {
    const viewport = window.visualViewport
    const keepLiveEdge = () => {
      if (!nearBottom.current) return
      requestAnimationFrame(() => {
        const node = scroller.current
        if (node && nearBottom.current) node.scrollTop = node.scrollHeight
      })
    }
    viewport?.addEventListener("resize", keepLiveEdge)
    window.addEventListener("resize", keepLiveEdge)
    return () => {
      viewport?.removeEventListener("resize", keepLiveEdge)
      window.removeEventListener("resize", keepLiveEdge)
    }
  }, [])

  const observeVisible = useCallback((cursors: string[]) => {
    readQueue.current.enqueue(cursors)
    void flushReadReceipts()
  }, [flushReadReceipts])

  useEffect(() => {
    const element = scroller.current
    if (!id || !element || !items.length) return
    const observer = new IntersectionObserver(entries => {
      if (document.visibilityState !== "visible") return
      const visible = entries.flatMap(entry => {
        const cursor = (entry.target as HTMLElement).dataset.cursor
        return entry.isIntersecting && cursor ? [cursor] : []
      })
      if (visible.length) observeVisible(visible)
    }, { root: element, threshold: 0.1 })
    const nodes = () => element.querySelectorAll<HTMLElement>("[data-cursor]").forEach(node => observer.observe(node))
    nodes()
    const resume = () => {
      if (document.visibilityState !== "visible") return
      observer.disconnect(); nodes()
      void flushReadReceipts()
    }
    document.addEventListener("visibilitychange", resume)
    return () => { observer.disconnect(); document.removeEventListener("visibilitychange", resume) }
  }, [items, id, observeVisible, flushReadReceipts])

  async function loadOlder() {
    if (!id || !oldest.current || loadingOlder) return
    const controller = lifecycle.current
    const element = scroller.current
    if (!controller || !element) return
    const bounds = element.getBoundingClientRect()
    const anchor = [...element.querySelectorAll<HTMLElement>("[data-cursor]")].find(node => node.getBoundingClientRect().bottom >= bounds.top)
    if (anchor?.dataset.cursor) preserveAnchor.current = { cursor: anchor.dataset.cursor, offset: anchor.getBoundingClientRect().top - bounds.top }
    setLoadingOlder(true)
    try {
      const page = await supportApi.activities(id, admin, { before: oldest.current }, controller.signal)
      if (controller.signal.aborted) return
      setItems(previous => merge(previous, page.items)); setHasOlder(page.has_more)
      if (page.oldest_cursor) oldest.current = page.oldest_cursor
    } catch (reason) { if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : "加载失败") }
    finally { setLoadingOlder(false) }
  }

  async function deliver(message: Pending) {
    const controller = lifecycle.current
    if (!controller || controller.signal.aborted) return
    setPending(previous => previous.map(item => item.key === message.key ? { ...item, state: "sending", error: undefined } : item))
    try {
      await supportApi.send({ conversationId: message.context.conversationId, orderId: message.context.orderId, requestId: message.context.requestId,
        round: message.context.round, noticeId: message.context.noticeId, clientKey: message.key, content: message.content, files: message.files }, admin, message.context.afterSale, controller.signal)
      if (controller.signal.aborted) return
      setPending(previous => previous.map(item => item.key === message.key ? { ...item, state: "sent" } : item))
      notifyMessagesChanged()
    } catch (reason) {
      if (!controller.signal.aborted) setPending(previous => previous.map(item => item.key === message.key
        ? { ...item, state: "failed", error: reason instanceof Error ? reason.message : "发送失败" } : item))
    }
  }

  function send(event?: FormEvent) {
    event?.preventDefault()
    const value = content.trim()
    if (!id || (!value && !files.length) || (activeSale && !value) || sending) return
    const message = createPendingSupportMessage(messageKey(), {
      conversationId: id, afterSale: !!activeSale, orderId: activeSale?.orderId ?? orderId,
      requestId: activeSale?.requestId, round: activeSale?.round, noticeId,
    }, value, files)
    setContent(""); setFiles([])
    try { window.localStorage.removeItem(draftKey(identity, id)) } catch { /* Local draft storage is optional. */ }
    setPending(previous => [...previous, message])
    if (nearBottom.current) scrollAfterRender.current = true
    void deliver(message)
  }

  function keyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key !== "Enter" || event.shiftKey || event.nativeEvent.isComposing || event.nativeEvent.keyCode === 229) return
    if (!window.matchMedia("(pointer: fine)").matches) return
    event.preventDefault(); form.current?.requestSubmit()
  }

  function changeContent(value: string) {
    setContent(value)
    if (identity && id) try { window.localStorage.setItem(draftKey(identity, id), value) } catch { /* Local draft storage is optional. */ }
  }

  function addFiles(selected: File[]) { setFiles(previous => [...previous, ...selected].slice(0, 5)) }

  const saleCards = admin ? conversation?.pending_after_sales ?? [] : []
  const displayedOrderId = activeSale?.orderId ?? orderId
  const displayedOrderNo = activeSale?.orderNo || orderNo
  const adminCustomer = adminUserId ?? conversation?.user_id ?? null

  return <section className={`relative flex min-h-0 min-w-0 flex-1 flex-col ${admin ? "overflow-hidden rounded-card border border-border-subtle bg-surface" : "bg-background"} ${className}`}>
    {showHeader && admin && <header className="flex min-h-14 shrink-0 items-center justify-between gap-2 border-b border-border-subtle px-4 py-2">
      <div className="min-w-0"><h2 className="truncate font-semibold">{conversation?.customer_name || "客服对话"}</h2>
        <p className="truncate text-xs text-muted-foreground">{conversation?.site_name || "主站"}{conversation?.customer_level_name ? " · " + conversation.customer_level_name : ""}</p></div>
      {displayedOrderId && <Link href={"/admin/orders/" + encodeURIComponent(displayedOrderId)} className="shrink-0 text-sm text-primary">查看订单</Link>}
    </header>}
    {error && <div role="alert" className="flex shrink-0 items-center justify-between gap-2 px-4 py-2 text-sm text-destructive">
      <span>{error}</span><button type="button" className="shrink-0 underline" onClick={() => { const controller = lifecycle.current; if (controller) void poll(controller.signal).catch(reason => setError(reason instanceof Error ? reason.message : "消息加载失败")) }}>重试</button>
    </div>}
    {saleCards.length > 0 && <div className="flex shrink-0 gap-2 overflow-x-auto border-b border-border-subtle px-3 py-2">
      {saleCards.map(sale => {
        const selected = activeSale?.requestId === sale.request_id
        return <button key={sale.request_id} type="button" onClick={() => setAdminSale({ orderId: sale.order_id, orderNo: sale.order_no, requestId: sale.request_id, round: sale.round })}
          className={`min-h-10 shrink-0 rounded-control border px-3 py-1.5 text-left text-xs ${selected ? "border-primary/40 bg-primary/10 text-primary" : "border-border-subtle hover:bg-surface-muted"}`}>
          订单 #{sale.order_no} · 第 {sale.round} 次售后 · {sale.waiting_for === "ADMIN" ? "待客服" : "待顾客"}
        </button>
      })}
    </div>}
    {saleCards.length > 0 && <div className="sr-only" aria-live="polite">待处理售后 {saleCards.length} 项</div>}
    <div ref={scroller} onScroll={event => {
      const node = event.currentTarget
      nearBottom.current = node.scrollHeight - node.scrollTop - node.clientHeight < 96
      if (nearBottom.current) setShowLatest(false)
    }} className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-3 py-4 sm:px-5" aria-label="会话消息">
      {hasOlder && <div className="mb-3 text-center"><Button type="button" variant="ghost" size="sm" disabled={loadingOlder} onClick={() => void loadOlder()}>{loadingOlder ? "加载中…" : "加载更早消息"}</Button></div>}
      {!conversation && !items.length && <div className="space-y-3" role="status" aria-label="正在加载消息"><div className="h-12 w-3/5 animate-pulse rounded-2xl bg-muted" /><div className="ml-auto h-12 w-2/3 animate-pulse rounded-2xl bg-muted" /></div>}
      {conversation && !items.length && <div className="flex min-h-full items-center justify-center text-sm text-muted-foreground">向客服发送消息</div>}
      <MessageGroup className="min-h-full justify-end gap-0.5">
        {items.map((item, index) => {
          const previous = items[index - 1]
          const next = items[index + 1]
          const newDate = !previous || dateKey(previous.created_at) !== dateKey(item.created_at)
          const senderChanged = !!previous && previous.sender_type !== item.sender_type
          const groupEnd = !next || next.sender_type !== item.sender_type || dateKey(next.created_at) !== dateKey(item.created_at)
          return <Fragment key={item.id}>
            {newDate && <Marker>{dateLabel(item.created_at)}</Marker>}
            <Activity item={item} admin={admin} adminUserId={adminCustomer} showTime={groupEnd}
              className={senderChanged ? "mt-2.5" : ""} />
          </Fragment>
        })}
        {pending.map(item => <Message key={item.key} align="end" className="mt-1" data-pending-key={item.key}>
          <Bubble variant="primary" className="[overflow-wrap:anywhere]">
            {item.content && <p className="whitespace-pre-wrap break-words">{item.content}</p>}
            {item.files.map((file, index) => <div key={file.name + ":" + file.size + ":" + index} className="mt-2"><PendingAttachment file={file} /></div>)}
          <div className="mt-1 text-right text-xs text-white/75">{item.state === "sending" ? "发送中…" : item.state === "sent" ? "已发送" : <span role="alert">{item.error || "发送失败"} <button type="button" className="underline" onClick={() => void deliver(item)}>重试</button></span>}</div>
          </Bubble>
        </Message>)}
      </MessageGroup>
    </div>
    {showLatest && <button type="button" onClick={() => {
      if (scroller.current) scroller.current.scrollTo({ top: scroller.current.scrollHeight, behavior: "smooth" })
      nearBottom.current = true; setShowLatest(false)
    }} className="absolute bottom-[5.7rem] left-1/2 z-10 inline-flex min-h-10 -translate-x-1/2 items-center gap-2 rounded-full border border-border-subtle bg-surface px-4 text-sm shadow-lg">
      <ArrowDown className="size-4" aria-hidden="true" />回到最新
    </button>}
    <form ref={form} onSubmit={send} className="shrink-0 border-t border-border-subtle bg-background px-2 py-2 sm:px-3" style={{ paddingBottom: "max(0.5rem, env(safe-area-inset-bottom))" }}>
      {(displayedOrderId || activeSale || noticeId) && <div className="mb-2 flex min-w-0 flex-wrap gap-1.5">
        {displayedOrderId && <ContextChip label={"订单 #" + (displayedOrderNo || "—")} onClear={activeSale && !orderId ? undefined : onClearOrder} />}
        {activeSale && <ContextChip label={"第 " + activeSale.round + " 次售后"} onClear={admin ? () => setAdminSale(null) : onClearAfterSale} />}
        {afterSaleRequestId && !activeSale && <ContextChip label="售后引用不完整" onClear={onClearAfterSale} />}
        {noticeId && <ContextChip label={"通知：" + (noticeTitle || "商城通知")} onClear={onClearNotice} />}
      </div>}
      {files.length > 0 && <div className="mb-2 flex max-h-24 gap-2 overflow-x-auto pb-1">
        {files.map((file, index) => <PendingAttachment key={file.name + ":" + index} file={file} onRemove={() => setFiles(previous => previous.filter((_, current) => current !== index))} />)}
      </div>}
      <div className="flex min-w-0 items-end gap-2">
        <label className="inline-flex size-11 shrink-0 cursor-pointer items-center justify-center rounded-full text-muted-foreground hover:bg-muted hover:text-foreground focus-within:ring-2 focus-within:ring-ring" aria-label="添加图片">
          <Paperclip className="size-5" aria-hidden="true" />
          <input type="file" className="sr-only" multiple accept="image/jpeg,image/png,image/gif,image/webp,image/svg+xml" disabled={!id || sending}
            onChange={event => { addFiles(Array.from(event.currentTarget.files || [])); event.currentTarget.value = "" }} />
        </label>
        <div className="flex min-h-11 min-w-0 flex-1 items-end rounded-[1.35rem] border border-input bg-surface px-3 py-1.5 focus-within:ring-2 focus-within:ring-ring/40">
          <Textarea ref={textarea} aria-label="消息内容" className="min-h-8 max-h-32 resize-none border-0 bg-transparent px-0 py-1 text-[15px] leading-5 shadow-none focus-visible:ring-0" rows={1} maxLength={1000} value={content}
            onChange={event => changeContent(event.target.value)} onKeyDown={keyDown} placeholder="输入消息" disabled={!id} />
        </div>
        <Button type="submit" variant="default" size="icon" className="size-11 shrink-0 rounded-full" aria-label="发送消息" disabled={!id || sending || (!content.trim() && (!files.length || !!activeSale))}>
          <Send className="size-4" aria-hidden="true" />
        </Button>
      </div>
    </form>
  </section>
}

function ContextChip({ label, onClear }: { label: string; onClear?: () => void }) {
  return <span className="inline-flex min-h-8 max-w-full items-center gap-1 rounded-full bg-primary/10 px-2.5 text-xs text-primary">
    <span className="min-w-0 truncate">{label}</span>{onClear && <button type="button" aria-label={"移除" + label} onClick={onClear} className="flex size-6 shrink-0 items-center justify-center rounded-full hover:bg-primary/10"><X className="size-3.5" aria-hidden="true" /></button>}
  </span>
}

function Activity({ item, admin, adminUserId, showTime, className = "" }: { item: SupportActivity; admin: boolean; adminUserId: string | null; showTime: boolean; className?: string }) {
  const system = item.sender_type === "SYSTEM"
  const mine = !system && (admin ? item.sender_type === "ADMIN" : item.sender_type === "CUSTOMER")
  const label = item.source_type === "AFTER_SALE_APPLY" ? "第 " + (item.round ?? "—") + " 次售后申请"
    : item.source_type === "AFTER_SALE_REJECT" ? "第 " + (item.round ?? "—") + " 次售后已驳回"
    : item.source_type === "ORDER_REFUND" ? "退款结果"
    : item.source_type === "SUPPLIER_UPDATE" ? (item.round ? "第 " + item.round + " 次售后 · " : "") + "处理更新"
    : system ? "系统" : ""
  const amount = item.amount == null ? null : formatYuan(item.amount)
  const alignment = system ? "flex-col items-center justify-center"
    : mine ? "flex-col items-end" : "flex-col items-start"
  return <Message align={mine ? "end" : "start"} className={alignment + " " + className} data-cursor={item.cursor}>
    <Bubble variant={system ? "system" : mine ? "primary" : "muted"}>
      {label && <div className={`mb-1 text-[11px] ${mine ? "text-primary-foreground/75" : "text-muted-foreground"}`}>{label}</div>}
      {item.source_type === "ORDER_REFUND" && <div className="space-y-1">
        <p>{item.refund_amount == null
          ? "退款处理结果已更新，金额或去向暂不可用。"
          : item.refund_destination === "NO_PAYMENT"
            ? <>订单 {item.order_id && <OrderReference orderId={item.order_id} orderNo={item.order_no} admin={admin} />} 已关闭。</>
            : <>订单 {item.order_id && <OrderReference orderId={item.order_id} orderNo={item.order_no} admin={admin} />} 已退款 {formatYuan(item.refund_amount)}，款项已退回账户余额。</>}</p>
        {item.ledger_id && <BalanceReference id={item.ledger_id} admin={admin} userId={adminUserId} />}
      </div>}
      {item.source_type === "SYSTEM_RESULT" && <SystemResult item={item} amount={amount} admin={admin} adminUserId={adminUserId} />}
      {item.content && <p className="whitespace-pre-wrap break-words">{item.content}</p>}
      {(item.source_type === "SUPPORT_MESSAGE" || item.source_type === "AFTER_SALE_MESSAGE" || item.source_type === "AFTER_SALE_APPLY"
        || item.source_type === "AFTER_SALE_REJECT" || item.source_type === "SUPPLIER_UPDATE") && item.order_id
        && <p className="mt-1 text-sm">订单 <OrderReference orderId={item.order_id} orderNo={item.order_no} admin={admin} /></p>}
      {item.notice_id && <p className="mt-1 text-xs opacity-75">引用通知：{item.notice_title || "商城通知"}</p>}
      {item.source_type === "SUPPLIER_UPDATE" && <p className="mt-1 text-xs opacity-75">{({1:"等待处理",2:"已受理",3:"处理完成",4:"审核终止"} as Record<number,string>)[item.supplier_status ?? 0] || "处理中"}</p>}
      {item.attachments.length > 0 && <div className="mt-2 flex flex-wrap gap-2">{item.attachments.map(attachment => <PrivateAttachment key={attachment.id} attachment={attachment} />)}</div>}
    </Bubble>
    {showTime && item.created_at && <time className={`mt-0.5 block whitespace-nowrap px-1 text-[10px] text-muted-foreground ${mine ? "text-right" : "text-left"}`}>{timeLabel(item.created_at)}</time>}
  </Message>
}

function OrderReference({ orderId, orderNo, admin }: { orderId: string; orderNo?: string | null; admin: boolean }) {
  const href = (admin ? "/admin/orders/" : "/order/") + encodeURIComponent(orderId)
  return <Link href={href} className="break-all font-mono text-sm underline underline-offset-2">#{orderNo || "查看订单"}</Link>
}

function SystemResult({ item, amount, admin, adminUserId }: { item: SupportActivity; amount: string | null; admin: boolean; adminUserId: string | null }) {
  if (item.event_type === "ORDER_DELIVERED" && item.manual_delivery) return <p>您的订单{item.order_id ? <> <OrderReference orderId={item.order_id} orderNo={item.order_no} admin={admin} /></> : ""} 已完成人工发货，请查看订单详情。</p>
  if (item.event_type === "ORDER_DELIVERED") return <p>订单{item.order_id ? <> <OrderReference orderId={item.order_id} orderNo={item.order_no} admin={admin} /></> : ""} 已完成发货或直充。</p>
  if (item.event_type === "RECHARGE_CREDITED") return <p>充值{amount ? " " + amount : ""}已到账。{item.ledger_id && <BalanceReference id={item.ledger_id} admin={admin} userId={adminUserId} />}</p>
  if (item.event_type === "LEVEL_UPGRADE_COMPLETED") return <p>会员等级已升级至{item.level_name || "新等级"}。</p>
  if (item.event_type === "LEVEL_UPGRADE_RECOVERED") return <p>等级升级未完成，{amount ? amount + " " : "款项"}已转入账户余额。{item.ledger_id && <BalanceReference id={item.ledger_id} admin={admin} userId={adminUserId} />}</p>
  if (item.event_type === "BALANCE_ADJUSTMENT") return <p>账户余额{item.direction === "CREDIT" ? "增加" : "扣减"}{amount ? " " + amount : ""}，当前余额 {item.balance_after == null ? "—" : formatYuan(item.balance_after)}。{item.ledger_id && <BalanceReference id={item.ledger_id} admin={admin} userId={adminUserId} />}</p>
  return null
}

function BalanceReference({ id, admin, userId }: { id: string; admin: boolean; userId: string | null }) {
  const href = admin && userId
    ? "/admin/users/" + encodeURIComponent(userId) + "?tab=balance&ledger=" + encodeURIComponent(id) + "#balance-records"
    : "/my/balance?ledger=" + encodeURIComponent(id) + "#history"
  return <Link href={href} className="ml-1 inline-flex min-h-8 items-center underline underline-offset-2">账户余额明细</Link>
}

function PendingAttachment({ file, onRemove }: { file: File; onRemove?: () => void }) {
  const [url, setUrl] = useState<string | null>(null)
  useEffect(() => {
    if (!file.type.startsWith("image/")) return
    const objectUrl = URL.createObjectURL(file); setUrl(objectUrl)
    return () => URL.revokeObjectURL(objectUrl)
  }, [file])
  return <ChatAttachment className="bg-background/20">
    {url && <AttachmentMedia variant="image" className="size-10"><ImagePreview url={url} alt={file.name} /></AttachmentMedia>}
    <AttachmentContent><AttachmentTitle className="max-w-36">{file.name}</AttachmentTitle></AttachmentContent>
    {onRemove && <AttachmentActions><button type="button" aria-label={"移除附件 " + file.name} className="inline-flex size-7 items-center justify-center rounded-full hover:bg-muted" onClick={onRemove}><X className="size-3.5" aria-hidden="true" /></button></AttachmentActions>}
  </ChatAttachment>
}

function ImagePreview({ url, alt }: { url: string; alt: string }) {
  // eslint-disable-next-line @next/next/no-img-element
  return <img src={url} alt={alt} className="size-10 rounded object-cover" />
}

function PrivateAttachment({ attachment }: { attachment: SupportAttachment }) {
  const [url, setUrl] = useState<string | null>(null)
  const [error, setError] = useState(false)
  const [retry, setRetry] = useState(0)
  const [open, setOpen] = useState(false)
  useEffect(() => {
    const controller = new AbortController(); let objectUrl: string | null = null
    setError(false); setUrl(null)
    supportApi.attachment(attachment.url, controller.signal).then(blob => {
      if (controller.signal.aborted) return
      objectUrl = URL.createObjectURL(blob); setUrl(objectUrl)
    }).catch(() => { if (!controller.signal.aborted) setError(true) })
    return () => { controller.abort(); if (objectUrl) URL.revokeObjectURL(objectUrl) }
  }, [attachment.url, retry])
  if (error) return <button type="button" className="inline-flex h-32 w-36 items-center justify-center rounded-control text-xs text-destructive" onClick={() => setRetry(value => value + 1)}>图片加载失败 · 重试</button>
  if (!url) return <span className="inline-flex h-32 w-36 items-center justify-center rounded-control text-xs text-muted-foreground">加载图片…</span>
  if (attachment.content_type === "image/svg+xml") return <a href={url} download="附件.svg" className="text-xs underline">下载附件</a>
  return <>
    <button type="button" onClick={() => setOpen(true)} className="flex h-32 w-36 items-center justify-center rounded-control focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" aria-label="查看聊天图片">
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img src={url} alt="聊天图片缩略图" width={144} height={128} className="max-h-32 max-w-36 rounded-control object-contain" />
    </button>
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-4xl border-0 bg-transparent p-0 shadow-none">
        <DialogTitle className="sr-only">聊天图片</DialogTitle>
        <DialogClose className="absolute right-2 top-2 z-10 inline-flex size-10 items-center justify-center rounded-full bg-background/80 text-foreground" aria-label="关闭图片"><X className="size-5" /></DialogClose>
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img src={url} alt="聊天图片" className="mx-auto max-h-[85dvh] max-w-full rounded-control object-contain" />
      </DialogContent>
    </Dialog>
  </>
}
