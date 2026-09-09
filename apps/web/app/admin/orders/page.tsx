"use client"

import { useCallback, useEffect, useMemo, useState } from "react"
import Link from "next/link"
import { CheckCircle, Copy, Eye } from "lucide-react"
import { toast } from "sonner"

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
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Textarea } from "@/components/ui/textarea"
import { useLocale } from "@/lib/context"
import { formatAdminOrderCustomer, formatAdminOrderProductMeta, limitAdminOrderProductItems } from "@/lib/admin-order-display"
import { formatDateTime, formatYuan, stripInvisible } from "@/lib/utils"
import { adminOrderApi, requestOrThrow } from "@/services/api"
import type { AdminOrderItem } from "@/types"

const PAGE_SIZE = 20
type TimeRange = "all" | "today" | "yesterday" | "7d" | "30d" | "custom"

function dateValue(date: Date) {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, "0")
  const day = String(date.getDate()).padStart(2, "0")
  return `${year}-${month}-${day}`
}

function shiftDate(date: Date, days: number) {
  const result = new Date(date)
  result.setDate(result.getDate() + days)
  return result
}

function dateBounds(range: TimeRange, customFrom: string, customTo: string) {
  if (range === "all") return { created_from: undefined, created_to: undefined }
  if (range === "custom") {
    return {
      created_from: customFrom || undefined,
      created_to: customTo ? dateValue(shiftDate(new Date(`${customTo}T00:00:00`), 1)) : undefined,
    }
  }
  const today = new Date()
  const start = new Date(today.getFullYear(), today.getMonth(), today.getDate())
  if (range === "today") return { created_from: dateValue(start), created_to: dateValue(shiftDate(start, 1)) }
  if (range === "yesterday") return { created_from: dateValue(shiftDate(start, -1)), created_to: dateValue(start) }
  const days = range === "7d" ? 6 : 29
  return { created_from: dateValue(shiftDate(start, -days)), created_to: dateValue(shiftDate(start, 1)) }
}

function ProductSummary({ order, emptyLabel }: { order: AdminOrderItem; emptyLabel: string }) {
  const items = order.items ?? []
  if (items.length === 0) return <span className="text-muted-foreground">{emptyLabel}</span>
  const { visible, additionalCount } = limitAdminOrderProductItems(items)
  return (
    <div className="min-w-[14rem] max-w-[22rem] space-y-1">
      {visible.map((item) => (
        <div key={item.id} className="min-w-0">
          <p className="truncate font-medium text-foreground">{item.product_title}</p>
          <p className="truncate text-xs text-muted-foreground">
            {formatAdminOrderProductMeta(item)}
          </p>
        </div>
      ))}
      {additionalCount > 0 && <p className="text-xs text-muted-foreground">+{additionalCount}</p>}
    </div>
  )
}

