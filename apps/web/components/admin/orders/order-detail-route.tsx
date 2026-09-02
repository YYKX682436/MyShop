"use client"

import { useCallback, useEffect, useState } from "react"
import { CheckCircle, Loader2 } from "lucide-react"
import { toast } from "sonner"

import { InspectorSheet, useRouteOverlayClose } from "@/components/admin/route-overlay"
import { OrderDetailSheet } from "@/components/admin/orders/order-detail-sheet"
import { AdminPage, ConfirmAlert, PageHeader } from "@/components/admin/shared"
import { Button } from "@/components/ui/button"
import { Textarea } from "@/components/ui/textarea"
import { useLocale } from "@/lib/context"
import { adminCardKeyApi, adminOrderApi, requestOrThrow } from "@/services/api"
import type { AdminOrderItem, OrderCardKey } from "@/types"

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
  const [completingManualItem, setCompletingManualItem] = useState<string | null>(null)
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
      if (detail.status === "DELIVERED") {
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

  const handleCompleteManualItem = async (currentOrderId: string, orderItemId: string) => {
    setCompletingManualItem(orderItemId)
    try {
      await requestOrThrow(() => adminOrderApi.completeManualItem(currentOrderId, orderItemId))
      toast.success(t("admin.manualDeliveryCompleted"))
      await load()
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.saveFailed"))
    } finally {
      setCompletingManualItem(null)
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
    if (!order || order.status !== "DELIVERED") return
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
          embedded
          order={order}
          onOpenChange={(open) => { if (!open) close() }}
          detailLoading={loading}
          detailError={error}
          onRetryDetail={() => void load()}
          detailCardKeys={cardKeys}
          detailCardKeysLoading={cardKeysLoading}
          detailCardKeysError={cardKeysError}
          onRetryCardKeys={retryCardKeys}
          completingManualItem={completingManualItem}
          onCompleteManualItem={(currentOrderId, orderItemId) => void handleCompleteManualItem(currentOrderId, orderItemId)}
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

  if (presentation === "full") {
    return (
      <AdminPage>
        <PageHeader title={t("admin.orderDetail")} description={orderId} actions={markPaidButton} />
        <div className="surface min-h-[32rem] overflow-hidden p-0">{detailContent}</div>
        {confirmDialog}
      </AdminPage>
    )
  }

  return (
    <>
      <InspectorSheet
        title={t("admin.orderDetail")}
        description={order?.id || orderId}
        onClose={close}
        contentClassName="p-0 sm:p-0"
        footer={
          <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
            {markPaidButton}
            <Button type="button" variant="outline" onClick={close}>{t("common.close")}</Button>
          </div>
        }
      >
        {detailContent}
      </InspectorSheet>
      {confirmDialog}
    </>
  )
}
