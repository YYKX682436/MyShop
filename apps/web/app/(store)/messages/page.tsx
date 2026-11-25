"use client"

import Link from "next/link"
import { Bell, ChevronRight, Headset } from "lucide-react"
import { useMessageSummary } from "@/components/store/message-summary"
import { formatDateTime } from "@/lib/utils"

function previewNotice(value: string | undefined) {
  if (!value) return "暂无通知"
  const text = value.replace(/!\[([^\]]*)\]\([^)]*\)/g, (_, alt: string) => alt ? `[图片：${alt}]` : "[图片]")
    .replace(/\[([^\]]+)\]\([^)]*\)/g, "$1").replace(/[`*_>#~]/g, "").replace(/\s+/g, " ").trim()
  return text || "[图片]"
}

function Entry({ href, title, preview, time, unread, icon: Icon }: {
  href: string; title: string; preview: string; time?: string | null; unread: number; icon: typeof Bell
}) {
  return <Link href={href} className="group block min-w-0 rounded-card border border-border-subtle bg-surface p-4 transition-colors hover:bg-surface-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring sm:p-5">
    <div className="flex min-w-0 items-start gap-3">
      <span className="flex size-10 shrink-0 items-center justify-center rounded-control bg-primary/10 text-primary"><Icon className="size-5" aria-hidden="true" /></span>
      <div className="min-w-0 flex-1">
        <div className="flex min-w-0 items-center gap-2"><span className="min-w-0 flex-1 truncate font-semibold">{title}</span>
          {unread > 0 && <span className="shrink-0 rounded-full bg-destructive px-2 py-0.5 text-xs text-white" aria-label={`${unread} 条未读`}>{unread > 99 ? "99+" : unread}</span>}
          <ChevronRight className="size-4 shrink-0 text-muted-foreground transition-transform group-hover:translate-x-0.5" aria-hidden="true" />
        </div>
        <p className="mt-2 line-clamp-2 break-words text-sm leading-5 text-foreground">{preview}</p>
        <div className="mt-2 min-h-4 text-xs text-muted-foreground">{time && <time>{formatDateTime(time)}</time>}</div>
      </div>
    </div>
  </Link>
}

export default function MessagesPage() {
  const { announcements, support } = useMessageSummary()
  const notification = announcements.latest?.title || previewNotice(announcements.latest?.body)
  return <div className="mx-auto max-w-3xl space-y-4 py-0 sm:space-y-5 sm:py-6">
    <h1 className="text-2xl font-semibold">消息</h1>
    <div className="space-y-4">
      <Entry href="/messages/support" title="商城客服" preview={support.latest?.preview || "联系客服"}
        time={support.latest?.latest_at} unread={support.unread} icon={Headset} />
      <Entry href="/messages/announcements" title="商城通知" preview={notification}
        time={announcements.latest?.published_at} unread={announcements.unread} icon={Bell} />
    </div>
  </div>
}
