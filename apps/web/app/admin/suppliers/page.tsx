"use client"

import { useCallback, useEffect, useRef, useState, type FormEvent } from "react"
import { Plus, RefreshCw, Search } from "lucide-react"
import { toast } from "sonner"
import { AdminPage, PageHeader, StatusBadge } from "@/components/admin/shared"
import { Button } from "@/components/ui/button"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { SupplierPicker, type SupplierSelection } from "@/components/admin/suppliers/supplier-picker"
import { useAuth } from "@/lib/context"
import { adminCategoryApi, adminProductApi, adminSupplierApi, type SupplierAccount, type SupplierAccountInput, type SupplierCategory, type SupplierMapping, type SupplierProduct, type SupplierSummary } from "@/services/api"
import type { AdminProductDetail, Category } from "@/types"

const blank: SupplierAccountInput = { name: "", provider_type: "KAYIXIN", base_url: "https://", app_id: "", secret: "", enabled: true }
const fieldClass = "flex flex-col gap-1.5 text-sm font-medium"
const selectClass = "h-10 w-full rounded-control border border-input bg-surface px-3 text-sm"
function message(error: unknown) { return error instanceof Error ? error.message : "操作失败，请重试" }
function syncError(row: SupplierMapping) {
  if (row.local_state === "PRODUCT_DELETED") return "本地商品已删除，可解除关联后重新导入"
  if (row.local_state === "PRODUCT_CHANGED") return "本地库存方式已变更，请解除关联后重新配置"
  if (row.local_state === "SPEC_DELETED") return "本地规格不存在，请解除关联并重新选择规格"
  switch (row.sync_error) {
    case "DATA": return "上游资料或规格不兼容，请检查后重新同步"
    case "CONNECTION": return "无法连接供货站，请检查连接后重试"
    case "CONFIG": return "货源配置异常，请检查账号"
    default: return row.sync_error ? "同步失败，请检查货源配置后重试" : ""
  }
}
function flatten<T extends { id: string; name: string; children?: T[] }>(items: T[], depth = 0): { id: string; name: string }[] {
  return items.flatMap(item => [{ id: item.id, name: `${"　".repeat(depth)}${item.name}` }, ...flatten(item.children || [], depth + 1)])
}

