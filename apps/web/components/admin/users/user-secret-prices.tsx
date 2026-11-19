"use client"

import { useCallback, useEffect, useId, useRef, useState } from "react"
import { Plus } from "lucide-react"
import { toast } from "sonner"
import { ConfirmAlert } from "@/components/admin/shared"
import { Button } from "@/components/ui/button"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { useLocale } from "@/lib/context"
import { formatYuanValue, parsePositiveMoneyToCents } from "@/lib/money"
import { adminProductApi, adminUserApi, requestOrThrow } from "@/services/api"
import type { AdminProductDetail, UserSecretPrice } from "@/types"

export function UserSecretPrices({ userId, onDirtyChange }: { userId: string; onDirtyChange: (dirty: boolean) => void }) {
  const { t } = useLocale()
  const [entries, setEntries] = useState<UserSecretPrice[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)
  const [editing, setEditing] = useState<UserSecretPrice | null>(null)
  const [open, setOpen] = useState(false)
  const [removing, setRemoving] = useState<UserSecretPrice | null>(null)
  const [busy, setBusy] = useState(false)
  const [keyword, setKeyword] = useState("")
  const [search, setSearch] = useState("")
  const [page, setPage] = useState(1)
  const [total, setTotal] = useState(0)
  const [products, setProducts] = useState<AdminProductDetail[]>([])
  const [selected, setSelected] = useState<AdminProductDetail | null>(null)
  const [specId, setSpecId] = useState("")
  const [amount, setAmount] = useState("")
  const [amountError, setAmountError] = useState(false)
  const amountErrorId = useId()
  const amountRef = useRef<HTMLInputElement>(null)

  const load = useCallback(async () => {
    setLoading(true); setError(false)
    try { setEntries(await requestOrThrow(() => adminUserApi.getSecretPrices(userId))) }
    catch { setError(true) }
    finally { setLoading(false) }
  }, [userId])
  useEffect(() => { void load() }, [load])
  useEffect(() => {
    if (!open || editing || selected) return
    let active = true
    void requestOrThrow(() => adminProductApi.getList({ keyword: search, page, page_size: 10 }))
      .then(data => { if (active) { setProducts(data.list); setTotal(data.pagination.total) } })
      .catch(() => { if (active) { setProducts([]); setTotal(0); toast.error(t("admin.loadError")) } })
    return () => { active = false }
  }, [open, editing, selected, search, page, t])
  useEffect(() => { onDirtyChange(open && Boolean(amount || selected)); return () => onDirtyChange(false) }, [open, amount, selected, onDirtyChange])

  const start = (entry: UserSecretPrice | null) => {
    setEditing(entry); setSelected(null); setSpecId(""); setAmount(entry ? String(entry.price) : "")
    setAmountError(false); setKeyword(""); setSearch(""); setPage(1); setProducts([]); setTotal(0); setOpen(true)
  }
  const choose = async (product: AdminProductDetail) => {
    try { setSelected(await requestOrThrow(() => adminProductApi.getDetail(product.id))); setSpecId("") }
    catch (e) { toast.error(e instanceof Error ? e.message : t("admin.loadError")) }
  }
  const spec = selected?.specs.find(item => item.id === specId)
  const retail = editing?.retail_price ?? (selected?.spec_enabled ? spec?.retail_price : selected?.retail_price)
  const cost = editing?.cost_price ?? (selected?.spec_enabled ? spec?.cost_price : selected?.cost_price)
  const cents = parsePositiveMoneyToCents(amount)
  const belowCost = cents !== null && cost != null && cents < Math.round(cost * 100)
  const save = async () => {
    if (cents === null || retail == null || cents > Math.round(retail * 100)) { setAmountError(true); amountRef.current?.focus(); return }
    if (!editing && (!selected || (selected.spec_enabled && !specId))) return
    setBusy(true)
    try {
      if (editing) await requestOrThrow(() => adminUserApi.updateSecretPrice(userId, editing.id, amount))
      else if (selected) await requestOrThrow(() => adminUserApi.createSecretPrice(userId, { product_id: selected.id, spec_id: selected.spec_enabled ? specId : null, price: amount }))
      setOpen(false); setAmount(""); setSelected(null); await load(); toast.success(t("admin.productSaved"))
    } catch (e) { toast.error(e instanceof Error ? e.message : t("admin.saveFailed")) }
    finally { setBusy(false) }
  }
  const remove = async () => {
    if (!removing) return
    setBusy(true)
    try { await requestOrThrow(() => adminUserApi.deleteSecretPrice(userId, removing.id)); setRemoving(null); await load(); toast.success(t("admin.productDeleted")) }
    catch (e) { toast.error(e instanceof Error ? e.message : t("admin.saveFailed")) }
    finally { setBusy(false) }
  }
  return <section className="space-y-4">
    <div className="flex justify-end"><Button type="button" onClick={() => start(null)}><Plus className="h-4 w-4" aria-hidden="true" />{t("admin.addSecretPrice")}</Button></div>
    <section className="admin-table-region min-w-0"><div className="admin-table-scroll min-w-0 overflow-x-auto" tabIndex={0} role="region" aria-label={t("admin.secretPrices")}><table className="w-full min-w-[620px] text-sm">
      <thead><tr className="border-b border-border bg-muted/30">{[t("admin.product"), t("admin.specification"), t("admin.secretPrice"), t("admin.levelPrice"), t("admin.actions")].map(label => <th key={label} scope="col" className="px-4 py-3 text-left font-medium text-muted-foreground">{label}</th>)}</tr></thead>
      <tbody>{loading ? <tr><td colSpan={5} className="p-6 text-center">{t("common.loading")}</td></tr> : error ? <tr><td colSpan={5} className="p-6 text-center"><Button type="button" variant="outline" onClick={() => void load()}>{t("common.retry")}</Button></td></tr> : entries.length === 0 ? <tr><td colSpan={5} className="p-6 text-center text-muted-foreground">{t("admin.noSecretPrices")}</td></tr> : entries.map(entry => <tr key={entry.id} className="border-b border-border/60 last:border-0">
        <td className="px-4 py-3">{entry.product_title}</td><td className="px-4 py-3">{entry.spec_name || "—"}</td>
        <td className="px-4 py-3">{formatYuanValue(entry.price)}{entry.below_cost && <span className="ml-2 text-xs text-destructive">{t("admin.secretBelowCost")}</span>}</td>
        <td className="px-4 py-3">{formatYuanValue(entry.level_price)}</td><td className="whitespace-nowrap px-4 py-3"><Button type="button" size="sm" variant="ghost" onClick={() => start(entry)}>{t("admin.edit")}</Button><Button type="button" size="sm" variant="ghost" onClick={() => setRemoving(entry)}>{t("admin.delete")}</Button></td>
      </tr>)}</tbody>
    </table></div></section>
    <Dialog open={open} onOpenChange={value => { if (!busy) { setOpen(value); if (!value) { setSelected(null); setAmount("") } } }}>
      <DialogContent className="max-w-lg"><DialogHeader><DialogTitle>{editing ? t("admin.editSecretPrice") : t("admin.addSecretPrice")}</DialogTitle><DialogDescription>{editing ? editing.product_title : t("admin.secretChooseProduct")}</DialogDescription></DialogHeader>
        {!editing && !selected && <div className="space-y-3">
          <form onSubmit={event => { event.preventDefault(); setSearch(keyword.trim()); setPage(1) }} className="flex gap-2"><Input aria-label={t("admin.searchProducts")} value={keyword} onChange={event => setKeyword(event.target.value)} placeholder={t("admin.searchProduct")} /><Button type="submit" variant="outline">{t("admin.searchProducts")}</Button></form>
          <div className="max-h-64 overflow-y-auto rounded-control border border-border">{products.length === 0 ? <p className="p-4 text-sm text-muted-foreground">{t("admin.noMatchingProducts")}</p> : products.map(product => <button type="button" key={product.id} onClick={() => void choose(product)} className="block w-full border-b border-border px-3 py-2 text-left text-sm last:border-0 hover:bg-muted">{product.title} <span className="text-muted-foreground">#{product.product_no}</span></button>)}</div>
          <div className="flex items-center justify-between text-sm"><Button type="button" size="sm" variant="ghost" disabled={page <= 1} onClick={() => setPage(page - 1)}>{t("admin.previousPage")}</Button><span>{page} / {Math.max(1, Math.ceil(total / 10))}</span><Button type="button" size="sm" variant="ghost" disabled={page * 10 >= total} onClick={() => setPage(page + 1)}>{t("admin.nextPage")}</Button></div>
        </div>}
        {selected && !editing && <div className="space-y-3"><div className="flex items-center justify-between gap-2"><span className="font-medium">{selected.title}</span><Button type="button" variant="ghost" size="sm" onClick={() => setSelected(null)}>{t("admin.secretChooseProduct")}</Button></div>
          {selected.spec_enabled && <label className="flex flex-col gap-1.5 text-sm font-medium">{t("admin.chooseSpec")}<select className="h-11 rounded-control border border-input bg-surface px-3" value={specId} onChange={event => setSpecId(event.target.value)}><option value="">{t("admin.chooseSpec")}</option>{selected.specs.filter(item => item.is_deleted === 0).map(item => <option key={item.id} value={item.id}>{item.name}{!item.is_visible ? ` · ${t("admin.specHidden")}` : ""}</option>)}</select></label>}</div>}
        {(editing || selected) && <form onSubmit={event => { event.preventDefault(); void save() }} className="space-y-3">
          {editing?.spec_name && <p className="text-sm text-muted-foreground">{editing.spec_name}</p>}
          {retail != null && <p className="text-sm text-muted-foreground">{t("admin.retailPriceShort")} {formatYuanValue(retail)}{cost != null && ` · ${t("admin.costPriceShort")} ${formatYuanValue(cost)}`}</p>}
          <label className="flex flex-col gap-1.5 text-sm font-medium">{t("admin.secretPrice")}<Input ref={amountRef} type="text" inputMode="decimal" name="price" value={amount} onChange={event => { setAmount(event.target.value); setAmountError(false) }} aria-invalid={amountError} aria-describedby={amountError ? amountErrorId : undefined} placeholder="0.00" required /></label>
          {amountError && <p id={amountErrorId} role="alert" className="text-sm text-destructive">{t("admin.secretPriceInvalid")}</p>}
          {belowCost && <p className="text-sm text-destructive" role="status">{t("admin.secretBelowCost")}</p>}
          <DialogFooter><Button type="button" variant="outline" onClick={() => setOpen(false)} disabled={busy}>{t("admin.cancel")}</Button><Button type="submit" disabled={busy || (!editing && selected?.spec_enabled && !specId)}>{t("admin.save")}</Button></DialogFooter>
        </form>}
      </DialogContent>
    </Dialog>
    <ConfirmAlert open={removing !== null} onOpenChange={value => { if (!value && !busy) setRemoving(null) }} title={t("admin.deleteConfirm")} description={t("admin.secretPriceDelete")} confirmLabel={t("admin.delete")} cancelLabel={t("admin.cancel")} onConfirm={() => void remove()} loading={busy} destructive />
  </section>
}
