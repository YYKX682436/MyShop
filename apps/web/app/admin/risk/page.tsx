"use client"

import { useCallback, useEffect, useRef, useState } from "react"
import { Eye, Save, ShieldAlert, ShieldCheck } from "lucide-react"
import { toast } from "sonner"

import { OrderDetailSheet } from "@/components/admin/orders/order-detail-sheet"
import { AdminPage, AdminPagination, DataTableShell, PageHeader, SettingsSection, TableEmptyState, TableErrorState, TableLoadingState } from "@/components/admin/shared"
import { OrderStatusBadge } from "@/components/shared/order-status-badge"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Switch } from "@/components/ui/switch"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { useLocale } from "@/lib/context"
import { formatDateTime, formatYuan } from "@/lib/utils"
import { adminCardKeyApi, adminOrderApi, adminRiskApi, requestOrThrow } from "@/services/api"
import type { AdminOrderItem, OrderCardKey, RiskConfig } from "@/types"

const PAGE_SIZE = 20

const DEFAULT_CONFIG: RiskConfig = {
  turnstile_enabled: false,
  device_rate_limit_enabled: false,
  device_order_limit_per_hour: 15,
  device_query_limit_per_hour: 50,
  device_login_limit_per_hour: 10,
  device_register_limit_per_hour: 10,
  rate_limit_per_second: 25,
  login_attempt_limit: 5,
  max_purchase_per_user: 50,
  order_expire_minutes: 15,
}

