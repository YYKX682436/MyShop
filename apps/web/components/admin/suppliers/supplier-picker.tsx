"use client"

import { useEffect, useRef, useState } from "react"
import { toast } from "sonner"
import { Button } from "@/components/ui/button"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { adminSupplierApi, type SupplierAccount, type SupplierMapping, type SupplierProduct, type SupplierSku, type SupplierSummary } from "@/services/api"

export type SupplierSelection = { account: SupplierAccount; product: SupplierProduct; sku: SupplierSku; source: SupplierMapping | null }

/** Shared by the product editor and supplier management; it only reads until selection. */
export function SupplierPicker({ open, onOpenChange, onSelect }: {
  open: boolean
  onOpenChange: (open: boolean) => void
  onSelect: (selection: SupplierSelection) => void
}) {
  const [accounts, setAccounts] = useState<SupplierAccount[]>([])
  const [accountId, setAccountId] = useState("")
  const [keyword, setKeyword] = useState("")
  const [items, setItems] = useState<SupplierSummary[]>([])
  const [page, setPage] = useState(0)
  const [hasMore, setHasMore] = useState(false)
  const [detail, setDetail] = useState<SupplierProduct | null>(null)
  const [skuId, setSkuId] = useState<string | null>(null)
  const [mappings, setMappings] = useState<SupplierMapping[]>([])
  const [busy, setBusy] = useState(false)
  const requestId = useRef(0)

  useEffect(() => {
    if (!open) return
    const requests = requestId
    const sequence = ++requests.current
    setBusy(false)
    void adminSupplierApi.accounts().then((rows) => {
      if (sequence !== requestId.current) return
      setAccounts(rows.filter(row => row.enabled))
      setAccountId(rows.find(row => row.enabled)?.id || "")
      setKeyword(""); setItems([]); setPage(0); setHasMore(false); setDetail(null); setSkuId(null); setMappings([])
    }).catch((error: unknown) => { if (sequence === requestId.current) toast.error(error instanceof Error ? error.message : "读取货源失败") })
    return () => { requests.current++ }
  }, [open])

  async function search(nextPage = 1) {
    if (!accountId || busy) return
    const sequence = ++requestId.current
    setBusy(true); setDetail(null); setSkuId(null)
    try {
      const result = await adminSupplierApi.goods(accountId, keyword.trim(), "", nextPage)
      if (sequence === requestId.current) {
        const seen = new Set(nextPage === 1 ? [] : items.map(item => item.id))
        const fresh = result.items.filter(item => { if (seen.has(item.id)) return false; seen.add(item.id); return true })
        setItems(nextPage === 1 ? fresh : [...items, ...fresh]); setPage(nextPage); setHasMore(fresh.length > 0 && nextPage < 1000)
      }
    } catch (error) { if (sequence === requestId.current) toast.error(error instanceof Error ? error.message : "搜索失败") }
    finally { if (sequence === requestId.current) setBusy(false) }
  }
  async function choose(id: string) {
    if (!accountId || busy) return
    const sequence = ++requestId.current
    setBusy(true)
    try {
      const [product, rows] = await Promise.all([adminSupplierApi.detail(accountId, id), adminSupplierApi.mappings(accountId)])
      if (sequence !== requestId.current) return
      setDetail(product); setMappings(rows); setSkuId(product.sku_type === 0 ? "" : null)
    } catch (error) { if (sequence === requestId.current) toast.error(error instanceof Error ? error.message : "读取商品失败") }
    finally { if (sequence === requestId.current) setBusy(false) }
  }
  const sku = detail?.skus.find(row => row.id === skuId)
  const source = detail && sku ? mappings.find(row => row.remote_goods_id === detail.id && row.remote_sku === sku.id) || null : null
  const account = accounts.find(row => row.id === accountId)

  return <Dialog open={open} onOpenChange={onOpenChange}><DialogContent className="max-h-[90dvh] overflow-y-auto sm:max-w-xl">
    <DialogHeader><DialogTitle>选择货源</DialogTitle><DialogDescription>选择供货站、上游商品和规格。</DialogDescription></DialogHeader>
    <div className="space-y-4">
      <label className="flex flex-col gap-1.5 text-sm font-medium">供货站<select className="h-10 rounded-control border border-input bg-surface px-3 text-sm" value={accountId} onChange={event => { requestId.current++; setAccountId(event.target.value); setItems([]); setPage(0); setHasMore(false); setDetail(null); setBusy(false) }}><option value="">选择供货站</option>{accounts.map(row => <option key={row.id} value={row.id}>{row.name}</option>)}</select></label>
      <div className="flex items-end gap-2"><label className="flex min-w-0 flex-1 flex-col gap-1.5 text-sm font-medium">搜索上游商品<Input type="search" value={keyword} onChange={event => setKeyword(event.target.value)} onKeyDown={event => { if (event.key === "Enter" && !event.nativeEvent.isComposing) { event.preventDefault(); void search() } }} /></label><Button type="button" variant="outline" disabled={!accountId || busy} onClick={() => void search()}>搜索</Button></div>
      <div className="max-h-48 space-y-1 overflow-y-auto" aria-label="上游商品搜索结果">{items.map(item => <Button className="h-auto w-full justify-start whitespace-normal text-left" type="button" variant={detail?.id === item.id ? "secondary" : "ghost"} key={item.id} disabled={busy} onClick={() => void choose(item.id)}>{item.name}<span className="ml-2 text-xs text-muted-foreground">#{item.id}</span></Button>)}{hasMore && <Button type="button" variant="ghost" size="sm" disabled={busy} onClick={() => void search(page + 1)}>加载更多</Button>}</div>
      {detail && <div className="space-y-2 rounded-control border border-border-subtle p-3"><div className="font-medium">{detail.name}</div><label className="flex flex-col gap-1.5 text-sm font-medium">上游规格<select className="h-10 rounded-control border border-input bg-surface px-3 text-sm" value={skuId ?? "__select__"} onChange={event => setSkuId(event.target.value === "__select__" ? null : event.target.value)}>{detail.sku_type !== 0 && <option value="__select__">选择规格</option>}{detail.skus.map(row => <option key={row.id || "single"} value={row.id}>{[row.group,row.name].filter(Boolean).join(" / ") || "单规格"} · ¥{row.cost} · 库存 {row.stock}</option>)}</select></label>{source && <p className="text-sm text-warning">已关联：{source.product_name || "本地商品"}{source.spec_name ? ` / ${source.spec_name}` : ""}{source.local_state === "PRODUCT_DELETED" ? "（已删除）" : ""}</p>}</div>}
    </div>
    <DialogFooter><Button type="button" variant="outline" onClick={() => onOpenChange(false)}>取消</Button><Button type="button" disabled={!account || !detail || !sku || busy} onClick={() => { if (account && detail && sku) { onSelect({ account, product: detail, sku, source }); onOpenChange(false) } }}>使用此货源</Button></DialogFooter>
  </DialogContent></Dialog>
}
