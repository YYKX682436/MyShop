"use client"

import { Suspense, useEffect, useState } from "react"
import { useRouter, useSearchParams } from "next/navigation"
import { AdminPage, PageHeader } from "@/components/admin/shared"
import { SupportThread } from "@/components/shared/support-thread"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { useVisiblePoll } from "@/hooks/use-visible-poll"
import { useAuth } from "@/lib/context"
import { formatDateTime } from "@/lib/utils"
import { adminSubsiteApi, adminUserLevelApi, supportApi, type SubsiteInfo, type SupportConversation } from "@/services/api"
import type { UserLevel } from "@/types"

function Workbench() {
  const { user, authLoaded } = useAuth()
  const identity = authLoaded && user ? `admin:${user.id}` : ""
  const search = useSearchParams(); const router = useRouter()
  const selected = search.get("conversation") || ""
  const order = search.get("order") || ""
  const [sites, setSites] = useState<SubsiteInfo[]>([])
  const [levels, setLevels] = useState<UserLevel[]>([])
  const [site, setSite] = useState("")
  const [level, setLevel] = useState("")
  const [kind, setKind] = useState("all")
  const [unread, setUnread] = useState(false)
  const [pending, setPending] = useState(false)
  const [orderDraft, setOrderDraft] = useState("")
  const [orderFilter, setOrderFilter] = useState("")
  const [page, setPage] = useState(0)
  const [snapshot, setSnapshot] = useState<{ scope: string; rows: SupportConversation[] }>({ scope: "", rows: [] })
  const [error, setError] = useState("")
  const params = new URLSearchParams({ kind, page: String(page), size: "30" })
  if (site) params.set("site", site)
  if (level) params.set("level", level)
  if (orderFilter) params.set("order", orderFilter)
  if (unread) params.set("unread", "true")
  if (pending) params.set("pending", "true")
  const query = params.toString(); const scope = `${identity}:${query}`

  useEffect(() => {
    if (!identity) return
    let active = true
    Promise.all([adminSubsiteApi.list(), adminUserLevelApi.getList()]).then(([subsites, userLevels]) => {
      if (active) { setSites(subsites); setLevels(userLevels.filter(item => item.enabled)) }
    }).catch(() => {})
    return () => { active = false }
  }, [identity])

  useEffect(() => {
    if (!identity || !order || selected) return
    const controller = new AbortController()
    supportApi.adminOrder(order, controller.signal).then(result => {
      if (controller.signal.aborted) return
      if (result.conversation_id) router.replace(`/admin/support?conversation=${result.conversation_id}`)
      else setError("该订单暂无客服对话")
    }).catch(reason => { if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : "无法读取会话") })
    return () => controller.abort()
  }, [identity, order, selected, router])

  useVisiblePoll(async signal => {
    try {
      const rows = await supportApi.list(true, query, signal)
      if (!signal.aborted) { setSnapshot({ scope, rows }); setError("") }
    } catch (reason) { if (!signal.aborted) setError(reason instanceof Error ? reason.message : "加载失败"); throw reason }
  }, !!identity, 15_000, scope)
  const rows = snapshot.scope === scope ? snapshot.rows : []
  function resetPage(action: () => void) { action(); setPage(0) }

  return <AdminPage><PageHeader title="客服工作台" />
    {error && <p role="alert" className="mb-3 text-sm text-destructive">{error}</p>}
    <div className="grid gap-4 md:grid-cols-[minmax(17rem,22rem)_minmax(0,1fr)]">
      <section className={`${selected ? "hidden md:block" : "block"} min-w-0 space-y-3`} aria-label="客服对话列表">
        <div className="grid gap-2 sm:grid-cols-2">
          <label className="text-sm">来源站点<select className="mt-1 block min-h-11 w-full rounded-control border border-input bg-background px-2 text-base" value={site} onChange={event => resetPage(() => setSite(event.target.value))}>
            <option value="">全部站点</option><option value="main">主站</option>{sites.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}
          </select></label>
          <label className="text-sm">顾客等级<select className="mt-1 block min-h-11 w-full rounded-control border border-input bg-background px-2 text-base" value={level} onChange={event => resetPage(() => setLevel(event.target.value))}>
            <option value="">全部等级</option>{levels.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}
          </select></label>
          <label className="text-sm">类型<select className="mt-1 block min-h-11 w-full rounded-control border border-input bg-background px-2 text-base" value={kind} onChange={event => resetPage(() => setKind(event.target.value))}>
            <option value="all">全部</option><option value="consultation">普通咨询</option><option value="after_sale">含售后</option>
          </select></label>
          <form className="flex min-w-0 items-end gap-2" onSubmit={event => { event.preventDefault(); resetPage(() => setOrderFilter(orderDraft.trim())) }}>
            <label className="min-w-0 flex-1 text-sm">订单号<Input className="mt-1" value={orderDraft} onChange={event => setOrderDraft(event.target.value)} /></label>
            <Button type="submit" variant="outline" className="shrink-0">查找</Button>
          </form>
        </div>
        <div className="flex flex-wrap gap-x-5 gap-y-2 text-sm">
          <label className="inline-flex min-h-11 items-center gap-2"><input type="checkbox" checked={unread} onChange={event => resetPage(() => setUnread(event.target.checked))} />未读</label>
          <label className="inline-flex min-h-11 items-center gap-2"><input type="checkbox" checked={pending} onChange={event => resetPage(() => setPending(event.target.checked))} />待处理</label>
        </div>
        <div className="overflow-hidden rounded-card border border-border-subtle bg-surface">
          {rows.map(row => <button key={row.id} type="button" onClick={() => router.push(`/admin/support?conversation=${row.id}`)}
            className={`block w-full min-w-0 border-b border-border-subtle px-3 py-3 text-left last:border-0 ${selected === row.id ? "bg-primary/10" : "hover:bg-surface-muted"}`}>
            <span className="flex min-w-0 items-center justify-between gap-2 text-sm font-medium">
              <span className="min-w-0 truncate">{row.customer_name || "顾客"}{row.customer_level_name ? ` · ${row.customer_level_name}` : ""}</span>
              {row.unread > 0 && <span className="shrink-0 rounded-full bg-destructive px-1.5 text-xs text-white" aria-label={`${row.unread} 条未读`}>{row.unread > 99 ? "99+" : row.unread}</span>}
            </span>
            <span className="mt-1 block truncate text-xs text-muted-foreground">{row.site_name || "主站"} · {row.preview || "暂无消息"}</span>
            <span className="mt-1 flex justify-between gap-2 text-xs text-muted-foreground"><span>{row.needs_attention ? "待处理" : row.waiting_for === "CUSTOMER" ? "等待顾客" : ""}</span>{row.latest_at && <time>{formatDateTime(row.latest_at)}</time>}</span>
          </button>)}
          {!rows.length && <p className="p-4 text-sm text-muted-foreground">暂无客服对话</p>}
        </div>
        <div className="flex justify-between"><Button type="button" variant="ghost" size="sm" disabled={page === 0} onClick={() => setPage(value => value - 1)}>上一页</Button><Button type="button" variant="ghost" size="sm" disabled={rows.length < 30} onClick={() => setPage(value => value + 1)}>下一页</Button></div>
      </section>
      <section className={`${selected ? "block" : "hidden md:block"} min-w-0`}>
        {selected ? <><button type="button" className="mb-2 min-h-11 text-sm text-muted-foreground md:hidden" onClick={() => router.push("/admin/support")}>← 对话列表</button><SupportThread key={`${identity}:${selected}`} id={selected} admin identity={identity} /></>
          : <div className="rounded-card border border-border-subtle p-8 text-center text-sm text-muted-foreground">选择客服对话</div>}
      </section>
    </div>
  </AdminPage>
}
export default function SupportWorkbenchPage() { return <Suspense><Workbench /></Suspense> }
