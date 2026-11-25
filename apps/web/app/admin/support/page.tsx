"use client"

import { Suspense, useEffect, useState } from "react"
import { useRouter, useSearchParams } from "next/navigation"
import { AdminPage, PageHeader } from "@/components/admin/shared"
import { SupportThread } from "@/components/shared/support-thread"
import { Button } from "@/components/ui/button"
import { useVisiblePoll } from "@/hooks/use-visible-poll"
import { useAuth } from "@/lib/context"
import { formatDateTime } from "@/lib/utils"
import { adminSubsiteApi, supportApi, type SubsiteInfo, type SupportConversation } from "@/services/api"

function Workbench() {
  const { user, authLoaded } = useAuth()
  const identity = authLoaded && user ? `admin:${user.id}` : ""
  const search = useSearchParams(); const router = useRouter()
  const selected = search.get("conversation") || ""
  const order = search.get("order") || ""
  const [sites, setSites] = useState<SubsiteInfo[]>([])
  const [site, setSite] = useState("")
  const [kind, setKind] = useState("all")
  const [unread, setUnread] = useState(false)
  const [pending, setPending] = useState(false)
  const [page, setPage] = useState(0)
  const [snapshot, setSnapshot] = useState<{ scope: string; rows: SupportConversation[] }>({ scope: "", rows: [] })
  const [error, setError] = useState("")
  const params = new URLSearchParams({ kind, page: String(page), size: "30" })
  if (site) params.set("site", site)
  if (unread) params.set("unread", "true")
  if (pending) params.set("pending", "true")
  const query = params.toString(); const scope = `${identity}:${query}`
  useEffect(() => {
    if (!identity) return
    let active = true
    adminSubsiteApi.list().then(value => { if (active) setSites(value) }).catch(() => {})
    return () => { active = false }
  }, [identity])
  useEffect(() => {
    if (!identity || !order || selected) return
    const controller = new AbortController()
    supportApi.adminOrder(order, controller.signal).then(result => {
      if (controller.signal.aborted) return
      if (result.conversation_id) router.replace(`/admin/support?conversation=${result.conversation_id}`)
      else setError("该订单暂无会话")
    }).catch(e => { if (!controller.signal.aborted) setError(e instanceof Error ? e.message : "无法读取会话") })
    return () => controller.abort()
  }, [identity, order, selected, router])
  useVisiblePoll(async signal => {
    try {
      const rows = await supportApi.list(true, query, signal)
      if (!signal.aborted) { setSnapshot({ scope, rows }); setError("") }
    } catch (e) { if (!signal.aborted) setError(e instanceof Error ? e.message : "加载失败"); throw e }
  }, !!identity, 15_000, scope)
  const rows = snapshot.scope === scope ? snapshot.rows : []
  return <AdminPage><PageHeader title="客服消息" />
    {error && <p role="alert" className="mb-3 text-sm text-destructive">{error}</p>}
    <div className="grid gap-4 md:grid-cols-[minmax(16rem,21rem)_minmax(0,1fr)]">
      <section className={`${selected ? "hidden md:block" : "block"} space-y-3`}>
        <div className="grid grid-cols-2 gap-2">
          <label className="text-sm">来源站点<select className="mt-1 block min-h-10 w-full rounded-control border border-input bg-background px-2 text-base" value={site} onChange={e => { setSite(e.target.value); setPage(0) }}><option value="">全部站点</option><option value="main">主站</option>{sites.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
          <label className="text-sm">类型<select className="mt-1 block min-h-10 w-full rounded-control border border-input bg-background px-2 text-base" value={kind} onChange={e => { setKind(e.target.value); setPage(0) }}><option value="all">全部</option><option value="consultation">普通咨询</option><option value="after_sale">订单售后</option></select></label>
        </div>
        <div className="flex gap-4 text-sm"><label><input type="checkbox" checked={unread} onChange={e => { setUnread(e.target.checked); setPage(0) }} /> 未读</label><label><input type="checkbox" checked={pending} onChange={e => { setPending(e.target.checked); setPage(0) }} /> 待处理</label></div>
        <div className="overflow-hidden rounded-card border border-border-subtle bg-surface">{rows.map(row => <button key={row.id} type="button" onClick={() => router.push(`/admin/support?conversation=${row.id}`)} className={`block w-full border-b border-border-subtle px-3 py-3 text-left last:border-0 ${selected === row.id ? "bg-primary/10" : "hover:bg-surface-muted"}`}>
          <span className="flex justify-between gap-2 text-sm font-medium"><span className="min-w-0 flex-1 break-all">{row.order_no ? `订单 #${row.order_no}` : "普通咨询"}</span>{row.unread > 0 && <span className="shrink-0 rounded-full bg-destructive px-1.5 text-xs text-white">{row.unread}</span>}</span>
          <span className="mt-1 block truncate text-xs text-muted-foreground">{row.site_name || "主站"} · {row.customer_name || "游客"} · {row.preview || "暂无消息"}</span>
          <span className="mt-1 flex justify-between text-xs text-muted-foreground"><span>{row.needs_attention ? "待处理" : row.waiting_for === "CUSTOMER" ? "等待顾客" : ""}</span>{row.latest_at && <time>{formatDateTime(row.latest_at)}</time>}</span>
        </button>)}{!rows.length && <p className="p-4 text-sm text-muted-foreground">暂无会话</p>}</div>
        <div className="flex justify-between"><Button type="button" variant="ghost" size="sm" disabled={page === 0} onClick={() => setPage(value => value - 1)}>上一页</Button><Button type="button" variant="ghost" size="sm" disabled={rows.length < 30} onClick={() => setPage(value => value + 1)}>下一页</Button></div>
      </section>
      <section className={selected ? "block" : "hidden md:block"}>
        {selected ? <><button type="button" className="mb-2 text-sm text-muted-foreground md:hidden" onClick={() => router.push("/admin/support")}>← 会话列表</button><SupportThread key={`${identity}:${selected}`} id={selected} admin identity={identity} /></> : <div className="rounded-card border border-border-subtle p-8 text-center text-sm text-muted-foreground">选择会话</div>}
      </section>
    </div>
  </AdminPage>
}
export default function SupportWorkbenchPage() { return <Suspense><Workbench /></Suspense> }
