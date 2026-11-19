"use client"

import { useCallback, useEffect, useRef, useState } from "react"
import { Eye, Save, ShieldAlert, ShieldCheck } from "lucide-react"
import Link from "next/link"
import { toast } from "sonner"

import { AdminPage, AdminPagination, DataTableShell, PageHeader, SettingsSection, TableEmptyState, TableErrorState, TableLoadingState } from "@/components/admin/shared"
import { OrderStatusBadge } from "@/components/shared/order-status-badge"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Switch } from "@/components/ui/switch"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { useLocale } from "@/lib/context"
import { useAdminPageRefresh, useRequestGuard } from "@/lib/admin-page-requests"
import { formatAdminOrderCustomer } from "@/lib/admin-order-display"
import { formatDateTime, formatYuan } from "@/lib/utils"
import { adminRiskApi, requestOrThrow } from "@/services/api"
import type { AdminOrderItem, RiskConfig } from "@/types"

const PAGE_SIZE = 20

const DEFAULT_CONFIG: RiskConfig = {
  turnstile_enabled: false,
  device_rate_limit_enabled: false,
  device_order_limit_per_hour: 15,
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
  const [toggling, setToggling] = useState(false)
  const togglingRef = useRef(false)
  const [flaggedOrders, setFlaggedOrders] = useState<AdminOrderItem[]>([])
  const [flaggedTotal, setFlaggedTotal] = useState(0)
  const [flaggedPage, setFlaggedPage] = useState(1)
  const [flaggedLoading, setFlaggedLoading] = useState(false)
  const [flaggedError, setFlaggedError] = useState(false)
  const [hasConfig, setHasConfig] = useState(false)
  const savedConfig = useRef<RiskConfig | null>(null)
  const draftVersion = useRef(0)
  const startConfigRequest = useRequestGuard()
  const startFlaggedRequest = useRequestGuard()

  const loadConfig = useCallback(async () => {
    const current = startConfigRequest()
    const version = draftVersion.current
    setConfigLoading(true)
    setConfigError(false)
    try {
      const data = { ...DEFAULT_CONFIG, ...await requestOrThrow(() => adminRiskApi.getConfig()) }
      if (!current()) return
      if (version !== draftVersion.current) { setConfigError(true); return }
      setConfig(data)
      savedConfig.current = data
      setHasConfig(true)
    } catch {
      if (current()) setConfigError(true)
    } finally {
      if (current()) setConfigLoading(false)
    }
  }, [startConfigRequest])

  const loadFlaggedOrders = useCallback(async () => {
    const current = startFlaggedRequest()
    setFlaggedLoading(true)
    setFlaggedError(false)
    try {
      const data = await requestOrThrow(() => adminRiskApi.getFlaggedOrders({ page: flaggedPage, page_size: PAGE_SIZE }))
      if (!current()) return
      const lastPage = Math.max(1, Math.ceil(data.pagination.total / PAGE_SIZE))
      if (flaggedPage > lastPage) { setFlaggedPage(lastPage); return }
      setFlaggedOrders(data.list)
      setFlaggedTotal(data.pagination.total)
    } catch {
      if (!current()) return
      setFlaggedOrders([])
      setFlaggedTotal(0)
      setFlaggedError(true)
    } finally {
      if (current()) setFlaggedLoading(false)
    }
  }, [flaggedPage, startFlaggedRequest])

  useEffect(() => { void loadConfig() }, [loadConfig])
  useEffect(() => { if (tab === "flagged") void loadFlaggedOrders() }, [loadFlaggedOrders, tab])
  const refreshPage = useCallback(async () => { await Promise.all([loadConfig(), loadFlaggedOrders()]) }, [loadConfig, loadFlaggedOrders])
  const { refresh, refreshing } = useAdminPageRefresh(refreshPage)
  const requestRefresh = () => {
    if (savedConfig.current && JSON.stringify(savedConfig.current) !== JSON.stringify(config) && !window.confirm(t("admin.refreshDiscardDraft"))) return
    void refresh()
  }

  const handleToggle = async (key: "turnstile_enabled" | "device_rate_limit_enabled", value: boolean) => {
    if (togglingRef.current) return
    togglingRef.current = true
    setToggling(true)
    const previous = config[key]
    draftVersion.current += 1
    setConfig((current) => ({ ...current, [key]: value }))
    try {
      await requestOrThrow(() => adminRiskApi.updateConfig({ [key]: value }))
      if (savedConfig.current) savedConfig.current = { ...savedConfig.current, [key]: value }
      try {
        const authoritative = await requestOrThrow(() => adminRiskApi.getConfig())
        const actual = authoritative[key] ?? value
        setConfig((current) => ({ ...current, [key]: actual }))
        if (savedConfig.current) savedConfig.current = { ...savedConfig.current, [key]: actual }
      } catch { setConfigError(true) }
      toast.success(t("admin.riskSaved"))
    } catch (requestError: unknown) {
      setConfig((current) => ({ ...current, [key]: previous }))
      toast.error(requestError instanceof Error ? requestError.message : t("admin.riskSaveFailed"))
    } finally {
      togglingRef.current = false
      setToggling(false)
    }
  }

  const handleSaveConfig = async () => {
    const version = draftVersion.current
    setSaving(true)
    try {
      await requestOrThrow(() => adminRiskApi.updateConfig(config))
      savedConfig.current = config
      if (version === draftVersion.current) await loadConfig()
      toast.success(t("admin.riskSaved"))
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.riskSaveFailed"))
    } finally {
      setSaving(false)
    }
  }

  const configNumber = (key: keyof RiskConfig, label: string) => <label className="flex flex-col gap-1.5 text-sm"><span className="font-medium">{label}</span><Input type="number" min="0" value={Number(config[key])} onChange={(event) => { draftVersion.current += 1; setConfig((current) => ({ ...current, [key]: Number.parseInt(event.target.value, 10) || 0 })) }} /></label>

  const header = <PageHeader title={t("admin.risk")} description={t("admin.riskDesc")} onRefresh={requestRefresh} refreshing={refreshing} refreshDisabled={saving || toggling} />
  if (configLoading && !hasConfig) return <AdminPage>{header}<div className="h-64 animate-pulse rounded-card border border-border-subtle bg-surface-muted" role="status" aria-label={t("common.loading")} /></AdminPage>
  if (configError && !hasConfig) return <AdminPage>{header}<div className="flex flex-col items-center gap-3 rounded-card border border-destructive/30 bg-destructive/5 px-4 py-12 text-center text-sm text-destructive"><span>{t("admin.loadError")}</span><Button type="button" variant="outline" onClick={requestRefresh}>{t("common.retry")}</Button></div></AdminPage>

  return (
    <AdminPage>
      {header}
      {configError && <div role="alert" className="flex items-center gap-3 text-sm text-destructive">{t("admin.loadError")}<Button type="button" variant="outline" size="sm" onClick={requestRefresh}>{t("common.retry")}</Button></div>}
      <Tabs value={tab} onValueChange={(value) => setTab(value as "config" | "flagged")}>
        <TabsList><TabsTrigger value="config"><ShieldCheck className="mr-2 h-4 w-4" aria-hidden="true" />{t("admin.riskConfig")}</TabsTrigger><TabsTrigger value="flagged"><ShieldAlert className="mr-2 h-4 w-4" aria-hidden="true" />{t("admin.flaggedOrders")}</TabsTrigger></TabsList>
        <TabsContent value="config" className="flex flex-col gap-6">
          <div className="grid gap-6 lg:grid-cols-2">
            <div className="flex flex-col gap-6">
              <SettingsSection title={t("admin.turnstileTitle")} description={t("admin.turnstileDescription")}>
                <div className="flex items-center justify-between gap-4"><span className="text-sm font-medium">{t("admin.turnstileLabel")}</span><Switch checked={config.turnstile_enabled} onCheckedChange={(checked) => void handleToggle("turnstile_enabled", checked)} disabled={toggling || saving || refreshing} aria-label={t("admin.turnstileLabel")} /></div>
              </SettingsSection>
              <SettingsSection title={t("admin.generalLimits")}>
                <div className="grid gap-4 sm:grid-cols-2">{configNumber("rate_limit_per_second", t("admin.ratePerSecond"))}{configNumber("login_attempt_limit", t("admin.loginAttemptLimit"))}{configNumber("max_purchase_per_user", t("admin.maxPurchasePerUser"))}{configNumber("order_expire_minutes", t("admin.orderExpire"))}</div>
              </SettingsSection>
            </div>
            <SettingsSection title={t("admin.deviceRateLimitTitle")} description={t("admin.deviceRateLimitDescription")}>
              <div className="space-y-4"><div className="flex items-center justify-between gap-4"><span className="text-sm font-medium">{t("admin.deviceRateLimitLabel")}</span><Switch checked={config.device_rate_limit_enabled} onCheckedChange={(checked) => void handleToggle("device_rate_limit_enabled", checked)} disabled={toggling || saving || refreshing} aria-label={t("admin.deviceRateLimitLabel")} /></div><p className="text-xs text-muted-foreground">{t("admin.instantApplyHint")}</p><div className="grid gap-4 sm:grid-cols-2">{configNumber("device_order_limit_per_hour", t("admin.deviceOrderLimit"))}{configNumber("device_login_limit_per_hour", t("admin.deviceLoginLimit"))}{configNumber("device_register_limit_per_hour", t("admin.deviceRegisterLimit"))}</div></div>
            </SettingsSection>
          </div>
          <div className="flex justify-end"><Button type="button" onClick={() => void handleSaveConfig()} disabled={saving || toggling || refreshing}><Save className="h-4 w-4" aria-hidden="true" />{saving ? t("admin.saving") : t("admin.saveRiskConfig")}</Button></div>
        </TabsContent>

        <TabsContent value="flagged">
          <DataTableShell footer={<AdminPagination page={flaggedPage} total={flaggedTotal} pageSize={PAGE_SIZE} onPageChange={setFlaggedPage} />}>
            <table className="w-full min-w-[760px] text-sm"><thead><tr className="border-b border-border-subtle bg-surface-muted"><th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.orderNo")}</th><th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.user")}</th><th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.amount")}</th><th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.statusLabel")}</th><th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.time")}</th><th className="px-4 py-3 text-right font-medium text-muted-foreground">{t("admin.actions")}</th></tr></thead><tbody>
              {flaggedLoading && <TableLoadingState colSpan={6} />}
              {!flaggedLoading && flaggedError && <TableErrorState colSpan={6} onRetry={() => void loadFlaggedOrders()} />}
              {!flaggedLoading && !flaggedError && flaggedOrders.length === 0 && <TableEmptyState colSpan={6}>{t("admin.noFlaggedOrders")}</TableEmptyState>}
              {!flaggedLoading && !flaggedError && flaggedOrders.map((order) => { const identity = formatAdminOrderCustomer(order, t("admin.guest")); return <tr key={order.id} className="border-b border-border-subtle last:border-0 hover:bg-surface-muted/60"><td data-label={t("admin.orderNo")} className="px-4 py-3 font-mono text-xs">{order.order_no}</td><td data-label={t("admin.user")} className="px-4 py-3"><span>{identity.primary}</span>{identity.secondary && <span className="block text-xs text-muted-foreground">{identity.secondary}</span>}</td><td data-label={t("admin.amount")} className="px-4 py-3 font-medium">{formatYuan(order.actual_amount)}</td><td data-label={t("admin.statusLabel")} className="px-4 py-3"><OrderStatusBadge status={order.status} items={order.items} /></td><td data-label={t("admin.time")} className="whitespace-nowrap px-4 py-3 text-muted-foreground">{formatDateTime(order.created_at, locale)}</td><td data-label={t("admin.actions")} className="px-4 py-3 text-right"><Button asChild type="button" variant="ghost" size="icon" title={t("admin.viewDetail")} aria-label={t("admin.viewDetail")}><Link href={`/admin/orders/${order.id}`}><Eye className="h-4 w-4" aria-hidden="true" /></Link></Button></td></tr> })}
            </tbody></table>
          </DataTableShell>
        </TabsContent>
      </Tabs>

    </AdminPage>
  )
}
