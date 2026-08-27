"use client"

import {
  Activity,
  AlertTriangle,
  ArrowDownRight,
  ArrowUpRight,
  CheckCircle2,
  CircleHelp,
  CircleX,
  Clock3,
  Cpu,
  Gauge,
  HardDrive,
  Loader2,
  Minus,
  Package,
  Server,
  ShoppingCart,
  TrendingUp,
  UserPlus,
  Users,
  type LucideIcon,
} from "lucide-react"
import type { ReactNode } from "react"

import { useLocale } from "@/lib/context"
import type { TranslationKey } from "@/lib/i18n"
import {
  formatBytes,
  formatChangePct,
  formatNumber,
  formatPercent,
  getSystemStatusLevel,
  getUsageSeverity,
  type DashboardAttentionItem,
  type SystemStatusLevel,
} from "@/lib/dashboard"
import { cn } from "@/lib/utils"
import type {
  DashboardRecentUser,
  DashboardStats,
  DashboardTopProduct,
  LowStockProduct,
  ServiceHealth,
  SystemResourceMetric,
  SystemStatus,
} from "@/types"

function interpolate(text: string, values: Record<string, string | number>): string {
  return text.replace(/\{(\w+)\}/g, (_, key: string) => String(values[key] ?? `{${key}}`))
}

function Panel({
  title,
  icon: Icon,
  children,
  className,
  action,
}: {
  title: string
  icon?: LucideIcon
  children: ReactNode
  className?: string
  action?: ReactNode
}) {
  return (
    <section className={cn("min-w-0 rounded-xl border border-border bg-card p-4 shadow-sm sm:p-5", className)}>
      <div className="mb-4 flex items-center justify-between gap-3">
        <div className="flex min-w-0 items-center gap-2">
          {Icon && <Icon className="h-4 w-4 shrink-0 text-primary" aria-hidden="true" />}
          <h2 className="truncate font-semibold text-foreground">{title}</h2>
        </div>
        {action}
      </div>
      {children}
    </section>
  )
}

export function DashboardPanelState({
  loading = false,
  onRetry,
  compact = false,
}: {
  loading?: boolean
  onRetry?: () => void
  compact?: boolean
}) {
  const { t } = useLocale()
  if (loading) {
    return (
      <div className={cn("flex items-center justify-center gap-2 text-sm text-muted-foreground", compact ? "py-6" : "py-14")}>
        <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
        <span>{t("admin.dashboardLoading")}</span>
      </div>
    )
  }
  return (
    <div className={cn("flex flex-col items-center justify-center gap-2 text-center text-sm text-muted-foreground", compact ? "py-5" : "py-10")}>
      <span>{t("admin.loadFailedRetry")}</span>
      {onRetry && (
        <button
          type="button"
          onClick={onRetry}
          className="rounded-md px-2 py-1 font-medium text-primary underline-offset-4 hover:bg-primary/10 hover:underline"
        >
          {t("admin.refresh")}
        </button>
      )}
    </div>
  )
}

export function DashboardInlineError({ onRetry }: { onRetry: () => void }) {
  const { t } = useLocale()
  return (
    <div className="mb-3 flex flex-wrap items-center justify-between gap-2 rounded-lg border border-destructive/30 bg-destructive/5 px-3 py-2 text-xs text-foreground" role="alert">
      <span>{t("admin.loadFailedRetry")}</span>
      <button type="button" onClick={onRetry} className="font-medium text-primary underline-offset-4 hover:underline">
        {t("admin.refresh")}
      </button>
    </div>
  )
}

interface KpiCardProps {
  label: string
  value: string
  icon: LucideIcon
  change?: number | null
  helper?: string
}

