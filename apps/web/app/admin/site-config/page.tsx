"use client"

import { useCallback, useEffect, useRef, useState } from "react"
import Image from "next/image"
import { useRouter } from "next/navigation"
import { AlertTriangle, Loader2, Plus, Save, Trash2, Upload } from "lucide-react"
import { toast } from "sonner"

import { NotificationManagement } from "@/components/shared/announcement-management"
import { MediaPicker } from "@/components/admin/media-library"
import { useRouteOverlayClose } from "@/components/admin/route-overlay"
import { AdminPage, PageHeader, SettingsSection } from "@/components/admin/shared"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Switch } from "@/components/ui/switch"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { Textarea } from "@/components/ui/textarea"
import { useAuth, useLocale, useSiteConfig } from "@/lib/context"
import { useAdminPageRefresh, useRequestGuard } from "@/lib/admin-page-requests"
import { cn } from "@/lib/utils"
import { ALLOWED_IMAGE_ACCEPT, validateImageFile } from "@/lib/image-upload"
import { uploadAdminImage } from "@/lib/admin-image-upload"
import { adminConfigApi, requestOrThrow } from "@/services/api"
import type { SiteConfigKV, UserNumberConfig } from "@/types"
import { normalizeAppShellConfig, normalizeAppShellPattern, selectAppShellPattern, type AppShellConfig } from "@/lib/app-shell-config"

type TabKey = "basic" | "notifications" | "user" | "maintenance" | "appShell"