export default function SuppliersPage() {
  const { user } = useAuth()
  const [accounts, setAccounts] = useState<SupplierAccount[]>([])
  const [selected, setSelected] = useState("")
  const selectedRef = useRef("")
  const searchSequence = useRef(0)
  const [accountDialog, setAccountDialog] = useState(false)
  const [editing, setEditing] = useState<string | null>(null)
  const [form, setForm] = useState<SupplierAccountInput>(blank)
  const [busy, setBusy] = useState(false)
  const [balance, setBalance] = useState<string | null>(null)
  const [remoteCategories, setRemoteCategories] = useState<SupplierCategory[]>([])
  const [localCategories, setLocalCategories] = useState<Category[]>([])
  const [localCategory, setLocalCategory] = useState("")
  const [remoteCategory, setRemoteCategory] = useState("")
  const [keyword, setKeyword] = useState("")
  const [goods, setGoods] = useState<SupplierSummary[]>([])
  const [page, setPage] = useState(0)
  const [hasMore, setHasMore] = useState(false)
  const [searching, setSearching] = useState(false)
  const [detail, setDetail] = useState<SupplierProduct | null>(null)
  const [mappings, setMappings] = useState<SupplierMapping[]>([])
  const [loadingMappings, setLoadingMappings] = useState(false)
  const [importMode, setImportMode] = useState<"new" | "existing">("new")
  const [localKeyword, setLocalKeyword] = useState("")
  const [localProducts, setLocalProducts] = useState<AdminProductDetail[]>([])
  const [targetProduct, setTargetProduct] = useState<AdminProductDetail | null>(null)
  const [targetMappings, setTargetMappings] = useState<SupplierMapping[]>([])
  const [targetSpec, setTargetSpec] = useState("")
  const [remoteSku, setRemoteSku] = useState<string | null>(null)
  const [resultProduct, setResultProduct] = useState("")
  const [replacement, setReplacement] = useState<SupplierMapping | null>(null)
  const targetSequence = useRef(0)
  const active = accounts.find(account => account.id === selected)
  const importedIds = new Set(mappings.filter(mapping => mapping.local_state !== "PRODUCT_DELETED").map(mapping => mapping.remote_goods_id))

  const choose = useCallback((id: string) => {
    selectedRef.current = id
    searchSequence.current += 1
    setSelected(id); setBalance(null); setRemoteCategories([]); setRemoteCategory("")
    setGoods([]); setPage(0); setHasMore(false); setSearching(false); setDetail(null); setMappings([])
    targetSequence.current += 1; setTargetProduct(null); setTargetMappings([]); setLocalProducts([]); setResultProduct(""); setBusy(false)
  }, [])
  const loadAccounts = useCallback(async () => {
    try {
      const items = await adminSupplierApi.accounts()
      setAccounts(items)
      if (!selectedRef.current && items.length) choose(items[0].id)
    } catch (error) { toast.error(message(error)) }
  }, [choose])
  const loadMappings = useCallback(async (id: string) => {
    setLoadingMappings(true)
    try {
      const rows = await adminSupplierApi.mappings(id)
      if (selectedRef.current === id) setMappings(rows)
    } catch (error) { if (selectedRef.current === id) toast.error(message(error)) }
    finally { if (selectedRef.current === id) setLoadingMappings(false) }
  }, [])
  useEffect(() => { void loadAccounts(); void adminCategoryApi.getList().then(setLocalCategories).catch(error => toast.error(message(error))) }, [loadAccounts])
  useEffect(() => {
    if (!selected) return
    void loadMappings(selected)
    if (active?.enabled) void adminSupplierApi.categories(selected).then(items => {
      if (selectedRef.current === selected) setRemoteCategories(items)
    }).catch(error => { if (selectedRef.current === selected) toast.error(message(error)) })
  }, [selected, active?.enabled, loadMappings])

  function editAccount(account?: SupplierAccount) {
    setEditing(account?.id || null)
    setForm(account ? { name: account.name, provider_type: account.provider_type, base_url: account.base_url, app_id: account.app_id, secret: "", enabled: account.enabled } : { ...blank })
    setAccountDialog(true)
  }
  function closeAccount() { setAccountDialog(false); setForm({ ...blank }); setEditing(null) }
  async function saveAccount(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setBusy(true)
    try {
      const saved = editing ? await adminSupplierApi.update(editing, form) : await adminSupplierApi.create(form)
      closeAccount(); await loadAccounts(); choose(saved.id); toast.success("货源账号已保存")
    } catch (error) { toast.error(message(error)) }
    finally { setBusy(false) }
  }
  async function toggleAccount() {
    if (!active) return
    if (!window.confirm(active.enabled ? `停用 ${active.name} 后将停止新采购，确定继续？` : `确定启用 ${active.name}？`)) return
    setBusy(true)
    try { await adminSupplierApi.status(active.id, !active.enabled); setBalance(null); await loadAccounts(); toast.success(active.enabled ? "货源已停用" : "货源已启用") }
    catch (error) { toast.error(message(error)) } finally { setBusy(false) }
  }
  async function testConnection() {
    if (!active) return
    const id = active.id; setBusy(true)
    try { const data = await adminSupplierApi.test(id); if (selectedRef.current === id) { setBalance(data.balance); toast.success("连接成功") } }
    catch (error) { if (selectedRef.current === id) { setBalance(null); toast.error(message(error)) } } finally { setBusy(false) }
  }
  async function searchGoods(nextPage: number) {
    if (!active || searching) return
    const id = active.id; const sequence = ++searchSequence.current
    setSearching(true)
    try {
      const result = await adminSupplierApi.goods(id, keyword.trim(), remoteCategory, nextPage)
      if (selectedRef.current !== id || searchSequence.current !== sequence) return
      const seen = new Set(nextPage === 1 ? [] : goods.map(item => item.id))
      const newItems = result.items.filter(item => { if (seen.has(item.id)) return false; seen.add(item.id); return true })
      setGoods(nextPage === 1 ? newItems : [...goods, ...newItems])
      setPage(nextPage); setHasMore(newItems.length > 0 && nextPage < 1000)
      setDetail(null)
    } catch (error) { if (selectedRef.current === id && searchSequence.current === sequence) toast.error(message(error)) }
    finally { if (selectedRef.current === id && searchSequence.current === sequence) setSearching(false) }
  }
  async function showDetail(goodsId: string) {
    if (!active) return
    const id = active.id; setBusy(true)
    try { const item = await adminSupplierApi.detail(id, goodsId); if (selectedRef.current === id) { setDetail(item); setRemoteSku(item.sku_type === 0 ? "" : null); setResultProduct("") } }
    catch (error) { if (selectedRef.current === id) toast.error(message(error)) } finally { setBusy(false) }
  }
  async function importProduct() {
    if (!active || !detail || !localCategory) return
    const id = active.id; setBusy(true)
    try {
      const result = await adminSupplierApi.import(id, detail.id, localCategory)
      if (selectedRef.current === id) { await loadMappings(id); setResultProduct(result.product_id); toast.success(result.created ? "已导入，下架待配置" : "该商品已导入") }
    } catch (error) { if (selectedRef.current === id) toast.error(message(error)) } finally { setBusy(false) }
  }
  async function searchLocal() {
    setBusy(true)
    try { const result=await adminProductApi.getList({ keyword: localKeyword.trim(), page: 1, page_size: 30 }); setLocalProducts(result.list) }
    catch (error) { toast.error(message(error)) } finally { setBusy(false) }
  }
  async function selectLocal(id: string) {
    const sequence=++targetSequence.current
    setTargetProduct(null); setTargetMappings([]); setTargetSpec("")
    if (!id) return
    setBusy(true)
    try {
      const [product, rows]=await Promise.all([adminProductApi.getDetail(id),adminSupplierApi.productMappings(id)])
      if (targetSequence.current===sequence) { setTargetProduct(product); setTargetMappings(rows) }
    } catch (error) { if (targetSequence.current===sequence) toast.error(message(error)) }
    finally { if (targetSequence.current===sequence) setBusy(false) }
  }
  async function bindProduct() {
    if (!active || !detail || !targetProduct || remoteSku===null || (targetProduct.spec_enabled && !targetSpec)) return
    const target=targetMappings.find(row => (row.spec_id || "") === targetSpec)
    const source=mappings.find(row => row.remote_goods_id===detail.id && row.remote_sku===remoteSku)
    const moving=Boolean(source && (source.product_id!==targetProduct.id || (source.spec_id || "")!==targetSpec))
    const oldTarget=target ? `当前规格绑定：${accounts.find(a => a.id===target.account_id)?.name || "货源"} / ${target.remote_goods_name || target.remote_goods_id} / ${target.remote_sku_name || target.remote_sku || "单规格"}。` : "当前规格尚未绑定货源。"
    const oldSource=moving && source ? `该上游规格已绑定「${source.product_name || "其他商品"}」${source.spec_name ? ` / ${source.spec_name}` : ""}${source.local_state === "PRODUCT_DELETED" ? "（已删除）" : ""}，确认后将移动绑定。` : ""
    const conversion=targetProduct.stock_mode!=="SUPPLIER" ? "整个商品将转换为供应商库存，其他未绑定规格将无库存；存在本地库存或未完成订单时不能转换。" : ""
    if (!window.confirm(`绑定到「${targetProduct.title}」${targetSpec ? ` / ${targetProduct.specs.find(s => s.id===targetSpec)?.name || targetSpec}` : ""}？\n${oldTarget}\n${oldSource}\n${conversion}\n将更新供应关系、成本和库存，保留名称及售价。`)) return
    const id=active.id; setBusy(true)
    try {
      const result=await adminSupplierApi.bind(id,detail.id,{ product_id: targetProduct.id, spec_id: targetSpec || null, remote_sku: remoteSku, confirmed: true, move_confirmed: moving, expected_target_mapping_id: target?.id || null, expected_source_mapping_id: source?.id || null, expected_source_product_id: source?.product_id || null, expected_source_spec_id: source?.spec_id || null })
      if (selectedRef.current===id) { await loadMappings(id); setTargetMappings(await adminSupplierApi.productMappings(targetProduct.id)); setResultProduct(result.product_id); toast.success("货源绑定已保存") }
    } catch (error) { if (selectedRef.current===id) toast.error(message(error)) } finally { setBusy(false) }
  }
  async function syncPresentation(row: SupplierMapping) {
    if (!active || !window.confirm("同步商品资料将覆盖本地图片、简介、详情和购买提醒，保留商品名称、分类及售价。确定继续？")) return
    const id=active.id; setBusy(true)
    try { await adminSupplierApi.presentation(id,row.remote_goods_id,row.product_id); if (selectedRef.current===id) toast.success("商品资料已同步") }
    catch (error) { if (selectedRef.current===id) toast.error(message(error)) } finally { setBusy(false) }
  }
  async function sync(goodsId: string) {
    if (!active) return
    const id = active.id; setBusy(true)
    try { await adminSupplierApi.sync(id, goodsId); if (selectedRef.current === id) toast.success("同步完成") }
    catch (error) { if (selectedRef.current === id) toast.error(message(error)) }
    finally { if (selectedRef.current === id) await loadMappings(id); setBusy(false) }
  }
  async function unbind(row: SupplierMapping) {
    if (!active || !window.confirm(`解除「${row.product_name || "本地商品"}」的货源关联？未删除的商品将下架。`)) return
    setBusy(true)
    try { await adminSupplierApi.unbind(row.account_id,row.id); await loadMappings(active.id); toast.success("货源关联已解除") }
    catch (error) { toast.error(message(error)) } finally { setBusy(false) }
  }
  async function replaceSource(row: SupplierMapping, selection: SupplierSelection) {
    const source=selection.source
    const moving=Boolean(source && (source.product_id!==row.product_id || (source.spec_id || "")!==(row.spec_id || "")))
    if (!window.confirm(`将「${row.product_name || "本地商品"}${row.spec_name ? ` / ${row.spec_name}` : ""}」改为「${selection.product.name}」？${moving ? `\n上游规格已关联 ${source?.product_name || "其他商品"}，将移动关联并下架原商品。` : ""}`)) return
    setBusy(true)
    try {
      await adminSupplierApi.bind(selection.account.id,selection.product.id,{product_id:row.product_id,spec_id:row.spec_id || null,remote_sku:selection.sku.id,
        confirmed:true,move_confirmed:moving,expected_target_mapping_id:row.id,expected_source_mapping_id:source?.id || null,
        expected_source_product_id:source?.product_id || null,expected_source_spec_id:source?.spec_id || null})
      if (active) await loadMappings(active.id)
      toast.success("货源已更换")
    } catch (error) { toast.error(message(error)) } finally { setBusy(false) }
  }

  return <AdminPage>
    <PageHeader title="货源管理" actions={<Button onClick={() => editAccount()}><Plus />添加货源</Button>} />
    <section className="rounded-card border border-border-subtle bg-surface p-4 sm:p-5">
      <div className="flex flex-wrap items-end gap-3">
        <label className={`${fieldClass} min-w-48 flex-1`}>供货站<select className={selectClass} value={selected} onChange={event => choose(event.target.value)}><option value="">选择货源</option>{accounts.map(a => <option key={a.id} value={a.id}>{a.name}{a.enabled ? "" : "（已停用）"}</option>)}</select></label>
        {active && <><Button type="button" variant="outline" onClick={() => editAccount(active)}>编辑</Button><Button type="button" variant="outline" disabled={busy} onClick={() => void toggleAccount()}>{active.enabled ? "停用" : "启用"}</Button><Button type="button" variant="outline" disabled={busy || !active.enabled} onClick={() => void testConnection()}>测试连接</Button></>}
      </div>
      {active && <div className="mt-4 flex flex-wrap items-center gap-3 text-sm"><StatusBadge label={active.enabled ? "启用" : "已停用"} tone={active.enabled ? "success" : "neutral"} /><span>{active.base_url}</span><span className="text-muted-foreground">客户编号 {active.app_id} · 密钥 {active.secret_mask}</span>{balance !== null && <strong>余额 ¥{balance}</strong>}</div>}
      {active?.provider_type === "KAYIXIN" && <div className="mt-3 text-sm"><span className="text-muted-foreground">商品通知地址：</span>{active.goods_callback_url ? <code className="break-all">{active.goods_callback_url}</code> : <span>配置公开 HTTPS 站点后可用</span>}</div>}
    </section>
    {active && <>
      <section className="rounded-card border border-border-subtle bg-surface p-4 sm:p-5">
        <h2 className="mb-4 text-lg font-semibold">上游商品</h2>
        <form aria-label="搜索上游商品" className="flex flex-wrap items-end gap-3" onSubmit={event => { event.preventDefault(); void searchGoods(1) }}>
          <label className={`${fieldClass} min-w-48 flex-1`}>商品名称或编号<Input name="keyword" type="search" value={keyword} onChange={event => { setKeyword(event.target.value); setHasMore(false); searchSequence.current += 1; setSearching(false) }} /></label>
          <label className={`${fieldClass} min-w-48`}>上游分类<select name="category" className={selectClass} value={remoteCategory} onChange={event => { setRemoteCategory(event.target.value); setHasMore(false); searchSequence.current += 1; setSearching(false) }}><option value="">全部分类</option>{flatten(remoteCategories).map(c => <option key={c.id} value={c.id}>{c.name}</option>)}</select></label>
          <Button type="submit" disabled={searching || !active.enabled}><Search />搜索</Button>
        </form>
        <div className="mt-4 space-y-2">{goods.map(item => <button key={item.id} type="button" disabled={busy || !active.enabled} onClick={() => void showDetail(item.id)} className="flex w-full flex-wrap items-center justify-between gap-2 rounded-control border border-border-subtle px-3 py-3 text-left hover:bg-surface-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"><span className="min-w-0 break-words font-medium">{item.name} <span className="text-xs text-muted-foreground">#{item.id}</span></span><span className="text-sm text-muted-foreground">{item.goods_type === 1 ? "卡券" : "直充"} · ¥{item.cost} {importedIds.has(item.id) ? "· 已导入" : ""}</span></button>)}</div>
        {page > 0 && <div className="mt-3 flex items-center gap-3 text-sm text-muted-foreground"><span>{goods.length ? `已显示 ${goods.length} 件` : "没有匹配商品"}</span>{hasMore && <Button type="button" size="sm" variant="outline" disabled={searching} onClick={() => void searchGoods(page + 1)}>加载更多</Button>}</div>}
        {detail && <div className="mt-5 rounded-card border border-border-subtle bg-surface-muted/30 p-4">
          <div className="flex flex-wrap items-start justify-between gap-2"><div><h3 className="font-semibold">{detail.name}</h3><p className="mt-1 text-sm text-muted-foreground">#{detail.id} · {detail.goods_type === 1 ? "卡券" : "直充"} · {detail.sku_type === 0 ? "单规格" : detail.sku_type === 1 ? "多规格" : "多单规格"} · {detail.status === 1 ? "销售中" : "停售"}</p></div></div>
          <p className="mt-3 text-sm">购买倍数 {detail.multiple} · 数量 {detail.min_quantity}–{detail.max_quantity}</p>
          <div className="mt-3 overflow-x-auto" role="region" aria-label="上游规格" tabIndex={0}><table className="w-full text-left text-sm"><thead><tr><th scope="col" className="p-2">规格</th><th scope="col" className="p-2">SKU</th><th scope="col" className="p-2">成本</th><th scope="col" className="p-2">库存</th><th scope="col" className="p-2">状态</th></tr></thead><tbody>{detail.skus.map(s => <tr className="border-t border-border-subtle" key={s.id || "single"}><td className="p-2">{[s.group, s.name].filter(Boolean).join(" / ") || "默认"}</td><td className="p-2">{s.id || "—"}</td><td className="p-2">¥{s.cost}</td><td className="p-2">{s.stock}</td><td className="p-2">{s.status === 1 ? "上架" : "下架"}</td></tr>)}</tbody></table></div>
          <fieldset className="mt-4"><legend className="mb-2 text-sm font-medium">导入方式</legend><div className="flex flex-wrap gap-5 text-sm">
            <label className="flex items-center gap-2"><input type="radio" name="import-mode" checked={importMode==="new"} onChange={() => setImportMode("new")} />新增商品</label>
            <label className="flex items-center gap-2"><input type="radio" name="import-mode" checked={importMode==="existing"} onChange={() => setImportMode("existing")} />覆盖已有</label>
          </div></fieldset>
          {importMode==="new" ? <div className="mt-4 flex flex-wrap items-end gap-3"><label className={`${fieldClass} min-w-48`}>本地分类<select className={selectClass} value={localCategory} onChange={event => setLocalCategory(event.target.value)}><option value="">选择分类</option>{flatten(localCategories).map(c => <option key={c.id} value={c.id}>{c.name}</option>)}</select></label><Button type="button" disabled={busy || !localCategory || !active.enabled} onClick={() => void importProduct()}>{importedIds.has(detail.id) ? "检查导入" : "导入商品"}</Button><span className="text-sm text-muted-foreground">导入后默认下架</span></div> : <div className="mt-4 space-y-3">
            <div className="flex flex-wrap items-end gap-3"><label className={`${fieldClass} min-w-48 flex-1`}>搜索本地商品<Input type="search" value={localKeyword} onChange={event => setLocalKeyword(event.target.value)} /></label><Button type="button" variant="outline" disabled={busy} onClick={() => void searchLocal()}>搜索</Button></div>
            <label className={fieldClass}>本地商品<select className={selectClass} value={targetProduct?.id || ""} onChange={event => void selectLocal(event.target.value)}><option value="">选择商品</option>{localProducts.map(p => <option key={p.id} value={p.id}>{p.title} #{p.product_no}</option>)}</select></label>
            {targetProduct?.spec_enabled && <label className={fieldClass}>本地规格<select className={selectClass} value={targetSpec} onChange={event => setTargetSpec(event.target.value)}><option value="">选择规格</option>{targetProduct.specs.filter(s => s.is_deleted===0).map(s => <option key={s.id} value={s.id}>{s.name}</option>)}</select></label>}
            {detail.sku_type!==0 && <label className={fieldClass}>上游规格<select className={selectClass} value={remoteSku ?? ""} onChange={event => setRemoteSku(event.target.value || null)}><option value="">选择上游 SKU</option>{detail.skus.map(s => <option key={s.id} value={s.id}>{[s.group,s.name].filter(Boolean).join(" / ")} · {s.id}</option>)}</select></label>}
            {targetProduct && <div className="text-sm text-muted-foreground">当前绑定：{(() => { const row=targetMappings.find(m => (m.spec_id || "")===targetSpec); return row ? `${accounts.find(a => a.id===row.account_id)?.name || "货源"} / ${row.remote_goods_name || row.remote_goods_id} / ${row.remote_sku_name || row.remote_sku || "单规格"}` : "无" })()}</div>}
            {remoteSku!==null && mappings.filter(m => m.remote_goods_id===detail.id && m.remote_sku===remoteSku).map(m => <div className="text-sm text-muted-foreground" key={m.id}>上游规格已绑定 {m.local_state === "PRODUCT_DELETED" ? `${m.product_name || "原商品"}（已删除，可解除或移动关联）` : <a className="text-primary underline" href={`/admin/products/${m.product_id}/edit`}>{m.product_name || "查看商品"}</a>}{m.spec_name ? ` / ${m.spec_name}` : ""}</div>)}
            <p className="text-sm text-muted-foreground">绑定会更新供应关系、成本和库存；名称、售价及分类保持不变。商品资料可单独同步。</p>
            {targetProduct && targetProduct.stock_mode!=="SUPPLIER" && <p className="text-sm text-muted-foreground">整个商品将改为供应商库存；其他未绑定规格无库存。本地库存或未完成订单需先处理。</p>}
            <Button type="button" disabled={busy || !active.enabled || !targetProduct || remoteSku===null || Boolean(targetProduct.spec_enabled && !targetSpec)} onClick={() => void bindProduct()}>确认绑定</Button>
          </div>}
          {resultProduct && <a className="mt-3 inline-block text-sm text-primary underline" href={`/admin/products/${resultProduct}/edit`}>进入商品编辑</a>}
        </div>}
      </section>
      <section>
        <div className="mb-4 flex items-center justify-between"><h2 className="text-lg font-semibold">已关联商品</h2><Button type="button" variant="ghost" size="sm" disabled={loadingMappings} onClick={() => void loadMappings(active.id)}><RefreshCw />刷新</Button></div>
        <section className="admin-table-region min-w-0"><div className="admin-table-scroll min-w-0 overflow-x-auto" role="region" aria-label="货源商品同步状态" tabIndex={0}><table className="w-full min-w-[760px] text-left text-sm"><thead><tr className="border-b border-border-subtle"><th scope="col" className="p-3">上游商品 / 规格</th><th scope="col" className="p-3">MyShop 商品 / 规格</th><th scope="col" className="p-3">成本</th><th scope="col" className="p-3">库存</th><th scope="col" className="p-3">状态</th><th scope="col" className="p-3">操作</th></tr></thead><tbody>{mappings.map(row => <tr className="border-b border-border-subtle" key={row.id}><td className="p-3">{row.remote_goods_name || `上游商品 ${row.remote_goods_id}`}<div className="text-xs text-muted-foreground">{row.remote_sku_name || row.remote_sku || "单规格"} · #{row.remote_goods_id}{row.remote_sku ? ` / ${row.remote_sku}` : ""}</div></td><td className="p-3">{row.local_state === "PRODUCT_DELETED" ? row.product_name || "已删除商品" : <a className="text-primary underline" href={`/admin/products/${row.product_id}/edit`}>{row.product_name || "查看商品"}</a>}{row.spec_name && <div className="text-xs text-muted-foreground">{row.spec_name}</div>}</td><td className="p-3">¥{row.cost}</td><td className="p-3">{row.stock}</td><td className="p-3">{row.goods_status === 1 && row.sku_status === 1 ? "上游可售" : "上游停售"}{syncError(row) && <div className="text-destructive">{syncError(row)}</div>}</td><td className="p-3"><div className="flex flex-wrap gap-2"><Button type="button" variant="outline" size="sm" disabled={busy || Boolean(row.local_state)} onClick={() => setReplacement(row)}>更换货源</Button><Button type="button" variant="outline" size="sm" disabled={busy} onClick={() => void unbind(row)}>解除关联</Button><Button type="button" variant="outline" size="sm" disabled={busy || !active.enabled || Boolean(row.local_state)} onClick={() => void sync(row.remote_goods_id)}>同步库存</Button><Button type="button" variant="outline" size="sm" disabled={busy || !active.enabled || Boolean(row.local_state)} onClick={() => void syncPresentation(row)}>同步商品资料</Button></div></td></tr>)}{!mappings.length && <tr><td colSpan={6} className="p-8 text-center text-muted-foreground">暂无关联商品</td></tr>}</tbody></table></div></section>
      </section>
    </>}
    <SupplierPicker open={replacement !== null} onOpenChange={open => { if (!open) setReplacement(null) }} onSelect={selection => { if (replacement) void replaceSource(replacement,selection) }} />
    <Dialog open={accountDialog} onOpenChange={open => { if (!open && !busy) closeAccount() }}><DialogContent><DialogHeader><DialogTitle>{editing ? "编辑货源" : "添加货源"}</DialogTitle><DialogDescription>{editing ? "留空密钥以保留现有密钥。" : "密钥仅加密保存在服务端。"}</DialogDescription></DialogHeader><form onSubmit={saveAccount} aria-label={editing ? "编辑货源" : "添加货源"} className="space-y-4">
      <label className={fieldClass}>名称<Input name="name" required maxLength={120} value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} /></label>
      <label className={fieldClass}>供货系统<select name="provider_type" className={selectClass} value={form.provider_type} onChange={e => setForm({ ...form, provider_type: e.target.value })}><option value="KAYIXIN">卡易信 API 3.0</option></select></label>
      <label className={fieldClass}>站点地址<Input name="base_url" type="url" required value={form.base_url} onChange={e => setForm({ ...form, base_url: e.target.value })} /></label>
      <label className={fieldClass}>客户编号<Input name="app_id" required maxLength={120} value={form.app_id} onChange={e => setForm({ ...form, app_id: e.target.value })} /></label>
      <label className={fieldClass}>API 密钥<Input name="secret" type="password" autoComplete="new-password" required={!editing} value={form.secret || ""} onChange={e => setForm({ ...form, secret: e.target.value })} placeholder={editing ? "留空保持不变" : ""} /></label>
      <DialogFooter><Button type="button" variant="outline" onClick={closeAccount} disabled={busy}>取消</Button><Button type="submit" disabled={busy}>保存</Button></DialogFooter>
    </form></DialogContent></Dialog>
  </AdminPage>
}
