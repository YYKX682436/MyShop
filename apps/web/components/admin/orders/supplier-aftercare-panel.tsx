"use client"

import { useEffect, useId, useRef, useState } from "react"
import { toast } from "sonner"
import { Button } from "@/components/ui/button"
import { Textarea } from "@/components/ui/textarea"
import { adminOrderApi, requestOrThrow } from "@/services/api"
import type { AdminOrderItemDetail, AfterSaleRequest } from "@/types"

type Purchase = NonNullable<AdminOrderItemDetail["supplier_purchase"]>
type Confirmation = { kind: "refund" } | { kind: "recover" } | { kind: "void"; cardId: string; remoteId: string }
const cardStatus: Record<number, string> = { 0: "未使用 / 已发货", 1: "未使用 / 已处理", 2: "已作废 / 已退款", 3: "已使用", 4: "作废中" }
const operationStatus: Record<string, string> = { INTENT: "待提交", SUBMITTING: "提交中", UNKNOWN: "结果待核实", ACCEPTED: "上游处理中", COMPLETED: "上游处理结束", REJECTED: "未受理" }
const operationKind: Record<string, string> = { REFUND: "上游退款", CARD_VOID: "卡券作废", COMPLAINT: "售后申诉" }

export function SupplierAftercarePanel({ orderId, purchase, sale, onReload }: {
  orderId: string; purchase: Purchase; sale: AfterSaleRequest | null; onReload: () => Promise<void>
}) {
  const titleId = useId()
  const dialog = useRef<HTMLDialogElement>(null)
  const [confirmation, setConfirmation] = useState<Confirmation | null>(null)
  const [busy, setBusy] = useState(false)
  const [selected, setSelected] = useState<string[]>([])
  const [topic, setTopic] = useState("")
  const [content, setContent] = useState("")
  const operations = purchase.aftercare_operations ?? []
  const hasRefund = operations.some(op => op.kind === "REFUND")
  const refundPending = operations.some(op => op.kind === "REFUND" && ["INTENT", "SUBMITTING", "UNKNOWN", "ACCEPTED"].includes(op.status))
  const voidPending = operations.some(op => op.kind === "CARD_VOID" && ["INTENT", "SUBMITTING", "UNKNOWN", "ACCEPTED"].includes(op.status))
  const complaints = operations.filter(op => op.kind === "COMPLAINT")
  const firstComplaint = !complaints.some(op => op.status !== "REJECTED")
  const complaintClosed = complaints.some(op => op.upstream_status === 3 || op.upstream_status === 4)
  const complaintUnconfirmed = complaints.some(op => ["INTENT", "SUBMITTING", "UNKNOWN"].includes(op.status))
  const evidence = sale ? [...(sale.attachments ?? []), ...(sale.messages ?? []).flatMap(message => message.attachments ?? [])] : []

  useEffect(() => {
    if (confirmation && dialog.current && !dialog.current.open) dialog.current.showModal()
  }, [confirmation])

  const run = async (action: () => Promise<unknown>, success: string) => {
    if (busy) return
    setBusy(true)
    try { await action(); toast.success(success); await onReload() }
    catch (error) { toast.error(error instanceof Error ? error.message : "操作失败，请核实") }
    finally { setBusy(false) }
  }

  const submitComplaint = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!sale || busy) return
    setBusy(true)
    const storageKey = `myshop-supplier-complaint-${purchase.id}`
    let key = sessionStorage.getItem(storageKey)
    if (!key) { key = crypto.randomUUID(); sessionStorage.setItem(storageKey, key) }
    try {
      const response = await requestOrThrow(() => adminOrderApi.supplierComplaint(orderId, purchase.id, {
        idempotencyKey: key!, confirmed: true, afterSaleId: sale.id, topic, content, attachmentIds: selected,
      }))
      if (["ACCEPTED", "COMPLETED", "REJECTED"].includes(response.status)) sessionStorage.removeItem(storageKey)
      toast.success(response.status === "UNKNOWN" ? "申诉结果待人工核实" : "申诉已记录")
      await onReload()
    } catch (error) { toast.error(error instanceof Error ? error.message : "结果待核实，请勿重复提交") }
    finally { setBusy(false) }
  }

  return <div className="mt-4 space-y-4 border-t border-border-subtle pt-4">
    <div className="flex flex-wrap items-center justify-between gap-2"><h5 className="text-sm font-medium">供应商售后</h5>
      {purchase.can_recover && <Button type="button" variant="outline" size="sm" disabled={busy} onClick={() => setConfirmation({ kind: "recover" })}>重新检查并继续采购</Button>}
      <Button type="button" variant="outline" size="sm" disabled={busy || hasRefund || voidPending || purchase.upstream_status == null} onClick={() => setConfirmation({ kind: "refund" })}>申请上游退款</Button>
    </div>
    {operations.map(op => <div key={op.id} className="rounded-control bg-surface-muted p-3 text-sm">
      <p className="font-medium">{operationKind[op.kind] || op.kind} · {operationStatus[op.status] || op.status}</p>
      <p className="mt-1 text-xs text-muted-foreground">{op.supplier_req_id != null ? `申诉编号 ${op.supplier_req_id} · ` : ""}{op.upstream_status != null ? `上游状态 ${op.upstream_status} · ` : ""}{op.created_at}</p>
      {op.content && <p className="mt-1 whitespace-pre-wrap break-words">{op.content}</p>}
      {op.result && <p className="mt-1 whitespace-pre-wrap break-words"><span className="text-xs text-muted-foreground">上游处理结果：</span>{op.result}</p>}
      {op.error_code && <p className="mt-1 text-xs text-destructive">{op.error_code} · 请人工核实</p>}
      {(op.reviews ?? []).map((review, i) => <p key={`${review.created_at}-${i}`} className="mt-1 text-xs text-muted-foreground">{review.outcome === "CONTACTED_SUPPLIER" ? "已联系供应商" : review.outcome === "MANUAL_HANDLING" ? "已转人工处理" : "结果尚未确认"} · {review.operator} · {review.created_at}</p>)}
      {["INTENT", "SUBMITTING", "UNKNOWN", "ACCEPTED"].includes(op.status) && <div className="mt-2 flex flex-wrap gap-2">
        {op.kind !== "COMPLAINT" && <Button type="button" variant="outline" size="sm" disabled={busy} onClick={() => void run(() => requestOrThrow(() => adminOrderApi.supplierOperationRefresh(orderId, purchase.id, op.id)), "查询完成")}>查询最新状态</Button>}
        {op.status === "INTENT" && op.kind === "REFUND" && <Button type="button" variant="outline" size="sm" disabled={busy} onClick={() => setConfirmation({ kind: "refund" })}>继续未提交的退款申请</Button>}
        {op.status === "INTENT" && op.kind === "CARD_VOID" && op.card_id && <Button type="button" variant="outline" size="sm" disabled={busy} onClick={() => { const card = purchase.supplier_cards?.find(item => item.id === op.card_id); if (card) setConfirmation({ kind: "void", cardId: card.id, remoteId: card.remote_card_id }) }}>继续未提交的作废申请</Button>}
        {op.status === "INTENT" && op.kind === "COMPLAINT" && op.after_sale_id && <Button type="button" variant="outline" size="sm" disabled={busy} onClick={() => void run(() => requestOrThrow(() => adminOrderApi.supplierComplaint(orderId, purchase.id, { idempotencyKey: op.idempotency_key, confirmed: true, afterSaleId: op.after_sale_id!, topic: op.topic || "", content: op.content || "", attachmentIds: op.attachment_ids || [] })), "申诉结果已更新")}>继续未提交的申诉</Button>}
        <Button type="button" variant="ghost" size="sm" disabled={busy} onClick={() => void run(() => requestOrThrow(() => adminOrderApi.supplierOperationReview(orderId, purchase.id, op.id, "CONTACTED_SUPPLIER")), "已记录")}>记录已联系供应商</Button>
      </div>}
    </div>)}
    {(purchase.supplier_cards ?? []).map(card => <div key={card.id} className="rounded-control border border-border-subtle p-3 text-sm">
      <div className="flex flex-wrap items-center justify-between gap-2"><p>上游卡券编号 {card.remote_card_id} · {cardStatus[card.status] || "状态未知"}</p><div className="flex gap-2">
        <Button type="button" variant="outline" size="sm" disabled={busy} onClick={() => void run(() => requestOrThrow(() => adminOrderApi.supplierCardQuery(orderId, purchase.id, card.id)), "查询完成")}>查询卡券</Button>
        <Button type="button" variant="outline" size="sm" disabled={busy || refundPending || card.cancel_request !== 1 || ![0, 1].includes(card.status) || operations.some(op => op.kind === "CARD_VOID" && op.card_id === card.id)} onClick={() => setConfirmation({ kind: "void", cardId: card.id, remoteId: card.remote_card_id })}>申请作废</Button>
      </div></div>
      {card.result && <p className="mt-1 whitespace-pre-wrap break-words text-xs text-muted-foreground">上游处理结果：{card.result}</p>}
      <p className="mt-1 text-xs text-muted-foreground">{card.cancel_request === 1 ? "供应商允许申请作废" : "供应商不允许申请作废"}{card.last_checked_at ? ` · ${card.last_checked_at}` : " · 尚未主动查询"}</p>
    </div>)}
    {sale && !complaintClosed && <form onSubmit={submitComplaint} aria-label="供应商申诉" className="space-y-3 rounded-control border border-border-subtle p-3">
      <h5 className="text-sm font-medium">{firstComplaint ? "提交上游申诉" : "追加上游申诉"}</h5>
      <label className="block text-sm">申诉主题<input name="topic" value={topic} onChange={event => setTopic(event.target.value)} maxLength={50} required={firstComplaint} className="mt-1 h-10 w-full rounded-md border border-input bg-background px-3 text-base" /></label>
      <label className="block text-sm">申诉内容<Textarea name="content" value={content} onChange={event => setContent(event.target.value)} maxLength={500} required className="mt-1 text-base" /></label>
      {evidence.length > 0 && <fieldset><legend className="text-sm">选择售后凭证（最多 5 张，总计不超过 10MB）</legend><div className="mt-2 space-y-1">{evidence.map(file => <label key={file.id} className="flex items-center gap-2 text-sm"><input type="checkbox" checked={selected.includes(file.id)} onChange={event => setSelected(current => event.target.checked ? [...current, file.id].slice(0, 5) : current.filter(id => id !== file.id))} />{file.original_filename}</label>)}</div></fieldset>}
      {complaintUnconfirmed && <p className="text-xs text-destructive">已有申诉结果尚未确认，请先人工核实。</p>}
      <Button type="submit" size="sm" disabled={busy || complaintUnconfirmed}>{busy ? "提交中" : "提交申诉"}</Button>
    </form>}
    <dialog ref={dialog} aria-labelledby={titleId} onClose={() => setConfirmation(null)} className="w-[min(92vw,28rem)] rounded-surface border border-border-subtle bg-background p-5 text-foreground shadow-xl">
      <h5 id={titleId} className="text-base font-semibold">{confirmation?.kind === "void" ? "确认申请作废" : confirmation?.kind === "recover" ? "重新检查采购" : "确认申请上游退款"}</h5>
      <p className="mt-3 text-sm">{confirmation?.kind === "void" ? `将向供应商申请作废卡券 ${confirmation.remoteId}，作废可能不可逆。`
        : confirmation?.kind === "recover" ? `将重新检查库存、映射、价格和余额，并继续原采购意图（${purchase.outer_number}）。仅当系统确认尚未提交上游时才会继续。`
        : "将向供应商申请订单退款，是否到账及金额以查询结果为准。"}</p>
      {confirmation?.kind !== "recover" && <p className="mt-2 text-sm">此操作不会自动向顾客退款。</p>}
      <div className="mt-5 flex justify-end gap-2"><form method="dialog"><Button type="submit" variant="outline" autoFocus>取消</Button></form>
        <Button type="button" disabled={busy} onClick={() => {
          const action=confirmation; dialog.current?.close()
          if (!action) return
          if (action.kind === "recover") void run(() => requestOrThrow(() => adminOrderApi.resumeSupplierPurchase(orderId,purchase.id,crypto.randomUUID())), "已重新检查采购状态")
          else if (action.kind === "refund") void run(() => requestOrThrow(() => adminOrderApi.supplierRefund(orderId,purchase.id,crypto.randomUUID())), "退款申请已记录")
          else void run(() => requestOrThrow(() => adminOrderApi.supplierCardVoid(orderId,purchase.id,action.cardId,crypto.randomUUID())), "作废申请已记录")
        }}>确认提交</Button>
      </div>
    </dialog>
  </div>
}