function KpiCard({ label, value, icon: Icon, change, helper }: KpiCardProps) {
  const { t } = useLocale()
  const hasChange = change !== undefined
  return (
    <article className="min-w-0 rounded-xl border border-border bg-card p-4 shadow-sm sm:p-5">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="truncate text-sm text-muted-foreground">{label}</p>
          <p className="mt-2 truncate text-2xl font-bold tracking-tight text-foreground sm:text-3xl">{value}</p>
        </div>
        <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
          <Icon className="h-5 w-5" aria-hidden="true" />
        </span>
      </div>
      {(hasChange || helper) && (
        <div className="mt-3 flex min-h-5 items-center gap-2 text-xs">
          {hasChange && <ChangeValue value={change ?? null} />}
          {hasChange && <span className="text-muted-foreground">{t("admin.vsYesterday")}</span>}
          {helper && <span className="truncate text-muted-foreground">{helper}</span>}
        </div>
      )}
    </article>
  )
}

function ChangeValue({ value }: { value: number | null }) {
  const { t } = useLocale()
  if (value === null) {
    return <span className="font-medium text-muted-foreground">{formatChangePct(null, t("admin.systemNoData"))}</span>
  }
  const Icon = value > 0 ? ArrowUpRight : value < 0 ? ArrowDownRight : Minus
  return (
    <span className={cn(
      "inline-flex items-center gap-0.5 font-medium",
      value > 0 ? "text-emerald-600 dark:text-emerald-400" : value < 0 ? "text-destructive" : "text-muted-foreground",
    )}>
      <Icon className="h-3.5 w-3.5" aria-hidden="true" />
      {formatChangePct(value, t("admin.systemNoData"))}
    </span>
  )
}

export function DashboardKpiCards({ stats }: { stats: DashboardStats }) {
  const { locale, t } = useLocale()
  const money = (value: number) => `¥${value.toLocaleString(locale === "en" ? "en-US" : "zh-CN", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`
  return (
    <div className="grid grid-cols-1 gap-4 min-[420px]:grid-cols-2 xl:grid-cols-4">
      <KpiCard
        label={t("admin.todaySales")}
        value={money(stats.today_sales)}
        icon={TrendingUp}
        change={stats.today_sales_change_pct}
      />
      <KpiCard
        label={t("admin.todayOrders")}
        value={formatNumber(stats.today_orders, locale)}
        icon={ShoppingCart}
        change={stats.today_orders_change_pct}
      />
      <KpiCard
        label={t("admin.todayNewUsers")}
        value={formatNumber(stats.today_new_users, locale)}
        icon={UserPlus}
        change={stats.today_new_users_change_pct}
      />
      <KpiCard
        label={t("admin.conversionRate")}
        value={formatPercent(stats.conversion_rate, t("admin.systemNoData"))}
        icon={Gauge}
        helper={interpolate(t("admin.todayUv"), {}) + `: ${formatNumber(stats.today_uv, locale)}`}
      />
    </div>
  )
}

function MetricTile({ label, value, note }: { label: string; value: string; note?: string }) {
  return (
    <div className="min-w-0 rounded-lg border border-border/70 bg-muted/20 px-3 py-3">
      <p className="truncate text-xs text-muted-foreground">{label}</p>
      <p className="mt-1 truncate text-lg font-semibold text-foreground">{value}</p>
      {note && <p className="mt-0.5 truncate text-xs text-muted-foreground">{note}</p>}
    </div>
  )
}

export function BusinessOverview({ stats }: { stats: DashboardStats }) {
  const { locale, t } = useLocale()
  const money = (value: number) => `¥${value.toLocaleString(locale === "en" ? "en-US" : "zh-CN", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`
  const number = (value: number) => formatNumber(value, locale)
  return (
    <Panel title={t("admin.businessOverview")} icon={Activity}>
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-7">
        <MetricTile label={t("admin.monthSales")} value={money(stats.month_sales)} />
        <MetricTile label={t("admin.monthOrders")} value={number(stats.month_orders)} />
        <MetricTile label={t("admin.monthNewUsers")} value={number(stats.month_new_users)} />
        <MetricTile label={t("admin.dashboardTotalUsers")} value={number(stats.total_users)} />
        <MetricTile label={t("admin.todayPv")} value={number(stats.today_pv)} />
        <MetricTile label={t("admin.todayUv")} value={number(stats.today_uv)} />
        <MetricTile label={t("admin.todayBuyers")} value={number(stats.today_buyers)} />
      </div>
    </Panel>
  )
}

