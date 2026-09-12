"use client"

import { useCallback, useEffect, useState } from "react"
import { CheckCircle, Loader2, ShieldAlert } from "lucide-react"
import { toast } from "sonner"

import { WorkspaceSheet, useRouteOverlayClose } from "@/components/admin/route-overlay"
import { ManualFulfillmentDialog } from "@/components/admin/orders/manual-fulfillment-dialog"
import { OrderDetailSheet } from "@/components/admin/orders/order-detail-sheet"
import { AdminPage, ConfirmAlert, PageHeader } from "@/components/admin/shared"
import { Button } from "@/components/ui/button"
import { Textarea } from "@/components/ui/textarea"
import { useLocale } from "@/lib/context"
import { invalidateAdminOrders } from "@/lib/admin-order-invalidation"
import { adminCardKeyApi, adminOrderApi, adminProductApi, requestOrThrow } from "@/services/api"
import { isManualFulfillmentItem } from "@/lib/order-status"
import { formatYuan } from "@/lib/utils"
import type { AdminManualFulfillmentItem, AdminOrderItem, OrderCardKey } from "@/types"

interface AdminOrderDetailRouteProps {
  orderId: string
  presentation?: "inspector" | "full"
}

export function AdminOrderDetailRoute({ orderId, presentation = "inspector" }: AdminOrderDetailRouteProps) {
  const { t } = useLocale()
  const [order, setOrder] = useState<AdminOrderItem | null>(null)
  const [cardKeys, setCardKeys] = useState<OrderCardKey[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)
  const [cardKeysLoading, setCardKeysLoading] = useState(false)
  const [cardKeysError, setCardKeysError] = useState(false)
  const [manualFulfillmentOpen, setManualFulfillmentOpen] = useState(false)
  const [manualFulfillmentLoading, setManualFulfillmentLoading] = useState(false)
  const [markPaidOpen, setMarkPaidOpen] = useState(false)
  const [markPaidReason, setMarkPaidReason] = useState("")
  const [markPaidLoading, setMarkPaidLoading] = useState(false)
  const [refundOpen, setRefundOpen] = useState(false)
  const [refundReason, setRefundReason] = useState("")
  const [refundLoading, setRefundLoading] = useState(false)
  const [reclaimCardKeys, setReclaimCardKeys] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    setError(false)
    try {
      const detail = await requestOrThrow(() => adminOrderApi.getDetail(orderId))
      setOrder(detail)
      setCardKeys([])
      setCardKeysError(false)
      setReclaimCardKeys(false)
      const hasAutomaticCardItems = detail.items.some((item) =>
        !isManualFulfillmentItem(item) && item.stock_mode === "CARD_KEY"
      )
      if ((detail.status === "PAID" || detail.status === "DELIVERED" || detail.status === "REFUNDED") && hasAutomaticCardItems) {
        setCardKeysLoading(true)
        try {
          setCardKeys(await requestOrThrow(() => adminCardKeyApi.getByOrder(orderId)))
        } catch {
          setCardKeys([])
          setCardKeysError(true)
        } finally {
          setCardKeysLoading(false)
        }
      }
    } catch {
      setOrder(null)
      setError(true)
    } finally {
      setLoading(false)
    }
  }, [orderId])

  useEffect(() => { void load() }, [load])

  const close = useRouteOverlayClose(false, presentation === "inspector")

  const handleCompleteManualFulfillment = async (items: AdminManualFulfillmentItem[]) => {
    if (!order || manualFulfillmentLoading) return
    setManualFulfillmentLoading(true)
    try {
      await requestOrThrow(() => adminOrderApi.fulfill(order.id, { confirmed: true, items }))
      toast.success(t("admin.manualDeliveryCompleted"))
      setManualFulfillmentOpen(false)
      await load()
      invalidateAdminOrders()
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.saveFailed"))
    } finally {
      setManualFulfillmentLoading(false)
    }
  }

  const handleMarkPaid = async () => {
    const reason = markPaidReason.trim()
    if (reason.length < 3) {
      toast.error(t("admin.markPaidReasonTooShort"))
      return
    }
    setMarkPaidLoading(true)
    try {
      await requestOrThrow(() => adminOrderApi.markPaid(orderId, reason))
      toast.success(t("admin.markedPaid"))
      setMarkPaidOpen(false)
      setMarkPaidReason("")
      await load()
      invalidateAdminOrders()
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.saveFailed"))
    } finally {
      setMarkPaidLoading(false)
    }
  }

  const handleRefund = async () => {
    if (!order || refundLoading) return
    const reason = refundReason.trim()
    setRefundLoading(true)
    try {
      await requestOrThrow(() => adminOrderApi.refund(order.id, reason, reclaimCardKeys))
      toast.success(t("admin.refundSuccess"))
      setRefundOpen(false)
      setRefundReason("")
      setReclaimCardKeys(false)
      await load()
      invalidateAdminOrders()
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.saveFailed"))
    } finally {
      setRefundLoading(false)
    }
  }

  const retryCardKeys = () => {
    if (!order || (order.status !== "PAID" && order.status !== "DELIVERED" && order.status !== "REFUNDED")
      || !order.items.some((item) => !isManualFulfillmentItem(item) && item.stock_mode === "CARD_KEY")) return
    setCardKeysLoading(true)
    setCardKeysError(false)
    void requestOrThrow(() => adminCardKeyApi.getByOrder(order.id)).then(setCardKeys).catch(() => {
      setCardKeys([])
      setCardKeysError(true)
    }).finally(() => setCardKeysLoading(false))
  }

  const detailContent = (
    <>
      {loading && !order && <div className="flex items-center gap-2 rounded-card border border-border-subtle bg-surface-muted px-3 py-3 text-sm text-muted-foreground"><Loader2 className="size-4 animate-spin" aria-hidden="true" />{t("common.loading")}</div>}
      {error && !order && <div className="flex flex-wrap items-center justify-between gap-3 rounded-card border border-destructive/30 bg-destructive/5 px-3 py-3 text-sm text-destructive"><span>{t("admin.loadError")}</span><Button type="button" variant="outline" size="sm" onClick={() => void load()}>{t("common.retry")}</Button></div>}
      {order && (
        <OrderDetailSheet
          order={order}
          detailLoading={loading}
          detailError={error}
          onRetryDetail={() => void load()}
          detailCardKeys={cardKeys}
          detailCardKeysLoading={cardKeysLoading}
          detailCardKeysError={cardKeysError}
          onRetryCardKeys={retryCardKeys}
        />
      )}
    </>
  )

  const canMarkPaid = order?.status === "PENDING"
    && order.order_type === "NORMAL"
    && order.payment_method === "ONLINE"
    && Boolean(order.payment_channel_code)
    && (!order.expires_at || new Date(order.expires_at).getTime() > Date.now())
  const markPaidButton = canMarkPaid ? (
    <Button type="button" onClick={() => { setMarkPaidOpen(true); setMarkPaidReason("") }}>
      <CheckCircle className="size-4" aria-hidden="true" />
      {t("admin.markPaid")}
    </Button>
  ) : null

  const canRefund = order?.customer_type === "REGISTERED"
    && order.order_type === "NORMAL"
    && (order.status === "PAID" || order.status === "DELIVERED")
  const reclaimableCardKeyCount = order
    ? cardKeys.filter((card) => {
      if (card.status !== "SOLD" || card.order_id !== order.id || !card.order_item_id) return false
      const item = order.items.find((candidate) => candidate.id === card.order_item_id)
      return Boolean(item
        && !isManualFulfillmentItem(item)
        && item.stock_mode === "CARD_KEY"
        && item.fulfillment_status === "DELIVERED")
    }).length
    : 0
  const hasDeliveredManualItems = order?.items.some((item) =>
    isManualFulfillmentItem(item) && item.fulfillment_status === "DELIVERED"
  ) ?? false
  const hasDeliveredItems = order?.status === "DELIVERED"
    || (order?.items.some((item) => item.fulfillment_status === "DELIVERED") ?? false)
  const refundButton = canRefund ? (
    <Button type="button" variant="destructive" onClick={() => { setRefundOpen(true); setRefundReason(""); setReclaimCardKeys(false) }} disabled={refundLoading}>
      <ShieldAlert className="size-4" aria-hidden="true" />
      {t("admin.refund")}
    </Button>
  ) : null
  const canCompleteManualFulfillment = order?.status === "PAID"
    && order.items.some((item) => isManualFulfillmentItem(item) && item.fulfillment_status === "WAITING_MANUAL")
  const manualFulfillmentButton = canCompleteManualFulfillment ? (
    <Button type="button" onClick={() => setManualFulfillmentOpen(true)} disabled={manualFulfillmentLoading}>
      {manualFulfillmentLoading && <Loader2 className="size-4 animate-spin" aria-hidden="true" />}
      {manualFulfillmentLoading ? t("admin.processing") : t("admin.completeDelivery")}
    </Button>
  ) : null
  const actionArea = (markPaidButton || refundButton || manualFulfillmentButton) ? (
    <div className="flex flex-wrap items-center gap-2">{markPaidButton}{manualFulfillmentButton}{refundButton}</div>
  ) : null

  const confirmDialog = (
    <ConfirmAlert
      open={markPaidOpen}
      onOpenChange={(open) => { if (!open && !markPaidLoading) { setMarkPaidOpen(false); setMarkPaidReason("") } }}
      title={t("admin.markPaidConfirm")}
      description={t("admin.markPaidReasonPlaceholder")}
      confirmLabel={t("admin.markPaidBtn")}
      cancelLabel={t("admin.cancel")}
      loading={markPaidLoading}
      confirmDisabled={markPaidReason.trim().length < 3}
      onConfirm={() => void handleMarkPaid()}
    >
      <Textarea value={markPaidReason} onChange={(event) => setMarkPaidReason(event.target.value)} placeholder={t("admin.markPaidReasonPlaceholder")} maxLength={500} rows={4} aria-label={t("admin.markPaidReasonPlaceholder")} />
    </ConfirmAlert>
  )

  const refundDialog = (
    <ConfirmAlert
      open={refundOpen}
      onOpenChange={(open) => { if (!open && !refundLoading) { setRefundOpen(false); setRefundReason(""); setReclaimCardKeys(false) } }}
      title={t("admin.refundConfirm")}
      description={t("admin.refundDescription")}
      confirmLabel={t("admin.refund")}
      cancelLabel={t("admin.cancel")}
      destructive
      loading={refundLoading}
      confirmDisabled={refundLoading}
      onConfirm={() => void handleRefund()}
    >
      {order && <div className="space-y-3 rounded-card border border-border-subtle bg-surface-muted/60 p-4 text-sm">
        <dl className="divide-y divide-border-subtle/70">
          <div className="flex items-center justify-between gap-4 py-2"><dt className="text-muted-foreground">{t("admin.refundOrderNo")}</dt><dd className="font-mono">{order.order_no}</dd></div>
          <div className="flex items-center justify-between gap-4 py-2"><dt className="text-muted-foreground">{t("admin.refundUser")}</dt><dd>{order.username || t("admin.guest")}</dd></div>
          <div className="flex items-center justify-between gap-4 py-2"><dt className="text-muted-foreground">{t("admin.refundPaidAmount")}</dt><dd className="font-semibold">{formatYuan(order.actual_amount ?? 0)}</dd></div>
          <div className="flex items-center justify-between gap-4 py-2"><dt className="text-muted-foreground">{t("admin.refundAmount")}</dt><dd className="font-semibold">{formatYuan(order.actual_amount ?? 0)}</dd></div>
          <div className="flex items-center justify-between gap-4 py-2"><dt className="text-muted-foreground">{t("admin.refundDestination")}</dt><dd>{t("admin.refundBalance")}</dd></div>
        </dl>
        {hasDeliveredItems && <p className="flex items-start gap-2 rounded-control border border-amber-300/50 bg-amber-50 px-3 py-2 text-sm text-amber-800 dark:border-amber-500/30 dark:bg-amber-950/20 dark:text-amber-200"><ShieldAlert className="mt-0.5 size-4 shrink-0" aria-hidden="true" />{t("admin.refundDeliveredWarning")}</p>}
        {reclaimableCardKeyCount > 0 && <label className="flex cursor-pointer items-start gap-3 rounded-control border border-border-subtle bg-surface px-3 py-3" htmlFor="admin-refund-reclaim-card-keys">
          <input id="admin-refund-reclaim-card-keys" type="checkbox" checked={reclaimCardKeys} onChange={(event) => setReclaimCardKeys(event.target.checked)} disabled={refundLoading} className="mt-0.5 size-4 accent-primary" />
          <span className="min-w-0"><span className="block font-medium text-foreground">{t("admin.refundReclaimLabel")}</span><span className="mt-1 block text-xs text-muted-foreground">{t("admin.refundReclaimEstimate").replace("{count}", String(reclaimableCardKeyCount))}</span></span>
        </label>}
        {reclaimCardKeys && <p className="rounded-control border border-destructive/30 bg-destructive/5 px-3 py-2 text-sm text-destructive">{t("admin.refundReclaimRiskWarning")}</p>}
        {hasDeliveredManualItems && <p className="rounded-control border border-amber-300/50 bg-amber-50 px-3 py-2 text-sm text-amber-800 dark:border-amber-500/30 dark:bg-amber-950/20 dark:text-amber-200">{t("admin.refundManualContentWarning")}</p>}
        <label className="block text-sm font-medium text-foreground" htmlFor="admin-refund-reason">{t("admin.refundReason")}</label>
        <Textarea id="admin-refund-reason" value={refundReason} onChange={(event) => setRefundReason(event.target.value)} placeholder={t("admin.refundReasonPlaceholder")} maxLength={500} rows={4} aria-label={t("admin.refundReason")} />
      </div>}
    </ConfirmAlert>
  )

  const manualFulfillmentDialog = (
    <ManualFulfillmentDialog
      open={manualFulfillmentOpen}
      items={order?.items.filter((item) =>
        isManualFulfillmentItem(item) && item.fulfillment_status === "WAITING_MANUAL"
      ) ?? []}
      loading={manualFulfillmentLoading}
      uploadImage={(file) => adminProductApi.uploadImage(file).then((result) => result.url)}
      onOpenChange={setManualFulfillmentOpen}
      onConfirm={(items) => void handleCompleteManualFulfillment(items)}
    />
  )

  if (presentation === "full") {
    return (
      <AdminPage>
        <PageHeader title={t("admin.orderWorkspace")} description={order ? `${t("admin.orderNo")} ${order.order_no}` : t("common.loading")} actions={actionArea} />
        <div className="surface min-h-[32rem] overflow-hidden p-0">{detailContent}</div>
        {confirmDialog}
        {refundDialog}
        {manualFulfillmentDialog}
      </AdminPage>
    )
  }

  return (
    <>
      <WorkspaceSheet
        title={t("admin.orderWorkspace")}
        description={order ? `${t("admin.orderNo")} ${order.order_no}` : t("common.loading")}
        onClose={close}
        headerActions={actionArea}
      >
        {detailContent}
      </WorkspaceSheet>
      {confirmDialog}
      {refundDialog}
      {manualFulfillmentDialog}
    </>
  )
}
