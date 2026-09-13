"use client"

import { useCallback, useEffect, useMemo, useState } from "react"
import Link from "next/link"
import { Copy, Eye } from "lucide-react"
import { toast } from "sonner"

import {
  AdminPage,
  AdminPagination,
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
import { useLocale } from "@/lib/context"
import { formatAdminOrderCustomer, formatAdminOrderProductMeta, limitAdminOrderProductItems } from "@/lib/admin-order-display"
import { useAdminOrderInvalidation } from "@/lib/admin-order-invalidation"
import { formatDateTime, formatYuan, stripInvisible } from "@/lib/utils"
import { adminOrderApi, requestOrThrow } from "@/services/api"
import type { AdminOrderItem, OrderStatus } from "@/types"
import type { TranslationKey } from "@/lib/i18n"

const PAGE_SIZE = 20
type TimeRange = "all" | "today" | "yesterday" | "7d" | "30d" | "custom"
type OrderTab = "ALL" | OrderStatus

const ORDER_STATUS_TABS: { value: OrderTab; label: TranslationKey }[] = [
  { value: "ALL", label: "admin.orderTabAll" },
  { value: "PENDING", label: "admin.orderTabPending" },
  { value: "PAID", label: "admin.orderTabPaid" },
  { value: "DELIVERED", label: "admin.orderTabDelivered" },
  { value: "AFTER_SALE", label: "admin.orderTabAfterSale" },
  { value: "REFUNDED", label: "admin.orderTabRefunded" },
  { value: "EXPIRED", label: "admin.orderTabExpired" },
]

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
  const orderInvalidationRevision = useAdminOrderInvalidation()
  const [orders, setOrders] = useState<AdminOrderItem[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)
  const [search, setSearch] = useState("")
  const [debouncedSearch, setDebouncedSearch] = useState("")
  const [statusFilter, setStatusFilter] = useState<OrderTab>("ALL")
  const [timeRange, setTimeRange] = useState<TimeRange>("all")
  const [customFrom, setCustomFrom] = useState("")
  const [customTo, setCustomTo] = useState("")
  const [sortDirection, setSortDirection] = useState<"asc" | "desc">("desc")
  const [page, setPage] = useState(1)

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
        status: statusFilter === "ALL" ? undefined : statusFilter,
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

  useEffect(() => { void loadOrders() }, [loadOrders, orderInvalidationRevision])

  const copyOrderNo = (value: number) => {
    const cleanValue = stripInvisible(String(value))
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

      <div className="-mx-1 overflow-x-auto px-1" aria-label={t("admin.statusLabel")}>
        <div className="flex min-w-max gap-1 border-b border-border-subtle">
          {ORDER_STATUS_TABS.map((tab) => (
            <button
              key={tab.value}
              type="button"
              aria-pressed={statusFilter === tab.value}
              onClick={() => { setStatusFilter(tab.value); setPage(1) }}
              className={`whitespace-nowrap border-b-2 px-4 py-3 text-sm font-medium transition-colors ${statusFilter === tab.value
                ? "border-primary text-primary"
                : "border-transparent text-muted-foreground hover:border-border-strong hover:text-foreground"}`}
            >
              {t(tab.label)}
            </button>
          ))}
        </div>
      </div>

      <PageToolbar>
        <div className="flex min-w-0 flex-1 flex-col gap-3 lg:flex-row lg:flex-wrap">
          <Input className="min-w-0 lg:max-w-sm" value={search} onChange={(event) => setSearch(event.target.value)} placeholder={t("admin.searchOrder")} aria-label={t("admin.searchOrder")} />
          <select className="h-11 rounded-control border border-input bg-surface px-3 text-sm text-foreground shadow-sm" value={timeRange} onChange={(event) => { setTimeRange(event.target.value as TimeRange); setPage(1) }} aria-label={t("admin.timeRangeLabel")}>
            <option value="all">{t("admin.allTime")}</option>
            <option value="today">{t("admin.today")}</option>
            <option value="yesterday">{t("admin.yesterday")}</option>
            <option value="7d">{t("admin.last7Days")}</option>
            <option value="30d">{t("admin.last30Days")}</option>
            <option value="custom">{t("admin.custom")}</option>
          </select>
          <select className="h-11 rounded-control border border-input bg-surface px-3 text-sm text-foreground shadow-sm" value={sortDirection} onChange={(event) => { setSortDirection(event.target.value as "asc" | "desc"); setPage(1) }} aria-label={t("admin.timeSortLabel")}>
            <option value="desc">{t("admin.newestFirst")}</option>
            <option value="asc">{t("admin.oldestFirst")}</option>
          </select>
          {timeRange === "custom" && <div className="flex items-center gap-2">
            <Input type="date" value={customFrom} onChange={(event) => { setCustomFrom(event.target.value); setPage(1) }} aria-label={t("admin.startDate")} />
            <span className="text-sm text-muted-foreground">{t("admin.to")}</span>
            <Input type="date" value={customTo} onChange={(event) => { setCustomTo(event.target.value); setPage(1) }} aria-label={t("admin.endDate")} />
          </div>}
        </div>
      </PageToolbar>

      <DataTableShell footer={<AdminPagination page={page} total={total} pageSize={PAGE_SIZE} onPageChange={setPage} />}>
        <table className="w-full min-w-[1180px] text-sm">
          <thead><tr className="border-b border-border-subtle bg-surface-muted">
            <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.orderNo")}</th>
            <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.product")}</th>
            <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.customer")}</th>
            <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.amount")}</th>
            <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.profit")}</th>
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
                <td className="px-4 py-3"><div className="flex items-center gap-1"><button type="button" className="font-mono text-xs text-foreground hover:text-primary hover:underline" title={`${t("admin.orderNo")} ${order.order_no}`} onClick={() => copyOrderNo(order.order_no)}>{order.order_no}</button><Copy className="h-3.5 w-3.5 text-muted-foreground" aria-hidden="true" />{order.is_risk_flagged && <StatusBadge label={t("admin.riskFlagged")} tone="danger" />}</div></td>
                <td className="px-4 py-3"><ProductSummary order={order} emptyLabel={t("admin.systemNoData")} /></td>
                <td className="px-4 py-3"><CustomerCell order={order} guestLabel={t("admin.guest")} /></td>
                <td className="whitespace-nowrap px-4 py-3 font-medium">{formatYuan(order.actual_amount)}</td>
                <td className="whitespace-nowrap px-4 py-3"><span className="font-medium">{formatYuan(order.gross_profit_total)}</span><span className="ml-1 text-xs text-muted-foreground">{order.gross_margin_total === null ? "—" : `${order.gross_margin_total.toFixed(2)}%`}</span></td>
                <td className="px-4 py-3"><OrderStatusBadge status={order.status} items={order.items} /></td>
                <td className="whitespace-nowrap px-4 py-3 text-muted-foreground">{formatDateTime(order.created_at, locale)}</td>
                <td className="px-4 py-3"><div className="flex justify-end gap-1"><Button asChild type="button" variant="ghost" size="icon" title={t("admin.viewDetail")} aria-label={t("admin.viewDetail")}><Link href={`/admin/orders/${order.id}`}><Eye className="h-4 w-4" aria-hidden="true" /></Link></Button></div></td>
              </tr>
            ))}
          </tbody>
        </table>
      </DataTableShell>

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