export function OrderHealthCard({ stats }: { stats: DashboardStats }) {
  const { locale, t } = useLocale()
  return (
    <Panel title={t("admin.orderHealth")} icon={ShoppingCart}>
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-5">
        <MetricTile label={t("admin.pendingOrders")} value={formatNumber(stats.pending_orders, locale)} />
        <MetricTile label={t("admin.paidWaitingDelivery")} value={formatNumber(stats.paid_waiting_delivery, locale)} />
        <MetricTile label={t("admin.deliveredToday")} value={formatNumber(stats.delivered_today, locale)} />
        <MetricTile label={t("admin.expiredToday")} value={formatNumber(stats.expired_today, locale)} />
        <MetricTile
          label={t("admin.todayPaymentSuccessRate")}
          value={formatPercent(stats.today_payment_success_rate, t("admin.systemNoData"))}
          note={interpolate(t("admin.paymentSampleCount"), { count: stats.today_payment_success_sample_count })}
        />
      </div>
    </Panel>
  )
}

export function InventoryHealthCard({ stats }: { stats: DashboardStats }) {
  const { locale, t } = useLocale()
  return (
    <Panel title={t("admin.inventoryHealth")} icon={Package}>
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
        <MetricTile label={t("admin.activeProducts")} value={formatNumber(stats.active_products, locale)} />
        <MetricTile label={t("admin.availableCardKeys")} value={formatNumber(stats.available_card_keys, locale)} />
        <MetricTile label={t("admin.lowStockProducts")} value={formatNumber(stats.low_stock_products.length, locale)} />
        <MetricTile label={t("admin.zeroStockProducts")} value={formatNumber(stats.zero_stock_products, locale)} />
      </div>
    </Panel>
  )
}

export function LowStockPanel({ products }: { products: LowStockProduct[] }) {
  const { locale, t } = useLocale()
  return (
    <Panel title={t("admin.lowStockWarning")} icon={AlertTriangle}>
      {products.length === 0 ? (
        <p className="py-6 text-center text-sm text-muted-foreground">{t("admin.noLowStock")}</p>
      ) : (
        <div className="space-y-2">
          {products.map((product) => (
            <div key={product.product_id} className="flex min-w-0 items-center justify-between gap-3 rounded-lg bg-amber-500/5 px-3 py-2.5">
              <div className="min-w-0">
                <p className="truncate text-sm font-medium text-foreground" title={product.title}>{product.title}</p>
                <p className="text-xs text-muted-foreground">
                  {t("admin.lowStockProducts")} · {formatNumber(product.threshold, locale)}
                </p>
              </div>
              <span className="shrink-0 rounded-full bg-amber-500/10 px-2.5 py-1 text-xs font-medium text-amber-700 dark:text-amber-300">
                {t("admin.remaining")} {formatNumber(product.available_stock, locale)}
              </span>
            </div>
          ))}
        </div>
      )}
    </Panel>
  )
}

export function TopProductsPanel({ products }: { products: DashboardTopProduct[] }) {
  const { locale, t } = useLocale()
  const money = (value: number) => `¥${value.toLocaleString(locale === "en" ? "en-US" : "zh-CN", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`
  return (
    <Panel title={t("admin.topProducts")} icon={TrendingUp}>
      {products.length === 0 ? (
        <p className="py-6 text-center text-sm text-muted-foreground">{t("admin.noTopProducts")}</p>
      ) : (
        <div className="space-y-2">
          {products.map((product, index) => (
            <div key={product.product_id} className="flex min-w-0 items-center gap-3 rounded-lg border border-border/60 px-3 py-2.5">
              <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-primary/10 text-xs font-semibold text-primary">
                {index + 1}
              </span>
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm font-medium text-foreground" title={product.title}>{product.title}</p>
                <p className="text-xs text-muted-foreground">{t("admin.productQuantity")} {formatNumber(product.quantity, locale)}</p>
              </div>
              <span className="shrink-0 text-sm font-semibold text-foreground">{money(product.sales_amount)}</span>
            </div>
          ))}
        </div>
      )}
    </Panel>
  )
}

