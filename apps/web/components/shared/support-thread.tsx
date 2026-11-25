"use client"

import { Fragment, useCallback, useEffect, useLayoutEffect, useRef, useState } from "react"
import Link from "next/link"
import { X } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Textarea } from "@/components/ui/textarea"
import { notifyMessagesChanged } from "@/components/store/message-summary"
import { useVisiblePoll } from "@/hooks/use-visible-poll"
import { formatYuan } from "@/lib/utils"
import { supportApi, type SupportActivity, type SupportAttachment, type SupportConversation } from "@/services/api"

type SaleContext = { orderId: string; orderNo: string; requestId: string; round: number }
type Pending = {
  key: string; content: string; files: File[]; afterSale: boolean; orderId?: string; requestId?: string
  round?: number; noticeId?: string; state: "sending" | "sent" | "failed"; error?: string
}
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
  identity: string
}

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

function dateKey(value: string | null) {
  if (!value) return "unknown"
  const date = new Date(value)
  return `${date.getFullYear()}-${date.getMonth()}-${date.getDate()}`
}
function dateLabel(value: string | null) {
  if (!value) return "历史记录"
  return new Date(value).toLocaleDateString(undefined, { year: "numeric", month: "long", day: "numeric" })
}
function timeLabel(value: string | null) {
  if (!value) return ""
  return new Date(value).toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" })
}

