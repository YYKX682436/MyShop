"use client"

import { useCallback, useEffect, useState } from "react"
import { toast } from "sonner"
import { AdminPage, PageHeader, StatusBadge } from "@/components/admin/shared"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { formatYuan } from "@/lib/utils"
import { adminSubsiteApi, adminUserApi, adminUserLevelApi, type SubsiteCustomer, type SubsiteInfo, type SubsiteSettings } from "@/services/api"
import type { AdminUserItem, UserLevel } from "@/types"

export default function AdminSubsitesPage() {
  const [sites, setSites] = useState<SubsiteInfo[]>([])
  const [settings, setSettings] = useState<SubsiteSettings | null>(null)
  const [levels, setLevels] = useState<UserLevel[]>([])
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState("")
  const [filter, setFilter] = useState("")
  const [status, setStatus] = useState("")
  const [search, setSearch] = useState("")
  const [users, setUsers] = useState<AdminUserItem[]>([])
  const [ownerId, setOwnerId] = useState("")
  const [prefix, setPrefix] = useState("")
  const [name, setName] = useState("")
  const [customerSite, setCustomerSite] = useState<SubsiteInfo | null>(null)
  const [customers, setCustomers] = useState<SubsiteCustomer[]>([])
  const [customerPage, setCustomerPage] = useState(0)
  const [customerPages, setCustomerPages] = useState(0)
  const [transferLevels, setTransferLevels] = useState<Record<string, string>>({})
  const load = useCallback(async () => {
    setLoading(true); setError("")
    try { const [siteData, config, levelData] = await Promise.all([adminSubsiteApi.list(), adminSubsiteApi.settings(), adminUserLevelApi.getList()]); setSites(siteData); setSettings(config); setLevels(levelData.filter(level => level.enabled)) }
    catch (e) { setError(e instanceof Error ? e.message : "读取失败") }
    finally { setLoading(false) }
  }, [])
  useEffect(() => { void load() }, [load])
  const loadCustomers = useCallback(async (site: SubsiteInfo, page: number) => {
    const result = await adminSubsiteApi.customers(site.id, page)
    setCustomerSite(site); setCustomers(result.content); setCustomerPage(page); setCustomerPages(result.total_pages)
  }, [])
  async function run(action: () => Promise<unknown>, message: string) {
    setBusy(true)
    try { await action(); toast.success(message); await load() }
    catch (e) { toast.error(e instanceof Error ? e.message : "操作失败") }
    finally { setBusy(false) }
  }
  async function searchUsers(event: React.FormEvent) {
    event.preventDefault(); setBusy(true)
    try { const result = await adminUserApi.getList({ page: 1, page_size: 20, keyword: search.trim(), is_deleted: 0 }); setUsers(result.list); setOwnerId("") }
    catch (e) { toast.error(e instanceof Error ? e.message : "搜索失败") }
    finally { setBusy(false) }
  }
  async function openSite(event: React.FormEvent) {
    event.preventDefault()
    await run(() => adminSubsiteApi.open({ owner_id: ownerId, prefix, name }), "分站已开通")
  }
  async function transfer(user: SubsiteCustomer) {
    if (!customerSite || !transferLevels[user.id]) return
    if (!window.confirm(`将 ${user.username} 移交主站并调整等级？`)) return
    setBusy(true)
    try { await adminSubsiteApi.transfer(customerSite.id, user.id, transferLevels[user.id]); toast.success("客户已移交"); await loadCustomers(customerSite, customerPage); await load() }
    catch (e) { toast.error(e instanceof Error ? e.message : "移交失败") }
    finally { setBusy(false) }
  }
  const shown = sites.filter(site => (!status || String(site.enabled) === status) && `${site.name} ${site.prefix} ${site.owner_username ?? ""} ${site.owner_user_no ?? ""}`.toLowerCase().includes(filter.toLowerCase().trim()))
  return <AdminPage><PageHeader title="分站管理" onRefresh={() => void load()} refreshing={loading} />
    {error && <p role="alert" className="text-destructive">{error} <Button type="button" variant="ghost" onClick={() => void load()}>重试</Button></p>}
    {loading ? <p>加载中…</p> : !error && settings && <div className="space-y-6">
      <form onSubmit={event => { event.preventDefault(); void run(() => adminSubsiteApi.updateSettings(settings), "开通设置已保存") }} className="content-surface space-y-4 rounded-card p-5"><h2 className="text-lg font-semibold">开通设置</h2>
        <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={settings.self_service_enabled} onChange={e => setSettings({ ...settings, self_service_enabled: e.target.checked })} />允许自助开通</label>
        <label className="block max-w-xs space-y-1 text-sm"><span>开通费用（元）</span><Input type="number" min="0" step="0.01" required value={settings.opening_price} onChange={e => setSettings({ ...settings, opening_price: Number(e.target.value) })} /></label>
        <label className="block max-w-xs space-y-1 text-sm"><span>统一供货等级</span><select required className="h-10 w-full rounded-control border border-input bg-surface px-3 text-sm" value={settings.supply_level_id ?? ""} onChange={e => setSettings({ ...settings, supply_level_id: e.target.value || null })}><option value="">请选择</option>{levels.map(level => <option key={level.id} value={level.id}>{level.name}</option>)}</select></label>
        <fieldset><legend className="mb-2 text-sm font-medium">允许开通的等级</legend><div className="flex flex-wrap gap-4">{levels.map(level => <label key={level.id} className="flex items-center gap-2 text-sm"><input type="checkbox" checked={settings.allowed_level_ids.includes(level.id)} onChange={e => setSettings({ ...settings, allowed_level_ids: e.target.checked ? [...settings.allowed_level_ids, level.id] : settings.allowed_level_ids.filter(id => id !== level.id) })} />{level.name}</label>)}</div></fieldset>
        <Button type="submit" disabled={busy}>保存设置</Button>
      </form>
      <section className="content-surface space-y-4 rounded-card p-5"><h2 className="text-lg font-semibold">人工开通</h2>
        <form onSubmit={searchUsers} className="flex flex-wrap items-end gap-2"><label className="min-w-48 flex-1 space-y-1 text-sm"><span>搜索站长账号或编号</span><Input required value={search} onChange={e => setSearch(e.target.value)} /></label><Button type="submit" variant="outline" disabled={busy}>搜索</Button></form>
        <form onSubmit={openSite} className="grid gap-3 sm:grid-cols-3"><label className="space-y-1 text-sm"><span>站长</span><select required className="h-10 w-full rounded-control border border-input bg-surface px-3 text-sm" value={ownerId} onChange={e => setOwnerId(e.target.value)}><option value="">请选择搜索结果</option>{users.map(user => <option key={user.id} value={user.id}>{user.username} · {user.user_no}</option>)}</select></label><label className="space-y-1 text-sm"><span>域名前缀</span><Input required maxLength={63} value={prefix} onChange={e => setPrefix(e.target.value)} /></label><label className="space-y-1 text-sm"><span>网站名称</span><Input required maxLength={100} value={name} onChange={e => setName(e.target.value)} /></label><div><Button type="submit" disabled={busy}>开通分站</Button></div></form>
      </section>
      <section className="content-surface space-y-4 rounded-card p-5"><h2 className="text-lg font-semibold">分站列表</h2><div className="flex flex-wrap gap-3"><Input className="max-w-sm" placeholder="搜索名称、域名或站长" aria-label="搜索分站" value={filter} onChange={e => setFilter(e.target.value)} /><select aria-label="状态筛选" className="h-10 rounded-control border border-input bg-surface px-3 text-sm" value={status} onChange={e => setStatus(e.target.value)}><option value="">全部状态</option><option value="true">运行中</option><option value="false">已停用</option></select></div>
        <ul className="divide-y divide-border">{shown.map(site => <li key={site.id} className="space-y-2 py-4"><div className="flex flex-wrap items-center justify-between gap-2"><strong>{site.name}</strong><StatusBadge label={site.enabled ? "运行中" : "已停用"} tone={site.enabled ? "success" : "neutral"} /></div><p className="break-all text-sm text-muted-foreground">{site.url} · 站长 {site.owner_username ?? "—"}{site.owner_user_no != null ? ` #${site.owner_user_no}` : ""}</p><p className="text-sm">客户 {site.user_count} · 订单 {site.order_count ?? 0} · 已入账 {formatYuan(site.settled_profit ?? 0)}</p><div className="flex flex-wrap gap-2"><Button type="button" variant="outline" disabled={busy} onClick={() => { if (window.confirm(`${site.enabled ? "停用" : "启用"} ${site.name}？`)) void run(() => adminSubsiteApi.status(site.id, !site.enabled), "状态已更新") }}>{site.enabled ? "停用" : "启用"}</Button><Button type="button" variant="outline" disabled={busy} onClick={() => void loadCustomers(site, 0).catch(e => toast.error(e instanceof Error ? e.message : "读取失败"))}>客户移交</Button></div></li>)}</ul>
        {!shown.length && <p className="text-sm text-muted-foreground">暂无分站</p>}
      </section>
      {customerSite && <section className="content-surface space-y-4 rounded-card p-5"><div className="flex items-center justify-between gap-2"><h2 className="text-lg font-semibold">{customerSite.name} · 客户移交</h2><Button type="button" variant="ghost" onClick={() => setCustomerSite(null)}>关闭</Button></div><ul className="divide-y divide-border">{customers.map(customer => <li key={customer.id} className="flex flex-wrap items-center gap-3 py-3"><span className="min-w-32 flex-1">{customer.username} #{customer.user_no}</span><select aria-label={`${customer.username} 移交后的等级`} className="h-10 rounded-control border border-input bg-surface px-3 text-sm" value={transferLevels[customer.id] ?? ""} onChange={e => setTransferLevels(value => ({ ...value, [customer.id]: e.target.value }))}><option value="">选择主站等级</option>{levels.map(level => <option key={level.id} value={level.id}>{level.name}</option>)}</select><Button type="button" disabled={busy || !transferLevels[customer.id]} onClick={() => void transfer(customer)}>移交</Button></li>)}</ul>{!customers.length && <p className="text-sm text-muted-foreground">暂无客户</p>}<div className="flex justify-between"><Button type="button" variant="ghost" disabled={customerPage === 0} onClick={() => void loadCustomers(customerSite, customerPage - 1)}>上一页</Button><span>{customerPage + 1} / {Math.max(1, customerPages)}</span><Button type="button" variant="ghost" disabled={customerPage + 1 >= customerPages} onClick={() => void loadCustomers(customerSite, customerPage + 1)}>下一页</Button></div></section>}
    </div>}
  </AdminPage>
}