export function RecentUsersPanel({ users }: { users: DashboardRecentUser[] }) {
  const { locale, t } = useLocale()
  return (
    <Panel title={t("admin.recentCustomers")} icon={Users}>
      {users.length === 0 ? (
        <p className="py-6 text-center text-sm text-muted-foreground">{t("admin.noRecentCustomers")}</p>
      ) : (
        <div className="space-y-2">
          {users.map((user) => (
            <div key={user.id} className="flex min-w-0 items-center gap-3 rounded-lg border border-border/60 px-3 py-2.5">
              <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary">
                <Users className="h-4 w-4" aria-hidden="true" />
              </span>
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm font-medium text-foreground" title={user.username}>{user.username}</p>
                <p className="truncate text-xs text-muted-foreground" title={user.email}>{user.email}</p>
              </div>
              <time className="hidden shrink-0 text-xs text-muted-foreground sm:block">
                {new Date(user.created_at).toLocaleDateString(locale === "en" ? "en-US" : "zh-CN")}
              </time>
            </div>
          ))}
        </div>
      )}
    </Panel>
  )
}

export function AttentionStrip({ items, loading = false, unavailable = false }: { items: DashboardAttentionItem[]; loading?: boolean; unavailable?: boolean }) {
  const { t } = useLocale()
  if (loading) {
    return (
      <div className="flex min-w-0 items-center gap-3 rounded-xl border border-border bg-muted/20 px-4 py-3 text-sm text-muted-foreground" role="status">
        <Loader2 className="h-5 w-5 shrink-0 animate-spin" aria-hidden="true" />
        <span>{t("admin.dashboardLoading")}</span>
      </div>
    )
  }
  if (unavailable) {
    return (
      <div className="flex min-w-0 items-center justify-between gap-3 rounded-xl border border-destructive/30 bg-destructive/5 px-4 py-3 text-sm text-foreground" role="alert">
        <span className="flex min-w-0 items-center gap-3"><AlertTriangle className="h-5 w-5 shrink-0 text-destructive" aria-hidden="true" /><span>{t("admin.loadFailedRetry")}</span></span>
      </div>
    )
  }
  if (items.length === 0) {
    return (
      <div className="flex min-w-0 items-center gap-3 rounded-xl border border-emerald-500/30 bg-emerald-500/5 px-4 py-3 text-sm text-foreground" role="status">
        <CheckCircle2 className="h-5 w-5 shrink-0 text-emerald-600 dark:text-emerald-400" aria-hidden="true" />
        <span>{t("admin.systemHealthy")} · {t("admin.noPendingIssues")}</span>
      </div>
    )
  }
  const visible = items.slice(0, 5)
  return (
    <div className="min-w-0 rounded-xl border border-amber-500/30 bg-amber-500/5 px-4 py-3" role="status">
      <div className="flex items-center gap-2">
        <AlertTriangle className="h-5 w-5 shrink-0 text-amber-600 dark:text-amber-400" aria-hidden="true" />
        <h2 className="font-semibold text-foreground">{t("admin.needAttention")}</h2>
      </div>
      <ul className="mt-2 grid gap-1 text-sm text-foreground sm:grid-cols-2">
        {visible.map((item) => (
          <li key={item.id} className="flex min-w-0 items-start gap-2">
            <span className={cn(
              "mt-1.5 h-2 w-2 shrink-0 rounded-full",
              item.severity === "critical" ? "bg-destructive" : "bg-amber-500",
            )} aria-hidden="true" />
            <span className="min-w-0">
              <span className="mr-1 font-medium">{item.severity === "critical" ? t("admin.systemCritical") : t("admin.systemWarning")}:</span>
              {item.message}
            </span>
          </li>
        ))}
      </ul>
      {items.length > visible.length && (
        <p className="mt-2 text-xs text-muted-foreground">
          {interpolate(t("admin.moreIssues"), { count: items.length - visible.length })}
        </p>
      )}
    </div>
  )
}

