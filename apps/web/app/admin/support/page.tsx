"use client"

import { Suspense, useEffect, useMemo, useState } from "react"
import { useRouter, useSearchParams } from "next/navigation"
import { ArrowLeft, Search } from "lucide-react"
import { AdminPage, PageHeader } from "@/components/admin/shared"
import { SupportThread } from "@/components/shared/support-thread"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { useVisiblePoll } from "@/hooks/use-visible-poll"
import { useAuth } from "@/lib/context"
import { adminSubsiteApi, adminUserLevelApi, supportApi, type SubsiteInfo, type SupportConversation } from "@/services/api"
import type { UserLevel } from "@/types"

type QuickFilter = "all" | "unread" | "pending"

function compactTime(value: string | null) {
  if (!value) return ""
  const date = new Date(value)
  const today = new Date()
  return date.toDateString() === today.toDateString()
    ? date.toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" })
    : date.toLocaleDateString(undefined, { month: "numeric", day: "numeric" })
}

function Workbench() {
  const { user, authLoaded } = useAuth()
  const identity = authLoaded && user ? "admin:" + user.id : ""
  const search = useSearchParams()
  const router = useRouter()
  const selected = search.get("conversation") || ""
  const order = search.get("order") || ""
  const urlSearch = search.get("search") || ""
  const urlUnread = search.get("unread") === "true"
  const urlPending = search.get("pending") === "true"
  const [sites, setSites] = useState<SubsiteInfo[]>([])
  const [levels, setLevels] = useState<UserLevel[]>([])
  const [site, setSite] = useState("")
  const [level, setLevel] = useState("")
  const [kind, setKind] = useState("all")
  const [quickFilter, setQuickFilter] = useState<QuickFilter>(() => urlUnread ? "unread" : urlPending ? "pending" : "all")
  const [searchDraft, setSearchDraft] = useState(urlSearch)
  const [searchTerm, setSearchTerm] = useState(urlSearch)
  const [page, setPage] = useState(0)
  const [snapshot, setSnapshot] = useState<{ scope: string; rows: SupportConversation[] }>({ scope: "", rows: [] })
  const [error, setError] = useState("")
  const params = new URLSearchParams({ kind, page: String(page), size: "30" })
  if (site) params.set("site", site)
  if (level) params.set("level", level)
  if (searchTerm) params.set("search", searchTerm)
  if (quickFilter === "unread") params.set("unread", "true")
  if (quickFilter === "pending") params.set("pending", "true")
  const query = params.toString()
  const scope = identity + ":" + query

  useEffect(() => {
    setSearchDraft(urlSearch); setSearchTerm(urlSearch)
    setQuickFilter(urlUnread ? "unread" : urlPending ? "pending" : "all")
  }, [urlSearch, urlUnread, urlPending])

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
      if (result.conversation_id) {
        const next = new URLSearchParams(search.toString())
        next.set("conversation", result.conversation_id); next.delete("order")
        router.replace("/admin/support?" + next.toString(), { scroll: false })
      } else setError("该订单暂无客服对话")
    }).catch(reason => { if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : "无法读取会话") })
    return () => controller.abort()
  }, [identity, order, selected, search, router])

  useVisiblePoll(async signal => {
    try {
      const rows = await supportApi.list(true, query, signal)
      if (!signal.aborted) { setSnapshot({ scope, rows }); setError("") }
    } catch (reason) { if (!signal.aborted) setError(reason instanceof Error ? reason.message : "加载失败"); throw reason }
  }, !!identity, 15_000, scope)

  const rows = useMemo(() => snapshot.scope === scope ? snapshot.rows : [], [snapshot, scope])
  const ready = snapshot.scope === scope
  useEffect(() => {
    if (!identity || !ready || order && !selected) return
    const selectedExists = rows.some(row => row.id === selected)
    if (selected && selectedExists) return
    const next = new URLSearchParams(search.toString())
    next.delete("order")
    if (rows.length) next.set("conversation", rows[0].id)
    else next.delete("conversation")
    const target = "/admin/support" + (next.size ? "?" + next.toString() : "")
    if (target !== window.location.pathname + window.location.search) router.replace(target, { scroll: false })
  }, [identity, ready, order, rows, selected, search, router])

  const selectedRow = useMemo(() => rows.find(row => row.id === selected) ?? null, [rows, selected])
  function navigate(next: URLSearchParams, replace = false) {
    const target = "/admin/support" + (next.size ? "?" + next.toString() : "")
    if (replace) router.replace(target, { scroll: false })
    else router.push(target, { scroll: false })
  }
  function setFilter(update: () => void) { update(); setPage(0) }
  function setConversation(id: string) {
    const next = new URLSearchParams(search.toString())
    next.delete("order"); next.set("conversation", id)
    navigate(next)
  }
  function backToList() {
    const next = new URLSearchParams(search.toString())
    next.delete("conversation"); next.delete("order")
    navigate(next)
  }

  return <AdminPage className="max-w-none gap-3">
    <PageHeader title="客服工作台" />
    {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
    <div className="grid h-[calc(100dvh-12rem)] min-h-[32rem] min-w-0 overflow-hidden rounded-card border border-border-subtle bg-surface md:grid-cols-[19rem_minmax(0,1fr)]">
      <aside className={`${selected ? "hidden md:flex" : "flex"} min-h-0 min-w-0 flex-col border-r border-border-subtle`} aria-label="客服对话列表">
        <form className="flex shrink-0 items-center gap-2 border-b border-border-subtle p-3" onSubmit={event => {
          event.preventDefault()
          setFilter(() => setSearchTerm(searchDraft.trim()))
          const next = new URLSearchParams(search.toString())
          const value = searchDraft.trim()
          if (value) next.set("search", value); else next.delete("search")
          next.delete("page")
          navigate(next, true)
        }}>
          <label className="relative min-w-0 flex-1">
            <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
            <Input aria-label="搜索顾客或订单" className="h-10 pl-9 text-sm" value={searchDraft} onChange={event => setSearchDraft(event.target.value)} placeholder="姓名、用户编号或订单号" />
          </label>
          <Button type="submit" size="sm" className="h-10 shrink-0">查找</Button>
        </form>
        <div className="flex shrink-0 gap-1 border-b border-border-subtle px-2 py-2" aria-label="快速筛选">
          {(["all", "unread", "pending"] as const).map(value => <button key={value} type="button" aria-pressed={quickFilter === value}
            onClick={() => setFilter(() => setQuickFilter(value))}
            className={`min-h-9 flex-1 rounded-control px-2 text-xs ${quickFilter === value ? "bg-primary/10 font-medium text-primary" : "text-muted-foreground hover:bg-muted"}`}>
            {value === "all" ? "全部" : value === "unread" ? "未读" : "待处理"}
          </button>)}
        </div>
        <details className="group shrink-0 border-b border-border-subtle px-3 py-2">
          <summary className="flex min-h-8 cursor-pointer list-none items-center justify-between text-xs text-muted-foreground">筛选条件<span className="text-primary">站点 · 等级 · 类型</span></summary>
          <div className="grid gap-2 pb-2 pt-1 sm:grid-cols-3 md:grid-cols-1">
            <label className="min-w-0 text-xs text-muted-foreground">来源站点<select className="mt-1 block h-9 w-full min-w-0 rounded-control border border-input bg-background px-2 text-sm text-foreground" value={site} onChange={event => setFilter(() => setSite(event.target.value))}>
              <option value="">全部站点</option><option value="main">主站</option>{sites.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}
            </select></label>
            <label className="min-w-0 text-xs text-muted-foreground">顾客等级<select className="mt-1 block h-9 w-full min-w-0 rounded-control border border-input bg-background px-2 text-sm text-foreground" value={level} onChange={event => setFilter(() => setLevel(event.target.value))}>
              <option value="">全部等级</option>{levels.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}
            </select></label>
            <label className="min-w-0 text-xs text-muted-foreground">类型<select className="mt-1 block h-9 w-full min-w-0 rounded-control border border-input bg-background px-2 text-sm text-foreground" value={kind} onChange={event => setFilter(() => setKind(event.target.value))}>
              <option value="all">全部类型</option><option value="consultation">普通咨询</option><option value="after_sale">含售后</option>
            </select></label>
          </div>
        </details>
        <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain">
          {rows.map(row => <button key={row.id} type="button" onClick={() => setConversation(row.id)}
            className={`block min-h-[5.25rem] w-full min-w-0 border-b border-border-subtle px-3 py-3 text-left last:border-0 ${selected === row.id ? "bg-primary/8" : "hover:bg-surface-muted"}`}>
            <span className="flex min-w-0 items-center gap-2">
              <span className="flex size-9 shrink-0 items-center justify-center rounded-full bg-primary/10 text-xs font-semibold text-primary">{(row.customer_name || "访").slice(0, 1)}</span>
              <span className="min-w-0 flex-1 truncate text-sm font-medium">{row.customer_name || "顾客"}</span>
              {row.unread > 0 && <span className="inline-flex min-h-5 min-w-5 items-center justify-center rounded-full bg-destructive px-1.5 text-[10px] text-white" aria-label={row.unread + " 条未读"}>{row.unread > 99 ? "99+" : row.unread}</span>}
              <time className="shrink-0 text-[10px] text-muted-foreground">{compactTime(row.latest_at)}</time>
            </span>
            <span className="mt-1.5 block truncate pl-11 text-xs text-muted-foreground">{row.preview || "暂无消息"}</span>
            <span className="mt-1 flex min-w-0 justify-between gap-2 pl-11 text-[10px] text-muted-foreground"><span className="truncate">{row.site_name || "主站"}{row.customer_level_name ? " · " + row.customer_level_name : ""}</span>
              {row.needs_attention && <span className="shrink-0 text-amber-600 dark:text-amber-400">待处理</span>}</span>
          </button>)}
          {ready && !rows.length && <p className="px-4 py-10 text-center text-sm text-muted-foreground">没有匹配的会话</p>}
          {!ready && <div className="space-y-3 p-3" role="status" aria-label="正在加载会话">{[0, 1, 2, 3].map(key => <div key={key} className="h-16 animate-pulse rounded-control bg-muted" />)}</div>}
        </div>
        <footer className="flex shrink-0 items-center justify-between border-t border-border-subtle px-3 py-2">
          <Button type="button" variant="ghost" size="sm" disabled={page === 0} onClick={() => setPage(value => value - 1)}>上一页</Button>
          <span className="text-xs tabular-nums text-muted-foreground">{page + 1}</span>
          <Button type="button" variant="ghost" size="sm" disabled={!ready || rows.length < 30} onClick={() => setPage(value => value + 1)}>下一页</Button>
        </footer>
      </aside>
      <section className={`${selected ? "flex" : "hidden md:flex"} min-h-0 min-w-0 flex-col`}>
        {selected && <button type="button" className="inline-flex min-h-11 shrink-0 items-center gap-2 border-b border-border-subtle px-3 text-sm text-muted-foreground md:hidden" onClick={backToList}><ArrowLeft className="size-4" aria-hidden="true" />对话列表</button>}
        {selected && selectedRow
          ? <SupportThread key={identity + ":" + selected} id={selected} admin adminUserId={selectedRow.user_id} identity={identity} />
          : <div className="flex flex-1 items-center justify-center text-sm text-muted-foreground">{ready ? "选择客服对话" : "正在加载会话"}</div>}
      </section>
    </div>
  </AdminPage>
}

export default function SupportWorkbenchPage() { return <Suspense><Workbench /></Suspense> }
