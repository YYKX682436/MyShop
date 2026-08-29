"use client"

import { useCallback, useEffect, useState } from "react"
import { AlertTriangle, ImagePlus, Loader2, Plus, Save, Trash2, Upload } from "lucide-react"
import { toast } from "sonner"

import { MarkdownEditor } from "@/components/admin/markdown-editor"
import { AdminPage, PageHeader, SettingsSection } from "@/components/admin/shared"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Switch } from "@/components/ui/switch"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { Textarea } from "@/components/ui/textarea"
import { useLocale } from "@/lib/context"
import { cn } from "@/lib/utils"
import { MAX_CONTACT_ITEMS, parseContactItemsSafely, serializeContactItems } from "@/lib/contact-items"
import { ALLOWED_IMAGE_ACCEPT, QR_ALLOWED_IMAGE_ACCEPT, validateImageFile, validateQrImageFile } from "@/lib/image-upload"
import { adminConfigApi, adminProductApi, requestOrThrow } from "@/services/api"
import type { ContactItem, SiteConfigKV } from "@/types"

type TabKey = "basic" | "announcement" | "contact" | "maintenance"

export default function AdminSiteConfigPage() {
  const { t } = useLocale()
  const [tab, setTab] = useState<TabKey>("basic")
  const [configMap, setConfigMap] = useState<Record<string, string>>({})
  const [contactItems, setContactItems] = useState<ContactItem[]>([])
  const [contactConfigError, setContactConfigError] = useState(false)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)
  const [saving, setSaving] = useState(false)
  const [logoUploading, setLogoUploading] = useState(false)
  const [qrUploadingIndex, setQrUploadingIndex] = useState<number | null>(null)
  const [maintenanceToggling, setMaintenanceToggling] = useState(false)
  const popupContent = getValueFromMap(configMap, "popup_content")

  const load = useCallback(async () => {
    setLoading(true)
    setError(false)
    setContactConfigError(false)
    try {
      const data = await requestOrThrow(() => adminConfigApi.get())
      const map: Record<string, string> = {}
      data.forEach((item: SiteConfigKV) => { map[item.config_key] = item.config_value })
      const parsedContacts = parseContactItemsSafely(map.contact_items)
      if (!parsedContacts.hasError) map.contact_items = serializeContactItems(parsedContacts.items)
      setContactItems(parsedContacts.items)
      setContactConfigError(parsedContacts.hasError)
      setConfigMap(map)
    } catch {
      setConfigMap({})
      setContactItems([])
      setContactConfigError(false)
      setError(true)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { void load() }, [load])

  const getValue = (key: string) => configMap[key] ?? ""
  const setValue = (key: string, value: string) => setConfigMap((current) => ({ ...current, [key]: value }))
  const getBool = (key: string) => getValue(key) === "true"
  const updateContactItems = (updater: (items: ContactItem[]) => ContactItem[]) => {
    setContactConfigError(false)
    setContactItems((current) => {
      const next = updater(current)
      setConfigMap((previous) => ({ ...previous, contact_items: serializeContactItems(next) }))
      return next
    })
  }

  const handleSave = async () => {
    setSaving(true)
    try {
      const configs = Object.entries(configMap).filter(([key]) => key !== "contact_items").map(([config_key, config_value]) => ({ config_key, config_value }))
      configs.push({ config_key: "contact_items", config_value: serializeContactItems(contactItems) })
      await requestOrThrow(() => adminConfigApi.update({ configs }))
      toast.success(t("admin.settingsSaved"))
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.saveFailed"))
    } finally {
      setSaving(false)
    }
  }

  const saveButton = <div className="flex justify-end border-t border-border pt-5"><Button type="button" onClick={() => void handleSave()} disabled={saving}><Save className="h-4 w-4" aria-hidden="true" />{saving ? t("admin.saving") : t("admin.saveSettings")}</Button></div>

  const uploadLogo = async (file: File | undefined) => {
    if (!file) return
    const validationError = validateImageFile(file)
    if (validationError) { toast.error(validationError); return }
    setLogoUploading(true)
    try {
      const result = await adminProductApi.uploadImage(file)
      setValue("logo_url", result.url)
      toast.success(t("admin.uploadSuccess"))
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.uploadFailed"))
    } finally {
      setLogoUploading(false)
    }
  }

  const uploadContactQr = async (index: number, file: File | undefined) => {
    if (!file) return
    const validationError = validateQrImageFile(file)
    if (validationError) { toast.error(validationError); return }
    setQrUploadingIndex(index)
    try {
      const result = await adminProductApi.uploadImage(file)
      updateContactItems((items) => items.map((item, itemIndex) => itemIndex === index ? { ...item, qr_url: result.url } : item))
      toast.success(t("admin.uploadSuccess"))
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.uploadFailed"))
    } finally {
      setQrUploadingIndex(null)
    }
  }

  const toggleMaintenance = async (enabled: boolean) => {
    setMaintenanceToggling(true)
    try {
      await requestOrThrow(() => adminConfigApi.toggleMaintenance(enabled))
      setValue("maintenance_enabled", String(enabled))
      toast.success(enabled ? t("admin.maintenanceEnabled") : t("admin.maintenanceDisabled"))
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.saveFailed"))
    } finally {
      setMaintenanceToggling(false)
    }
  }

  if (loading) return <AdminPage><PageHeader title={t("admin.siteConfig")} description={t("admin.siteConfigDesc")} /><div className="h-64 animate-pulse rounded-xl border border-border bg-muted" role="status" aria-label={t("common.loading")} /></AdminPage>
  if (error) return <AdminPage><PageHeader title={t("admin.siteConfig")} description={t("admin.siteConfigDesc")} /><div className="flex flex-col items-center gap-3 rounded-xl border border-destructive/30 bg-destructive/5 px-4 py-12 text-center text-sm text-destructive"><span>{t("admin.loadError")}</span><Button type="button" variant="outline" onClick={() => void load()}>{t("common.retry")}</Button></div></AdminPage>

  return (
    <AdminPage>
      <PageHeader title={t("admin.siteConfig")} description={t("admin.siteConfigDesc")} />
      <Tabs value={tab} onValueChange={(value) => setTab(value as TabKey)}>
        <TabsList className="max-w-full justify-start overflow-x-auto"><TabsTrigger value="basic">{t("admin.basicInfo")}</TabsTrigger><TabsTrigger value="announcement">{t("admin.announcementTab")}</TabsTrigger><TabsTrigger value="contact">{t("admin.contactTab")}</TabsTrigger><TabsTrigger value="maintenance">{t("admin.maintenanceTab")}</TabsTrigger></TabsList>

        <TabsContent value="basic">
          <SettingsSection title={t("admin.basicInfo")} description={t("admin.siteConfigDesc")}>
            <div className="grid max-w-3xl gap-5 md:grid-cols-2">
              <label className="flex flex-col gap-1.5 text-sm font-medium"><span>{t("admin.siteName")}</span><Input value={getValue("site_name")} onChange={(event) => setValue("site_name", event.target.value)} /></label>
              <label className="flex flex-col gap-1.5 text-sm font-medium"><span>{t("admin.siteSlogan")}</span><Input value={getValue("site_slogan")} onChange={(event) => setValue("site_slogan", event.target.value)} placeholder="Unlock Your AI Potential" /><span className="text-xs font-normal text-muted-foreground">{t("admin.siteSloganHint")}</span></label>
              <label className="flex flex-col gap-1.5 text-sm font-medium md:col-span-2"><span>{t("admin.siteDesc")}</span><Textarea value={getValue("site_description")} onChange={(event) => setValue("site_description", event.target.value)} /><span className="text-xs font-normal text-muted-foreground">{t("admin.siteDescHint")}</span></label>
              <div className="flex flex-col gap-1.5 text-sm font-medium md:col-span-2"><span>{t("admin.logoUrl")}</span><div className="flex flex-col gap-3 sm:flex-row"><Input className="min-w-0 flex-1" value={getValue("logo_url")} onChange={(event) => setValue("logo_url", event.target.value)} placeholder="https://..." /><label className={cn("inline-flex h-10 shrink-0 cursor-pointer items-center justify-center gap-1.5 rounded-md border border-input px-3 text-sm hover:bg-accent", logoUploading && "pointer-events-none opacity-50")}>{logoUploading ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" /> : <Upload className="h-4 w-4" aria-hidden="true" />}{logoUploading ? t("admin.uploading") : t("admin.upload")}<input type="file" accept={ALLOWED_IMAGE_ACCEPT} className="hidden" onChange={(event) => { void uploadLogo(event.target.files?.[0]); event.target.value = "" }} /></label></div><span className="text-xs font-normal text-muted-foreground">{t("admin.logoUrlHint")}</span></div>
              <label className="flex flex-col gap-1.5 text-sm font-medium md:col-span-2"><span>{t("admin.footerText")}</span><Input value={getValue("footer_text")} onChange={(event) => setValue("footer_text", event.target.value)} /></label>
            </div>
            {saveButton}
          </SettingsSection>
        </TabsContent>

        <TabsContent value="announcement">
          <SettingsSection title={t("admin.announcementTab")} description={t("admin.popupContent")}>
            <div className="max-w-4xl space-y-5">
              <div className="flex items-center justify-between rounded-lg border border-border px-3 py-2.5"><div><p className="text-sm font-medium">{t("admin.enableAnnouncement")}</p><p className="text-xs text-muted-foreground">{t("admin.scrollAnnouncement")}</p></div><Switch checked={getBool("announcement_enabled")} onCheckedChange={(checked) => setValue("announcement_enabled", String(checked))} aria-label={t("admin.enableAnnouncement")} /></div>
              <label className="flex flex-col gap-1.5 text-sm font-medium"><span>{t("admin.scrollAnnouncement")}</span><Input value={getValue("announcement")} onChange={(event) => setValue("announcement", event.target.value)} /></label>
              <div className="flex items-center justify-between rounded-lg border border-border px-3 py-2.5"><div><p className="text-sm font-medium">{t("admin.enablePopup")}</p><p className="text-xs text-muted-foreground">{t("admin.popupContent")}</p></div><Switch checked={getBool("popup_enabled")} onCheckedChange={(checked) => setValue("popup_enabled", String(checked))} aria-label={t("admin.enablePopup")} /></div>
              <div className="flex flex-col gap-2"><label className="text-sm font-medium">{t("admin.popupContent")}</label><MarkdownEditor value={popupContent} onChange={(value) => setValue("popup_content", value)} uploadImage={(file) => adminProductApi.uploadImage(file).then((result) => result.url)} /></div>
            </div>
            {saveButton}
          </SettingsSection>
        </TabsContent>

        <TabsContent value="contact">
          <SettingsSection title={t("admin.contactTab")} description={t("admin.contactItemsHint")}>
            {contactConfigError && <div role="alert" className="mb-5 flex items-start gap-3 rounded-lg border border-amber-500/30 bg-amber-500/5 p-4 text-sm text-amber-700 dark:text-amber-300"><AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" /><span>{t("admin.contactConfigError")}</span></div>}
            <div className="max-w-4xl space-y-5">
              <label className="flex max-w-xl flex-col gap-1.5 text-sm font-medium"><span>{t("admin.contactEmail")}</span><Input type="email" value={getValue("contact_email")} onChange={(event) => setValue("contact_email", event.target.value)} /></label>
              <div className="space-y-3"><div className="flex flex-wrap items-center justify-between gap-2"><div><p className="text-sm font-medium">{t("admin.contactItems")}</p><p className="text-xs text-muted-foreground">{t("admin.contactItemsHint")}</p></div><span className="text-xs text-muted-foreground">{contactItems.length}/{MAX_CONTACT_ITEMS}</span></div>
                {contactItems.length === 0 && <div className="rounded-lg border border-dashed border-border px-4 py-6 text-center text-sm text-muted-foreground">{t("admin.noContactItems")}</div>}
                {contactItems.map((item, index) => { const uploading = qrUploadingIndex === index; return <div key={index} className="space-y-4 rounded-xl border border-border bg-background/60 p-4"><div className="flex items-center justify-between gap-3"><p className="text-sm font-semibold">{t("admin.contactItemNumber")} {index + 1}</p><Button type="button" variant="ghost" size="sm" className="text-destructive" onClick={() => updateContactItems((items) => items.filter((_, itemIndex) => itemIndex !== index))} disabled={saving || qrUploadingIndex !== null}><Trash2 className="h-3.5 w-3.5" aria-hidden="true" />{t("admin.removeContactItem")}</Button></div><div className="grid gap-4 sm:grid-cols-2"><label className="flex flex-col gap-1.5 text-sm font-medium"><span>{t("admin.contactName")}</span><Input maxLength={30} value={item.name} placeholder={t("admin.contactNamePlaceholder")} onChange={(event) => updateContactItems((items) => items.map((current, itemIndex) => itemIndex === index ? { ...current, name: event.target.value } : current))} disabled={saving || uploading} /><span className="text-xs font-normal text-muted-foreground">{t("admin.contactNameHint")}</span></label><label className="flex flex-col gap-1.5 text-sm font-medium"><span>{t("admin.contactUrl")}</span><Input inputMode="url" value={item.url || ""} placeholder="https://..." onChange={(event) => updateContactItems((items) => items.map((current, itemIndex) => itemIndex === index ? { ...current, url: event.target.value } : current))} disabled={saving || uploading} /><span className="text-xs font-normal text-muted-foreground">{t("admin.contactUrlHint")}</span></label></div><div className="space-y-2"><p className="text-sm font-medium">{t("admin.contactQr")}</p>{item.qr_url ? <div className="flex flex-col gap-3 rounded-lg border border-border bg-muted/20 p-3 sm:flex-row"><img src={item.qr_url} alt={t("admin.contactQrPreviewAlt")} className="h-36 w-36 rounded-md bg-white object-contain p-2" /><div className="min-w-0 flex-1 space-y-2"><p className="break-all text-xs text-muted-foreground">{item.qr_url}</p><div className="flex flex-wrap gap-2"><label className="inline-flex cursor-pointer items-center gap-1.5 rounded-md border border-input px-2.5 py-1.5 text-xs font-medium hover:bg-accent"><Upload className="h-3.5 w-3.5" aria-hidden="true" />{t("admin.replaceQr")}<input type="file" accept={QR_ALLOWED_IMAGE_ACCEPT} className="hidden" onChange={(event) => { void uploadContactQr(index, event.target.files?.[0]); event.target.value = "" }} /></label><Button type="button" variant="ghost" size="sm" className="text-destructive" onClick={() => updateContactItems((items) => items.map((current, itemIndex) => itemIndex === index ? { ...current, qr_url: "" } : current))} disabled={saving || qrUploadingIndex !== null}><Trash2 className="h-3.5 w-3.5" aria-hidden="true" />{t("admin.removeQr")}</Button></div></div></div> : <label className="flex min-h-28 cursor-pointer flex-col items-center justify-center gap-2 rounded-lg border-2 border-dashed border-border px-4 py-5 text-center hover:border-primary/60"><ImagePlus className="h-6 w-6 text-primary/70" aria-hidden="true" /><span className="text-sm font-medium">{t("admin.uploadQr")}</span><span className="text-xs text-muted-foreground">{t("admin.contactQrHint")}</span><input type="file" accept={QR_ALLOWED_IMAGE_ACCEPT} className="hidden" onChange={(event) => { void uploadContactQr(index, event.target.files?.[0]); event.target.value = "" }} /></label>}</div></div> })}
                <Button type="button" variant="outline" className="border-dashed" onClick={() => updateContactItems((items) => [...items, { name: "", url: "", qr_url: "" }])} disabled={contactItems.length >= MAX_CONTACT_ITEMS || saving || qrUploadingIndex !== null}><Plus className="h-4 w-4" aria-hidden="true" />{t("admin.addContactItem")}</Button>
              </div>
            </div>
            {saveButton}
          </SettingsSection>
        </TabsContent>

        <TabsContent value="maintenance">
          <SettingsSection title={t("admin.maintenanceTab")} description={t("admin.maintenanceLabelDesc")}>
            <div className="max-w-3xl space-y-5"><div className="flex items-start gap-3 rounded-lg border border-amber-500/30 bg-amber-500/5 p-4"><AlertTriangle className="mt-0.5 h-5 w-5 shrink-0 text-amber-600" aria-hidden="true" /><div><p className="text-sm font-medium">{t("admin.maintenanceWarning")}</p><p className="mt-1 text-xs text-muted-foreground">{t("admin.maintenanceWarningDesc")}</p></div></div><div className="flex items-center justify-between rounded-lg border border-border px-3 py-2.5"><div><p className="text-sm font-medium">{t("admin.maintenanceLabel")}</p><p className="text-xs text-muted-foreground">{t("admin.maintenanceLabelDesc")}</p></div><Switch checked={getBool("maintenance_enabled")} onCheckedChange={(checked) => void toggleMaintenance(checked)} disabled={maintenanceToggling} aria-label={t("admin.maintenanceLabel")} /></div><label className="flex flex-col gap-1.5 text-sm font-medium"><span>{t("admin.maintenanceMessage")}</span><Textarea value={getValue("maintenance_message")} onChange={(event) => setValue("maintenance_message", event.target.value)} /></label></div>
            {saveButton}
          </SettingsSection>
        </TabsContent>
      </Tabs>
    </AdminPage>
  )
}

function getValueFromMap(map: Record<string, string>, key: string) {
  return map[key] ?? ""
}