export function SystemStatusCard({
  system,
  loading,
  error,
  onRetry,
}: {
  system: SystemStatus | null
  loading: boolean
  error: boolean
  onRetry: () => void
}) {
  const { t } = useLocale()
  return (
    <Panel title={t("admin.serverStatus")} icon={Server} action={system && <SystemLevelBadge level={getSystemStatusLevel(system)} />}>
      {loading && <DashboardPanelState loading compact />}
      {!loading && error && <DashboardPanelState onRetry={onRetry} compact />}
      {!loading && !error && !system && <DashboardPanelState onRetry={onRetry} compact />}
      {!loading && !error && system && (
        <div className="space-y-4">
          <div className="grid gap-3 sm:grid-cols-3">
            <ResourceMetric label={t("admin.systemCpu")} icon={Cpu} showCapacity={false} metric={system.cpu ? {
              total_bytes: null,
              used_bytes: null,
              available_bytes: null,
              usage_pct: system.cpu.usage_pct,
            } : null} note={system.cpu?.cores === null || system.cpu?.cores === undefined
              ? undefined
              : interpolate(t("admin.systemCores"), { count: system.cpu.cores })} />
            <ResourceMetric label={t("admin.systemMemory")} icon={Activity} metric={system.memory} />
            <ResourceMetric label={t("admin.systemDisk")} icon={HardDrive} metric={system.disk} />
          </div>

          <div className="grid grid-cols-2 gap-2 rounded-lg border border-border/70 bg-muted/20 px-3 py-3 text-sm sm:grid-cols-3">
            <InfoValue label={t("admin.systemUptime")} value={formatUptime(system.uptime_seconds, t)} icon={Clock3} />
            <InfoValue label={t("admin.systemLoad")} value={system.load_1m === null || system.load_1m === undefined ? t("admin.systemNoData") : system.load_1m.toFixed(2)} icon={Gauge} />
            <div className="col-span-2 sm:col-span-1">
              <p className="mb-2 text-xs text-muted-foreground">{t("admin.systemServices")}</p>
              <div className="grid grid-cols-2 gap-x-3 gap-y-1.5">
                <ServiceStatusRow label={t("admin.systemApi")} service={system.services.api} />
                <ServiceStatusRow label={t("admin.systemWeb")} service={system.services.web} />
                <ServiceStatusRow label={t("admin.systemDatabase")} service={system.services.database} />
                <ServiceStatusRow label={t("admin.systemCaddy")} service={system.services.caddy} />
              </div>
            </div>
          </div>
        </div>
      )}
    </Panel>
  )
}

function ResourceMetric({
  label,
  icon: Icon,
  metric,
  note,
  showCapacity = true,
}: {
  label: string
  icon: LucideIcon
  metric: SystemResourceMetric | null
  note?: string
  showCapacity?: boolean
}) {
  const { t } = useLocale()
  const usage = metric?.usage_pct ?? null
  const severity = getUsageSeverity(usage)
  const barClass = severity === "critical"
    ? "bg-destructive"
    : severity === "warning"
      ? "bg-amber-500"
      : "bg-primary"
  return (
    <div className="min-w-0 rounded-lg border border-border/70 px-3 py-3">
      <div className="flex items-center justify-between gap-2">
        <span className="flex min-w-0 items-center gap-2 text-sm font-medium text-foreground">
          <Icon className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden="true" />
          <span className="truncate">{label}</span>
        </span>
        <span className="shrink-0 text-sm font-semibold text-foreground">{formatPercent(usage, t("admin.systemNoData"))}</span>
      </div>
      <div
        className="mt-3 h-2 overflow-hidden rounded-full bg-muted"
        role="progressbar"
        aria-label={label}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={usage ?? undefined}
      >
        <div className={cn("h-full rounded-full transition-[width]", barClass)} style={{ width: usage === null ? "0%" : `${Math.max(0, Math.min(100, usage))}%` }} />
      </div>
      {metric && showCapacity && (
        <p className="mt-2 truncate text-xs text-muted-foreground" title={interpolate(t("admin.systemUsedOf"), {
          used: formatBytes(metric.used_bytes, t("admin.systemNoData")),
          total: formatBytes(metric.total_bytes, t("admin.systemNoData")),
        })}>
          {interpolate(t("admin.systemUsedOf"), {
            used: formatBytes(metric.used_bytes, t("admin.systemNoData")),
            total: formatBytes(metric.total_bytes, t("admin.systemNoData")),
          })}
        </p>
      )}
      {note && <p className="mt-1 truncate text-xs text-muted-foreground">{note}</p>}
      {(!metric || usage === null) && <p className="mt-2 text-xs text-muted-foreground">{t("admin.systemMonitorUnavailable")}</p>}
    </div>
  )
}

