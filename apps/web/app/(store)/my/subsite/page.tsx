"use client"

import { useCallback, useEffect, useState } from "react"
import Link from "next/link"
import { toast } from "sonner"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { AnnouncementManagement } from "@/components/shared/announcement-management"
import { useRequireAuth } from "@/lib/hooks"
import { formatYuan } from "@/lib/utils"
import { subsiteApi, type SubsiteInfo, type SubsiteCustomer, type SubsiteLevel, type SubsiteOrder } from "@/services/api"

export default function MySubsitePage() {
  const user = useRequireAuth()
  const [site, setSite] = useState<SubsiteInfo | null>(null)
  const [opening, setOpening] = useState<{ enabled: boolean; opening_price: number; eligible: boolean } | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState("")
  const [busy, setBusy] = useState(false)
  const [prefix, setPrefix] = useState("")
  const [name, setName] = useState("")
  const [logo, setLogo] = useState("")
  const [contact, setContact] = useState("")
  const [customers, setCustomers] = useState<SubsiteCustomer[]>([])
  const [levels, setLevels] = useState<SubsiteLevel[]>([])
  const [customerLevels, setCustomerLevels] = useState<Record<string, string>>({})
  const [overview, setOverview] = useState<{ order_count: number; estimated_profit: number; pending_profit: number; settled_profit: number } | null>(null)
  const [orders, setOrders] = useState<SubsiteOrder[]>([])
  const [orderPage, setOrderPage] = useState(0)
  const [orderTotalPages, setOrderTotalPages] = useState(0)
  const [page, setPage] = useState(0)
  const [totalPages, setTotalPages] = useState(0)
  const load = useCallback(async () => {
    setLoading(true); setError("")
    try {
      const result = await subsiteApi.mine()
      setSite(result)
      if (result) {
        setName(result.name); setLogo(result.logo_url ?? ""); setContact(result.customer_service ?? "")
        const [customerPage, availableLevels, stats, orderResult] = await Promise.all([subsiteApi.customers(page), subsiteApi.levels(), subsiteApi.overview(), subsiteApi.orders(orderPage)])
        setCustomers(customerPage.content); setTotalPages(customerPage.total_pages); setLevels(availableLevels)
        setCustomerLevels(Object.fromEntries(customerPage.content.map(customer => [customer.id, customer.user_level_id ?? ""])))
        setOverview(stats); setOrders(orderResult.content); setOrderTotalPages(orderResult.total_pages)
      } else setOpening(await subsiteApi.opening())
    } catch (e) { setError(e instanceof Error ? e.message : "读取失败") }
    finally { setLoading(false) }
  }, [page, orderPage])
  useEffect(() => { if (user) void load() }, [user, load])
  async function open(event: React.FormEvent) {
    event.preventDefault(); setBusy(true)
    try { await subsiteApi.open({ prefix, name }); toast.success("分站已开通"); await load() }
    catch (e) { toast.error(e instanceof Error ? e.message : "开通失败") }
    finally { setBusy(false) }
  }
  async function save(event: React.FormEvent) {
    event.preventDefault(); setBusy(true)
    try { setSite(await subsiteApi.update({ name, logo_url: logo || null, customer_service: contact || null })); toast.success("设置已保存") }
    catch (e) { toast.error(e instanceof Error ? e.message : "保存失败") }
    finally { setBusy(false) }
  }
  async function updateLevel(id: string, levelId: string) {
    setBusy(true)
    try { await subsiteApi.setCustomerLevel(id, levelId); toast.success("等级已更新"); await load() }
    catch (e) { toast.error(e instanceof Error ? e.message : "修改失败") }
    finally { setBusy(false) }
  }
  if (!user) return null
  return <div className="mx-auto max-w-4xl space-y-4 py-0 sm:space-y-6 sm:py-6">
    <header><Link href="/my" className="inline-flex min-h-11 items-center text-sm text-muted-foreground">← 我的</Link><h1 className="mt-2 text-2xl font-semibold">我的分站</h1></header>
    {error && <p role="alert" className="text-destructive">{error} <Button type="button" variant="ghost" onClick={() => void load()}>重试</Button></p>}
    {loading ? <p>加载中…</p> : !error && (site ? <>
      <section className="content-surface space-y-3 rounded-card p-5"><div className="flex flex-wrap items-center justify-between gap-2"><h2 className="text-lg font-semibold">{site.name}</h2><span>{site.enabled ? "运行中" : "已停用"}</span></div>{site.url && <a href={site.url} className="break-all text-primary underline">{site.url}</a>}<p className="text-sm text-muted-foreground">客户 {site.user_count}</p><Link href="/my/subsite/pricing" className="inline-flex min-h-11 items-center rounded-control bg-primary px-4 text-sm font-medium text-primary-foreground">商品定价</Link></section>
      {overview && <section className="content-surface rounded-card p-5"><h2 className="mb-4 text-lg font-semibold">经营概览</h2><dl className="grid grid-cols-2 gap-4 sm:grid-cols-4"><div><dt className="text-sm text-muted-foreground">订单</dt><dd className="mt-1 text-xl font-semibold">{overview.order_count}</dd></div><div><dt className="text-sm text-muted-foreground">预计收益</dt><dd className="mt-1 text-xl font-semibold">{formatYuan(overview.estimated_profit)}</dd></div><div><dt className="text-sm text-muted-foreground">待入账收益</dt><dd className="mt-1 text-xl font-semibold">{formatYuan(overview.pending_profit)}</dd></div><div><dt className="text-sm text-muted-foreground">已入账收益</dt><dd className="mt-1 text-xl font-semibold">{formatYuan(overview.settled_profit)}</dd></div></dl></section>}
      <form onSubmit={save} className="content-surface space-y-4 rounded-card p-5"><h2 className="text-lg font-semibold">网站设置</h2>
        <label className="block space-y-1 text-sm"><span>网站名称</span><Input required maxLength={100} value={name} onChange={e => setName(e.target.value)} /></label>
        <label className="block space-y-1 text-sm"><span>Logo 地址</span><Input maxLength={500} value={logo} onChange={e => setLogo(e.target.value)} /></label>
        <label className="block space-y-1 text-sm"><span>客服</span><Input maxLength={500} value={contact} onChange={e => setContact(e.target.value)} /></label>
        <Button type="submit" disabled={busy}>保存设置</Button>
      </form>
      <section className="content-surface space-y-4 rounded-card p-5"><h2 className="text-lg font-semibold">公告管理</h2><AnnouncementManagement scope="subsite" /></section>
      <section className="content-surface space-y-4 rounded-card p-5"><h2 className="text-lg font-semibold">分站订单</h2><ul className="divide-y divide-border">{orders.map(order => <li key={order.id} className="space-y-1 py-3 text-sm"><div className="flex flex-wrap justify-between gap-2"><span className="break-all font-medium">{order.order_no}</span><span>{order.status}</span></div><div className="flex flex-wrap justify-between gap-2 text-muted-foreground"><span>成交 {formatYuan(order.actual_amount)}</span><span>{order.subsite_profit_state === "CANCELED" ? `已取消 ${formatYuan(order.subsite_profit)}` : order.subsite_profit_state === "REVERSED" ? `已冲回 ${formatYuan(order.subsite_profit)}` : order.subsite_profit_state === "SETTLED" ? `已入账 ${formatYuan(order.subsite_profit)}` : order.subsite_profit_state === "PENDING" ? `待入账 ${formatYuan(order.subsite_profit)}` : order.status === "PAID" ? `预计收益 ${formatYuan(order.subsite_profit)}` : "未结算"}</span></div></li>)}</ul>{!orders.length && <p className="text-sm text-muted-foreground">暂无订单</p>}<div className="flex items-center justify-between text-sm"><Button type="button" variant="ghost" disabled={orderPage === 0 || busy} onClick={() => setOrderPage(value => value - 1)}>上一页</Button><span>{orderPage + 1} / {Math.max(1, orderTotalPages)}</span><Button type="button" variant="ghost" disabled={orderPage + 1 >= orderTotalPages || busy} onClick={() => setOrderPage(value => value + 1)}>下一页</Button></div></section>
      <section className="content-surface space-y-4 rounded-card p-5"><h2 className="text-lg font-semibold">我的客户</h2>
        <ul className="divide-y divide-border">{customers.map(customer => <li key={customer.id} className="flex flex-wrap items-center justify-between gap-3 py-3"><span>{customer.username} {customer.user_no != null && <span className="text-muted-foreground">#{customer.user_no}</span>}</span><label className="text-sm">等级 <select className="ml-2 rounded-control border border-input bg-background p-2" value={customerLevels[customer.id] ?? ""} disabled={busy} onChange={e => setCustomerLevels(value => ({ ...value, [customer.id]: e.target.value }))}><option value="" disabled>选择等级</option>{levels.map(level => <option key={level.id} value={level.id}>{level.name}</option>)}</select></label><Button type="button" variant="outline" disabled={busy || !customerLevels[customer.id] || customerLevels[customer.id] === customer.user_level_id} onClick={() => void updateLevel(customer.id, customerLevels[customer.id])}>保存等级</Button></li>)}</ul>
        {!customers.length && <p className="text-sm text-muted-foreground">暂无客户</p>}
        <div className="flex items-center justify-between text-sm"><Button type="button" variant="ghost" disabled={page === 0 || busy} onClick={() => setPage(value => value - 1)}>上一页</Button><span>{page + 1} / {Math.max(1, totalPages)}</span><Button type="button" variant="ghost" disabled={page + 1 >= totalPages || busy} onClick={() => setPage(value => value + 1)}>下一页</Button></div>
      </section>
    </> : opening?.enabled && opening.eligible ? <form onSubmit={open} className="content-surface space-y-4 rounded-card p-5"><h2 className="text-lg font-semibold">开通分站</h2><p className="text-sm">开通费用：{formatYuan(opening.opening_price)}，从余额扣除。</p>
      <label className="block space-y-1 text-sm"><span>域名前缀</span><Input required maxLength={63} pattern="[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?" autoCapitalize="none" spellCheck={false} value={prefix} onChange={e => setPrefix(e.target.value)} /></label>
      <label className="block space-y-1 text-sm"><span>网站名称</span><Input required maxLength={100} value={name} onChange={e => setName(e.target.value)} /></label><Button type="submit" disabled={busy}>开通分站</Button></form> : <p>当前账号暂不可自助开通分站。</p>)}
  </div>
}
