"use client"

import { useCallback, useEffect, useRef, useState } from "react"
import { CheckCircle, Copy, Eye } from "lucide-react"
import { toast } from "sonner"

import { OrderDetailSheet } from "@/components/admin/orders/order-detail-sheet"
import {
  AdminPage,
  AdminPagination,
  ConfirmAlert,
  DataTableShell,
  PageHeader,
  PageToolbar,
  StatusBadge,
  TableEmptyState,
  TableErrorState,
  TableLoadingState,
} from "@/components/admin/shared"
import { OrderStatusBadge } from "@/components/shared/order-status-badge"
import { getPaymentLabel, PaymentIcon } from "@/components/shared/payment-icon"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Textarea } from "@/components/ui/textarea"
import { useLocale } from "@/lib/context"
import { formatDateTime, formatYuan, stripInvisible } from "@/lib/utils"
import { adminCardKeyApi, adminOrderApi, requestOrThrow } from "@/services/api"
import type { AdminOrderItem, OrderCardKey } from "@/types"

const PAGE_SIZE = 20

export default function AdminOrdersPage() {
  const { t, locale } = useLocale()
  const [orders, setOrders] = useState<AdminOrderItem[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)
  const [search, setSearch] = useState("")
  const [debouncedSearch, setDebouncedSearch] = useState("")
  const [statusFilter, setStatusFilter] = useState("")
  const [paymentFilter, setPaymentFilter] = useState("")
  const [orderTypeFilter, setOrderTypeFilter] = useState("")
  const [page, setPage] = useState(1)

  const [detailOrder, setDetailOrder] = useState<AdminOrderItem | null>(null)
  const [detailOrderLoading, setDetailOrderLoading] = useState(false)
  const [detailOrderError, setDetailOrderError] = useState(false)
  const [detailCardKeys, setDetailCardKeys] = useState<OrderCardKey[]>([])
  const [detailCardKeysLoading, setDetailCardKeysLoading] = useState(false)
  const [detailCardKeysError, setDetailCardKeysError] = useState(false)
  const [markPaidOrderId, setMarkPaidOrderId] = useState<string | null>(null)
  const [markPaidReason, setMarkPaidReason] = useState("")
  const [markPaidLoading, setMarkPaidLoading] = useState(false)
  const [completingManualItem, setCompletingManualItem] = useState<string | null>(null)
  const detailRequestRef = useRef(0)
  const detailCardKeysRequestRef = useRef(0)

  useEffect(() => {
    const timer = window.setTimeout(() => {
      setDebouncedSearch(search.trim())
      setPage(1)
    }, 300)
    return () => window.clearTimeout(timer)
  }, [search])

  const loadOrders = useCallback(async () => {
    setLoading(true)
    setError(false)
    try {
      const data = await requestOrThrow(() => adminOrderApi.getList({
        page,
        page_size: PAGE_SIZE,
        status: statusFilter || undefined,
        order_type: orderTypeFilter || undefined,
        payment_method: paymentFilter || undefined,
        keyword: debouncedSearch || undefined,
      }))
      setOrders(data.list)
      setTotal(data.pagination.total)
    } catch {
      setOrders([])
      setTotal(0)
      setError(true)
    } finally {
      setLoading(false)
    }
  }, [debouncedSearch, orderTypeFilter, page, paymentFilter, statusFilter])

  useEffect(() => { void loadOrders() }, [loadOrders])

  const invalidateDetailRequests = () => {
    detailRequestRef.current += 1
    detailCardKeysRequestRef.current += 1
  }

  const loadDetailCardKeys = useCallback(async (orderId: string, detailRequestId = detailRequestRef.current) => {
    const cardKeysRequestId = ++detailCardKeysRequestRef.current
    setDetailCardKeysLoading(true)
    setDetailCardKeysError(false)
    try {
      const cardKeys = await requestOrThrow(() => adminCardKeyApi.getByOrder(orderId))
      if (detailRequestRef.current !== detailRequestId || detailCardKeysRequestRef.current !== cardKeysRequestId) return
      setDetailCardKeys(cardKeys)
    } catch {
      if (detailRequestRef.current !== detailRequestId || detailCardKeysRequestRef.current !== cardKeysRequestId) return
      setDetailCardKeys([])
      setDetailCardKeysError(true)
    } finally {
      if (detailRequestRef.current === detailRequestId && detailCardKeysRequestRef.current === cardKeysRequestId) {
        setDetailCardKeysLoading(false)
      }
    }
  }, [])

  const loadOrderDetail = useCallback(async (orderId: string) => {
    const detailRequestId = ++detailRequestRef.current
    detailCardKeysRequestRef.current += 1
    setDetailOrderLoading(true)
    setDetailOrderError(false)
    try {
      const detail = await requestOrThrow(() => adminOrderApi.getDetail(orderId))
      if (detailRequestRef.current !== detailRequestId) return
      setDetailOrder(detail)
      if (detail.status === "DELIVERED") await loadDetailCardKeys(orderId, detailRequestId)
      else {
        setDetailCardKeys([])
        setDetailCardKeysLoading(false)
        setDetailCardKeysError(false)
      }
    } catch {
      if (detailRequestRef.current !== detailRequestId) return
      setDetailOrderError(true)
    } finally {
      if (detailRequestRef.current === detailRequestId) setDetailOrderLoading(false)
    }
  }, [loadDetailCardKeys])

  const openDetail = (order: AdminOrderItem) => {
    setDetailOrder(order)
    setDetailCardKeys([])
    setDetailCardKeysLoading(false)
    setDetailCardKeysError(false)
    void loadOrderDetail(order.id)
  }

  const closeDetail = (open: boolean) => {
    if (!open) {
      invalidateDetailRequests()
      setDetailOrder(null)
      setDetailOrderLoading(false)
      setDetailOrderError(false)
      setDetailCardKeys([])
      setDetailCardKeysLoading(false)
      setDetailCardKeysError(false)
    }
  }

  const handleMarkPaid = async () => {
    if (!markPaidOrderId) return
    const reason = markPaidReason.trim()
    if (reason.length < 3) {
      toast.error(t("admin.markPaidReasonTooShort"))
      return
    }
    setMarkPaidLoading(true)
    try {
      await requestOrThrow(() => adminOrderApi.markPaid(markPaidOrderId, reason))
      toast.success(t("admin.markedPaid"))
      setMarkPaidOrderId(null)
      setMarkPaidReason("")
      invalidateDetailRequests()
      setDetailOrder(null)
      setDetailOrderLoading(false)
      setDetailOrderError(false)
      setDetailCardKeys([])
      setDetailCardKeysLoading(false)
      setDetailCardKeysError(false)
      await loadOrders()
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.saveFailed"))
    } finally {
      setMarkPaidLoading(false)
    }
  }

  const handleCompleteManualItem = async (orderId: string, orderItemId: string) => {
    const detailRequestId = detailRequestRef.current
    setCompletingManualItem(orderItemId)
    try {
      await requestOrThrow(() => adminOrderApi.completeManualItem(orderId, orderItemId))
      toast.success(t("admin.manualDeliveryCompleted"))
      if (detailRequestRef.current === detailRequestId) await loadOrderDetail(orderId)
      await loadOrders()
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.saveFailed"))
    } finally {
      setCompletingManualItem(null)
    }
  }

  const copyOrderNumber = (value: string) => {
    const cleanValue = stripInvisible(value)
    const fallback = () => {
      const textarea = document.createElement("textarea")
      textarea.value = cleanValue
      textarea.style.position = "fixed"
      textarea.style.opacity = "0"
      document.body.appendChild(textarea)
      textarea.select()
      document.execCommand("copy")
      document.body.removeChild(textarea)
      toast.success(t("order.copied"))
    }
    if (navigator.clipboard?.writeText) void navigator.clipboard.writeText(cleanValue).then(() => toast.success(t("order.copied")), fallback)
    else fallback()
  }

  return (
    <AdminPage>
      <PageHeader title={t("admin.orders")} description={t("admin.ordersDesc")} />

      <PageToolbar>
        <div className="flex min-w-0 flex-1 flex-col gap-3 lg:flex-row">
          <Input className="min-w-0 lg:max-w-sm" value={search} onChange={(event) => setSearch(event.target.value)} placeholder={t("admin.searchOrder")} aria-label={t("admin.searchOrder")} />
          <select className="h-10 rounded-md border border-input bg-background px-3 text-sm text-foreground" value={statusFilter} onChange={(event) => { setStatusFilter(event.target.value); setPage(1) }} aria-label={t("admin.statusLabel")}>
            <option value="">{t("admin.allStatus")}</option>
            <option value="PENDING">{t("status.PENDING")}</option>
            <option value="PAID">{t("status.PAID")}</option>
            <option value="DELIVERED">{t("status.DELIVERED")}</option>
            <option value="EXPIRED">{t("status.EXPIRED")}</option>
          </select>
          <select className="h-10 rounded-md border border-input bg-background px-3 text-sm text-foreground" value={paymentFilter} onChange={(event) => { setPaymentFilter(event.target.value); setPage(1) }} aria-label={t("admin.paymentMethod")}>
            <option value="">{t("admin.allPayment")}</option>
            <option value="alipay">{t("payment.alipay")}</option>
            <option value="wechat">{t("payment.wechat")}</option>
          </select>
          <select className="h-10 rounded-md border border-input bg-background px-3 text-sm text-foreground" value={orderTypeFilter} onChange={(event) => { setOrderTypeFilter(event.target.value); setPage(1) }} aria-label={t("admin.orderSource")}>
            <option value="">{t("admin.allOrderType")}</option>
            <option value="DIRECT">{t("admin.directOrder")}</option>
            <option value="CART">{t("admin.cartOrder")}</option>
          </select>
        </div>
      </PageToolbar>

      <DataTableShell footer={<AdminPagination page={page} total={total} pageSize={PAGE_SIZE} onPageChange={setPage} />}>
        <table className="w-full min-w-[1200px] text-sm">
          <thead><tr className="border-b border-border bg-muted/30">
            <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.orderNo")}</th>
            <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.orderProducts")}</th>
            <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.quantity")}</th>
            <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.user")}</th>
            <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.amount")}</th>
            <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.orderSource")}</th>
            <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.paymentMethod")}</th>
            <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.statusLabel")}</th>
            <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.time")}</th>
            <th className="px-4 py-3 text-right font-medium text-muted-foreground">{t("admin.actions")}</th>
          </tr></thead>
          <tbody>
            {loading && <TableLoadingState colSpan={10} />}
            {!loading && error && <TableErrorState colSpan={10} onRetry={() => void loadOrders()} />}
            {!loading && !error && orders.length === 0 && <TableEmptyState colSpan={10}>{t("admin.noOrderData")}</TableEmptyState>}
            {!loading && !error && orders.map((order) => {
              const item = order.items[0]
              const quantity = order.items.reduce((sum, current) => sum + current.quantity, 0)
              return (
                <tr key={order.id} className="border-b border-border/60 last:border-0 hover:bg-muted/30">
                  <td className="px-4 py-3"><div className="flex items-center gap-1"><button type="button" className="font-mono text-xs text-foreground hover:text-primary hover:underline" title={order.id} onClick={() => copyOrderNumber(order.id)}>{order.id.length > 16 ? `${order.id.slice(0, 8)}…${order.id.slice(-4)}` : order.id}</button><Copy className="h-3.5 w-3.5 text-muted-foreground" aria-hidden="true" />{order.is_risk_flagged && <StatusBadge label={t("admin.riskFlagged")} tone="danger" />}</div></td>
                  <td className="max-w-[220px] truncate px-4 py-3">{item?.product_title || t("admin.systemNoData")}{item?.spec_name && <span className="text-muted-foreground"> · {item.spec_name}</span>}{order.items.length > 1 && <span className="ml-1 text-xs text-muted-foreground">+{order.items.length - 1}</span>}</td>
                  <td className="px-4 py-3">{quantity}</td>
                  <td className="px-4 py-3"><div className="flex min-w-[12rem] flex-col"><span>{order.username || t("admin.guest")}</span><span className="text-xs text-muted-foreground">{order.email}</span></div></td>
                  <td className="whitespace-nowrap px-4 py-3 font-medium">{formatYuan(order.actual_amount)}</td>
                  <td className="px-4 py-3"><StatusBadge label={order.order_type === "CART" ? t("admin.cartOrder") : t("admin.directOrder")} tone={order.order_type === "CART" ? "purple" : "info"} /></td>
                  <td className="px-4 py-3"><span className="inline-flex items-center gap-1.5"><PaymentIcon method={order.payment_method} className="h-4 w-4" />{getPaymentLabel(order.payment_method, t)}</span></td>
                  <td className="px-4 py-3"><OrderStatusBadge status={order.status} /></td>
                  <td className="whitespace-nowrap px-4 py-3 text-muted-foreground">{formatDateTime(order.created_at, locale)}</td>
                  <td className="px-4 py-3"><div className="flex justify-end gap-1"><Button type="button" variant="ghost" size="icon" title={t("admin.viewDetail")} aria-label={t("admin.viewDetail")} onClick={() => openDetail(order)}><Eye className="h-4 w-4" aria-hidden="true" /></Button>{order.status === "PENDING" && <Button type="button" variant="ghost" size="icon" title={t("admin.markPaid")} aria-label={t("admin.markPaid")} onClick={() => { setMarkPaidOrderId(order.id); setMarkPaidReason("") }}><CheckCircle className="h-4 w-4 text-emerald-600" aria-hidden="true" /></Button>}</div></td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </DataTableShell>

      <OrderDetailSheet
        order={detailOrder}
        onOpenChange={closeDetail}
        detailLoading={detailOrderLoading}
        detailError={detailOrderError}
        onRetryDetail={() => detailOrder && void loadOrderDetail(detailOrder.id)}
        detailCardKeys={detailCardKeys}
        detailCardKeysLoading={detailCardKeysLoading}
        detailCardKeysError={detailCardKeysError}
        onRetryCardKeys={() => detailOrder && void loadDetailCardKeys(detailOrder.id)}
        completingManualItem={completingManualItem}
        onCompleteManualItem={(orderId, orderItemId) => void handleCompleteManualItem(orderId, orderItemId)}
        onMarkPaid={(orderId) => { setMarkPaidOrderId(orderId); setMarkPaidReason("") }}
      />

      <ConfirmAlert
        open={markPaidOrderId !== null}
        onOpenChange={(open) => { if (!open && !markPaidLoading) { setMarkPaidOrderId(null); setMarkPaidReason("") } }}
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

    </AdminPage>
  )
}
