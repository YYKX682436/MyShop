"use client"

import { RefreshCw } from "lucide-react"
import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import type { ReactNode } from "react"

import {
  AttentionStrip,
  BusinessOverview,
  DashboardInlineError,
  DashboardKpiCards,
  DashboardPanelState,
  InventoryHealthCard,
  LowStockPanel,
  OrderHealthCard,
  RecentUsersPanel,
  SystemStatusCard,
  TopProductsPanel,
} from "@/components/admin/dashboard-stats"
import { SalesChart } from "@/components/admin/sales-chart"
import { RecentOrders } from "@/components/admin/recent-orders"
import { useLocale } from "@/lib/context"
import { buildDashboardAttention, shouldShowInitialDashboardLoading } from "@/lib/dashboard"
import type { TranslationKey } from "@/lib/i18n"
import { formatDateTime } from "@/lib/utils"
import { adminDashboardApi, adminOrderApi, requestOrThrow } from "@/services/api"
import type { DashboardStats, SalesTrend, AdminOrderItem, SystemStatus } from "@/types"

interface AsyncState<T> {
  data: T | null
  loading: boolean
  error: boolean
}

const initialState = <T,>(): AsyncState<T> => ({ data: null, loading: true, error: false })

export default function AdminDashboardPage() {
  const { locale, t } = useLocale()
  const mountedRef = useRef(false)
  const businessAbortRef = useRef<AbortController | null>(null)
  const systemAbortRef = useRef<AbortController | null>(null)
  const [stats, setStats] = useState<AsyncState<DashboardStats>>(initialState)
  const [trends, setTrends] = useState<AsyncState<SalesTrend[]>>(initialState)
  const [recentOrders, setRecentOrders] = useState<AsyncState<AdminOrderItem[]>>(initialState)
  const [system, setSystem] = useState<AsyncState<SystemStatus>>(initialState)
  const [manualRefreshing, setManualRefreshing] = useState(false)
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null)

  const loadBusiness = useCallback(async () => {
    businessAbortRef.current?.abort()
    const controller = new AbortController()
    businessAbortRef.current = controller
    setStats((state) => ({ ...state, loading: true, error: false }))
    setTrends((state) => ({ ...state, loading: true, error: false }))
    setRecentOrders((state) => ({ ...state, loading: true, error: false }))

    const [statsResult, trendResult, ordersResult] = await Promise.allSettled([
      requestOrThrow(() => adminDashboardApi.getStats({ signal: controller.signal })),
      requestOrThrow(() => adminDashboardApi.getSalesTrend({}, { signal: controller.signal })),
      requestOrThrow(() => adminOrderApi.getList({ page: 1, page_size: 5 }, { signal: controller.signal })),
    ])
    if (controller.signal.aborted || !mountedRef.current) return

    let succeeded = false
    if (statsResult.status === "fulfilled") {
      setStats({ data: statsResult.value, loading: false, error: false })
      succeeded = true
    } else {
      setStats((state) => ({ ...state, loading: false, error: true }))
    }
    if (trendResult.status === "fulfilled") {
      setTrends({ data: trendResult.value, loading: false, error: false })
      succeeded = true
    } else {
      setTrends((state) => ({ ...state, loading: false, error: true }))
    }
    if (ordersResult.status === "fulfilled") {
      setRecentOrders({ data: ordersResult.value.list, loading: false, error: false })
      succeeded = true
    } else {
      setRecentOrders((state) => ({ ...state, loading: false, error: true }))
    }
    if (succeeded) setLastUpdated(new Date())
  }, [])

  const loadSystem = useCallback(async () => {
    systemAbortRef.current?.abort()
    const controller = new AbortController()
    systemAbortRef.current = controller
    setSystem((state) => ({ ...state, loading: state.data === null, error: false }))

    const result = await Promise.allSettled([
      requestOrThrow(() => adminDashboardApi.getSystemStatus({ signal: controller.signal })),
    ])
    if (controller.signal.aborted || !mountedRef.current) return
    const systemResult = result[0]
    if (systemResult.status === "fulfilled") {
      setSystem({ data: systemResult.value, loading: false, error: false })
      setLastUpdated(new Date())
    } else {
      setSystem((state) => ({ ...state, loading: false, error: true }))
    }
  }, [])

  const refreshAll = useCallback(async (showIndicator = false) => {
    if (showIndicator) setManualRefreshing(true)
    try {
      await Promise.allSettled([loadBusiness(), loadSystem()])
    } finally {
      if (showIndicator && mountedRef.current) setManualRefreshing(false)
    }
  }, [loadBusiness, loadSystem])

  useEffect(() => {
    mountedRef.current = true
    void refreshAll()

    return () => {
      mountedRef.current = false
      businessAbortRef.current?.abort()
      systemAbortRef.current?.abort()
    }
  }, [refreshAll])

  const attention = useMemo(() => {
    const copy = (key: TranslationKey, values: Record<string, string | number>) =>
      t(key).replace(/\{(\w+)\}/g, (_, name: string) => String(values[name] ?? `{${name}}`))
    const resourceKeys: Record<"cpu" | "memory" | "disk", TranslationKey> = {
      cpu: "admin.attentionCpu",
      memory: "admin.attentionMemory",
      disk: "admin.attentionDisk",
    }
    const serviceKeys: Record<"api" | "web" | "database" | "caddy", TranslationKey> = {
      api: "admin.systemApi",
      web: "admin.systemWeb",
      database: "admin.systemDatabase",
      caddy: "admin.systemCaddy",
    }
    return buildDashboardAttention(stats.data, system.data, {
      paidWaitingDelivery: (count) => copy("admin.attentionPaidWaitingDelivery", { count }),
      lowStock: (count) => copy("admin.attentionLowStock", { count }),
      zeroStock: (count) => copy("admin.attentionZeroStock", { count }),
      paymentSuccessRate: (rate) => copy("admin.attentionPaymentSuccessRate", { value: rate.toFixed(1) }),
      monitorUnavailable: () => t("admin.attentionMonitorUnavailable"),
      resource: (resource, usagePct) => copy(resourceKeys[resource], { value: usagePct.toFixed(1) }),
      serviceUnavailable: (service) => copy("admin.attentionServiceUnavailable", {
        service: t(serviceKeys[service]),
      }),
      serviceUnknown: (service) => copy("admin.attentionServiceUnknown", {
        service: t(serviceKeys[service]),
      }),
    })
  }, [stats.data, system.data, t])

  const loadingStrip = shouldShowInitialDashboardLoading(stats.data, stats.loading)
    || shouldShowInitialDashboardLoading(system.data, system.loading)
  const unavailableStrip = (!stats.data && stats.error) || (!system.data && system.error)
  const isInitialLoading = [stats, trends, recentOrders, system].some((state) =>
    shouldShowInitialDashboardLoading(state.data, state.loading),
  )
  const isRefreshing = manualRefreshing || isInitialLoading
  const updatedText = lastUpdated ? formatDateTime(lastUpdated.toISOString(), locale) : t("admin.dashboardLoading")

  return (
    <div className="mx-auto flex w-full max-w-[1600px] flex-col gap-5 sm:gap-6">
      <header className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
        <div className="min-w-0">
          <h1 className="text-2xl font-bold tracking-tight text-foreground sm:text-3xl">{t("admin.dashboard")}</h1>
          <p className="mt-1 text-sm text-muted-foreground">{t("admin.welcome")}</p>
        </div>
        <div className="flex items-center justify-between gap-3 sm:justify-end">
          <p className="truncate text-xs text-muted-foreground">
            {t("admin.dashboardLastUpdated").replace("{time}", updatedText)}
          </p>
          <button
            type="button"
            onClick={() => void refreshAll(true)}
            disabled={isRefreshing}
            className="inline-flex shrink-0 items-center gap-2 rounded-lg border border-border bg-card px-3 py-2 text-sm font-medium text-foreground shadow-sm transition-colors hover:bg-accent disabled:cursor-not-allowed disabled:opacity-60"
            aria-label={t("admin.refresh")}
          >
            <RefreshCw className={isRefreshing ? "h-4 w-4 animate-spin" : "h-4 w-4"} aria-hidden="true" />
            <span className="hidden min-[420px]:inline">{isRefreshing ? t("admin.refreshing") : t("admin.refresh")}</span>
          </button>
        </div>
      </header>

      <AttentionStrip items={attention} loading={loadingStrip} unavailable={unavailableStrip} />

      {stats.data ? (
        <>
          {stats.error && <DashboardInlineError onRetry={() => void loadBusiness()} />}
          <DashboardKpiCards stats={stats.data} />
          <BusinessOverview stats={stats.data} />
        </>
      ) : (
        <DashboardRegion title={t("admin.businessOverview")}>
          <DashboardPanelState loading={stats.loading} onRetry={() => void loadBusiness()} />
        </DashboardRegion>
      )}

      <div className="grid min-w-0 gap-5 xl:grid-cols-3">
        <div className="min-w-0 xl:col-span-2">
          {trends.data ? (
            <>
              {trends.error && <DashboardInlineError onRetry={() => void loadBusiness()} />}
              <SalesChart trends={trends.data} />
            </>
          ) : (
            <DashboardRegion title={t("admin.salesTrend")}>
              <DashboardPanelState loading={trends.loading} onRetry={() => void loadBusiness()} />
            </DashboardRegion>
          )}
        </div>
        <SystemStatusCard
          system={system.data}
          loading={system.loading}
          error={system.error}
          onRetry={() => void loadSystem()}
        />
      </div>

      {stats.data && (
        <div className="grid min-w-0 gap-5 lg:grid-cols-2">
          <OrderHealthCard stats={stats.data} />
          <InventoryHealthCard stats={stats.data} />
        </div>
      )}

      {stats.data && (
        <div className="grid min-w-0 gap-5 xl:grid-cols-2">
          <TopProductsPanel products={stats.data.top_products} />
          <LowStockPanel products={stats.data.low_stock_products} />
        </div>
      )}

      <div className="grid min-w-0 gap-5 xl:grid-cols-2">
        {recentOrders.data ? (
          <div className="min-w-0">
            {recentOrders.error && <DashboardInlineError onRetry={() => void loadBusiness()} />}
            <RecentOrders orders={recentOrders.data} />
          </div>
        ) : (
          <DashboardRegion title={t("admin.recentOrders")}>
            <DashboardPanelState loading={recentOrders.loading} onRetry={() => void loadBusiness()} />
          </DashboardRegion>
        )}
        {stats.data ? (
          <RecentUsersPanel users={stats.data.recent_users} />
        ) : (
          <DashboardRegion title={t("admin.recentCustomers")}>
            <DashboardPanelState loading={stats.loading} onRetry={() => void loadBusiness()} />
          </DashboardRegion>
        )}
      </div>
    </div>
  )
}

function DashboardRegion({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="min-w-0 rounded-xl border border-border bg-card p-4 shadow-sm sm:p-5">
      <h2 className="mb-4 font-semibold text-foreground">{title}</h2>
      {children}
    </section>
  )
}
