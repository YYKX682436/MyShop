"use client"

import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react"
import Link from "next/link"
import { useSearchParams } from "next/navigation"
import { ArrowDown, ArrowLeft, Bell, Check, MessageCircle } from "lucide-react"
import { Button } from "@/components/ui/button"
import { MarkdownContent } from "@/components/shared/markdown-content"
import { notifyMessagesChanged } from "@/components/store/message-summary"
import { useSupportIdentity } from "@/hooks/use-support-identity"
import { useVisiblePoll } from "@/hooks/use-visible-poll"
import { announcementApi, type SiteAnnouncement } from "@/services/api"

type Feed = { identity: string; items: SiteAnnouncement[]; nextCursor: string | null; anchored: boolean }
const emptyFeed: Feed = { identity: "", items: [], nextCursor: null, anchored: false }

function chronological(items: SiteAnnouncement[]) {
  const unique = new Map(items.map(item => [item.id, item]))
  return [...unique.values()].sort((left, right) => {
    if (left.published_at === null) return right.published_at === null ? left.id.localeCompare(right.id) : -1
    if (right.published_at === null) return 1
    const time = left.published_at.localeCompare(right.published_at)
    return time || left.id.localeCompare(right.id)
  })
}

function noticeTime(value: string | null) {
  if (!value) return "发布时间未知"
  return new Date(value).toLocaleString(undefined, { month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit" })
}

export default function NotificationsPage() {
  const identity = useSupportIdentity()
  const search = useSearchParams()
  const focus = search.get("focus")
  const [feed, setFeed] = useState<Feed>(emptyFeed)
  const [incoming, setIncoming] = useState<{ identity: string; items: SiteAnnouncement[] }>({ identity: "", items: [] })
  const [loading, setLoading] = useState(true)
  const [loadingOlder, setLoadingOlder] = useState(false)
  const [markingAll, setMarkingAll] = useState(false)
  const [error, setError] = useState("")
  const [focusError, setFocusError] = useState("")
  const feedElement = useRef<HTMLOListElement>(null)
  const preserveHeight = useRef<number | null>(null)
  const readIds = useRef(new Set<string>())
  const readInFlight = useRef(new Set<string>())
  const readControllers = useRef(new Map<string, AbortController>())
  const identityController = useRef<AbortController | null>(null)
  const located = useRef("")
  const items = useMemo(() => feed.identity === identity ? feed.items : [], [feed, identity])
  const arriving = useMemo(() => incoming.identity === identity ? incoming.items : [], [incoming, identity])

  useEffect(() => {
    const identityScope = new AbortController()
    identityController.current = identityScope
    if (!identity) { setLoading(true); return }
    const controller = new AbortController()
    setLoading(true); setError(""); setFocusError(""); setIncoming({ identity, items: [] })
    readIds.current.clear(); readInFlight.current.clear(); located.current = ""
    async function load() {
      let target: SiteAnnouncement | null = null
      if (focus) {
        try { target = await announcementApi.detail(focus, controller.signal) }
        catch (reason) {
          if (controller.signal.aborted) return
          setFocusError(reason instanceof Error ? "该通知已撤下，或当前身份不可见" : "该通知不可见")
        }
      }
      if (controller.signal.aborted) return
      if (target?.cursor) {
        const latest = await announcementApi.list(null, controller.signal)
        if (controller.signal.aborted) return
        if (latest.items.some(item => item.id === target?.id)) {
          setFeed({ identity, items: chronological([...latest.items].reverse()), nextCursor: latest.next_cursor, anchored: false })
        } else {
          const older = await announcementApi.list(target.cursor, controller.signal)
          if (controller.signal.aborted) return
          setFeed({ identity, items: chronological([...older.items.reverse(), target]), nextCursor: older.next_cursor, anchored: true })
        }
      } else {
        const first = await announcementApi.list(null, controller.signal, true)
        if (controller.signal.aborted) return
        setFeed({ identity, items: chronological([...first.items].reverse()), nextCursor: first.next_cursor, anchored: false })
        if (first.first_unread_id && !first.items.some(item => item.id === first.first_unread_id)) {
          const target = await announcementApi.detail(first.first_unread_id, controller.signal)
          if (controller.signal.aborted) return
          const older = await announcementApi.list(target.cursor, controller.signal)
          if (controller.signal.aborted) return
          setFeed({ identity, items: chronological([...older.items.reverse(), target]), nextCursor: older.next_cursor, anchored: true })
        }
      }
    }
    load().catch(reason => { if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : "通知加载失败") })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => { controller.abort(); identityScope.abort(); if (identityController.current === identityScope) identityController.current = null }
  }, [identity, focus])

  const pollLatest = useCallback(async (signal: AbortSignal) => {
    const known = new Set([...(feed.identity === identity ? feed.items : []), ...(incoming.identity === identity ? incoming.items : [])].map(item => item.id))
    const unseen: SiteAnnouncement[] = []
    let cursor: string | null = null
    for (let pageIndex = 0; pageIndex < 5 && !signal.aborted; pageIndex++) {
      const result = await announcementApi.list(cursor, signal)
      if (signal.aborted) return
      let reachedKnown = false
      for (const item of result.items) {
        if (known.has(item.id)) { reachedKnown = true; break }
        unseen.push(item)
      }
      if (reachedKnown || !result.next_cursor) break
      cursor = result.next_cursor
    }
    if (signal.aborted) return
    const loadedIds = [...known].slice(-500)
    const refreshed: SiteAnnouncement[] = []
    for (let offset = 0; offset < loadedIds.length && !signal.aborted; offset += 100)
      refreshed.push(...await announcementApi.refresh(loadedIds.slice(offset, offset + 100), signal))
    if (signal.aborted) return
    const current = new Map(refreshed.map(item => [item.id, item]))
    const sync = (values: SiteAnnouncement[]) => values.flatMap(old => {
      const next = current.get(old.id)
      if (!next) {
        readIds.current.delete(old.id)
        readControllers.current.get(old.id)?.abort()
        readControllers.current.delete(old.id)
        readInFlight.current.delete(old.id)
        return []
      }
      if (next.unread) readIds.current.delete(next.id)
      return [next]
    })
    const nextItems = chronological(sync(feed.identity === identity ? feed.items : []))
    const nextIncoming = chronological(sync(incoming.identity === identity ? incoming.items : []))
    if (nextItems.length !== (feed.identity === identity ? feed.items.length : 0)
        || nextIncoming.length !== (incoming.identity === identity ? incoming.items.length : 0)
        || [...current.values()].some(item => {
          const prior = known.has(item.id) ? [...(feed.identity === identity ? feed.items : []), ...(incoming.identity === identity ? incoming.items : [])].find(value => value.id === item.id) : null
          return !prior || prior.publish_version !== item.publish_version || prior.body !== item.body || prior.unread !== item.unread
        })) notifyMessagesChanged()
    setFeed(previous => previous.identity !== identity ? previous : { ...previous, items: nextItems })
    setIncoming(previous => previous.identity !== identity ? previous : { ...previous, items: chronological([...nextIncoming, ...unseen]) })
  }, [feed, incoming, identity])

  useVisiblePoll(pollLatest, !!identity && !loading, 30_000, `${identity}:notice-stream`)

  const markVisibleRead = useCallback((item: SiteAnnouncement) => {
    if (!item.unread || readIds.current.has(item.id) || readInFlight.current.has(item.id)) return
    readInFlight.current.add(item.id)
    const controller = new AbortController()
    readControllers.current.set(item.id, controller)
    announcementApi.read(item.id, item.publish_version, controller.signal).then(() => {
      readIds.current.add(item.id)
      setFeed(previous => previous.identity !== identity ? previous : { ...previous, items: previous.items.map(current => current.id === item.id ? { ...current, unread: false } : current) })
      setIncoming(previous => previous.identity !== identity ? previous : { ...previous, items: previous.items.map(current => current.id === item.id ? { ...current, unread: false } : current) })
      notifyMessagesChanged()
    }).catch(() => {}).finally(() => { readInFlight.current.delete(item.id); readControllers.current.delete(item.id) })
  }, [identity])

  useEffect(() => () => {
    for (const controller of readControllers.current.values()) controller.abort()
    readControllers.current.clear(); readInFlight.current.clear()
  }, [identity])

  useEffect(() => {
    const list = feedElement.current
    if (!list || !items.length) return
    const observer = new IntersectionObserver(entries => {
      if (document.visibilityState !== "visible") return
      for (const entry of entries) {
        if (!entry.isIntersecting) continue
        const id = (entry.target as HTMLElement).dataset.noticeId
        const item = id ? items.find(value => value.id === id) : undefined
        if (item) markVisibleRead(item)
      }
    }, { root: null, threshold: 0.1 })
    list.querySelectorAll<HTMLElement>("[data-notice-id]").forEach(node => observer.observe(node))
    const refresh = () => {
      if (document.visibilityState !== "visible") return
      observer.disconnect()
      list.querySelectorAll<HTMLElement>("[data-notice-id]").forEach(node => observer.observe(node))
    }
    document.addEventListener("visibilitychange", refresh)
    return () => { observer.disconnect(); document.removeEventListener("visibilitychange", refresh) }
  }, [items, markVisibleRead])

  useLayoutEffect(() => {
    if (loading || !items.length) return
    const locationKey = `${identity}:${focus || "default"}`
    if (located.current === locationKey) return
    const target = focus ? items.find(item => item.id === focus)
      : items.find(item => item.unread) ?? items.at(-1)
    if (target) {
      document.getElementById(`notice-${target.id}`)?.scrollIntoView({ block: "start", behavior: "auto" })
      located.current = locationKey
    }
    if (preserveHeight.current === null || !feedElement.current) return
    const delta = feedElement.current.scrollHeight - preserveHeight.current
    window.scrollBy({ top: delta, behavior: "instant" })
    preserveHeight.current = null
  }, [items, focus, loading, identity])

  async function loadOlder() {
    if (!feed.nextCursor || loadingOlder) return
    const controller = new AbortController()
    setLoadingOlder(true)
    preserveHeight.current = feedElement.current?.scrollHeight ?? null
    try {
      const older = await announcementApi.list(feed.nextCursor, controller.signal)
      if (controller.signal.aborted) return
      setFeed(previous => previous.identity !== identity ? previous : {
        ...previous, items: chronological([...older.items, ...previous.items]), nextCursor: older.next_cursor,
      })
    } catch (reason) { setError(reason instanceof Error ? reason.message : "历史通知加载失败") }
    finally { setLoadingOlder(false) }
  }

  function showIncoming() {
    if (!arriving.length) return
    setFeed(previous => previous.identity !== identity ? previous : { ...previous, items: chronological([...previous.items, ...arriving]), anchored: false })
    setIncoming({ identity, items: [] })
  }

  async function markAllRead() {
    setMarkingAll(true)
    try {
      const controller = identityController.current
      if (!controller || controller.signal.aborted) return
      const receipt = await announcementApi.readAll(controller.signal)
      if (controller.signal.aborted) return
      const versions = new Map(receipt.map(item => [item.id, item.version]))
      const apply = (item: SiteAnnouncement) => {
        const readVersion = versions.get(item.id)
        return readVersion === undefined ? item : { ...item, unread: item.publish_version > readVersion }
      }
      setFeed(previous => previous.identity !== identity ? previous : { ...previous, items: previous.items.map(apply) })
      setIncoming(previous => previous.identity !== identity ? previous : { ...previous, items: previous.items.map(apply) })
      notifyMessagesChanged()
    } catch (reason) { setError(reason instanceof Error ? reason.message : "标记已读失败") }
    finally { setMarkingAll(false) }
  }

  return <div className="mx-auto max-w-3xl space-y-4 py-0 sm:space-y-5 sm:py-6">
    <header className="flex items-center justify-between gap-3">
      <div className="min-w-0"><Link href="/messages" className="inline-flex min-h-11 items-center gap-2 text-sm text-muted-foreground"><ArrowLeft className="size-4" aria-hidden="true" />商城客服</Link><h1 className="mt-1 flex items-center gap-2 text-xl font-semibold"><Bell className="size-5 text-primary" aria-hidden="true" />商城通知</h1></div>
      <div className="flex shrink-0 items-center gap-2">
        {feed.anchored && <Button asChild type="button" variant="ghost" size="sm"><Link href="/messages/announcements">最新通知</Link></Button>}
        {items.some(item => item.unread) && <Button type="button" variant="ghost" size="sm" disabled={markingAll} onClick={() => void markAllRead()}><Check className="size-4" aria-hidden="true" />全部已读</Button>}
      </div>
    </header>

    {focusError && <p role="status" className="text-sm text-muted-foreground">{focusError}</p>}
    {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
    {arriving.length > 0 && !feed.anchored && <div className="flex items-center justify-between gap-3 rounded-control border border-primary/20 bg-primary/5 px-3 py-2 text-sm">
      <span>有 {arriving.length} 条新通知</span><Button type="button" variant="ghost" size="sm" onClick={showIncoming}>查看新通知<ArrowDown className="size-4" aria-hidden="true" /></Button>
    </div>}
    {arriving.length > 0 && feed.anchored && <div className="flex items-center justify-between gap-3 rounded-control border border-primary/20 bg-primary/5 px-3 py-2 text-sm"><span>有新通知</span><Button asChild type="button" variant="ghost" size="sm"><Link href="/messages/announcements">查看最新</Link></Button></div>}

    {loading && <div className="space-y-5" aria-label="正在加载通知" role="status">{[0, 1].map(key => <div key={key} className="space-y-3 border-b border-border-subtle py-5"><div className="h-4 w-1/3 animate-pulse rounded bg-muted" /><div className="h-24 animate-pulse rounded bg-muted" /></div>)}</div>}
    {!loading && <>
      {feed.nextCursor && <div className="text-center"><Button type="button" variant="outline" disabled={loadingOlder} onClick={() => void loadOlder()}>{loadingOlder ? "加载中…" : "更早的通知"}</Button></div>}
      <ol ref={feedElement} className="min-w-0 divide-y divide-border-subtle">
        {items.map(item => <li key={item.id}>
          <article data-notice-id={item.id} className="min-w-0 py-5">
            <header className="mb-3 flex min-w-0 items-start justify-between gap-3">
              <div className="min-w-0">{item.title && <h2 id={`notice-${item.id}`} className="break-words text-base font-semibold">{item.title}</h2>}
                {!item.title && <h2 id={`notice-${item.id}`} className="sr-only">商城通知正文</h2>}
                <div className={`flex flex-wrap items-center gap-2 text-xs text-muted-foreground ${item.title ? "mt-1" : ""}`}><time>{noticeTime(item.published_at)}</time>{item.unread && <span className="inline-flex items-center gap-1 text-primary"><span className="size-1.5 rounded-full bg-primary" />未读</span>}</div></div>
            </header>
            <MarkdownContent value={item.body} className="min-w-0 text-foreground [&_img]:max-h-[70vh] [&_img]:max-w-full [&_a]:break-all [&_pre]:max-w-full [&_pre]:overflow-x-auto [&_*]:break-words" />
            <div className="mt-2 flex justify-end">
              <Link href={`/messages?notice=${encodeURIComponent(item.id)}`} aria-label="引用此通知联系店铺" title="引用此通知联系店铺" className="inline-flex size-10 items-center justify-center rounded-full text-muted-foreground hover:bg-muted hover:text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"><MessageCircle className="size-4" aria-hidden="true" /></Link>
            </div>
          </article>
        </li>)}
        {!items.length && <li className="px-5 py-10 text-center text-sm text-muted-foreground">暂无通知</li>}
      </ol>
      {feed.nextCursor && <div className="text-center"><Button type="button" variant="outline" disabled={loadingOlder} onClick={() => void loadOlder()}>{loadingOlder ? "加载中…" : "更早的通知"}</Button></div>}
    </>}
  </div>
}
