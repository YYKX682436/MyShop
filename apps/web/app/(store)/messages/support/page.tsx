"use client"

import { useState } from "react"
import Link from "next/link"
import { ChevronRight } from "lucide-react"
import { Button } from "@/components/ui/button"
import { useSupportIdentity } from "@/hooks/use-support-identity"
import { useVisiblePoll } from "@/hooks/use-visible-poll"
import { formatDateTime } from "@/lib/utils"
import { supportApi, type SupportConversation } from "@/services/api"

export default function SupportListPage() {
  const identity = useSupportIdentity()
  const [snapshot, setSnapshot] = useState<{ identity: string; rows: SupportConversation[] }>({ identity: "", rows: [] })
  const [error, setError] = useState("")
  const [page, setPage] = useState(0)
  const scope = `${identity}:${page}`
  useVisiblePoll(async signal => {
    try {
      const rows = await supportApi.list(false, `size=30&page=${page}`, signal)
      if (!signal.aborted) { setSnapshot({ identity: scope, rows }); setError("") }
    } catch (e) { if (!signal.aborted) setError(e instanceof Error ? e.message : "加载失败"); throw e }
  }, !!identity, 20_000, scope)
  const rows = snapshot.identity === scope ? snapshot.rows : []
  return <div className="mx-auto max-w-3xl space-y-4 py-0 sm:space-y-5 sm:py-6">
    <header className="flex items-end justify-between gap-3"><div><Link href="/messages" className="inline-flex min-h-11 items-center text-sm text-muted-foreground">← 消息</Link><h1 className="mt-2 text-2xl font-semibold">主站客服</h1></div><Button asChild><Link href="/messages/support/new">联系客服</Link></Button></header>
    {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
    <div className="overflow-hidden rounded-card border border-border-subtle bg-surface">
      {rows.map(row => <Link key={row.id} href={`/messages/support/${row.id}`} className="flex min-h-20 items-center gap-3 border-b border-border-subtle px-4 py-4 last:border-0 hover:bg-surface-muted">
        <span className="min-w-0 flex-1"><span className="flex justify-between gap-3"><span className="min-w-0 flex-1 break-all font-medium">{row.order_no ? `订单 #${row.order_no}` : "普通咨询"}</span>{row.latest_at && <time className="shrink-0 text-xs text-muted-foreground">{formatDateTime(row.latest_at)}</time>}</span><span className="mt-1 block truncate text-sm text-muted-foreground">{row.preview || "暂无消息"}</span></span>
        {row.unread > 0 && <span className="rounded-full bg-destructive px-1.5 text-xs text-white" aria-label={`${row.unread} 条未读`}>{row.unread > 99 ? "99+" : row.unread}</span>}<ChevronRight className="size-4 text-muted-foreground" aria-hidden="true" />
      </Link>)}
      {snapshot.identity === scope && !rows.length && !error && <p className="p-6 text-sm text-muted-foreground">暂无会话</p>}
    </div>
    <div className="flex justify-between"><Button type="button" variant="ghost" disabled={page === 0} onClick={() => setPage(value => value - 1)}>上一页</Button><Button type="button" variant="ghost" disabled={rows.length < 30} onClick={() => setPage(value => value + 1)}>下一页</Button></div>
  </div>
}
