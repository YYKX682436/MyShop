"use client"

import { useCallback, useEffect, useRef, useState } from "react"
import Image from "next/image"
import { useRouter } from "next/navigation"
import { AlertTriangle, Loader2, Save, Trash2, Upload } from "lucide-react"
import { toast } from "sonner"

import { AnnouncementManagement } from "@/components/shared/announcement-management"
import { MediaPicker } from "@/components/admin/media-library"
import { AdminPage, PageHeader, SettingsSection } from "@/components/admin/shared"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Switch } from "@/components/ui/switch"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { Textarea } from "@/components/ui/textarea"
import { useLocale, useSiteConfig } from "@/lib/context"
import { useAdminPageRefresh, useRequestGuard } from "@/lib/admin-page-requests"
import { cn } from "@/lib/utils"
import { ALLOWED_IMAGE_ACCEPT, validateImageFile } from "@/lib/image-upload"
import { adminConfigApi, adminProductApi, requestOrThrow } from "@/services/api"
import type { SiteConfigKV, UserNumberConfig } from "@/types"

type TabKey = "basic" | "announcement" | "user" | "maintenance"

export default function AdminSiteConfigPage() {
  const { t } = useLocale()
  const { refresh: refreshSiteConfig } = useSiteConfig()
  const router = useRouter()
  const [tab, setTab] = useState<TabKey>("basic")
  const [configMap, setConfigMap] = useState<Record<string, string>>({})
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)
  const [saving, setSaving] = useState(false)
  const [faviconUploading, setFaviconUploading] = useState(false)
  const [maintenanceToggling, setMaintenanceToggling] = useState(false)
  const [userNumberConfig, setUserNumberConfig] = useState<UserNumberConfig>({ start_user_no: 10000, next_user_no: 10000, has_existing_users: false })
  const [userNumberLoading, setUserNumberLoading] = useState(true)
  const [userNumberSaving, setUserNumberSaving] = useState(false)
  const [hasConfig, setHasConfig] = useState(false)
  const [hasNumbering, setHasNumbering] = useState(false)
  const [userNumberError, setUserNumberError] = useState(false)
  const [settingsDirty, setSettingsDirty] = useState(false)
  const [numberDirty, setNumberDirty] = useState(false)
  const settingsVersion = useRef(0)
  const numberVersion = useRef(0)
  const startRequest = useRequestGuard()

  const load = useCallback(async () => {
    const current = startRequest()
    const configVersion = settingsVersion.current
    const numberingVersion = numberVersion.current
    setLoading(true)
    setError(false)
    setUserNumberLoading(true)
    setUserNumberError(false)
    const [data, numbering] = await Promise.allSettled([
        requestOrThrow(() => adminConfigApi.get()),
        requestOrThrow(() => adminConfigApi.getUserNumbering()),
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
    setUserNumberLoading(false)
    setLoading(false)
  }, [startRequest])

  useEffect(() => { void load() }, [load])
  const { refresh, refreshing } = useAdminPageRefresh(load)
  const requestRefresh = () => {
    if ((settingsDirty || numberDirty) && !window.confirm(t("admin.refreshDiscardDraft"))) return
    void refresh()
  }

  const getValue = (key: string) => configMap[key] ?? ""
  const setValue = (key: string, value: string) => {
    settingsVersion.current += 1
    setSettingsDirty(true)
    setConfigMap((current) => ({ ...current, [key]: value }))
  }
  const getBool = (key: string) => getValue(key) === "true"
  const handleSave = async () => {
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
      const result = await adminProductApi.uploadImage(file)
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

  const header = <PageHeader title={t("admin.siteConfig")} description={t("admin.siteConfigDesc")} onRefresh={requestRefresh} refreshing={refreshing} refreshDisabled={saving || userNumberSaving || faviconUploading || maintenanceToggling} />
  if (loading && !hasConfig) return <AdminPage>{header}<div className="h-64 animate-pulse rounded-card border border-border bg-muted" role="status" aria-label={t("common.loading")} /></AdminPage>
  if (error && !hasConfig) return <AdminPage>{header}<div className="flex flex-col items-center gap-3 rounded-card border border-destructive/30 bg-destructive/5 px-4 py-12 text-center text-sm text-destructive"><span>{t("admin.loadError")}</span><Button type="button" variant="outline" onClick={requestRefresh}>{t("common.retry")}</Button></div></AdminPage>

  return (
    <AdminPage>
      {header}
      {error && <div role="alert" className="flex items-center gap-3 text-sm text-destructive">{t("admin.loadError")}<Button type="button" variant="outline" size="sm" onClick={requestRefresh}>{t("common.retry")}</Button></div>}
      <Tabs value={tab} onValueChange={(value) => setTab(value as TabKey)}>
        <TabsList className="max-w-full justify-start overflow-x-auto"><TabsTrigger value="basic">{t("admin.basicInfo")}</TabsTrigger><TabsTrigger value="announcement">{t("admin.announcementTab")}</TabsTrigger><TabsTrigger value="user">{t("admin.userSettingsTab")}</TabsTrigger><TabsTrigger value="maintenance">{t("admin.maintenanceTab")}</TabsTrigger></TabsList>

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
                  <MediaPicker disabled={saving || faviconUploading} onSelect={(item) => setValue("favicon_url", item.url)} />
                  {getValue("favicon_url") && <Button type="button" variant="ghost" disabled={saving || faviconUploading} onClick={() => setValue("favicon_url", "")}><Trash2 className="size-4" aria-hidden="true" />{t("admin.faviconRemove")}</Button>}
                </div>
              </div>
            </div>
            {saveButton}
          </SettingsSection>
        </TabsContent>

        <TabsContent value="announcement">
          <SettingsSection title={t("admin.announcementTab")}>
            <AnnouncementManagement scope="admin" />
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
        </TabsContent>

        <TabsContent value="maintenance">
          <SettingsSection title={t("admin.maintenanceTab")} description={t("admin.maintenanceLabelDesc")}>
            <div className="max-w-3xl space-y-5"><div className="admin-warning-callout flex items-start gap-3 rounded-card p-4"><AlertTriangle className="mt-0.5 h-5 w-5 shrink-0 text-warning" aria-hidden="true" /><div><p className="text-sm font-semibold">{t("admin.maintenanceWarning")}</p><p className="mt-1 text-xs opacity-90">{t("admin.maintenanceWarningDesc")}</p></div></div><div className="flex items-center justify-between rounded-control border border-border-subtle bg-surface-muted/60 px-3.5 py-3"><div><p className="text-sm font-medium">{t("admin.maintenanceLabel")}</p><p className="text-xs text-muted-foreground">{t("admin.maintenanceLabelDesc")}</p></div><Switch checked={getBool("maintenance_enabled")} onCheckedChange={(checked) => void toggleMaintenance(checked)} disabled={maintenanceToggling || saving || refreshing} aria-label={t("admin.maintenanceLabel")} /></div><label className="flex flex-col gap-1.5 text-sm font-medium"><span>{t("admin.maintenanceMessage")}</span><Textarea value={getValue("maintenance_message")} onChange={(event) => setValue("maintenance_message", event.target.value)} /></label></div>
            {saveButton}
          </SettingsSection>
        </TabsContent>
      </Tabs>
    </AdminPage>
  )
}
