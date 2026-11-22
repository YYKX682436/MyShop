"use client"

import { useEffect, useState } from "react"
import Link from "next/link"
import { useSupportIdentity } from "@/hooks/use-support-identity"
import { formatDateTime } from "@/lib/utils"
import { announcementApi, type SiteAnnouncement } from "@/services/api"
import { Button } from "@/components/ui/button"

export default function AnnouncementsPage() {
  const identity = useSupportIdentity()
  const [snapshot, setSnapshot] = useState<{ identity: string; items: SiteAnnouncement[]; page: number; more: boolean }>({ identity: "", items: [], page: 0, more: false })
  const [error, setError] = useState("")
  const [loading, setLoading] = useState(true)
  useEffect(() => {
    if (!identity) return
    const controller = new AbortController()
    setLoading(true); setError("")
    announcementApi.list(0, controller.signal).then(items => {
      if (!controller.signal.aborted) setSnapshot({ identity, items, page: 0, more: items.length === 30 })
    }).catch(e => { if (!controller.signal.aborted) setError(e instanceof Error ? e.message : "加载失败") })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [identity])
  const items = snapshot.identity === identity ? snapshot.items : []
  async function more() {
    setLoading(true)
    try {
      const next = await announcementApi.list(snapshot.page + 1)
      setSnapshot(previous => previous.identity !== identity ? previous : { ...previous, items: [...previous.items, ...next], page: previous.page + 1, more: next.length === 30 })
    } catch (e) { setError(e instanceof Error ? e.message : "加载失败") }
    finally { setLoading(false) }
  }
  return <div className="mx-auto max-w-3xl space-y-5 py-6">
    <header><Link href="/messages" className="text-sm text-muted-foreground">← 消息</Link><h1 className="mt-2 text-2xl font-semibold">网站公告</h1></header>
    {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
    <div className="overflow-hidden rounded-card border border-border-subtle bg-surface">
      {items.map(item => <Link key={item.id} href={`/messages/announcements/${item.id}`} className="block border-b border-border-subtle px-4 py-4 last:border-0 hover:bg-surface-muted">
        <span className="flex items-center gap-2 font-medium">{item.unread && <span className="size-2 rounded-full bg-destructive" aria-label="未读" />}{item.title}</span>
        <time className="mt-1 block text-xs text-muted-foreground">{item.published_at ? formatDateTime(item.published_at) : "发布时间未知"}</time>
      </Link>)}
      {!loading && !items.length && !error && <p className="p-6 text-sm text-muted-foreground">暂无公告</p>}
    </div>
    {snapshot.identity === identity && snapshot.more && <Button type="button" variant="outline" disabled={loading} onClick={() => void more()}>加载更多</Button>}
  </div>
}