export function SupportThread({ id, orderId, orderNo, afterSaleRequestId, afterSaleRound, noticeId, noticeTitle,
  onClearOrder, onClearAfterSale, onClearNotice, admin = false, identity }: Props) {
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
  const loaded = useRef(false)
  const latest = useRef("0")
  const oldest = useRef<string | null>(null)
  const firstUnread = useRef<string | null>(null)
  const nearBottom = useRef(true)
  const scrollAfterRender = useRef(false)
  const preserveHeight = useRef<number | null>(null)
  const lifecycle = useRef<AbortController | null>(null)
  const readQueued = useRef(new Set<string>())
  const readSaved = useRef(new Set<string>())
  const reading = useRef(false)
  const root = useRef<HTMLDivElement>(null)
  const fitViewport = useRef<(() => void) | null>(null)
  const [height, setHeight] = useState<number | undefined>()
  const [keyboardSpace, setKeyboardSpace] = useState(0)
  const sending = pending.some(item => item.state === "sending")
  const activeSale = admin ? adminSale : afterSaleRequestId && orderId && afterSaleRound
    ? { orderId, orderNo: orderNo ?? "", requestId: afterSaleRequestId, round: afterSaleRound } : null
  useEffect(() => {
    const controller = new AbortController(); lifecycle.current = controller
    loaded.current = false; latest.current = "0"; oldest.current = null; firstUnread.current = null
    setConversation(null); setItems([]); setPending([]); setError(""); setShowLatest(false)
    readQueued.current.clear(); readSaved.current.clear()
    return () => { controller.abort(); lifecycle.current = null }
  }, [identity, id])

  useEffect(() => {
    const viewport = window.visualViewport
    const resize = () => {
      if (!root.current) return
      const bottom = viewport ? viewport.height + viewport.offsetTop : window.innerHeight
      const top = Math.max(root.current.getBoundingClientRect().top, viewport?.offsetTop ?? 0)
      setHeight(Math.max(260, Math.floor(bottom - top - 8)))
      if (nearBottom.current) scrollAfterRender.current = true
    }
    const adjust = () => {
      if (!root.current) return
      const bottom = viewport ? viewport.height + viewport.offsetTop : window.innerHeight
      const top = Math.max(root.current.getBoundingClientRect().top, viewport?.offsetTop ?? 0)
      const shortage = 260 - (bottom - top - 8)
      if (shortage > 0) window.scrollBy({ top: shortage, behavior: "instant" })
      resize()
    }
    const fit = () => {
      const focused = !!root.current?.contains(document.activeElement)
      setKeyboardSpace(focused && viewport ? Math.max(0, Math.ceil(window.innerHeight - viewport.height)) : 0)
      adjust()
    }
    fitViewport.current = adjust
    const observer = new ResizeObserver(resize)
    observer.observe(document.body)
    if (root.current?.parentElement) observer.observe(root.current.parentElement)
    const element = root.current
    fit(); viewport?.addEventListener("resize", fit); viewport?.addEventListener("scroll", resize)
    window.addEventListener("resize", fit); window.addEventListener("scroll", resize, { passive: true })
    element?.addEventListener("focusin", fit)
    return () => {
      observer.disconnect(); element?.removeEventListener("focusin", fit)
      viewport?.removeEventListener("resize", fit); viewport?.removeEventListener("scroll", resize)
      window.removeEventListener("resize", fit); window.removeEventListener("scroll", resize)
      fitViewport.current = null
    }
  }, [])
  useLayoutEffect(() => { fitViewport.current?.() }, [keyboardSpace])

  const flushReadReceipts = useCallback(async () => {
    const controller = lifecycle.current
    if (!id || !controller || reading.current || !readQueued.current.size) return
    reading.current = true
    try {
      while (readQueued.current.size && !controller.signal.aborted) {
        const batch = [...readQueued.current].slice(0, 50)
        batch.forEach(cursor => readQueued.current.delete(cursor))
        await supportApi.read(id, batch, admin, controller.signal)
        batch.forEach(cursor => readSaved.current.add(cursor))
        notifyMessagesChanged()
      }
    } catch {
      // The next visible observation or foreground catch-up retries these exact rows.
      return
    } finally { reading.current = false }
  }, [id, admin])

  const poll = useCallback(async (signal: AbortSignal) => {
    if (!id) return
    let first = !loaded.current
    let keepGoing = true
    while (keepGoing && !signal.aborted) {
      const page = await supportApi.activities(id, admin, first ? undefined : { after: latest.current }, signal)
      if (signal.aborted) return
      if (first) {
        setHasOlder(page.has_more); oldest.current = page.oldest_cursor; loaded.current = true
        firstUnread.current = page.first_unread_cursor ?? null
        scrollAfterRender.current = !firstUnread.current
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
      if (first || page.items.length) {
        const next = await supportApi.detail(id, admin, signal)
        if (!signal.aborted) setConversation(next)
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
  }, !!id && !!identity, 2500, `${identity}:${id ?? "new"}:${admin}`)

  useLayoutEffect(() => {
    const element = scroller.current
    if (!element) return
    if (preserveHeight.current !== null) {
      element.scrollTop += element.scrollHeight - preserveHeight.current
      preserveHeight.current = null
    } else if (firstUnread.current) {
      const target = element.querySelector<HTMLElement>(`[data-cursor="${firstUnread.current}"]`)
      if (target) {
        element.scrollTop += target.getBoundingClientRect().top - element.getBoundingClientRect().top - element.clientHeight / 3
        nearBottom.current = false
      }
      firstUnread.current = null
    } else if (scrollAfterRender.current && nearBottom.current) element.scrollTop = element.scrollHeight
    scrollAfterRender.current = false
  }, [items, pending, height])

  const observeVisible = useCallback((cursors: string[]) => {
    cursors.forEach(cursor => { if (!readSaved.current.has(cursor)) readQueued.current.add(cursor) })
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
    }
    document.addEventListener("visibilitychange", resume)
    return () => { observer.disconnect(); document.removeEventListener("visibilitychange", resume) }
  }, [items, id, observeVisible])

  async function loadOlder() {
    if (!id || !oldest.current || loadingOlder) return
    const controller = lifecycle.current
    if (!controller) return
    setLoadingOlder(true)
    preserveHeight.current = scroller.current?.scrollHeight ?? null
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
    if (!controller) return
    setPending(previous => previous.map(item => item.key === message.key ? { ...item, state: "sending", error: undefined } : item))
    try {
      await supportApi.send({ conversationId: id, orderId: message.orderId, requestId: message.requestId,
        round: message.round, noticeId: message.noticeId, clientKey: message.key, content: message.content, files: message.files }, admin, message.afterSale, controller.signal)
      if (controller.signal.aborted) return
      setPending(previous => previous.map(item => item.key === message.key ? { ...item, state: "sent" } : item))
      notifyMessagesChanged()
      if (!id) return
    } catch (reason) {
      if (!controller.signal.aborted) setPending(previous => previous.map(item => item.key === message.key
        ? { ...item, state: "failed", error: reason instanceof Error ? reason.message : "发送失败" } : item))
    }
  }

  function send(event: React.FormEvent) {
    event.preventDefault()
    const value = content.trim()
    const sale = !!activeSale
    if ((!value && !files.length) || (sale && !value) || sending) return
    const message: Pending = {
      key: messageKey(), content: value, files, afterSale: sale, orderId: activeSale?.orderId ?? orderId,
      requestId: activeSale?.requestId, round: activeSale?.round, noticeId, state: "sending",
    }
    setContent(""); setFiles([]); setPending(previous => [...previous, message])
    if (nearBottom.current) scrollAfterRender.current = true
    void deliver(message)
  }

  const saleCards = admin ? conversation?.pending_after_sales ?? [] : []
  const displayedOrderId = activeSale?.orderId ?? orderId
  const displayedOrderNo = activeSale?.orderNo || orderNo

  return <div ref={root} style={{ height, marginBottom: keyboardSpace }} className="flex min-h-[260px] flex-col rounded-card border border-border-subtle bg-surface">
    <header className="flex min-h-14 shrink-0 items-center justify-between gap-2 border-b border-border-subtle px-4 py-2">
      <div className="min-w-0"><h2 className="font-semibold">{admin ? conversation?.customer_name || "客服对话" : "商城客服"}</h2>
        {admin && <p className="truncate text-xs text-muted-foreground">{conversation?.site_name || "主站"}{conversation?.customer_level_name ? ` · ${conversation.customer_level_name}` : ""}</p>}</div>
      {admin && displayedOrderId && <Link href={`/admin/orders/${displayedOrderId}`} className="shrink-0 text-sm text-primary">查看订单</Link>}
    </header>
    {error && <p role="alert" className="shrink-0 px-4 py-2 text-sm text-destructive">{error}</p>}
    {saleCards.length > 0 && <div className="flex shrink-0 flex-wrap gap-2 border-b border-border-subtle px-3 py-2">
      {saleCards.map(sale => {
        const selected = activeSale?.requestId === sale.request_id
        return <button key={sale.request_id} type="button" onClick={() => setAdminSale({ orderId: sale.order_id, orderNo: sale.order_no, requestId: sale.request_id, round: sale.round })}
          className={`min-h-10 max-w-full rounded-control border px-3 py-1.5 text-left text-xs ${selected ? "border-primary/40 bg-primary/10 text-primary" : "border-border-subtle hover:bg-surface-muted"}`}>
          订单 #{sale.order_no} · 第 {sale.round} 次售后 · {sale.waiting_for === "ADMIN" ? "待客服" : "待顾客"}
        </button>
      })}
    </div>}
    {showLatest && <div className="flex shrink-0 items-center justify-center border-b border-border-subtle px-3 py-1.5">
      <Button type="button" variant="ghost" size="sm" onClick={() => { if (scroller.current) scroller.current.scrollTop = scroller.current.scrollHeight; nearBottom.current = true; setShowLatest(false) }}>有新消息 · 回到最新</Button>
    </div>}
    <div ref={scroller} onScroll={event => {
      const node = event.currentTarget
      nearBottom.current = node.scrollHeight - node.scrollTop - node.clientHeight < 96
      if (nearBottom.current) setShowLatest(false)
    }} className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-4 py-4" aria-label="会话消息">
      {hasOlder && <div className="mb-4 text-center"><Button type="button" variant="ghost" size="sm" disabled={loadingOlder} onClick={() => void loadOlder()}>{loadingOlder ? "加载中…" : "加载更早消息"}</Button></div>}
      {!conversation && <div className="space-y-3" role="status" aria-label="正在加载消息"><div className="h-14 animate-pulse rounded-control bg-muted" /><div className="ml-auto h-12 w-2/3 animate-pulse rounded-control bg-muted" /></div>}
      {conversation && !items.length && <div className="flex min-h-full items-center justify-center text-sm text-muted-foreground">向客服发送消息</div>}
      <div className="space-y-3">
        {items.map((item, index) => <Fragment key={item.id}>
          {index === 0 || dateKey(items[index - 1].created_at) !== dateKey(item.created_at)
            ? <div className="py-2 text-center text-xs text-muted-foreground">{dateLabel(item.created_at)}</div> : null}
          <Activity item={item} admin={admin} />
        </Fragment>)}
        {pending.map(item => <div key={item.key} className="ml-auto max-w-[85%] rounded-control bg-primary/10 px-3 py-2 text-sm">
          <p className="whitespace-pre-wrap break-words">{item.content}</p>
          {item.files.map(file => <p className="break-all text-xs" key={`${file.name}:${file.size}`}>{file.name}</p>)}
          <div className="mt-1 text-xs text-muted-foreground">{item.state === "sending" ? "发送中…" : item.state === "sent" ? "已发送" : <span role="alert">{item.error || "发送失败"} <button type="button" className="text-primary underline" onClick={() => void deliver(item)}>重试</button></span>}</div>
        </div>)}
      </div>
    </div>
    <form onSubmit={send} className="flex shrink-0 flex-col gap-2 border-t border-border-subtle p-3" style={{ paddingBottom: "max(0.75rem, env(safe-area-inset-bottom))" }}>
      {(displayedOrderId || activeSale || noticeId) && <div className="flex min-w-0 flex-wrap gap-2">
        {displayedOrderId && <ContextChip label={`订单 #${displayedOrderNo || "—"}`} onClear={activeSale && !orderId ? undefined : onClearOrder} />}
        {activeSale && <ContextChip label={`第 ${activeSale.round} 次售后`} onClear={admin ? () => setAdminSale(null) : () => {
          setPending(previous => previous.map(item => item.state === "failed" && item.afterSale
            ? { ...item, afterSale: false, requestId: undefined, round: undefined } : item))
          onClearAfterSale?.()
        }} />}
        {afterSaleRequestId && !activeSale && <ContextChip label="售后引用不完整" onClear={onClearAfterSale} />}
        {noticeId && <ContextChip label={`通知：${noticeTitle || "商城通知"}`} onClear={onClearNotice} />}
      </div>}
      {files.length > 0 && <div className="flex min-h-0 max-h-20 shrink flex-wrap gap-2 overflow-y-auto">{files.map((file, index) => <button type="button" key={`${file.name}:${index}`} className="min-h-10 max-w-full truncate rounded bg-muted px-2 py-1 text-xs" onClick={() => setFiles(value => value.filter((_, i) => i !== index))} aria-label={`移除附件 ${file.name}`}>{file.name} <X className="inline size-3" aria-hidden="true" /></button>)}</div>}
      <Textarea aria-label="消息内容" className="min-h-11 max-h-28 resize-y text-base" rows={2} maxLength={1000} value={content}
        onChange={event => setContent(event.target.value)} placeholder="输入消息" disabled={!!id && !conversation} />
      <div className="flex shrink-0 items-center justify-between gap-2">
        <label className="inline-flex min-h-11 cursor-pointer items-center text-sm text-primary">添加图片<input type="file" className="sr-only" multiple accept="image/jpeg,image/png,image/gif,image/webp,image/svg+xml" onChange={event => { const selected = Array.from(event.target.files || []); setFiles(value => [...value, ...selected].slice(0, 5)); event.currentTarget.value = "" }} /></label>
        <Button type="submit" disabled={(!!id && !conversation) || sending || (!content.trim() && (!files.length || !!activeSale))}>发送</Button>
      </div>
    </form>
  </div>
}

function ContextChip({ label, onClear }: { label: string; onClear?: () => void }) {
  return <span className="inline-flex min-h-9 max-w-full items-center gap-1 rounded-full bg-primary/10 px-3 text-xs text-primary">
    <span className="min-w-0 truncate">{label}</span>{onClear && <button type="button" aria-label={`移除${label}`} onClick={onClear} className="flex size-6 shrink-0 items-center justify-center rounded-full hover:bg-primary/10"><X className="size-3.5" aria-hidden="true" /></button>}
  </span>
}

function Activity({ item, admin }: { item: SupportActivity; admin: boolean }) {
  const system = item.sender_type === "SYSTEM"
  const mine = !system && (admin ? item.sender_type === "ADMIN" : item.sender_type === "CUSTOMER")
  const label = item.source_type === "AFTER_SALE_APPLY" ? `第 ${item.round ?? "—"} 次售后申请`
    : item.source_type === "AFTER_SALE_REJECT" ? `第 ${item.round ?? "—"} 次售后已驳回`
    : item.source_type === "ORDER_REFUND" ? "退款结果"
    : item.source_type === "SUPPLIER_UPDATE" ? `${item.round ? `第 ${item.round} 次售后 · ` : ""}处理更新`
    : system ? "系统" : ""
  const amount = item.amount == null ? null : formatYuan(item.amount)
  const hasBalanceLink = !!item.ledger_id
  return <div data-cursor={item.cursor} className={`${system ? "mx-auto max-w-[92%] border border-border-subtle bg-muted/50" : mine ? "ml-auto max-w-[85%] bg-primary/10" : "mr-auto max-w-[85%] bg-muted/60"} rounded-card px-3 py-2.5 text-sm`}>
    <div className="flex flex-wrap items-center justify-between gap-2 text-xs text-muted-foreground">{label ? <span>{label}</span> : <span />}{item.created_at && <time>{timeLabel(item.created_at)}</time>}</div>
    {item.source_type === "ORDER_REFUND" ? <div className="mt-1 space-y-1">
      <p>{item.refund_amount == null
        ? "退款处理结果已更新，金额或去向暂不可用。"
        : item.refund_destination === "NO_PAYMENT"
          ? <>{item.order_id && <OrderReference orderId={item.order_id} orderNo={item.order_no} />}零金额订单已关闭。</>
          : <>{item.order_id && <OrderReference orderId={item.order_id} orderNo={item.order_no} />}已退款 {formatYuan(item.refund_amount)}，款项已退回账户余额。</>}</p>
      {item.ledger_id && <Link href={`/my/balance?ledger=${encodeURIComponent(item.ledger_id)}#history`} className="inline-flex min-h-10 items-center text-primary underline underline-offset-2">查看余额明细</Link>}
    </div> : null}
    {item.source_type === "SYSTEM_RESULT" && <SystemResult item={item} amount={amount} hasBalanceLink={hasBalanceLink} />}
    {item.content && <p className={`${item.source_type === "SYSTEM_RESULT" || item.source_type === "ORDER_REFUND" ? "mt-1" : "mt-1"} whitespace-pre-wrap break-words`}>{item.content}</p>}
    {(item.source_type === "SUPPORT_MESSAGE" || item.source_type === "AFTER_SALE_MESSAGE") && item.order_id && <div className="mt-1"><OrderReference orderId={item.order_id} orderNo={item.order_no} /></div>}
    {item.notice_id && <p className="mt-1 text-xs text-muted-foreground">引用通知：{item.notice_title || "商城通知"}</p>}
    {item.source_type === "SUPPLIER_UPDATE" && <p className="mt-1 text-xs text-muted-foreground">{({1:"等待处理",2:"已受理",3:"处理完成",4:"审核终止"} as Record<number,string>)[item.supplier_status ?? 0] || "处理中"}</p>}
    {item.attachments.length > 0 && <div className="mt-2 flex flex-wrap gap-2">{item.attachments.map(attachment => <PrivateAttachment key={attachment.id} attachment={attachment} />)}</div>}
  </div>
}

function OrderReference({ orderId, orderNo }: { orderId: string; orderNo?: string | null }) {
  return <Link href={`/order/${encodeURIComponent(orderId)}`} className="mr-1 inline-flex min-h-8 max-w-full items-center break-all font-mono text-xs text-primary underline underline-offset-2">订单 #{orderNo || "查看订单"}</Link>
}

function SystemResult({ item, amount, hasBalanceLink }: { item: SupportActivity; amount: string | null; hasBalanceLink: boolean }) {
  if (item.event_type === "ORDER_DELIVERED") return <p className="mt-1">订单{item.order_id ? <> <OrderReference orderId={item.order_id} orderNo={item.order_no} /></> : ""}已完成发货或直充。</p>
  if (item.event_type === "RECHARGE_CREDITED") return <p className="mt-1">充值{amount ? ` ${amount}` : ""}已到账。{hasBalanceLink && <BalanceReference id={item.ledger_id!} />}</p>
  if (item.event_type === "LEVEL_UPGRADE_COMPLETED") return <p className="mt-1">会员等级已升级至{item.level_name || "新等级"}。</p>
  if (item.event_type === "LEVEL_UPGRADE_RECOVERED") return <p className="mt-1">等级升级未完成，{amount ? `${amount} ` : "款项"}已转入账户余额。{hasBalanceLink && <BalanceReference id={item.ledger_id!} />}</p>
  if (item.event_type === "BALANCE_ADJUSTMENT") return <p className="mt-1">账户余额{item.direction === "CREDIT" ? "增加" : "扣减"}{amount ? ` ${amount}` : ""}，当前余额 {item.balance_after == null ? "—" : formatYuan(item.balance_after)}。{hasBalanceLink && <BalanceReference id={item.ledger_id!} />}</p>
  return null
}

function BalanceReference({ id }: { id: string }) {
  return <Link href={`/my/balance?ledger=${encodeURIComponent(id)}#history`} className="ml-1 inline-flex min-h-9 items-center text-primary underline underline-offset-2">账户余额明细</Link>
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
