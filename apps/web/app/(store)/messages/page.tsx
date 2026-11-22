"use client"

import Link from "next/link"
import { ChevronRight, Headset, Megaphone } from "lucide-react"
import { useMessageSummary } from "@/components/store/message-summary"
import { formatDateTime } from "@/lib/utils"

export default function MessagesPage() {
  const { announcements, support } = useMessageSummary()
  const entries = [
    { href: "/messages/announcements", title: "网站公告", text: announcements.latest?.title || "暂无公告", time: announcements.latest?.published_at, unread: announcements.unread, icon: Megaphone },
    { href: "/messages/support", title: "客服", text: support.latest?.preview || "联系主站客服", time: support.latest?.latest_at, unread: support.unread, icon: Headset },
  ]
  return <div className="mx-auto max-w-3xl space-y-5 py-6">
    <h1 className="text-2xl font-semibold">消息</h1>
    <div className="overflow-hidden rounded-card border border-border-subtle bg-surface">
      {entries.map(({ href, title, text, time, unread, icon: Icon }) => <Link key={href} href={href}
        className="flex min-h-20 items-center gap-3 border-b border-border-subtle px-4 py-4 last:border-b-0 hover:bg-surface-muted">
        <span className="flex size-11 shrink-0 items-center justify-center rounded-control bg-primary/10 text-primary"><Icon className="size-5" aria-hidden="true" /></span>
        <span className="min-w-0 flex-1"><span className="flex items-center justify-between gap-2"><span className="font-medium">{title}</span>{time && <time className="text-xs text-muted-foreground">{formatDateTime(time)}</time>}</span>
          <span className="mt-1 block truncate text-sm text-muted-foreground">{text}</span></span>
        {unread > 0 && <span className="rounded-full bg-destructive px-1.5 text-xs text-white" aria-label={`${unread} 条未读`}>{unread > 99 ? "99+" : unread}</span>}
        <ChevronRight className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
      </Link>)}
    </div>
  </div>
}