function InfoValue({ label, value, icon: Icon }: { label: string; value: string; icon: LucideIcon }) {
  return (
    <div className="min-w-0">
      <p className="flex items-center gap-1.5 text-xs text-muted-foreground"><Icon className="h-3.5 w-3.5" aria-hidden="true" />{label}</p>
      <p className="mt-1 truncate font-medium text-foreground">{value}</p>
    </div>
  )
}

function ServiceStatusRow({ label, service }: { label: string; service: ServiceHealth }) {
  const { t } = useLocale()
  const status = service?.status ?? "UNKNOWN"
  const Icon = status === "UP" ? CheckCircle2 : status === "DOWN" ? CircleX : CircleHelp
  const color = status === "UP" ? "text-emerald-600 dark:text-emerald-400" : status === "DOWN" ? "text-destructive" : "text-muted-foreground"
  const text = status === "UP" ? t("admin.systemUp") : status === "DOWN" ? t("admin.systemDown") : t("admin.systemUnknown")
  return (
    <span className="flex min-w-0 items-center gap-1.5 text-xs text-foreground">
      <Icon className={cn("h-3.5 w-3.5 shrink-0", color)} aria-hidden="true" />
      <span className="truncate">{label}</span>
      <span className="truncate text-muted-foreground">{text}</span>
    </span>
  )
}

function SystemLevelBadge({ level }: { level: SystemStatusLevel }) {
  const { t } = useLocale()
  const label = level === "normal"
    ? t("admin.systemNormal")
    : level === "warning"
      ? t("admin.systemWarning")
      : level === "critical"
        ? t("admin.systemCritical")
        : t("admin.systemMonitorUnavailable")
  const style = level === "normal"
    ? "border-emerald-500/30 bg-emerald-500/10 text-emerald-700 dark:text-emerald-300"
    : level === "warning"
      ? "border-amber-500/30 bg-amber-500/10 text-amber-700 dark:text-amber-300"
      : level === "critical"
        ? "border-destructive/30 bg-destructive/10 text-destructive"
        : "border-border bg-muted text-muted-foreground"
  return <span className={cn("shrink-0 rounded-full border px-2 py-1 text-xs font-medium", style)}>{label}</span>
}

function formatUptime(seconds: number | null, t: (key: TranslationKey) => string): string {
  if (seconds === null || !Number.isFinite(seconds) || seconds < 0) return t("admin.systemNoData")
  const days = Math.floor(seconds / 86400)
  const hours = Math.floor((seconds % 86400) / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  const values: string[] = []
  if (days) values.push(`${days}${t("admin.systemDays")}`)
  if (hours || days) values.push(`${hours}${t("admin.systemHours")}`)
  if (minutes || hours || days) values.push(`${minutes}${t("admin.systemMinutes")}`)
  if (values.length === 0) values.push(`${Math.floor(seconds)}${t("admin.systemSeconds")}`)
  return values.join(" ")
}
