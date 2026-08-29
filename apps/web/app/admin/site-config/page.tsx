"use client"

import { useState, useEffect, useCallback, useRef } from "react"
import { Save, AlertTriangle, Upload, Loader2, ImagePlus, Plus, Trash2 } from "lucide-react"
import { cn } from "@/lib/utils"
import { toast } from "sonner"
import { adminConfigApi, adminProductApi, requestOrThrow } from "@/services/api"
import { useLocale } from "@/lib/context"
import type { ContactItem, SiteConfigKV } from "@/types"
import {
  ALLOWED_IMAGE_ACCEPT,
  QR_ALLOWED_IMAGE_ACCEPT,
  validateImageFile,
  validateQrImageFile,
} from "@/lib/image-upload"
import {
  MAX_CONTACT_ITEMS,
  parseContactItems,
  serializeContactItems,
} from "@/lib/contact-items"

type TabKey = "basic" | "announcement" | "points" | "contact" | "maintenance"

export default function AdminSiteConfigPage() {
  const { t } = useLocale()
  const [tab, setTab] = useState<TabKey>("basic")
  const [configMap, setConfigMap] = useState<Record<string, string>>({})
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [logoUploading, setLogoUploading] = useState(false)
  const [popupUploading, setPopupUploading] = useState(false)
  const [qrUploadingIndex, setQrUploadingIndex] = useState<number | null>(null)
  const [contactItems, setContactItems] = useState<ContactItem[]>([])
  const popupTextareaRef = useRef<HTMLTextAreaElement>(null)

  const fetchConfig = useCallback(async () => {
    setLoading(true)
    try {
      const data = await requestOrThrow(() => adminConfigApi.get())
      const map: Record<string, string> = {}
      data.forEach((kv: SiteConfigKV) => { map[kv.config_key] = kv.config_value })
      try {
        const parsedItems = parseContactItems(map.contact_items)
        map.contact_items = serializeContactItems(parsedItems)
        setContactItems(parsedItems)
      } catch (error: unknown) {
        toast.error(error instanceof Error ? error.message : "联系方式配置无效")
        map.contact_items = "[]"
        setContactItems([])
      }
      setConfigMap(map)
    } catch {
      setConfigMap({})
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { fetchConfig() }, [fetchConfig])

  const getValue = (key: string) => configMap[key] ?? ""
  const setValue = (key: string, value: string) => {
    setConfigMap(prev => ({ ...prev, [key]: value }))
  }
  const updateContactItems = (updater: (items: ContactItem[]) => ContactItem[]) => {
    setContactItems(current => {
      const next = updater(current)
      setConfigMap(prev => ({ ...prev, contact_items: JSON.stringify(next) }))
      return next
    })
  }
  const getBool = (key: string) => configMap[key] === "true"
  const toggleBool = (key: string) => {
    setConfigMap(prev => ({ ...prev, [key]: prev[key] === "true" ? "false" : "true" }))
  }

  const handleSave = async () => {
    setSaving(true)
    try {
      const contactItemsValue = serializeContactItems(contactItems)
      const configs = Object.entries(configMap)
        .filter(([config_key]) => config_key !== "contact_items")
        .map(([config_key, config_value]) => ({
        config_key,
        config_value,
        }))
      configs.push({ config_key: "contact_items", config_value: contactItemsValue })
      await requestOrThrow(() => adminConfigApi.update({ configs }))
      toast.success("保存成功")
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : "保存失败")
    } finally {
      setSaving(false)
    }
  }

  const uploadContactQr = async (index: number, file: File | undefined) => {
    if (!file) return
    const validationError = validateQrImageFile(file)
    if (validationError) {
      toast.error(validationError)
      return
    }

    setQrUploadingIndex(index)
    try {
      const result = await adminProductApi.uploadImage(file)
      updateContactItems(items => items.map((item, itemIndex) =>
        itemIndex === index ? { ...item, qr_url: result.url } : item,
      ))
      toast.success("二维码上传成功")
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : "上传失败")
    } finally {
      setQrUploadingIndex(null)
    }
  }

  const handleToggleMaintenance = async () => {
    const newEnabled = !getBool("maintenance_enabled")
    try {
      await requestOrThrow(() => adminConfigApi.toggleMaintenance(newEnabled))
      setValue("maintenance_enabled", String(newEnabled))
      toast.success(newEnabled ? "已开启维护模式" : "已关闭维护模式")
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : "操作失败")
    }
  }

  if (loading) {
    return (
      <div className="flex flex-col gap-6">
        <div>
          <h1 className="text-2xl font-bold text-foreground">{t("admin.siteConfig")}</h1>
          <p className="text-sm text-muted-foreground">{t("admin.siteConfigDesc")}</p>
        </div>
        <div className="h-64 animate-pulse rounded-xl bg-muted" />
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-6">
      {/* Header */}
      <div>
        <h1 className="text-2xl font-bold text-foreground">{t("admin.siteConfig")}</h1>
        <p className="text-sm text-muted-foreground">{t("admin.siteConfigDesc")}</p>
      </div>

      {/* Tabs */}
      <div className="flex gap-1 overflow-x-auto border-b border-border">
        {([
          { key: "basic" as const, label: t("admin.basicInfo") },
          { key: "announcement" as const, label: t("admin.announcementTab") },
          { key: "points" as const, label: t("admin.pointsSettings") },
          { key: "contact" as const, label: t("admin.contactTab") },
          { key: "maintenance" as const, label: t("admin.maintenanceTab") },
        ]).map((tabItem) => (
          <button
            key={tabItem.key}
            type="button"
            className={cn(
              "whitespace-nowrap px-4 py-2.5 text-sm font-medium border-b-2 transition-colors",
              tab === tabItem.key
                ? "border-primary text-primary"
                : "border-transparent text-muted-foreground hover:text-foreground"
            )}
            onClick={() => setTab(tabItem.key)}
          >
            {tabItem.label}
          </button>
        ))}
      </div>

      {/* Basic Info */}
      {tab === "basic" && (
        <div className="rounded-xl border border-border bg-card p-6 shadow-sm">
          <div className="flex flex-col gap-5 max-w-xl">
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-foreground">{t("admin.siteName")}</label>
              <input
                type="text"
                className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                value={getValue("site_name")}
                onChange={(e) => setValue("site_name", e.target.value)}
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-foreground">{t("admin.siteSlogan")}</label>
              <input
                type="text"
                className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                value={getValue("site_slogan")}
                onChange={(e) => setValue("site_slogan", e.target.value)}
                placeholder="Unlock Your AI Potential"
              />
              <p className="text-xs text-muted-foreground">{t("admin.siteSloganHint")}</p>
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-foreground">{t("admin.siteDesc")}</label>
              <textarea
                className="min-h-20 rounded-lg border border-input bg-background px-3 py-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                value={getValue("site_description")}
                onChange={(e) => setValue("site_description", e.target.value)}
              />
              <p className="text-xs text-muted-foreground">{t("admin.siteDescHint")}</p>
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-foreground">{t("admin.logoUrl")}</label>
              <div className="flex gap-2">
                <input
                  type="text"
                  className="h-10 flex-1 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                  placeholder="https://..."
                  value={getValue("logo_url")}
                  onChange={(e) => setValue("logo_url", e.target.value)}
                />
                <label className={cn("flex h-10 shrink-0 cursor-pointer items-center gap-1.5 rounded-lg border border-input bg-background px-3 text-sm font-medium text-foreground hover:bg-accent transition-colors", logoUploading && "pointer-events-none opacity-50")}>
                  {logoUploading ? <Loader2 className="h-4 w-4 animate-spin" /> : <Upload className="h-4 w-4" />}
                  上传
                  <input
                    type="file"
                    accept={ALLOWED_IMAGE_ACCEPT}
                    className="hidden"
                    onChange={async (e) => {
                      const file = e.target.files?.[0]
                      if (!file) return
                      const err = validateImageFile(file)
                      if (err) { toast.error(err); e.target.value = ""; return }
                      setLogoUploading(true)
                      try {
                        const result = await adminProductApi.uploadImage(file)
                        setValue("logo_url", result.url)
                        toast.success("上传成功")
                      } catch (err: unknown) {
                        toast.error(err instanceof Error ? err.message : "上传失败")
                      } finally {
                        setLogoUploading(false)
                        e.target.value = ""
                      }
                    }}
                  />
                </label>
              </div>
              <p className="text-xs text-muted-foreground">建议使用正方形 Logo 图片，支持 JPG/PNG/GIF/WebP/SVG</p>
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-foreground">{t("admin.footerText")}</label>
              <input
                type="text"
                className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                value={getValue("footer_text")}
                onChange={(e) => setValue("footer_text", e.target.value)}
              />
            </div>
            <button
              type="button"
              className="flex w-fit items-center gap-2 rounded-lg bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
              onClick={handleSave}
              disabled={saving}
            >
              <Save className="h-4 w-4" />
              {saving ? t("admin.saving") : t("admin.saveSettings")}
            </button>
          </div>
        </div>
      )}

      {/* Announcement */}
      {tab === "announcement" && (
        <div className="rounded-xl border border-border bg-card p-6 shadow-sm">
          <div className="flex flex-col gap-5 max-w-xl">
            {/* ① 顶栏滚动公告开关 */}
            <div className="flex items-center justify-between">
              <label className="text-sm font-medium text-foreground">{t("admin.enableAnnouncement")}</label>
              <button
                type="button"
                className={cn(
                  "relative h-6 w-11 rounded-full transition-colors",
                  getBool("announcement_enabled") ? "bg-primary" : "bg-muted"
                )}
                onClick={() => toggleBool("announcement_enabled")}
              >
                <span className={cn(
                  "absolute left-0.5 top-0.5 h-5 w-5 rounded-full bg-white shadow transition-transform",
                  getBool("announcement_enabled") && "translate-x-5"
                )} />
              </button>
            </div>
            {/* ② 顶栏滚动公告内容 */}
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-foreground">{t("admin.scrollAnnouncement")}</label>
              <input
                type="text"
                className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                value={getValue("announcement")}
                onChange={(e) => setValue("announcement", e.target.value)}
              />
            </div>
            {/* ③ 弹窗公告开关 */}
            <div className="flex items-center justify-between">
              <label className="text-sm font-medium text-foreground">{t("admin.enablePopup")}</label>
              <button
                type="button"
                className={cn(
                  "relative h-6 w-11 rounded-full transition-colors",
                  getBool("popup_enabled") ? "bg-primary" : "bg-muted"
                )}
                onClick={() => toggleBool("popup_enabled")}
              >
                <span className={cn(
                  "absolute left-0.5 top-0.5 h-5 w-5 rounded-full bg-white shadow transition-transform",
                  getBool("popup_enabled") && "translate-x-5"
                )} />
              </button>
            </div>
            {/* ④ 弹窗公告内容（Markdown 编辑器 + 图片上传） */}
            <div className="flex flex-col gap-1.5">
              <div className="flex items-center justify-between">
                <label className="text-sm font-medium text-foreground">{t("admin.popupContent")}</label>
                <label className={cn("flex cursor-pointer items-center gap-1 rounded-md px-2 py-1 text-xs font-medium text-muted-foreground hover:bg-accent hover:text-foreground transition-colors", popupUploading && "pointer-events-none opacity-50")}>
                  {popupUploading ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <ImagePlus className="h-3.5 w-3.5" />}
                  插入图片
                  <input
                    type="file"
                    accept={ALLOWED_IMAGE_ACCEPT}
                    className="hidden"
                    onChange={async (e) => {
                      const file = e.target.files?.[0]
                      if (!file) return
                      const err = validateImageFile(file)
                      if (err) { toast.error(err); e.target.value = ""; return }
                      setPopupUploading(true)
                      try {
                        const result = await adminProductApi.uploadImage(file)
                        const textarea = popupTextareaRef.current
                        const mdImage = `![${file.name}](${result.url})`
                        if (textarea) {
                          const start = textarea.selectionStart
                          const end = textarea.selectionEnd
                          const text = getValue("popup_content")
                          const before = text.substring(0, start)
                          const after = text.substring(end)
                          const newText = before + (before.length > 0 && !before.endsWith("\n") ? "\n" : "") + mdImage + "\n" + after
                          setValue("popup_content", newText)
                          requestAnimationFrame(() => {
                            const newPos = before.length + (before.length > 0 && !before.endsWith("\n") ? 1 : 0) + mdImage.length + 1
                            textarea.selectionStart = textarea.selectionEnd = newPos
                            textarea.focus()
                          })
                        } else {
                          const cur = getValue("popup_content")
                          setValue("popup_content", cur + (cur ? "\n" : "") + mdImage + "\n")
                        }
                        toast.success("图片已插入")
                      } catch (err: unknown) {
                        toast.error(err instanceof Error ? err.message : "上传失败")
                      } finally {
                        setPopupUploading(false)
                        e.target.value = ""
                      }
                    }}
                  />
                </label>
              </div>
              <textarea
                ref={popupTextareaRef}
                className="min-h-32 rounded-lg border border-input bg-background px-3 py-2 text-sm text-foreground font-mono focus:outline-none focus:ring-2 focus:ring-ring"
                placeholder={"支持 Markdown 格式编辑\n# 标题  ## 二级标题  ### 三级标题\n**粗体**  *斜体*  空一行为段落换行\n![图片描述](图片URL) — 可点击上方「插入图片」自动生成"}
                value={getValue("popup_content")}
                onChange={(e) => setValue("popup_content", e.target.value)}
              />
            </div>
            <button
              type="button"
              className="flex w-fit items-center gap-2 rounded-lg bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
              onClick={handleSave}
              disabled={saving}
            >
              <Save className="h-4 w-4" />
              {saving ? t("admin.saving") : t("admin.saveSettings")}
            </button>
          </div>
        </div>
      )}

      {/* Points Setting */}
      {tab === "points" && (
        <div className="rounded-xl border border-border bg-card p-6 shadow-sm">
          <div className="flex flex-col gap-5 max-w-xl">
            <div className="flex items-center justify-between">
              <label className="text-sm font-medium text-foreground">{t("admin.enablePointsSystem")}</label>
              <button
                type="button"
                className={cn(
                  "relative h-6 w-11 rounded-full transition-colors",
                  getBool("points_enabled") ? "bg-primary" : "bg-muted"
                )}
                onClick={() => toggleBool("points_enabled")}
              >
                <span className={cn(
                  "absolute left-0.5 top-0.5 h-5 w-5 rounded-full bg-white shadow transition-transform",
                  getBool("points_enabled") && "translate-x-5"
                )} />
              </button>
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-foreground">{t("admin.pointsRate")}</label>
              <input
                type="number"
                className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                value={getValue("points_rate")}
                onChange={(e) => setValue("points_rate", e.target.value)}
              />
            </div>
            <button
              type="button"
              className="flex w-fit items-center gap-2 rounded-lg bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
              onClick={handleSave}
              disabled={saving}
            >
              <Save className="h-4 w-4" />
              {saving ? t("admin.saving") : t("admin.saveSettings")}
            </button>
          </div>
        </div>
      )}

      {/* Contact */}
      {tab === "contact" && (
        <div className="rounded-xl border border-border bg-card p-6 shadow-sm">
          <div className="flex max-w-3xl flex-col gap-5">
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-foreground">{t("admin.contactEmail")}</label>
              <input
                type="email"
                className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                value={getValue("contact_email")}
                onChange={(e) => setValue("contact_email", e.target.value)}
              />
            </div>
            <div className="flex flex-col gap-3">
              <div className="flex flex-col gap-1">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <label className="text-sm font-medium text-foreground">{t("admin.contactItems")}</label>
                  <span className="text-xs text-muted-foreground">
                    {contactItems.length}/{MAX_CONTACT_ITEMS}
                  </span>
                </div>
                <p className="text-xs text-muted-foreground">{t("admin.contactItemsHint")}</p>
              </div>

              {contactItems.length === 0 && (
                <div className="rounded-lg border border-dashed border-border px-4 py-5 text-center text-sm text-muted-foreground">
                  {t("admin.noContactItems")}
                </div>
              )}

              {contactItems.map((item, index) => {
                const qrUploading = qrUploadingIndex === index
                return (
                  <div key={index} className="flex flex-col gap-4 rounded-xl border border-border bg-background/60 p-4">
                    <div className="flex items-center justify-between gap-3">
                      <p className="text-sm font-semibold text-foreground">
                        {t("admin.contactItemNumber")} {index + 1}
                      </p>
                      <button
                        type="button"
                        className="inline-flex items-center gap-1.5 rounded-md px-2.5 py-1.5 text-xs font-medium text-muted-foreground transition-colors hover:bg-destructive/10 hover:text-destructive disabled:pointer-events-none disabled:opacity-50"
                        onClick={() => updateContactItems(items => items.filter((_, itemIndex) => itemIndex !== index))}
                        disabled={saving || qrUploadingIndex !== null}
                      >
                        <Trash2 className="h-3.5 w-3.5" />
                        {t("admin.removeContactItem")}
                      </button>
                    </div>

                    <div className="grid gap-4 sm:grid-cols-2">
                      <label className="flex min-w-0 flex-col gap-1.5 text-sm font-medium text-foreground">
                        {t("admin.contactName")}
                        <input
                          type="text"
                          maxLength={30}
                          className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm font-normal text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                          placeholder={t("admin.contactNamePlaceholder")}
                          value={item.name}
                          onChange={(event) => updateContactItems(items => items.map((current, itemIndex) =>
                            itemIndex === index ? { ...current, name: event.target.value } : current,
                          ))}
                          disabled={saving || qrUploading}
                        />
                        <span className="text-xs font-normal text-muted-foreground">{t("admin.contactNameHint")}</span>
                      </label>

                      <label className="flex min-w-0 flex-col gap-1.5 text-sm font-medium text-foreground">
                        {t("admin.contactUrl")}
                        <input
                          type="text"
                          inputMode="url"
                          className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm font-normal text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                          placeholder="https://..."
                          value={item.url ?? ""}
                          onChange={(event) => updateContactItems(items => items.map((current, itemIndex) =>
                            itemIndex === index ? { ...current, url: event.target.value } : current,
                          ))}
                          disabled={saving || qrUploading}
                        />
                        <span className="text-xs font-normal text-muted-foreground">{t("admin.contactUrlHint")}</span>
                      </label>
                    </div>

                    <div className="flex min-w-0 flex-col gap-2">
                      <span className="text-sm font-medium text-foreground">{t("admin.contactQr")}</span>
                      {item.qr_url ? (
                        <div className="flex flex-col gap-3 rounded-lg border border-border bg-muted/20 p-3 sm:flex-row sm:items-start">
                          <div className="flex h-36 w-36 shrink-0 items-center justify-center overflow-hidden rounded-md bg-white p-2">
                            <img src={item.qr_url} alt={t("admin.contactQrPreviewAlt")} className="max-h-full max-w-full object-contain" />
                          </div>
                          <div className="flex min-w-0 flex-1 flex-col gap-2">
                            <p className="break-all text-xs text-muted-foreground">{item.qr_url}</p>
                            <div className="flex flex-wrap gap-2">
                              <label className={cn(
                                "inline-flex cursor-pointer items-center gap-1.5 rounded-md border border-input bg-background px-2.5 py-1.5 text-xs font-medium text-foreground transition-colors hover:bg-accent",
                                (saving || qrUploadingIndex !== null) && "pointer-events-none opacity-50",
                              )}>
                                {qrUploading ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Upload className="h-3.5 w-3.5" />}
                                {t("admin.replaceQr")}
                                <input
                                  type="file"
                                  accept={QR_ALLOWED_IMAGE_ACCEPT}
                                  className="hidden"
                                  disabled={saving || qrUploadingIndex !== null}
                                  onChange={(event) => {
                                    void uploadContactQr(index, event.target.files?.[0])
                                    event.target.value = ""
                                  }}
                                />
                              </label>
                              <button
                                type="button"
                                className="inline-flex items-center gap-1.5 rounded-md px-2.5 py-1.5 text-xs font-medium text-muted-foreground transition-colors hover:bg-destructive/10 hover:text-destructive disabled:pointer-events-none disabled:opacity-50"
                                onClick={() => updateContactItems(items => items.map((current, itemIndex) =>
                                  itemIndex === index ? { ...current, qr_url: "" } : current,
                                ))}
                                disabled={saving || qrUploadingIndex !== null}
                              >
                                <Trash2 className="h-3.5 w-3.5" />
                                {t("admin.removeQr")}
                              </button>
                            </div>
                          </div>
                        </div>
                      ) : (
                        <label className={cn(
                          "flex min-h-28 cursor-pointer flex-col items-center justify-center gap-2 rounded-lg border-2 border-dashed border-border bg-muted/10 px-4 py-5 text-center transition-colors hover:border-primary/60 hover:bg-primary/5",
                          (saving || qrUploadingIndex !== null) && "pointer-events-none opacity-50",
                        )}>
                          {qrUploading ? <Loader2 className="h-6 w-6 animate-spin text-primary" /> : <ImagePlus className="h-6 w-6 text-primary/70" />}
                          <span className="text-sm font-medium text-foreground">{qrUploading ? t("admin.uploadingQr") : t("admin.uploadQr")}</span>
                          <span className="text-xs text-muted-foreground">{t("admin.contactQrHint")}</span>
                          <input
                            type="file"
                            accept={QR_ALLOWED_IMAGE_ACCEPT}
                            className="hidden"
                            disabled={saving || qrUploadingIndex !== null}
                            onChange={(event) => {
                              void uploadContactQr(index, event.target.files?.[0])
                              event.target.value = ""
                            }}
                          />
                        </label>
                      )}
                    </div>
                  </div>
                )
              })}

              <button
                type="button"
                className="inline-flex w-fit items-center gap-2 rounded-lg border border-dashed border-primary/60 px-4 py-2.5 text-sm font-medium text-primary transition-colors hover:bg-primary/5 disabled:pointer-events-none disabled:opacity-50"
                onClick={() => updateContactItems(items => [...items, { name: "", url: "", qr_url: "" }])}
                disabled={contactItems.length >= MAX_CONTACT_ITEMS || saving || qrUploadingIndex !== null}
              >
                <Plus className="h-4 w-4" />
                {t("admin.addContactItem")}
              </button>
              {contactItems.length >= MAX_CONTACT_ITEMS && (
                <p className="text-xs text-muted-foreground">{t("admin.contactItemsLimit")}</p>
              )}
            </div>
            <button
              type="button"
              className="flex w-fit items-center gap-2 rounded-lg bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
              onClick={handleSave}
              disabled={saving}
            >
              <Save className="h-4 w-4" />
              {saving ? t("admin.saving") : t("admin.saveSettings")}
            </button>
          </div>
        </div>
      )}

      {/* Maintenance */}
      {tab === "maintenance" && (
        <div className="rounded-xl border border-border bg-card p-6 shadow-sm">
          <div className="flex flex-col gap-5 max-w-xl">
            <div className="rounded-lg border border-amber-500/30 bg-amber-500/5 p-4">
              <div className="flex items-start gap-3">
                <AlertTriangle className="mt-0.5 h-5 w-5 shrink-0 text-amber-500" />
                <div>
                  <p className="text-sm font-medium text-foreground">{t("admin.maintenanceWarning")}</p>
                  <p className="mt-1 text-xs text-muted-foreground">
                    {t("admin.maintenanceWarningDesc")}
                  </p>
                </div>
              </div>
            </div>
            <div className="flex items-center justify-between">
              <div>
                <label className="text-sm font-medium text-foreground">{t("admin.maintenanceLabel")}</label>
                <p className="text-xs text-muted-foreground">{t("admin.maintenanceLabelDesc")}</p>
              </div>
              <button
                type="button"
                className={cn(
                  "relative h-6 w-11 rounded-full transition-colors",
                  getBool("maintenance_enabled") ? "bg-red-500" : "bg-muted"
                )}
                onClick={handleToggleMaintenance}
              >
                <span className={cn(
                  "absolute left-0.5 top-0.5 h-5 w-5 rounded-full bg-white shadow transition-transform",
                  getBool("maintenance_enabled") && "translate-x-5"
                )} />
              </button>
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-foreground">{t("admin.maintenanceMessage")}</label>
              <textarea
                className="min-h-20 rounded-lg border border-input bg-background px-3 py-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                value={getValue("maintenance_message")}
                onChange={(e) => setValue("maintenance_message", e.target.value)}
              />
            </div>
            <button
              type="button"
              className="flex w-fit items-center gap-2 rounded-lg bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
              onClick={handleSave}
              disabled={saving}
            >
              <Save className="h-4 w-4" />
              {saving ? t("admin.saving") : t("admin.saveSettings")}
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
