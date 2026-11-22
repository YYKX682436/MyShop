"use client"

import { useEffect, useState } from "react"
import Link from "next/link"
import { useParams } from "next/navigation"
import { MarkdownContent } from "@/components/shared/markdown-content"
import { notifyMessagesChanged } from "@/components/store/message-summary"
import { useSupportIdentity } from "@/hooks/use-support-identity"
import { formatDateTime } from "@/lib/utils"
import { announcementApi, type SiteAnnouncement } from "@/services/api"

export default function AnnouncementDetailPage() {
  const id = useParams<{ id: string }>().id
  const identity = useSupportIdentity()
  const [snapshot, setSnapshot] = useState<{ identity: string; item: SiteAnnouncement } | null>(null)
  const [error, setError] = useState("")
  useEffect(() => {
    if (!identity) return
    const controller = new AbortController()
    setError("")
    announcementApi.detail(id, controller.signal).then(item => {
      if (!controller.signal.aborted) setSnapshot({ identity, item })
    }).catch(e => { if (!controller.signal.aborted) setError(e instanceof Error ? e.message : "加载失败") })
    return () => controller.abort()
  }, [id, identity])
  const item = snapshot?.identity === identity && snapshot.item.id === id ? snapshot.item : null
  useEffect(() => {
    if (!item) return
    const controller = new AbortController()
    announcementApi.read(item.id, item.publish_version, controller.signal).then(() => {
      if (!controller.signal.aborted) notifyMessagesChanged()
    }).catch(() => {})
    return () => controller.abort()
  }, [item])
  return <div className="mx-auto max-w-3xl space-y-4 py-0 sm:space-y-5 sm:py-6">
    <Link href="/messages/announcements" className="inline-flex min-h-11 items-center text-sm text-muted-foreground">← 网站公告</Link>
    {error && <p role="alert" className="text-destructive">{error}</p>}
    {item && <article className="content-surface space-y-4 rounded-card p-5"><header><h1 className="text-xl font-semibold">{item.title}</h1><time className="text-xs text-muted-foreground">{item.published_at ? formatDateTime(item.published_at) : "发布时间未知"}</time></header><MarkdownContent value={item.body} /></article>}
  </div>
}
