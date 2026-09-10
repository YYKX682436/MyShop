"use client"

import { useCallback, useEffect, useState } from "react"
import { CheckCircle, Loader2 } from "lucide-react"
import { toast } from "sonner"

import { WorkspaceSheet, useRouteOverlayClose } from "@/components/admin/route-overlay"
import { ManualFulfillmentDialog } from "@/components/admin/orders/manual-fulfillment-dialog"
import { OrderDetailSheet } from "@/components/admin/orders/order-detail-sheet"
import { AdminPage, ConfirmAlert, PageHeader } from "@/components/admin/shared"
import { Button } from "@/components/ui/button"
import { Textarea } from "@/components/ui/textarea"
import { useLocale } from "@/lib/context"
import { adminCardKeyApi, adminOrderApi, adminProductApi, requestOrThrow } from "@/services/api"
import { isManualFulfillmentItem } from "@/lib/order-status"
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

  const load = useCallback(async () => {
    setLoading(true)
    setError(false)
    try {
      const detail = await requestOrThrow(() => adminOrderApi.getDetail(orderId))
      setOrder(detail)
      setCardKeys([])
      setCardKeysError(false)
      const hasAutomaticCardItems = detail.items.some((item) =>
        !isManualFulfillmentItem(item) && item.stock_mode === "CARD_KEY"
      )
      if ((detail.status === "PAID" || detail.status === "DELIVERED") && hasAutomaticCardItems) {
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
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.saveFailed"))
    } finally {
      setMarkPaidLoading(false)
    }
  }

  const retryCardKeys = () => {
    if (!order || (order.status !== "PAID" && order.status !== "DELIVERED")
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
          manualFulfillmentLoading={manualFulfillmentLoading}
          onOpenManualFulfillment={() => setManualFulfillmentOpen(true)}
        />
      )}
    </>
  )

  const markPaidButton = order?.status === "PENDING" ? (
    <Button type="button" onClick={() => { setMarkPaidOpen(true); setMarkPaidReason("") }}>
      <CheckCircle className="size-4" aria-hidden="true" />
      {t("admin.markPaid")}
    </Button>
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
        <PageHeader title={t("admin.orderWorkspace")} description={order ? `${t("admin.orderNo")} ${order.order_no}` : t("common.loading")} actions={markPaidButton} />
        <div className="surface min-h-[32rem] overflow-hidden p-0">{detailContent}</div>
        {confirmDialog}
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
        headerActions={markPaidButton}
      >
        {detailContent}
      </WorkspaceSheet>
      {confirmDialog}
      {manualFulfillmentDialog}
    </>
  )
}
