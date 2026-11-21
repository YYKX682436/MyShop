"use client"

import { useCallback, useEffect, useState } from "react"
import Link from "next/link"
import { toast } from "sonner"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { useRequireAuth } from "@/lib/hooks"
import { formatYuan } from "@/lib/utils"
import { subsiteApi, type SubsiteLevel, type SubsiteProduct, type SubsitePrice } from "@/services/api"

const key = (spec: string | null, level: string | null) => `${spec ?? "root"}:${level ?? "retail"}`
const supplyLabel = (value: number | null) => value == null ? "—" : formatYuan(value)

export default function SubsitePricingPage() {
  const user = useRequireAuth()
  const [levels, setLevels] = useState<SubsiteLevel[]>([])
  const [markups, setMarkups] = useState<Record<string, string>>({})
  const [products, setProducts] = useState<SubsiteProduct[]>([])
  const [page, setPage] = useState(1)
  const [total, setTotal] = useState(0)
  const [selected, setSelected] = useState<SubsiteProduct | null>(null)
  const [hidden, setHidden] = useState(false)
  const [prices, setPrices] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState(false)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState("")

  const load = useCallback(async () => {
    setLoading(true); setError("")
    try {
      const [levelData, markupData, productData] = await Promise.all([subsiteApi.levels(), subsiteApi.markups(), subsiteApi.products(page)])
      setLevels(levelData)
      setMarkups(Object.fromEntries(markupData.map(row => [row.user_level_id, String(row.markup_rate)])))
      setProducts(productData.list); setTotal(productData.pagination.total)
    } catch (e) { setError(e instanceof Error ? e.message : "读取失败") }
    finally { setLoading(false) }
  }, [page])
  useEffect(() => { if (user) void load() }, [user, load])

  async function edit(product: SubsiteProduct) {
    setBusy(true)
    try {
      const config = await subsiteApi.productPricing(product.id)
      setPrices(Object.fromEntries(config.prices.map(row => [key(row.spec_id, row.user_level_id), String(row.price)])))
      setHidden(config.hidden); setSelected(product)
    } catch (e) { toast.error(e instanceof Error ? e.message : "读取失败") }
    finally { setBusy(false) }
  }
  async function saveProduct(event: React.FormEvent) {
    event.preventDefault()
    if (!selected) return
    const rows: SubsitePrice[] = []
    const specs = selected.spec_enabled ? selected.specs.map(spec => spec.id) : [null]
    for (const spec of specs) for (const level of [null, ...levels.map(item => item.id)]) {
      const value = prices[key(spec, level)]?.trim()
      if (!value) continue
      if (!/^\d+(?:\.\d{1,2})?$/.test(value) || Number(value) <= 0) { toast.error("售价须大于零，最多两位小数"); return }
      rows.push({ spec_id: spec, user_level_id: level, price: Number(value) })
    }
    setBusy(true)
    try { await subsiteApi.updateProductPricing(selected.id, { hidden, prices: rows }); toast.success("商品定价已保存"); setSelected(null); await load() }
    catch (e) { toast.error(e instanceof Error ? e.message : "保存失败") }
    finally { setBusy(false) }
  }
  async function saveMarkups(event: React.FormEvent) {
    event.preventDefault()
    const rows = []
    for (const level of levels) {
      const value = markups[level.id]?.trim()
      if (!value) continue
      if (!/^\d+(?:\.\d{1,4})?$/.test(value)) { toast.error("加价率须为非负数，最多四位小数"); return }
      rows.push({ user_level_id: level.id, markup_rate: Number(value) })
    }
    setBusy(true)
    try { await subsiteApi.updateMarkups(rows); toast.success("等级加价已保存"); await load() }
    catch (e) { toast.error(e instanceof Error ? e.message : "保存失败") }
    finally { setBusy(false) }
  }
  if (!user) return null
  return <div className="mx-auto max-w-4xl space-y-6 py-6">
    <header><Link href="/my/subsite" className="text-sm text-muted-foreground">← 我的分站</Link><h1 className="mt-2 text-2xl font-semibold">商品定价</h1></header>
    {error && <p role="alert" className="text-destructive">{error} <Button type="button" variant="ghost" onClick={() => void load()}>重试</Button></p>}
    {loading ? <p>加载中…</p> : !error && <>
      <form onSubmit={saveMarkups} className="content-surface space-y-4 rounded-card p-5">
        <h2 className="text-lg font-semibold">等级加价率</h2><p className="text-sm text-muted-foreground">留空使用商品售价；设置后按拿货价加价，商品等级特价优先。</p>
        <div className="grid gap-3 sm:grid-cols-2">{levels.map(level => <label key={level.id} className="space-y-1 text-sm"><span>{level.name}（%）</span><Input inputMode="decimal" value={markups[level.id] ?? ""} onChange={e => setMarkups(value => ({ ...value, [level.id]: e.target.value }))} /></label>)}</div>
        <Button type="submit" disabled={busy}>保存加价率</Button>
      </form>
      <section className="content-surface rounded-card p-5"><h2 className="mb-3 text-lg font-semibold">商品</h2>
        <ul className="divide-y divide-border">{products.map(product => <li key={product.id} className="flex items-center justify-between gap-3 py-3"><div className="min-w-0 space-y-1"><p className="truncate">{product.title}</p>{product.spec_enabled ? product.specs.map(spec => <p key={spec.id} className={`text-xs ${spec.underpriced_levels.length ? "text-destructive" : "text-muted-foreground"}`}>{spec.name} · 拿货 {supplyLabel(spec.supply_price)}{spec.underpriced_levels.length > 0 && ` · 价格不可售（低于拿货价或不支持的零元订单）：${spec.underpriced_levels.join("、")}`}</p>) : <p className={`text-xs ${product.underpriced_levels.length ? "text-destructive" : "text-muted-foreground"}`}>拿货 {supplyLabel(product.supply_price)}{product.underpriced_levels.length > 0 && ` · 价格不可售（低于拿货价或不支持的零元订单）：${product.underpriced_levels.join("、")}`}</p>}</div><Button type="button" variant="outline" disabled={busy} onClick={() => void edit(product)}>设置</Button></li>)}</ul>
        {!products.length && <p className="text-sm text-muted-foreground">暂无上架商品</p>}
        <div className="mt-4 flex items-center justify-between text-sm"><Button type="button" variant="ghost" disabled={page <= 1 || busy} onClick={() => setPage(value => value - 1)}>上一页</Button><span>{page} / {Math.max(1, Math.ceil(total / 20))}</span><Button type="button" variant="ghost" disabled={page * 20 >= total || busy} onClick={() => setPage(value => value + 1)}>下一页</Button></div>
      </section>
      {selected && <form onSubmit={saveProduct} className="content-surface space-y-4 rounded-card p-5">
        <h2 className="text-lg font-semibold">{selected.title}</h2><label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={hidden} onChange={e => setHidden(e.target.checked)} />在分站隐藏此商品</label>
        <p className="text-sm text-muted-foreground">留空按等级加价规则或主站零售价计算；等级特价优先于等级加价率。</p>
        {(selected.spec_enabled ? selected.specs : [{ id: null, name: "商品", retail_price: selected.retail_price, supply_price: selected.supply_price, underpriced_levels: selected.underpriced_levels }]).map(spec => <fieldset key={spec.id ?? "root"} className="space-y-3 rounded-control border border-border p-4"><legend className="px-1 font-medium">{spec.name} · 主站 {formatYuan(spec.retail_price)} · 拿货 {supplyLabel(spec.supply_price)}</legend>
          {spec.underpriced_levels.length > 0 && <p role="status" className="text-sm text-destructive">价格不可售（低于拿货价或不支持的零元订单），请调整：{spec.underpriced_levels.join("、")}</p>}
          <div className="grid gap-3 sm:grid-cols-2">{[{ id: null, name: "分站零售价" }, ...levels].map(level => <label key={level.id ?? "retail"} className="space-y-1 text-sm"><span>{level.name}</span><Input inputMode="decimal" value={prices[key(spec.id, level.id)] ?? ""} onChange={e => setPrices(value => ({ ...value, [key(spec.id, level.id)]: e.target.value }))} /></label>)}</div>
        </fieldset>)}
        <div className="flex gap-2"><Button type="submit" disabled={busy}>保存商品</Button><Button type="button" variant="outline" onClick={() => setSelected(null)}>取消</Button></div>
      </form>}
    </>}
  </div>
}
