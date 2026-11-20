"use client"

import { useEffect, useRef, useState, type FormEvent } from "react"
import { toast } from "sonner"
import { Button } from "@/components/ui/button"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { adminSupplierApi, type SupplierAccount, type SupplierCdkey, type SupplierProduct, type SupplierSensitiveCapabilities, type SupplierSensitiveOperation } from "@/services/api"

type Kind = "transfers" | "cdkeys"
const label = "flex min-w-0 flex-col gap-1.5 text-sm font-medium"
const status: Record<string, string> = { INTENT: "未提交", SUBMITTING: "提交中", UNKNOWN: "结果未知", SUCCEEDED: "成功", REJECTED: "未提交或已拒绝" }
const initialTransfer = { to_user_no: "", money: "", sale_pwd: "" }
const initialCdkey = { card_count: "1", count: "1", card_day: "-1", card_prefix: "", card_length: "10", short_status: "0", short_length: "10", card_face_value: "0", safe_price: "", card_remark: "" }

export function SupplierSensitivePanel({ account, product, superAdmin }: { account: SupplierAccount; product: SupplierProduct | null; superAdmin: boolean }) {
  const [cap, setCap] = useState<SupplierSensitiveCapabilities | null>(null)
  const [transfers, setTransfers] = useState<SupplierSensitiveOperation[]>([])
  const [cdkeys, setCdkeys] = useState<SupplierSensitiveOperation[]>([])
  const [kind, setKind] = useState<Kind | null>(null)
  const [transfer, setTransfer] = useState(initialTransfer)
  const [cdkey, setCdkey] = useState(initialCdkey)
  const [confirming, setConfirming] = useState(false)
  const [busy, setBusy] = useState(false)
  const busyRef = useRef(false)
  const requestId = useRef("")
  const [result, setResult] = useState<SupplierCdkey[] | null>(null)
  const [resultOpen, setResultOpen] = useState(false)

  async function refresh() {
    const [c, keys, txs] = await Promise.all([
      adminSupplierApi.capabilities(account.id), adminSupplierApi.operations(account.id, "cdkeys"),
      superAdmin ? adminSupplierApi.operations(account.id, "transfers") : Promise.resolve([]),
    ])
    setCap(c); setCdkeys(keys); setTransfers(txs)
  }
  useEffect(() => { let active = true
    Promise.all([adminSupplierApi.capabilities(account.id), adminSupplierApi.operations(account.id, "cdkeys"),
      superAdmin ? adminSupplierApi.operations(account.id, "transfers") : Promise.resolve([])])
      .then(([c, keys, txs]) => { if (active) { setCap(c); setCdkeys(keys); setTransfers(txs) } })
      .catch(error => { if (active) toast.error(error instanceof Error ? error.message : "读取操作记录失败") })
    return () => { active = false }
  }, [account.id, superAdmin])

  function open(next: Kind) {
    requestId.current = crypto.randomUUID(); setTransfer(initialTransfer)
    setCdkey({ ...initialCdkey, safe_price: product?.skus[0]?.cost || "" })
    setConfirming(false); setKind(next)
  }
  function close() { if (busyRef.current) return; setKind(null); setConfirming(false); setTransfer(initialTransfer) }
  function prepare(event: FormEvent<HTMLFormElement>) { event.preventDefault(); setConfirming(true) }
  async function submit() {
    if (!kind || busyRef.current || !requestId.current) return
    busyRef.current = true; setBusy(true)
    const payload = kind === "transfers" ? { ...transfer } : {
      ...cdkey, goods_id: product?.id, card_count: Number(cdkey.card_count), count: Number(cdkey.count),
      card_day: Number(cdkey.card_day), card_length: Number(cdkey.card_length), short_status: Number(cdkey.short_status), short_length: Number(cdkey.short_length),
    }
    try {
      const op = await adminSupplierApi.sensitiveCreate(account.id, kind, { ...payload, idempotency_key: requestId.current, confirmed: true })
      setTransfer(initialTransfer); setKind(null); setConfirming(false)
      await refresh()
      if (op.status === "SUCCEEDED") toast.success("操作已完成")
      else if (op.status === "UNKNOWN" || op.status === "SUBMITTING") toast.warning("结果尚未确认，请查看操作记录并人工核实")
      else toast.warning(`操作状态：${status[op.status]}${op.error_code ? `（${op.error_code}）` : ""}`)
    } catch (error) {
      toast.error(error instanceof Error ? `${error.message}；请刷新记录核实，再决定是否重试` : "请求结果未知，请刷新记录核实")
    } finally { busyRef.current = false; setBusy(false) }
  }
  async function action(op: SupplierSensitiveOperation, actionKind: "review" | "cancel") {
    if (busyRef.current) return
    const operationKind = op.kind === "TRANSFER" ? "transfers" : "cdkeys"
    if (actionKind === "review" && !window.confirm("请先联系供货站核实。此记录不会证明成功或失败；确认已人工处理并接受再次操作的风险？")) return
    if (actionKind === "cancel" && !window.confirm("取消尚未提交的操作意图？")) return
    busyRef.current = true; setBusy(true)
    try {
      if (actionKind === "review") await adminSupplierApi.sensitiveReview(account.id, operationKind, op.id, "MANUAL_HANDLING")
      else await adminSupplierApi.sensitiveCancel(account.id, operationKind, op.id)
      await refresh(); toast.success("已记录")
    } catch (error) { toast.error(error instanceof Error ? error.message : "操作失败") }
    finally { busyRef.current = false; setBusy(false) }
  }
  async function showResult(op: SupplierSensitiveOperation) {
    if (busyRef.current) return
    busyRef.current = true; setBusy(true)
    try { setResult(await adminSupplierApi.cdkeyResult(account.id, op.id)); setResultOpen(true) }
    catch (error) { toast.error(error instanceof Error ? error.message : "读取失败") }
    finally { busyRef.current = false; setBusy(false) }
  }
  const resultText = result?.map(item => item.url ? `${item.pwd}\t${item.url}` : item.pwd).join("\n") || ""
  function exportResult() {
    const url = URL.createObjectURL(new Blob([resultText], { type: "text/plain;charset=utf-8" }))
    const a = document.createElement("a"); a.href = url; a.download = "supplier-cdkeys.txt"; a.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  }

  return <section className="rounded-card border border-border-subtle bg-surface p-4 sm:p-5">
    <div className="flex flex-wrap items-center justify-between gap-3"><h2 className="text-lg font-semibold">账户与 CDKEY 操作</h2>
      <div className="flex flex-wrap gap-2">
        {superAdmin && <Button type="button" variant="outline" disabled={!account.enabled || !cap?.transfer_enabled || busy} onClick={() => open("transfers")}>账户转账</Button>}
        <Button type="button" variant="outline" disabled={!account.enabled || !cap?.cdkey_enabled || !product || product.sku_type !== 0 || busy} onClick={() => open("cdkeys")}>生成所选商品 CDKEY</Button>
        <Button type="button" variant="ghost" disabled={busy} onClick={() => void refresh().catch(error => toast.error(error instanceof Error ? error.message : "读取失败"))}>刷新记录</Button>
      </div>
    </div>
    <p className="mt-2 text-sm text-muted-foreground">{superAdmin && <>转账{cap?.transfer_enabled ? "已启用" : "未启用"} · </>}CDKEY {cap?.cdkey_enabled ? "已启用" : "未启用"} · 生成仅支持已选单规格商品</p>
    <div className="mt-4 space-y-2">{[...transfers, ...cdkeys].sort((a,b) => b.created_at.localeCompare(a.created_at)).map(op => <div key={op.id} className="flex flex-wrap items-center justify-between gap-2 rounded-control border border-border-subtle p-3 text-sm">
      <div className="min-w-0 break-words"><strong>{op.kind === "TRANSFER" ? "转账" : "CDKEY"} · {status[op.status]}</strong><div className="text-muted-foreground">{op.account_name} / {op.app_id} · {op.created_at} · 操作人 {op.operator_id}</div>
        <div>{op.kind === "TRANSFER" ? `收款 ${op.parameters.to_user_no} · ¥${op.parameters.money} · ${op.outer_number}` : `商品 ${op.parameters.goods_id} · ${op.parameters.card_count} 张 · 每张兑换 ${op.parameters.count}`}</div>
        {op.error_code && <div className="text-muted-foreground">状态代码 {op.error_code}{op.review_outcome === "MANUAL_HANDLING" ? " · 已人工核实并记录" : ""}</div>}
      </div><div className="flex flex-wrap gap-2">
        {op.kind === "CDKEY" && op.status === "SUCCEEDED" && <Button type="button" variant="outline" size="sm" disabled={busy} onClick={() => void showResult(op)}>查看兑换码</Button>}
        {op.status === "INTENT" && <Button type="button" variant="outline" size="sm" disabled={busy} onClick={() => void action(op,"cancel")}>取消未提交意图</Button>}
        {op.status === "UNKNOWN" && op.review_outcome !== "MANUAL_HANDLING" && <Button type="button" variant="outline" size="sm" disabled={busy} onClick={() => void action(op,"review")}>记录人工核实</Button>}
      </div>
    </div>)}{!transfers.length && !cdkeys.length && <p className="text-sm text-muted-foreground">暂无操作记录</p>}</div>

    <Dialog open={kind !== null} onOpenChange={open => { if (!open) close() }}><DialogContent className="max-h-[90vh] overflow-y-auto"><DialogHeader><DialogTitle>{kind === "transfers" ? "账户转账" : "生成 CDKEY"}</DialogTitle><DialogDescription>{account.name} · 客户编号 {account.app_id}</DialogDescription></DialogHeader>
      {!confirming ? <form className="space-y-3" onSubmit={prepare}>
        {kind === "transfers" ? <><label className={label}>收款客户编号<Input required inputMode="numeric" pattern="[1-9][0-9]{0,17}" value={transfer.to_user_no} onChange={e => setTransfer({ ...transfer, to_user_no: e.target.value })} /></label>
          <label className={label}>转账金额（单次上限 ¥{cap?.transfer_max_amount}）<Input required type="number" min="0.00000001" step="0.00000001" max={String(cap?.transfer_max_amount || "100")} value={transfer.money} onChange={e => setTransfer({ ...transfer, money: e.target.value })} /></label>
          <label className={label}>交易密码（未启用可留空）<Input type="password" autoComplete="off" maxLength={128} value={transfer.sale_pwd} onChange={e => setTransfer({ ...transfer, sale_pwd: e.target.value })} /></label></> : <>
          <p className="text-sm">{product?.name} #{product?.id} · 当前成本单价 ¥{product?.skus[0]?.cost} · 发货倍数 {product?.multiple}</p>
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <label className={label}>生成张数<Input required type="number" min="1" max={cap?.cdkey_max_count || 20} value={cdkey.card_count} onChange={e => setCdkey({ ...cdkey, card_count: e.target.value })} /></label>
            <label className={label}>每张兑换数量<Input required type="number" min="1" max="1000" value={cdkey.count} onChange={e => setCdkey({ ...cdkey, count: e.target.value })} /></label>
            <label className={label}>有效天数（-1 为永久）<Input required type="number" min="-1" value={cdkey.card_day} onChange={e => setCdkey({ ...cdkey, card_day: e.target.value })} /></label>
            <label className={label}>成本安全单价<Input required type="number" min="0.00000001" step="0.00000001" value={cdkey.safe_price} onChange={e => setCdkey({ ...cdkey, safe_price: e.target.value })} /></label>
            <label className={label}>卡密前缀<Input maxLength={20} value={cdkey.card_prefix} onChange={e => setCdkey({ ...cdkey, card_prefix: e.target.value })} /></label>
            <label className={label}>卡密长度（不含前缀）<Input required type="number" min="5" max="32" value={cdkey.card_length} onChange={e => setCdkey({ ...cdkey, card_length: e.target.value })} /></label>
            <label className={label}>短链接<select className="h-11 rounded-control border border-input bg-surface px-3" value={cdkey.short_status} onChange={e => setCdkey({ ...cdkey, short_status: e.target.value })}><option value="0">不生成</option><option value="1">生成</option></select></label>
            <label className={label}>短链接长度<Input required type="number" min="5" max="32" value={cdkey.short_length} onChange={e => setCdkey({ ...cdkey, short_length: e.target.value })} /></label>
            <label className={label}>展示面额<Input required type="number" min="0" step="0.00000001" value={cdkey.card_face_value} onChange={e => setCdkey({ ...cdkey, card_face_value: e.target.value })} /></label>
            <label className={label}>备注<Input maxLength={255} value={cdkey.card_remark} onChange={e => setCdkey({ ...cdkey, card_remark: e.target.value })} /></label>
          </div>
        </>}
        <DialogFooter><Button type="button" variant="outline" onClick={close}>取消</Button><Button type="submit">核对参数</Button></DialogFooter>
      </form> : <div className="space-y-4"><div className="space-y-2 text-sm">
        {kind === "transfers" ? <p>从 {account.name}（{account.app_id}）向客户 {transfer.to_user_no} 转账 ¥{transfer.money}。</p> : <>
          <p>商品 {product?.name}（{product?.id}）：生成 {cdkey.card_count} 张，每张兑换 {cdkey.count} 份，有效期 {cdkey.card_day === "-1" ? "永久" : `${cdkey.card_day} 天`}。</p>
          <p>前缀 {cdkey.card_prefix || "无"}，卡密长度 {cdkey.card_length}，短链接 {cdkey.short_status === "1" ? `启用（长度 ${cdkey.short_length}）` : "关闭"}，展示面额 ¥{cdkey.card_face_value}，备注 {cdkey.card_remark || "无"}。</p>
          <p>成本风险上限：安全单价 ¥{cdkey.safe_price} × 每张兑换 {cdkey.count} × 发货倍数 {product?.multiple} × {cdkey.card_count} 张；服务端上限 ¥{cap?.cdkey_max_exposure}。</p>
        </>}
        <p>提交可能产生资金或重复生成风险；结果不明时须联系供货站核实。</p></div>
        <DialogFooter><Button type="button" variant="outline" disabled={busy} onClick={() => setConfirming(false)}>返回</Button><Button type="button" disabled={busy} onClick={() => void submit()}>{busy ? "提交中" : "确认提交"}</Button></DialogFooter>
      </div>}
    </DialogContent></Dialog>
    <Dialog open={resultOpen} onOpenChange={open => { setResultOpen(open); if (!open) setResult(null) }}><DialogContent><DialogHeader><DialogTitle>CDKEY 结果</DialogTitle><DialogDescription>兑换码和短链接仅供授权管理员查看。</DialogDescription></DialogHeader>
      <textarea aria-label="CDKEY 结果" readOnly className="min-h-40 w-full rounded-control border border-input bg-surface p-3 text-sm" value={resultText} />
      <DialogFooter><Button type="button" variant="outline" onClick={() => void navigator.clipboard.writeText(resultText).then(() => toast.success("已复制")).catch(() => toast.error("复制失败"))}>复制</Button><Button type="button" onClick={exportResult}>导出</Button></DialogFooter>
    </DialogContent></Dialog>
  </section>
}