export default function AdminRiskPage() {
  const { t, locale } = useLocale()
  const [tab, setTab] = useState<"config" | "flagged">("config")
  const [config, setConfig] = useState<RiskConfig>(DEFAULT_CONFIG)
  const [configLoading, setConfigLoading] = useState(true)
  const [configError, setConfigError] = useState(false)
  const [saving, setSaving] = useState(false)
  const [flaggedOrders, setFlaggedOrders] = useState<AdminOrderItem[]>([])
  const [flaggedTotal, setFlaggedTotal] = useState(0)
  const [flaggedPage, setFlaggedPage] = useState(1)
  const [flaggedLoading, setFlaggedLoading] = useState(false)
  const [flaggedError, setFlaggedError] = useState(false)
  const [detailOrder, setDetailOrder] = useState<AdminOrderItem | null>(null)
  const [detailLoading, setDetailLoading] = useState(false)
  const [detailError, setDetailError] = useState(false)
  const [detailCardKeys, setDetailCardKeys] = useState<OrderCardKey[]>([])
  const [detailCardKeysLoading, setDetailCardKeysLoading] = useState(false)
  const [detailCardKeysError, setDetailCardKeysError] = useState(false)
  const detailRequestRef = useRef(0)
  const detailCardKeysRequestRef = useRef(0)

  const loadConfig = useCallback(async () => {
    setConfigLoading(true)
    setConfigError(false)
    try {
      setConfig(await requestOrThrow(() => adminRiskApi.getConfig()))
    } catch {
      setConfigError(true)
    } finally {
      setConfigLoading(false)
    }
  }, [])

  const loadFlaggedOrders = useCallback(async () => {
    setFlaggedLoading(true)
    setFlaggedError(false)
    try {
      const data = await requestOrThrow(() => adminRiskApi.getFlaggedOrders({ page: flaggedPage, page_size: PAGE_SIZE }))
      setFlaggedOrders(data.list)
      setFlaggedTotal(data.pagination.total)
    } catch {
      setFlaggedOrders([])
      setFlaggedTotal(0)
      setFlaggedError(true)
    } finally {
      setFlaggedLoading(false)
    }
  }, [flaggedPage])

  useEffect(() => { void loadConfig() }, [loadConfig])
  useEffect(() => { if (tab === "flagged") void loadFlaggedOrders() }, [loadFlaggedOrders, tab])

  const handleToggle = async (key: "turnstile_enabled" | "device_rate_limit_enabled", value: boolean) => {
    const previous = config[key]
    setConfig((current) => ({ ...current, [key]: value }))
    try {
      await requestOrThrow(() => adminRiskApi.updateConfig({ [key]: value }))
      toast.success(t("admin.riskSaved"))
    } catch (requestError: unknown) {
      setConfig((current) => ({ ...current, [key]: previous }))
      toast.error(requestError instanceof Error ? requestError.message : t("admin.riskSaveFailed"))
    }
  }

  const handleSaveConfig = async () => {
    setSaving(true)
    try {
      await requestOrThrow(() => adminRiskApi.updateConfig(config))
      toast.success(t("admin.riskSaved"))
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.riskSaveFailed"))
    } finally {
      setSaving(false)
    }
  }

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
    setDetailLoading(true)
    setDetailError(false)
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
      setDetailError(true)
    } finally {
      if (detailRequestRef.current === detailRequestId) setDetailLoading(false)
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
      setDetailLoading(false)
      setDetailError(false)
      setDetailCardKeys([])
      setDetailCardKeysLoading(false)
      setDetailCardKeysError(false)
    }
  }

  const configNumber = (key: keyof RiskConfig, label: string) => <label className="flex flex-col gap-1.5 text-sm"><span className="font-medium">{label}</span><Input type="number" min="0" value={Number(config[key])} onChange={(event) => setConfig((current) => ({ ...current, [key]: Number.parseInt(event.target.value, 10) || 0 }))} /></label>

  if (configLoading) return <AdminPage><PageHeader title={t("admin.risk")} description={t("admin.riskDesc")} /><div className="h-64 animate-pulse rounded-xl border border-border bg-muted" role="status" aria-label={t("common.loading")} /></AdminPage>
  if (configError) return <AdminPage><PageHeader title={t("admin.risk")} description={t("admin.riskDesc")} /><div className="flex flex-col items-center gap-3 rounded-xl border border-destructive/30 bg-destructive/5 px-4 py-12 text-center text-sm text-destructive"><span>{t("admin.loadError")}</span><Button type="button" variant="outline" onClick={() => void loadConfig()}>{t("common.retry")}</Button></div></AdminPage>

  return (
    <AdminPage>
      <PageHeader title={t("admin.risk")} description={t("admin.riskDesc")} />
      <Tabs value={tab} onValueChange={(value) => setTab(value as "config" | "flagged")}>
        <TabsList><TabsTrigger value="config"><ShieldCheck className="mr-2 h-4 w-4" aria-hidden="true" />{t("admin.riskConfig")}</TabsTrigger><TabsTrigger value="flagged"><ShieldAlert className="mr-2 h-4 w-4" aria-hidden="true" />{t("admin.flaggedOrders")}</TabsTrigger></TabsList>
        <TabsContent value="config" className="flex flex-col gap-6">
          <div className="grid gap-6 lg:grid-cols-2">
            <div className="flex flex-col gap-6">
              <SettingsSection title={t("admin.turnstileTitle")} description={t("admin.turnstileDescription")}>
                <div className="flex items-center justify-between gap-4"><span className="text-sm font-medium">{t("admin.turnstileLabel")}</span><Switch checked={config.turnstile_enabled} onCheckedChange={(checked) => void handleToggle("turnstile_enabled", checked)} aria-label={t("admin.turnstileLabel")} /></div>
              </SettingsSection>
              <SettingsSection title={t("admin.generalLimits")}>
                <div className="grid gap-4 sm:grid-cols-2">{configNumber("rate_limit_per_second", t("admin.ratePerSecond"))}{configNumber("login_attempt_limit", t("admin.loginAttemptLimit"))}{configNumber("max_purchase_per_user", t("admin.maxPurchasePerUser"))}{configNumber("order_expire_minutes", t("admin.orderExpire"))}</div>
              </SettingsSection>
            </div>
            <SettingsSection title={t("admin.deviceRateLimitTitle")} description={t("admin.deviceRateLimitDescription")}>
              <div className="space-y-4"><div className="flex items-center justify-between gap-4"><span className="text-sm font-medium">{t("admin.deviceRateLimitLabel")}</span><Switch checked={config.device_rate_limit_enabled} onCheckedChange={(checked) => void handleToggle("device_rate_limit_enabled", checked)} aria-label={t("admin.deviceRateLimitLabel")} /></div><p className="text-xs text-muted-foreground">{t("admin.instantApplyHint")}</p><div className="grid gap-4 sm:grid-cols-2">{configNumber("device_order_limit_per_hour", t("admin.deviceOrderLimit"))}{configNumber("device_query_limit_per_hour", t("admin.deviceQueryLimit"))}{configNumber("device_login_limit_per_hour", t("admin.deviceLoginLimit"))}{configNumber("device_register_limit_per_hour", t("admin.deviceRegisterLimit"))}</div></div>
            </SettingsSection>
          </div>
          <div className="flex justify-end"><Button type="button" onClick={() => void handleSaveConfig()} disabled={saving}><Save className="h-4 w-4" aria-hidden="true" />{saving ? t("admin.saving") : t("admin.saveRiskConfig")}</Button></div>
        </TabsContent>

        <TabsContent value="flagged">
          <DataTableShell footer={<AdminPagination page={flaggedPage} total={flaggedTotal} pageSize={PAGE_SIZE} onPageChange={setFlaggedPage} />}>
            <table className="w-full min-w-[850px] text-sm"><thead><tr className="border-b border-border bg-muted/30"><th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.orderNo")}</th><th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.user")}</th><th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.emailLabel")}</th><th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.amount")}</th><th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.statusLabel")}</th><th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.time")}</th><th className="px-4 py-3 text-right font-medium text-muted-foreground">{t("admin.actions")}</th></tr></thead><tbody>
              {flaggedLoading && <TableLoadingState colSpan={7} />}
              {!flaggedLoading && flaggedError && <TableErrorState colSpan={7} onRetry={() => void loadFlaggedOrders()} />}
              {!flaggedLoading && !flaggedError && flaggedOrders.length === 0 && <TableEmptyState colSpan={7}>{t("admin.noFlaggedOrders")}</TableEmptyState>}
              {!flaggedLoading && !flaggedError && flaggedOrders.map((order) => <tr key={order.id} className="border-b border-border/60 last:border-0 hover:bg-muted/30"><td className="px-4 py-3 font-mono text-xs">{order.id.length > 16 ? `${order.id.slice(0, 8)}…${order.id.slice(-4)}` : order.id}</td><td className="px-4 py-3">{order.username || t("admin.guest")}</td><td className="px-4 py-3 text-muted-foreground">{order.email}</td><td className="px-4 py-3 font-medium">{formatYuan(order.actual_amount)}</td><td className="px-4 py-3"><OrderStatusBadge status={order.status} /></td><td className="whitespace-nowrap px-4 py-3 text-muted-foreground">{formatDateTime(order.created_at, locale)}</td><td className="px-4 py-3 text-right"><Button type="button" variant="ghost" size="icon" title={t("admin.viewDetail")} aria-label={t("admin.viewDetail")} onClick={() => openDetail(order)}><Eye className="h-4 w-4" aria-hidden="true" /></Button></td></tr>)}
            </tbody></table>
          </DataTableShell>
        </TabsContent>
      </Tabs>

      <OrderDetailSheet order={detailOrder} onOpenChange={closeDetail} detailLoading={detailLoading} detailError={detailError} onRetryDetail={() => detailOrder && void loadOrderDetail(detailOrder.id)} detailCardKeys={detailCardKeys} detailCardKeysLoading={detailCardKeysLoading} detailCardKeysError={detailCardKeysError} onRetryCardKeys={() => detailOrder && void loadDetailCardKeys(detailOrder.id)} />
    </AdminPage>
  )
}