export default function AdminSiteConfigPage() {
  const { t, locale } = useLocale()
  const { config: siteConfig, refresh: refreshSiteConfig } = useSiteConfig()
  const { user } = useAuth()
  const router = useRouter()
  const [tab, setTab] = useState<TabKey>("basic")
  const [isMainSite, setIsMainSite] = useState(false)
  const [configMap, setConfigMap] = useState<Record<string, string>>({})
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)
  const [saving, setSaving] = useState(false)
  const [appShellConfig, setAppShellConfig] = useState<AppShellConfig>({ enabled: false, rules: [] })
  const [appShellLoading, setAppShellLoading] = useState(false)
  const [appShellError, setAppShellError] = useState(false)
  const [appShellSaving, setAppShellSaving] = useState(false)
  const [faviconUploading, setFaviconUploading] = useState(false)
  const [notificationUploading, setNotificationUploading] = useState(false)
  const [maintenanceToggling, setMaintenanceToggling] = useState(false)
  const [userNumberConfig, setUserNumberConfig] = useState<UserNumberConfig>({ start_user_no: 10000, next_user_no: 10000, has_existing_users: false })
  const [userNumberLoading, setUserNumberLoading] = useState(true)
  const [userNumberSaving, setUserNumberSaving] = useState(false)
  const [hasConfig, setHasConfig] = useState(false)
  const [hasNumbering, setHasNumbering] = useState(false)
  const [userNumberError, setUserNumberError] = useState(false)
  const [settingsDirty, setSettingsDirty] = useState(false)
  const [numberDirty, setNumberDirty] = useState(false)
  const [appShellDirty, setAppShellDirty] = useState(false)
  const settingsVersion = useRef(0)
  const numberVersion = useRef(0)
  const appShellVersionRef = useRef(0)
  const startRequest = useRequestGuard()
  const canManageAppShell = user?.role === "SUPER_ADMIN" && isMainSite

  useEffect(() => {
    if (!siteConfig?.main_site_url) { setIsMainSite(false); return }
    try {
      setIsMainSite(new URL(siteConfig.main_site_url).hostname.toLowerCase() === window.location.hostname.toLowerCase())
    } catch {
      setIsMainSite(false)
    }
  }, [siteConfig?.main_site_url])

  useRouteOverlayClose(settingsDirty || numberDirty || appShellDirty, true, notificationUploading || appShellSaving)

  const load = useCallback(async () => {
    const current = startRequest()
    const configVersion = settingsVersion.current
    const numberingVersion = numberVersion.current
    const appShellVersion = appShellVersionRef.current
    setLoading(true)
    setError(false)
    setUserNumberLoading(true)
    setUserNumberError(false)
    setAppShellLoading(canManageAppShell)
    setAppShellError(false)
    const [data, numbering, appShell] = await Promise.allSettled([
        requestOrThrow(() => adminConfigApi.get()),
        requestOrThrow(() => adminConfigApi.getUserNumbering()),
        canManageAppShell ? requestOrThrow(() => adminConfigApi.getAppShell()) : Promise.resolve(null),
    ])
    if (!current()) return
    if (data.status === "fulfilled" && configVersion === settingsVersion.current) {
      const map: Record<string, string> = {}
      data.value.forEach((item: SiteConfigKV) => { map[item.config_key] = item.config_value })
      setConfigMap(map)
      setSettingsDirty(false)
      setHasConfig(true)
    } else {
      setError(true)
    }
    if (numbering.status === "fulfilled" && numberingVersion === numberVersion.current) {
      setUserNumberConfig(numbering.value)
      setHasNumbering(true)
      setNumberDirty(false)
    } else setUserNumberError(true)
    if (canManageAppShell) {
      if (appShell.status === "fulfilled" && appShell.value && appShellVersion === appShellVersionRef.current) {
        setAppShellConfig(appShell.value)
        setAppShellDirty(false)
      } else setAppShellError(true)
      setAppShellLoading(false)
    }
    setUserNumberLoading(false)
    setLoading(false)
  }, [canManageAppShell, startRequest])

  useEffect(() => { void load() }, [load])
  const { refresh, refreshing } = useAdminPageRefresh(load)
  const requestRefresh = () => {
    if (notificationUploading) { toast.error("请等待图片上传完成"); return }
    if ((settingsDirty || numberDirty || appShellDirty) && !window.confirm(t("admin.refreshDiscardDraft"))) return
    void refresh()
  }

  const getValue = (key: string) => configMap[key] ?? ""
  const setValue = (key: string, value: string) => {
    settingsVersion.current += 1
    setSettingsDirty(true)
    setConfigMap((current) => ({ ...current, [key]: value }))
  }
  const updateAppShell = (update: (current: AppShellConfig) => AppShellConfig) => {
    appShellVersionRef.current += 1
    setAppShellDirty(true)
    setAppShellConfig(update)
  }
  const saveAppShell = async () => {
    let normalized: AppShellConfig
    try {
      normalized = normalizeAppShellConfig(appShellConfig)
    } catch {
      toast.error(t("admin.appShellRuleInvalid"))
      return
    }
    const version = appShellVersionRef.current
    setAppShellSaving(true)
    try {
      const saved = await requestOrThrow(() => adminConfigApi.updateAppShell(normalized))
      if (version === appShellVersionRef.current) {
        setAppShellConfig(saved)
        setAppShellDirty(false)
      }
      toast.success(t("admin.appShellSaved"))
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.saveFailed"))
    } finally {
      setAppShellSaving(false)
    }
  }
  const addAppShellRule = () => updateAppShell((current) => ({
    ...current,
    rules: [...current.rules, { domain_pattern: "", site_type: "EXTERNAL", enabled: true, note: "" }],
  }))
  const updateAppShellRule = (index: number, patch: Partial<AppShellConfig["rules"][number]>) => updateAppShell((current) => ({
    ...current,
    rules: current.rules.map((rule, ruleIndex) => ruleIndex === index ? { ...rule, ...patch } : rule),
  }))
  const removeAppShellRule = (index: number) => {
    const target = appShellConfig.rules[index]
    if (!target) return
    const rules = appShellConfig.rules.filter((_, ruleIndex) => ruleIndex !== index)
    const targetDomain = normalizeAppShellPattern(target.domain_pattern)
    if (targetDomain && !targetDomain.startsWith("*.")) {
      const normalizedRules = rules.map((rule) => normalizeAppShellPattern(rule.domain_pattern)).filter((rule): rule is string => rule !== null)
      const fallback = selectAppShellPattern(normalizedRules, targetDomain)
      if (fallback?.startsWith("*.") && !window.confirm(t("admin.appShellDeleteFallback")
        .replace("{domain}", targetDomain).replace("{rule}", fallback))) return
    }
    updateAppShell((current) => ({ ...current, rules }))
  }
  const getBool = (key: string) => getValue(key) === "true"
  const handleSave = async () => {
    const rechargeMin = Number(getValue("recharge_min_amount") || "10")
    const rechargeMax = Number(getValue("recharge_max_amount") || "5000")
    if (!Number.isFinite(rechargeMin) || !Number.isFinite(rechargeMax)
      || rechargeMin <= 0 || rechargeMax < rechargeMin || rechargeMax > 9999999999.99
      || !/^\d+(\.\d{1,2})?$/.test(getValue("recharge_min_amount") || "10")
      || !/^\d+(\.\d{1,2})?$/.test(getValue("recharge_max_amount") || "5000")) {
      toast.error(t("admin.rechargeAmountInvalid"))
      return
    }
    const version = settingsVersion.current
    setSaving(true)
    try {
      const configs = Object.entries(configMap).map(([config_key, config_value]) => ({ config_key, config_value }))
      await requestOrThrow(() => adminConfigApi.update({ configs }))
      if (version === settingsVersion.current) setSettingsDirty(false)
      try {
        const saved = await requestOrThrow(() => adminConfigApi.get())
        if (version === settingsVersion.current) {
          const map: Record<string, string> = {}
          saved.forEach((item: SiteConfigKV) => { map[item.config_key] = item.config_value })
          setConfigMap(map)
        }
      } catch { setError(true) }
      await refreshSiteConfig().catch(() => {})
      router.refresh()
      toast.success(t("admin.settingsSaved"))
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.saveFailed"))
    } finally {
      setSaving(false)
    }
  }

  const saveButton = <div className="flex justify-end border-t border-border pt-5"><Button type="button" onClick={() => void handleSave()} disabled={saving || refreshing || maintenanceToggling || faviconUploading}><Save className="h-4 w-4" aria-hidden="true" />{saving ? t("admin.saving") : t("admin.saveSettings")}</Button></div>

  const uploadFavicon = async (file: File | undefined) => {
    if (!file) return
    const validationError = validateImageFile(file)
    if (validationError) { toast.error(validationError); return }
    setFaviconUploading(true)
    try {
      const result = await uploadAdminImage(file, locale)
      setValue("favicon_url", result.url)
      toast.success(t("admin.uploadSuccess"))
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.uploadFailed"))
    } finally {
      setFaviconUploading(false)
    }
  }

  const toggleMaintenance = async (enabled: boolean) => {
    setMaintenanceToggling(true)
    settingsVersion.current += 1
    try {
      await requestOrThrow(() => adminConfigApi.toggleMaintenance(enabled))
      setConfigMap((current) => ({ ...current, maintenance_enabled: String(enabled) }))
      try {
        const saved = await requestOrThrow(() => adminConfigApi.get())
        const value = saved.find((item) => item.config_key === "maintenance_enabled")?.config_value
        if (value !== undefined) setConfigMap((current) => ({ ...current, maintenance_enabled: value }))
      } catch { setError(true) }
      toast.success(enabled ? t("admin.maintenanceEnabled") : t("admin.maintenanceDisabled"))
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.saveFailed"))
    } finally {
      setMaintenanceToggling(false)
    }
  }

  const updateUserNumber = (key: "start_user_no" | "next_user_no", value: string) => {
    numberVersion.current += 1
    setNumberDirty(true)
    const parsed = Number(value)
    setUserNumberConfig((current) => ({ ...current, [key]: Number.isFinite(parsed) ? parsed : 0 }))
  }

  const handleSaveUserNumbering = async () => {
    if (!Number.isSafeInteger(userNumberConfig.start_user_no) || userNumberConfig.start_user_no < 1) {
      toast.error(t("admin.userNumberPositive"))
      return
    }
    if (!Number.isSafeInteger(userNumberConfig.next_user_no)
      || (userNumberConfig.has_existing_users
        ? userNumberConfig.next_user_no <= userNumberConfig.start_user_no
        : userNumberConfig.next_user_no !== userNumberConfig.start_user_no)) {
      toast.error(t("admin.userNumberNextInvalid"))
      return
    }
    const version = numberVersion.current
    setUserNumberSaving(true)
    try {
      await requestOrThrow(() => adminConfigApi.updateUserNumbering({
        start_user_no: userNumberConfig.start_user_no,
        next_user_no: userNumberConfig.next_user_no,
      }))
      if (version === numberVersion.current) setNumberDirty(false)
      try {
        const saved = await requestOrThrow(() => adminConfigApi.getUserNumbering())
        if (version === numberVersion.current) { setUserNumberConfig(saved); setUserNumberError(false) }
      } catch { setUserNumberError(true) }
      toast.success(t("admin.settingsSaved"))
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.saveFailed"))
    } finally {
      setUserNumberSaving(false)
    }
  }

  const header = <PageHeader title={t("admin.siteConfig")} description={t("admin.siteConfigDesc")} onRefresh={requestRefresh} refreshing={refreshing} refreshDisabled={saving || userNumberSaving || faviconUploading || maintenanceToggling || notificationUploading || appShellSaving} />
  if (loading && !hasConfig) return <AdminPage>{header}<div className="h-64 animate-pulse rounded-card border border-border bg-muted" role="status" aria-label={t("common.loading")} /></AdminPage>
  if (error && !hasConfig) return <AdminPage>{header}<div className="flex flex-col items-center gap-3 rounded-card border border-destructive/30 bg-destructive/5 px-4 py-12 text-center text-sm text-destructive"><span>{t("admin.loadError")}</span><Button type="button" variant="outline" onClick={requestRefresh}>{t("common.retry")}</Button></div></AdminPage>

  return (
    <AdminPage>
      {header}
      {error && <div role="alert" className="flex items-center gap-3 text-sm text-destructive">{t("admin.loadError")}<Button type="button" variant="outline" size="sm" onClick={requestRefresh}>{t("common.retry")}</Button></div>}
      <Tabs value={tab} onValueChange={(value) => { if (!notificationUploading) setTab(value as TabKey) }}>
        <TabsList className="max-w-full justify-start overflow-x-auto"><TabsTrigger value="basic">{t("admin.basicInfo")}</TabsTrigger><TabsTrigger value="notifications">{t("admin.notificationTab")}</TabsTrigger><TabsTrigger value="user">{t("admin.userSettingsTab")}</TabsTrigger><TabsTrigger value="maintenance">{t("admin.maintenanceTab")}</TabsTrigger>{canManageAppShell && <TabsTrigger value="appShell">{t("admin.appShellTab")}</TabsTrigger>}</TabsList>

        <TabsContent value="basic">
          <SettingsSection title={t("admin.basicInfo")} description={t("admin.siteConfigDesc")}>
            <div className="grid max-w-3xl gap-5 md:grid-cols-2">
              <label className="flex flex-col gap-1.5 text-sm font-medium"><span>{t("admin.siteName")}</span><Input value={getValue("site_name")} onChange={(event) => setValue("site_name", event.target.value)} /></label>
              <div className="space-y-3 md:col-span-2">
                <p className="text-sm font-medium">{t("admin.favicon")}</p>
                <div className="flex flex-wrap items-center gap-3">
                  <Image src={getValue("favicon_url") || "/default-icon.svg"} alt={t("admin.faviconPreview")} width={64} height={64} unoptimized className="size-16 rounded-control border border-border-subtle bg-surface object-contain p-1" />
                  <label className={cn("inline-flex min-h-11 cursor-pointer items-center gap-2 rounded-control border border-input px-3 text-sm hover:bg-accent", faviconUploading && "pointer-events-none opacity-50")}>
                    {faviconUploading ? <Loader2 className="size-4 animate-spin" aria-hidden="true" /> : <Upload className="size-4" aria-hidden="true" />}
                    {getValue("favicon_url") ? t("admin.faviconReplace") : t("admin.faviconUpload")}
                    <input type="file" accept={ALLOWED_IMAGE_ACCEPT} className="sr-only" disabled={faviconUploading || saving} onChange={(event) => { void uploadFavicon(event.target.files?.[0]); event.target.value = "" }} />
                  </label>
                  <MediaPicker disabled={saving || faviconUploading} onSelect={(item) => { if (item.url) setValue("favicon_url", item.url) }} />
                  {getValue("favicon_url") && <Button type="button" variant="ghost" disabled={saving || faviconUploading} onClick={() => setValue("favicon_url", "")}><Trash2 className="size-4" aria-hidden="true" />{t("admin.faviconRemove")}</Button>}
                </div>
              </div>
            </div>
            {saveButton}
          </SettingsSection>
        </TabsContent>

        <TabsContent value="notifications">
          <SettingsSection title={t("admin.notificationTab")}>
            <NotificationManagement scope="admin" onUploadingChange={setNotificationUploading} />
          </SettingsSection>
        </TabsContent>

        <TabsContent value="user">
          <SettingsSection title={t("admin.userSettingsTab")} description={t("admin.userNumberSettingsDesc")}>
            {userNumberLoading ? (
              <div className="h-32 animate-pulse rounded-card bg-muted" role="status" aria-label={t("common.loading")} />
            ) : userNumberError && !hasNumbering ? (
              <div role="alert" className="flex items-center gap-3 text-sm text-destructive">{t("admin.loadError")}<Button type="button" variant="outline" size="sm" onClick={requestRefresh}>{t("common.retry")}</Button></div>
            ) : (
              <div className="max-w-2xl space-y-5">
                {userNumberError && <div role="alert" className="flex items-center gap-3 text-sm text-destructive">{t("admin.loadError")}<Button type="button" variant="outline" size="sm" onClick={requestRefresh}>{t("common.retry")}</Button></div>}
                <div className="rounded-control border border-blue-500/20 bg-blue-500/10 p-4 text-sm text-blue-800 dark:border-blue-500/30 dark:bg-blue-500/15 dark:text-blue-200">
                  {t("admin.userNumberSettingsHint")}
                </div>
                <div className="grid gap-5 sm:grid-cols-2">
                  <label className="flex flex-col gap-1.5 text-sm font-medium">
                    <span>{t("admin.userNumberStart")}</span>
                    <Input type="number" min={1} step={1} value={userNumberConfig.start_user_no} onChange={(event) => updateUserNumber("start_user_no", event.target.value)} disabled={userNumberSaving || userNumberConfig.has_existing_users} />
                    {userNumberConfig.has_existing_users && <span className="text-xs font-normal text-muted-foreground">{t("admin.userNumberStartReadonly")}</span>}
                  </label>
                  <label className="flex flex-col gap-1.5 text-sm font-medium">
                    <span>{t("admin.userNumberNext")}</span>
                    <Input type="number" min={1} step={1} value={userNumberConfig.next_user_no} onChange={(event) => updateUserNumber("next_user_no", event.target.value)} disabled={userNumberSaving} />
                  </label>
                </div>
                <div className="flex justify-end border-t border-border pt-5">
                  <Button type="button" onClick={() => void handleSaveUserNumbering()} disabled={userNumberSaving || refreshing}>
                    <Save className="h-4 w-4" aria-hidden="true" />
                    {userNumberSaving ? t("admin.saving") : t("admin.saveSettings")}
                  </Button>
                </div>
              </div>
            )}
          </SettingsSection>
          <SettingsSection title={t("admin.loginAndAttention")}>
            <div className="max-w-2xl space-y-5">
              <label className="flex flex-col gap-1.5 text-sm font-medium">
                <span>{t("admin.loginDuration")}</span>
                <select className="h-10 rounded-control border border-input bg-surface px-3 text-sm" value={getValue("login_duration_days") || "30"} onChange={(event) => setValue("login_duration_days", event.target.value)} disabled={saving || refreshing}>
                  <option value="15">{t("admin.loginDuration15")}</option>
                  <option value="30">{t("admin.loginDuration30")}</option>
                  <option value="90">{t("admin.loginDuration90")}</option>
                  <option value="365">{t("admin.loginDurationLong")}</option>
                </select>
              </label>
              <div className="flex items-center justify-between gap-4 rounded-control border border-border-subtle bg-surface-muted/60 px-3.5 py-3">
                <span className="text-sm font-medium">{t("admin.stockAttentionEnabled")}</span>
                <Switch checked={getBool("low_stock_attention_enabled")} onCheckedChange={(checked) => setValue("low_stock_attention_enabled", String(checked))} disabled={saving || refreshing} aria-label={t("admin.stockAttentionEnabled")} />
              </div>
              {saveButton}
            </div>
          </SettingsSection>
          <SettingsSection title={t("admin.rechargeTitle")} description={t("admin.rechargeSettingsHint")}>
            <div className="max-w-2xl space-y-5">
              <div className="flex items-center justify-between gap-4 rounded-control border border-border-subtle bg-surface-muted/60 px-3.5 py-3">
                <span className="text-sm font-medium">{t("admin.rechargeEnabled")}</span>
                <Switch checked={getBool("recharge_enabled")} onCheckedChange={(checked) => setValue("recharge_enabled", String(checked))} disabled={saving || refreshing} aria-label={t("admin.rechargeEnabled")} />
              </div>
              <div className="grid gap-5 sm:grid-cols-2">
                <label className="flex flex-col gap-1.5 text-sm font-medium"><span>{t("admin.rechargeMin")}</span><Input type="number" min="0.01" step="0.01" value={getValue("recharge_min_amount") || "10.00"} onChange={(event) => setValue("recharge_min_amount", event.target.value || "10.00")} disabled={saving || refreshing} /></label>
                <label className="flex flex-col gap-1.5 text-sm font-medium"><span>{t("admin.rechargeMax")}</span><Input type="number" min="0.01" step="0.01" value={getValue("recharge_max_amount") || "5000.00"} onChange={(event) => setValue("recharge_max_amount", event.target.value || "5000.00")} disabled={saving || refreshing} /></label>
              </div>
              {saveButton}
            </div>
          </SettingsSection>
        </TabsContent>

        <TabsContent value="maintenance">
          <SettingsSection title={t("admin.maintenanceTab")} description={t("admin.maintenanceLabelDesc")}>
            <div className="max-w-3xl space-y-5"><div className="admin-warning-callout flex items-start gap-3 rounded-card p-4"><AlertTriangle className="mt-0.5 h-5 w-5 shrink-0 text-warning" aria-hidden="true" /><div><p className="text-sm font-semibold">{t("admin.maintenanceWarning")}</p><p className="mt-1 text-xs opacity-90">{t("admin.maintenanceWarningDesc")}</p></div></div><div className="flex items-center justify-between rounded-control border border-border-subtle bg-surface-muted/60 px-3.5 py-3"><div><p className="text-sm font-medium">{t("admin.maintenanceLabel")}</p><p className="text-xs text-muted-foreground">{t("admin.maintenanceLabelDesc")}</p></div><Switch checked={getBool("maintenance_enabled")} onCheckedChange={(checked) => void toggleMaintenance(checked)} disabled={maintenanceToggling || saving || refreshing} aria-label={t("admin.maintenanceLabel")} /></div><label className="flex flex-col gap-1.5 text-sm font-medium"><span>{t("admin.maintenanceMessage")}</span><Textarea value={getValue("maintenance_message")} onChange={(event) => setValue("maintenance_message", event.target.value)} /></label></div>
            {saveButton}
          </SettingsSection>
        </TabsContent>

        {canManageAppShell && <TabsContent value="appShell">
          <SettingsSection title={t("admin.appShellTitle")}>
            {appShellLoading ? (
              <div className="h-32 animate-pulse rounded-card bg-muted" role="status" aria-label={t("common.loading")} />
            ) : appShellError ? (
              <div role="alert" className="flex items-center gap-3 text-sm text-destructive">{t("admin.appShellLoadError")}<Button type="button" variant="outline" size="sm" onClick={requestRefresh}>{t("common.retry")}</Button></div>
            ) : (
              <div className="space-y-5">
                <div className="flex items-center justify-between gap-4 rounded-control border border-border-subtle bg-surface-muted/60 px-3.5 py-3">
                  <span className="text-sm font-medium">{t("admin.appShellEnabled")}</span>
                  <Switch checked={appShellConfig.enabled} onCheckedChange={(enabled) => updateAppShell((current) => ({ ...current, enabled }))} disabled={appShellSaving || refreshing} aria-label={t("admin.appShellEnabled")} />
                </div>
                <div className="space-y-3">
                  <div className="flex items-center justify-between gap-3"><h3 className="text-sm font-semibold">{t("admin.appShellDomain")}</h3><Button type="button" variant="outline" size="sm" onClick={addAppShellRule} disabled={appShellSaving || refreshing}><Plus className="size-4" aria-hidden="true" />{t("admin.appShellAddRule")}</Button></div>
                  {appShellConfig.rules.map((rule, index) => <div key={`${index}:${rule.domain_pattern}`} className="grid gap-3 rounded-card border border-border-subtle bg-surface p-3 sm:grid-cols-[minmax(0,1fr)_170px_auto]">
                    <label className="flex min-w-0 flex-col gap-1.5 text-sm font-medium"><span>{t("admin.appShellDomain")}</span><Input value={rule.domain_pattern} placeholder={t("admin.appShellRulePlaceholder")} maxLength={260} onChange={(event) => updateAppShellRule(index, { domain_pattern: event.target.value })} disabled={appShellSaving || refreshing} /></label>
                    <label className="flex flex-col gap-1.5 text-sm font-medium"><span>{t("admin.appShellType")}</span><select className="h-10 rounded-control border border-input bg-surface px-3 text-sm" value={rule.site_type} onChange={(event) => updateAppShellRule(index, { site_type: event.target.value as "MYSHOP" | "EXTERNAL" })} disabled={appShellSaving || refreshing}><option value="MYSHOP">{t("admin.appShellMyShop")}</option><option value="EXTERNAL">{t("admin.appShellExternal")}</option></select></label>
                    <div className="flex items-end justify-between gap-2 sm:justify-end"><label className="flex h-10 items-center gap-2 text-sm"><Switch checked={rule.enabled} onCheckedChange={(enabled) => updateAppShellRule(index, { enabled })} disabled={appShellSaving || refreshing} aria-label={`${t("admin.appShellRuleEnabled")}: ${rule.domain_pattern || index + 1}`} /><span>{t("admin.appShellRuleEnabled")}</span></label><Button type="button" variant="ghost" size="icon" aria-label={t("admin.appShellDeleteRule")} onClick={() => removeAppShellRule(index)} disabled={appShellSaving || refreshing}><Trash2 className="size-4" aria-hidden="true" /></Button></div>
                    <label className="flex flex-col gap-1.5 text-sm font-medium sm:col-span-3"><span>{t("admin.appShellNote")}</span><Input value={rule.note} maxLength={100} onChange={(event) => updateAppShellRule(index, { note: event.target.value })} disabled={appShellSaving || refreshing} /></label>
                  </div>)}
                </div>
                <div className="flex justify-end border-t border-border pt-5"><Button type="button" onClick={() => void saveAppShell()} disabled={appShellSaving || refreshing || !appShellDirty}><Save className="h-4 w-4" aria-hidden="true" />{appShellSaving ? t("admin.saving") : t("admin.appShellSave")}</Button></div>
              </div>
            )}
          </SettingsSection>
        </TabsContent>}
      </Tabs>
    </AdminPage>
  )
}