export default function AdminOrdersPage() {
  const { t, locale } = useLocale()
  const [orders, setOrders] = useState<AdminOrderItem[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)
  const [search, setSearch] = useState("")
  const [debouncedSearch, setDebouncedSearch] = useState("")
  const [statusFilter, setStatusFilter] = useState("")
  const [timeRange, setTimeRange] = useState<TimeRange>("all")
  const [customFrom, setCustomFrom] = useState("")
  const [customTo, setCustomTo] = useState("")
  const [sortDirection, setSortDirection] = useState<"asc" | "desc">("desc")
  const [page, setPage] = useState(1)

  const [markPaidOrderId, setMarkPaidOrderId] = useState<string | null>(null)
  const [markPaidReason, setMarkPaidReason] = useState("")
  const [markPaidLoading, setMarkPaidLoading] = useState(false)

  useEffect(() => {
    const timer = window.setTimeout(() => {
      setDebouncedSearch(search.trim())
      setPage(1)
    }, 300)
    return () => window.clearTimeout(timer)
  }, [search])

  const bounds = useMemo(() => dateBounds(timeRange, customFrom, customTo), [timeRange, customFrom, customTo])

  const loadOrders = useCallback(async () => {
    setLoading(true)
    setError(false)
    try {
      const data = await requestOrThrow(() => adminOrderApi.getList({
        page,
        page_size: PAGE_SIZE,
        status: statusFilter || undefined,
        keyword: debouncedSearch || undefined,
        ...bounds,
        sort_direction: sortDirection,
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
  }, [bounds, debouncedSearch, page, sortDirection, statusFilter])

  useEffect(() => { void loadOrders() }, [loadOrders])

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
      await loadOrders()
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.saveFailed"))
    } finally {
      setMarkPaidLoading(false)
    }
  }

  const copyOrderId = (value: string) => {
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
        <div className="flex min-w-0 flex-1 flex-col gap-3 lg:flex-row lg:flex-wrap">
          <Input className="min-w-0 lg:max-w-sm" value={search} onChange={(event) => setSearch(event.target.value)} placeholder={t("admin.searchOrder")} aria-label={t("admin.searchOrder")} />
          <select className="h-11 rounded-control border border-input bg-surface px-3 text-sm text-foreground shadow-sm" value={statusFilter} onChange={(event) => { setStatusFilter(event.target.value); setPage(1) }} aria-label={t("admin.statusLabel")}>
            <option value="">{t("admin.allStatus")}</option>
            <option value="PENDING">{t("status.PENDING")}</option>
            <option value="PAID">{t("status.PAID")}</option>
            <option value="DELIVERED">{t("status.DELIVERED")}</option>
            <option value="EXPIRED">{t("status.EXPIRED")}</option>
          </select>
          <select className="h-11 rounded-control border border-input bg-surface px-3 text-sm text-foreground shadow-sm" value={timeRange} onChange={(event) => { setTimeRange(event.target.value as TimeRange); setPage(1) }} aria-label="创建时间范围">
            <option value="all">全部时间</option>
            <option value="today">今天</option>
            <option value="yesterday">昨天</option>
            <option value="7d">近 7 天</option>
            <option value="30d">近 30 天</option>
            <option value="custom">自定义</option>
          </select>
          <select className="h-11 rounded-control border border-input bg-surface px-3 text-sm text-foreground shadow-sm" value={sortDirection} onChange={(event) => { setSortDirection(event.target.value as "asc" | "desc"); setPage(1) }} aria-label="创建时间排序">
            <option value="desc">最新在前</option>
            <option value="asc">最早在前</option>
          </select>
          {timeRange === "custom" && <div className="flex items-center gap-2">
            <Input type="date" value={customFrom} onChange={(event) => { setCustomFrom(event.target.value); setPage(1) }} aria-label="开始日期" />
            <span className="text-sm text-muted-foreground">至</span>
            <Input type="date" value={customTo} onChange={(event) => { setCustomTo(event.target.value); setPage(1) }} aria-label="结束日期" />
          </div>}
        </div>
      </PageToolbar>

      <DataTableShell footer={<AdminPagination page={page} total={total} pageSize={PAGE_SIZE} onPageChange={setPage} />}>
        <table className="w-full min-w-[1180px] text-sm">
          <thead><tr className="border-b border-border-subtle bg-surface-muted">
            <th className="px-4 py-3 text-left font-medium text-muted-foreground">商品</th>
            <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.orderId")}</th>
            <th className="px-4 py-3 text-left font-medium text-muted-foreground">客户</th>
            <th className="px-4 py-3 text-left font-medium text-muted-foreground">金额</th>
            <th className="px-4 py-3 text-left font-medium text-muted-foreground">利润</th>
            <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.statusLabel")}</th>
            <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.time")}</th>
            <th className="px-4 py-3 text-right font-medium text-muted-foreground">{t("admin.actions")}</th>
          </tr></thead>
          <tbody>
            {loading && <TableLoadingState colSpan={8} />}
            {!loading && error && <TableErrorState colSpan={8} onRetry={() => void loadOrders()} />}
            {!loading && !error && orders.length === 0 && <TableEmptyState colSpan={8}>{t("admin.noOrderData")}</TableEmptyState>}
            {!loading && !error && orders.map((order) => (
              <tr key={order.id} className="border-b border-border-subtle last:border-0 hover:bg-surface-muted/60">
                <td className="px-4 py-3"><ProductSummary order={order} emptyLabel={t("admin.systemNoData")} /></td>
                <td className="px-4 py-3"><div className="flex items-center gap-1"><button type="button" className="font-mono text-xs text-foreground hover:text-primary hover:underline" title={order.id} onClick={() => copyOrderId(order.id)}>{order.id}</button><Copy className="h-3.5 w-3.5 text-muted-foreground" aria-hidden="true" />{order.is_risk_flagged && <StatusBadge label={t("admin.riskFlagged")} tone="danger" />}</div></td>
                <td className="px-4 py-3"><CustomerCell order={order} guestLabel={t("admin.guest")} /></td>
                <td className="whitespace-nowrap px-4 py-3 font-medium">{formatYuan(order.actual_amount)}</td>
                <td className="whitespace-nowrap px-4 py-3"><span className="font-medium">{formatYuan(order.gross_profit_total)}</span><span className="ml-1 text-xs text-muted-foreground">{order.gross_margin_total === null ? "—" : `${order.gross_margin_total.toFixed(2)}%`}</span></td>
                <td className="px-4 py-3"><OrderStatusBadge status={order.status} /></td>
                <td className="whitespace-nowrap px-4 py-3 text-muted-foreground">{formatDateTime(order.created_at, locale)}</td>
                <td className="px-4 py-3"><div className="flex justify-end gap-1"><Button asChild type="button" variant="ghost" size="icon" title={t("admin.viewDetail")} aria-label={t("admin.viewDetail")}><Link href={`/admin/orders/${order.id}`}><Eye className="h-4 w-4" aria-hidden="true" /></Link></Button>{order.status === "PENDING" && <Button type="button" variant="ghost" size="icon" title={t("admin.markPaid")} aria-label={t("admin.markPaid")} onClick={() => { setMarkPaidOrderId(order.id); setMarkPaidReason("") }}><CheckCircle className="h-4 w-4 text-emerald-600" aria-hidden="true" /></Button>}</div></td>
              </tr>
            ))}
          </tbody>
        </table>
      </DataTableShell>

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

function CustomerCell({ order, guestLabel }: { order: AdminOrderItem; guestLabel: string }) {
  const identity = formatAdminOrderCustomer(order, guestLabel)
  return (
    <div className="flex min-w-[12rem] flex-col">
      <span>{identity.primary}</span>
      {identity.secondary && <span className="text-xs text-muted-foreground">{identity.secondary}</span>}
    </div>
  )
}
