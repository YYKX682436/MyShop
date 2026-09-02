"use client"

import { useEffect, useMemo, useRef, useState } from "react"
import { AlertTriangle, Loader2, Plus, Trash2 } from "lucide-react"
import { toast } from "sonner"

import { ImageUploadDropzone } from "@/components/admin/image-upload-dropzone"
import { MarkdownEditor } from "@/components/admin/markdown-editor"
import { FormSection } from "@/components/admin/shared"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Switch } from "@/components/ui/switch"
import { useLocale } from "@/lib/context"
import { adminProductApi, adminUserLevelApi, requestOrThrow, type AdminProductUpsertPayload } from "@/services/api"
import { cn, formatYuan } from "@/lib/utils"
import type { AdminProductDetail, Category, UserLevel } from "@/types"

type DeliveryType = "AUTO" | "MANUAL"
type StockMode = "CARD_KEY" | "MANUAL" | "UNLIMITED"

type ProductFormData = {
  title: string
  description: string
  detail_md: string
  category_id: string
  cost_price: string
  retail_price: string
  cover_url: string
  low_stock_threshold: string
  spec_enabled: boolean
  is_enabled: boolean
  initial_sales: string
  sort_order: string
  delivery_type: DeliveryType
  customer_input_fields: { id: string; label: string }[]
  stock_mode: StockMode
  manual_stock: string
}

type ProductSpecDraft = {
  id?: string
  name: string
  cost_price: string
  retail_price: string
  visible: boolean
  sort_order: string
  manual_stock: string
  card_key_count?: number
}

type OverrideDraft = {
  user_level_id: string
  spec_id: string | null
  price: string
}

function createId() {
  return globalThis.crypto?.randomUUID?.()
    || `xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx`.replace(/[xy]/g, (character) => {
      const random = Math.random() * 16 | 0
      const value = character === "x" ? random : (random & 0x3 | 0x8)
      return value.toString(16)
    })
}

function emptyForm(): ProductFormData {
  return {
    title: "",
    description: "",
    detail_md: "",
    category_id: "",
    cost_price: "",
    retail_price: "",
    cover_url: "",
    low_stock_threshold: "10",
    spec_enabled: false,
    is_enabled: true,
    initial_sales: "0",
    sort_order: "0",
    delivery_type: "AUTO",
    customer_input_fields: [],
    stock_mode: "CARD_KEY",
    manual_stock: "",
  }
}

function getFormData(product: AdminProductDetail | null): ProductFormData {
  if (!product) return emptyForm()
  return {
    title: product.title,
    description: product.description || "",
    detail_md: product.detail_md || "",
    category_id: product.category_id,
    cost_price: product.cost_price == null ? "" : String(product.cost_price),
    retail_price: String(product.retail_price),
    cover_url: product.cover_url || "",
    low_stock_threshold: String(product.low_stock_threshold ?? 10),
    spec_enabled: product.spec_enabled,
    is_enabled: product.is_enabled,
    initial_sales: String(product.initial_sales ?? 0),
    sort_order: String(product.sort_order ?? 0),
    delivery_type: product.delivery_type === "MANUAL" ? "MANUAL" : "AUTO",
    customer_input_fields: (product.customer_input_fields || []).map((field) => ({ id: field.id, label: field.label })),
    stock_mode: product.stock_mode || (product.delivery_type === "MANUAL" ? "MANUAL" : "CARD_KEY"),
    manual_stock: product.stock_mode === "MANUAL" && !product.has_specs && product.stock_available != null
      ? String(product.stock_available) : "",
  }
}

function getSpecs(product: AdminProductDetail | null): ProductSpecDraft[] {
  return (product?.specs || []).map((spec) => ({
    id: spec.id,
    name: spec.name,
    cost_price: String(spec.cost_price),
    retail_price: String(spec.retail_price),
    visible: spec.is_visible,
    sort_order: String(spec.sort_order ?? 0),
    manual_stock: spec.manual_stock == null ? "" : String(spec.manual_stock),
    card_key_count: spec.card_key_count,
  }))
}

function getOverrides(product: AdminProductDetail | null): OverrideDraft[] {
  return (product?.overrides || []).map((override) => ({
    user_level_id: override.user_level_id,
    spec_id: override.spec_id,
    price: String(override.price),
  }))
}

function asNumber(value: string, fallback = 0) {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : fallback
}

function isMoney(value: string, positive: boolean) {
  if (!value.trim() || !/^\d+(\.\d{1,2})?$/.test(value.trim())) return false
  const parsed = Number(value)
  return Number.isFinite(parsed) && (positive ? parsed > 0 : parsed >= 0)
}

interface ProductFormProps {
  product: AdminProductDetail | null
  categories: Category[]
  onSaved: () => void | Promise<void>
  onCancel: () => void
  onDirtyChange?: (dirty: boolean) => void
  stickyActions?: boolean
}

export function ProductForm({ product, categories, onSaved, onCancel, onDirtyChange, stickyActions = false }: ProductFormProps) {
  const { t } = useLocale()
  const [formData, setFormData] = useState<ProductFormData>(() => getFormData(product))
  const [formSpecs, setFormSpecs] = useState<ProductSpecDraft[]>(() => getSpecs(product))
  const [overrides, setOverrides] = useState<OverrideDraft[]>(() => getOverrides(product))
  const [levels, setLevels] = useState<UserLevel[]>([])
  const [levelsLoading, setLevelsLoading] = useState(true)
  const [preview, setPreview] = useState<{ pricing_matrix: AdminProductDetail["pricing_matrix"]; warnings: string[] } | null>(null)
  const [previewLoading, setPreviewLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const initialSnapshotRef = useRef("")
  const onDirtyChangeRef = useRef(onDirtyChange)

  const snapshot = useMemo(() => JSON.stringify({ formData, formSpecs, overrides }), [formData, formSpecs, overrides])

  useEffect(() => {
    onDirtyChangeRef.current = onDirtyChange
  }, [onDirtyChange])

  useEffect(() => {
    const nextFormData = getFormData(product)
    const nextFormSpecs = getSpecs(product)
    const nextOverrides = getOverrides(product)
    setFormData(nextFormData)
    setFormSpecs(nextFormSpecs)
    setOverrides(nextOverrides)
    initialSnapshotRef.current = JSON.stringify({ formData: nextFormData, formSpecs: nextFormSpecs, overrides: nextOverrides })
    setPreview(product?.pricing_matrix?.length ? { pricing_matrix: product.pricing_matrix, warnings: product.warnings || [] } : null)
  }, [product])

  useEffect(() => {
    if (!initialSnapshotRef.current) return
    onDirtyChangeRef.current?.(snapshot !== initialSnapshotRef.current)
  }, [snapshot])

  useEffect(() => {
    let active = true
    setLevelsLoading(true)
    void adminUserLevelApi.getList().then((data) => {
      if (active) setLevels(data)
    }).catch(() => {
      if (active) setLevels([])
    }).finally(() => {
      if (active) setLevelsLoading(false)
    })
    return () => { active = false }
  }, [])

  const uploadImage = async (file: File) => (await adminProductApi.uploadImage(file)).url

  const setField = <K extends keyof ProductFormData>(key: K, value: ProductFormData[K]) => {
    setFormData((current) => ({ ...current, [key]: value }))
  }

  const payload = useMemo<AdminProductUpsertPayload>(() => {
    const validSpecPrices = formSpecs.filter((spec) => spec.visible).map((spec) => asNumber(spec.retail_price)).filter((price) => price > 0)
    return {
      title: formData.title.trim(),
      description: formData.description || undefined,
      detail_md: formData.detail_md || undefined,
      cover_url: formData.cover_url || undefined,
      cost_price: formData.spec_enabled ? null : asNumber(formData.cost_price),
      retail_price: formData.spec_enabled ? (validSpecPrices.length ? Math.min(...validSpecPrices) : asNumber(formData.retail_price)) : asNumber(formData.retail_price),
      category_id: formData.category_id,
      low_stock_threshold: Math.max(0, Math.trunc(asNumber(formData.low_stock_threshold, 10))),
      spec_enabled: formData.spec_enabled,
      is_enabled: formData.is_enabled,
      initial_sales: Math.max(0, Math.trunc(asNumber(formData.initial_sales))),
      sort_order: Math.trunc(asNumber(formData.sort_order)),
      delivery_type: formData.delivery_type,
      customer_input_fields: formData.customer_input_fields,
      stock_mode: formData.stock_mode,
      manual_stock: formData.stock_mode === "MANUAL" && !formData.spec_enabled
        ? Math.max(0, Math.trunc(asNumber(formData.manual_stock))) : undefined,
      specs: formSpecs.map((spec) => ({
        id: spec.id,
        name: spec.name.trim(),
        cost_price: asNumber(spec.cost_price),
        retail_price: asNumber(spec.retail_price),
        visible: spec.visible,
        sort_order: Math.trunc(asNumber(spec.sort_order)),
        manual_stock: formData.stock_mode === "MANUAL" ? Math.max(0, Math.trunc(asNumber(spec.manual_stock))) : undefined,
      })),
      overrides: overrides.map((override) => ({
        user_level_id: override.user_level_id,
        spec_id: override.spec_id,
        price: asNumber(override.price),
      })),
    }
  }, [formData, formSpecs, overrides])

  const previewReady = useMemo(() => {
    if (!payload.title || !payload.category_id) return false
    if (!payload.spec_enabled) return isMoney(formData.cost_price, false) && isMoney(formData.retail_price, true)
    return payload.specs.length > 0 && (!formData.is_enabled || payload.specs.some((spec) => spec.visible))
      && payload.specs.every((spec) => isMoney(String(spec.cost_price), false) && isMoney(String(spec.retail_price), true))
  }, [formData.cost_price, formData.is_enabled, formData.retail_price, payload])

  useEffect(() => {
    if (!previewReady || levelsLoading) return
    const timer = window.setTimeout(() => {
      setPreviewLoading(true)
      void adminProductApi.previewPricing(payload).then(setPreview).catch(() => {
        // Invalid drafts are still validated by the save action; a preview
        // failure must never turn the editor into a broken state.
        setPreview(null)
      }).finally(() => setPreviewLoading(false))
    }, 350)
    return () => window.clearTimeout(timer)
  }, [levelsLoading, payload, previewReady])

  const updateSpec = (index: number, patch: Partial<ProductSpecDraft>) => {
    setFormSpecs((current) => current.map((spec, currentIndex) => currentIndex === index ? { ...spec, ...patch } : spec))
  }

  const removeSpec = (index: number) => {
    const spec = formSpecs[index]
    if (spec?.card_key_count && spec.card_key_count > 0
      && !window.confirm(t("admin.deleteSpecDescription").replace("{name}", spec.name).replace("{count}", String(spec.card_key_count)))) return
    setFormSpecs((current) => current.filter((_, currentIndex) => currentIndex !== index))
    if (spec?.id) setOverrides((current) => current.filter((override) => override.spec_id !== spec.id))
  }

  const updateOverride = (userLevelId: string, specId: string | null, value: string) => {
    setOverrides((current) => {
      const index = current.findIndex((override) => override.user_level_id === userLevelId && override.spec_id === specId)
      if (!value.trim()) return index < 0 ? current : current.filter((_, currentIndex) => currentIndex !== index)
      const next = { user_level_id: userLevelId, spec_id: specId, price: value }
      if (index < 0) return [...current, next]
      return current.map((override, currentIndex) => currentIndex === index ? next : override)
    })
  }

  const overrideValue = (userLevelId: string, specId: string | null) =>
    overrides.find((override) => override.user_level_id === userLevelId && override.spec_id === specId)?.price || ""

  const validateCustomerFields = () => {
    if (formData.customer_input_fields.length > 20) return t("admin.customerInputLimit")
    const labels = new Set<string>()
    for (const field of formData.customer_input_fields) {
      const label = field.label.trim()
      if (!label) return t("admin.customerInputNameRequired")
      if (label.length > 50) return t("admin.customerInputNameLength")
      if ([...label].some((character) => /\p{C}/u.test(character))) return t("admin.customerInputNameControl")
      const normalized = label.toLocaleLowerCase()
      if (!labels.add(normalized)) return t("admin.customerInputDuplicate").replace("{label}", label)
    }
    return null
  }

  const validate = () => {
    if (!formData.title.trim()) return t("admin.productNameRequired")
    if (!formData.category_id) return t("admin.categoryRequired")
    const customerError = validateCustomerFields()
    if (customerError) return customerError
    if (!formData.spec_enabled) {
      if (!isMoney(formData.cost_price, false)) return t("admin.costPriceRequired")
      if (!isMoney(formData.retail_price, true)) return t("admin.retailPriceRequired")
    } else {
      if (formData.is_enabled && (formSpecs.length === 0 || !formSpecs.some((spec) => spec.visible))) return t("admin.visibleSpecRequired")
      const names = new Set<string>()
      for (const spec of formSpecs) {
        if (!spec.name.trim()) return t("admin.specNameRequired")
        if (!names.add(spec.name.trim().toLocaleLowerCase())) {
          return t("admin.specNameDuplicate").replace("{name}", spec.name.trim())
        }
        if (!isMoney(spec.cost_price, false)) return t("admin.specCostInvalid").replace("{name}", spec.name || t("admin.unnamed"))
        if (!isMoney(spec.retail_price, true)) return t("admin.specRetailInvalid").replace("{name}", spec.name || t("admin.unnamed"))
        if (formData.stock_mode === "MANUAL" && (!/^\d+$/.test(spec.manual_stock) || Number(spec.manual_stock) < 0)) {
          return t("admin.specManualStockInvalid").replace("{name}", spec.name || t("admin.unnamed"))
        }
      }
    }
    if (formData.stock_mode === "MANUAL" && !formData.spec_enabled
      && (!/^\d+$/.test(formData.manual_stock) || Number(formData.manual_stock) < 0)) return t("admin.manualStockInvalid")
    const specIds = new Set(formSpecs.filter((spec) => spec.id).map((spec) => spec.id))
    const keys = new Set<string>()
    for (const override of overrides) {
      if (!override.user_level_id || !isMoney(override.price, true)) return t("admin.overridePriceInvalid")
      if (formData.spec_enabled && (!override.spec_id || !specIds.has(override.spec_id))) return t("admin.overrideSpecInvalid")
      if (!formData.spec_enabled && override.spec_id) return t("admin.overrideSpecInvalid")
      const target = formData.spec_enabled ? formSpecs.find((spec) => spec.id === override.spec_id) : null
      const retail = target ? Number(target.retail_price) : Number(formData.retail_price)
      if (Number(override.price) > retail) return t("admin.overrideAboveRetail")
      const key = `${override.user_level_id}:${override.spec_id || ""}`
      if (!keys.add(key)) return t("admin.overrideDuplicate")
    }
    return null
  }

  const handleSave = async () => {
    const error = validate()
    if (error) {
      toast.error(error)
      return
    }
    setSaving(true)
    try {
      if (product) await requestOrThrow(() => adminProductApi.update(product.id, payload))
      else await requestOrThrow(() => adminProductApi.create(payload))
      toast.success(t("admin.productSaved"))
      await onSaved()
    } catch (requestError: unknown) {
      toast.error(requestError instanceof Error ? requestError.message : t("admin.saveFailed"))
    } finally {
      setSaving(false)
    }
  }

  const handleDeliveryChange = (deliveryType: DeliveryType) => {
    setFormData((current) => ({
      ...current,
      delivery_type: deliveryType,
      stock_mode: deliveryType === "AUTO" ? "CARD_KEY" : current.stock_mode === "CARD_KEY" ? "MANUAL" : current.stock_mode,
    }))
  }

  const toggleSpecMode = (checked: boolean) => {
    if (checked !== formData.spec_enabled && product
      && !window.confirm(t("admin.specModeSwitchWarning"))) return
    setField("spec_enabled", checked)
    if (checked && formSpecs.length === 0) setFormSpecs([{ id: createId(), name: "", cost_price: "", retail_price: "", visible: true, sort_order: "0", manual_stock: "" }])
  }

  const rootCost = Number(formData.cost_price)
  const rootRetail = Number(formData.retail_price)
  const rootMargin = Number.isFinite(rootCost) && Number.isFinite(rootRetail) ? rootRetail - rootCost : null
  const rootMarginRate = rootMargin !== null && rootRetail > 0 ? (rootMargin / rootRetail) * 100 : null
  const targets = formData.spec_enabled ? formSpecs : [{ id: undefined, name: t("admin.product"), retail_price: formData.retail_price }]
  const matrixRow = (levelId: string, specId: string | null) => preview?.pricing_matrix.find((row) => row.user_level_id === levelId && row.spec_id === specId)

  return (
    <div className="flex min-w-0 flex-col gap-6">
      <FormSection title={t("admin.productBasics")} description={t("admin.productBasicsHint")}>
        <div className="grid gap-5 lg:grid-cols-2">
          <label className="flex min-w-0 flex-col gap-1.5 text-sm font-medium lg:col-span-2">
            {t("admin.productNameReq")}
            <Input value={formData.title} onChange={(event) => setField("title", event.target.value)} placeholder={t("admin.productNamePlaceholder")} />
          </label>
          <label className="flex min-w-0 flex-col gap-1.5 text-sm font-medium lg:col-span-2">
            {t("admin.productBrief")}
            <Input value={formData.description} onChange={(event) => setField("description", event.target.value)} placeholder={t("admin.productBriefPlaceholder")} />
          </label>
          <label className="flex min-w-0 flex-col gap-1.5 text-sm font-medium">
            {t("admin.categoryReq")}
            <select className="h-11 rounded-control border border-input bg-surface px-3 text-sm font-normal" value={formData.category_id} onChange={(event) => setField("category_id", event.target.value)}>
              <option value="">{t("admin.categoryPlaceholder")}</option>
              {categories.map((category) => <option key={category.id} value={category.id}>{category.name}</option>)}
            </select>
          </label>
          <div className="grid gap-3 sm:grid-cols-2">
            <label className="flex min-w-0 flex-col gap-1.5 text-sm font-medium">
              {t("admin.costPriceReq")}
              <Input type="number" min="0" step="0.01" value={formData.cost_price} disabled={formData.spec_enabled} onChange={(event) => setField("cost_price", event.target.value)} placeholder="0.00" />
              {formData.spec_enabled && <span className="text-xs font-normal text-muted-foreground">{t("admin.costFromSpecs")}</span>}
            </label>
            <label className="flex min-w-0 flex-col gap-1.5 text-sm font-medium">
              {t("admin.retailPriceReq")}
              <Input type="number" min="0" step="0.01" value={formData.retail_price} disabled={formData.spec_enabled && (formData.is_enabled || formSpecs.some((spec) => spec.visible))} onChange={(event) => setField("retail_price", event.target.value)} placeholder="0.00" />
              {formData.spec_enabled && <span className="text-xs font-normal text-muted-foreground">{t("admin.retailFromSpecs")}</span>}
            </label>
          </div>
          {!formData.spec_enabled && rootMargin !== null && (
            <div className="grid grid-cols-2 gap-3 rounded-card border border-border-subtle bg-surface-muted p-3.5 text-sm lg:col-span-2">
              <div><div className="text-xs text-muted-foreground">{t("admin.marginAmount")}</div><div className={cn("mt-1 font-semibold", rootMargin < 0 && "text-amber-600")}>{formatYuan(rootMargin)}</div></div>
              <div><div className="text-xs text-muted-foreground">{t("admin.marginRate")}</div><div className={cn("mt-1 font-semibold", rootMarginRate !== null && rootMarginRate < 0 && "text-amber-600")}>{rootMarginRate === null ? "—" : `${rootMarginRate.toFixed(2)}%`}</div></div>
            </div>
          )}
          <label className="flex min-w-0 flex-col gap-1.5 text-sm font-medium lg:col-span-2">
            {t("admin.coverUrl")}
            <ImageUploadDropzone value={formData.cover_url} onChange={(value) => setField("cover_url", value)} uploadImage={uploadImage} disabled={saving} />
          </label>
        </div>
      </FormSection>

      <FormSection title={t("admin.inventorySettings")} description={t("admin.inventorySettingsHint")}>
        <div className="grid gap-5 lg:grid-cols-2">
          <div className="flex flex-col gap-1.5 text-sm font-medium">
            {t("admin.deliveryMethod")}
            <div className="grid grid-cols-2 gap-2">
              {(["AUTO", "MANUAL"] as const).map((deliveryType) => (
                <Button key={deliveryType} type="button" variant={formData.delivery_type === deliveryType ? "default" : "outline"} onClick={() => handleDeliveryChange(deliveryType)}>
                  {deliveryType === "AUTO" ? t("admin.autoDelivery") : t("admin.manualDelivery")}
                </Button>
              ))}
            </div>
          </div>
          <label className="flex flex-col gap-1.5 text-sm font-medium">
            {t("admin.stockMode")}
            <select className="h-11 rounded-control border border-input bg-surface px-3 text-sm" value={formData.stock_mode} onChange={(event) => setField("stock_mode", event.target.value as StockMode)}>
              {formData.delivery_type === "AUTO" && <option value="CARD_KEY">{t("admin.cardKeyStock")}</option>}
              {formData.delivery_type === "MANUAL" && <><option value="MANUAL">{t("admin.manualStock")}</option><option value="UNLIMITED">{t("admin.unlimitedStock")}</option></>}
            </select>
          </label>
          <label className="flex flex-col gap-1.5 text-sm font-medium">
            {t("admin.sortOrderLabel")}
            <Input type="number" value={formData.sort_order} onChange={(event) => setField("sort_order", event.target.value)} />
          </label>
          <label className="flex flex-col gap-1.5 text-sm font-medium">
            {t("admin.lowStockAlert")}
            <Input type="number" min="0" value={formData.low_stock_threshold} onChange={(event) => setField("low_stock_threshold", event.target.value)} />
          </label>
          {formData.stock_mode === "MANUAL" && !formData.spec_enabled && (
            <label className="flex flex-col gap-1.5 text-sm font-medium lg:col-span-2">
              {t("admin.availableManualStock")}
              <Input type="number" min="0" step="1" value={formData.manual_stock} onChange={(event) => setField("manual_stock", event.target.value)} placeholder="0" />
            </label>
          )}
          <label className="flex items-center justify-between rounded-control border border-border-subtle bg-surface-muted px-3 py-2.5 lg:col-span-2">
            <span className="text-sm font-medium">{t("admin.listingStatus")}</span>
            <Switch checked={formData.is_enabled} onCheckedChange={(checked) => setField("is_enabled", checked)} aria-label={t("admin.listingStatus")} />
          </label>
        </div>
      </FormSection>

      <FormSection title={t("admin.customerInputFields")} description={`${formData.customer_input_fields.length}/20`}>
        <div className="space-y-2">
          {formData.customer_input_fields.map((field, index) => (
            <div className="flex items-center gap-2" key={field.id}>
              <Input maxLength={50} value={field.label} onChange={(event) => setFormData((current) => ({ ...current, customer_input_fields: current.customer_input_fields.map((item, itemIndex) => itemIndex === index ? { ...item, label: event.target.value } : item) }))} placeholder={t("admin.customerInputPlaceholder")} />
              <Button type="button" variant="ghost" size="icon" onClick={() => setFormData((current) => ({ ...current, customer_input_fields: current.customer_input_fields.filter((_, itemIndex) => itemIndex !== index) }))} aria-label={t("admin.removeCustomerInput")}><Trash2 className="h-4 w-4 text-destructive" /></Button>
            </div>
          ))}
          <Button type="button" variant="outline" onClick={() => setFormData((current) => ({ ...current, customer_input_fields: [...current.customer_input_fields, { id: createId(), label: "" }] }))} disabled={formData.customer_input_fields.length >= 20}>
            <Plus className="mr-2 h-4 w-4" />{t("admin.addCustomerInput")}
          </Button>
        </div>
      </FormSection>

      <FormSection title={t("admin.productSpecs")} description={t("admin.specsHint")}>
        <div className="flex items-center justify-between rounded-control border border-border-subtle bg-surface-muted px-3 py-2.5">
          <span className="text-sm font-medium">{t("admin.enableMultiSpec")}</span>
          <Switch checked={formData.spec_enabled} onCheckedChange={toggleSpecMode} aria-label={t("admin.enableMultiSpec")} />
        </div>
        <div className={cn("mt-4 overflow-x-auto rounded-card border border-border-subtle bg-surface", !formData.spec_enabled && "opacity-70")}>
          <table className="w-full min-w-[820px] text-sm">
            <thead className="bg-surface-muted"><tr><th className="px-3 py-2 text-left">{t("admin.specName")}</th><th className="px-3 py-2 text-left">{t("admin.specCost")}</th><th className="px-3 py-2 text-left">{t("admin.specRetail")}</th><th className="px-3 py-2 text-left">{t("admin.specMargin")}</th><th className="px-3 py-2 text-left">{t("admin.specVisible")}</th><th className="px-3 py-2 text-left">{t("admin.stockLabel")}</th><th className="px-3 py-2" /></tr></thead>
            <tbody>
              {formSpecs.map((spec, index) => {
                const cost = Number(spec.cost_price)
                const retail = Number(spec.retail_price)
                const margin = Number.isFinite(cost) && Number.isFinite(retail) ? retail - cost : 0
                return <tr key={spec.id || `new-${index}`} className="border-t border-border-subtle">
                  <td className="px-3 py-2"><Input value={spec.name} onChange={(event) => updateSpec(index, { name: event.target.value })} placeholder={t("admin.specNamePlaceholder")} /></td>
                  <td className="px-3 py-2"><Input type="number" min="0" step="0.01" value={spec.cost_price} onChange={(event) => updateSpec(index, { cost_price: event.target.value })} placeholder="0.00" /></td>
                  <td className="px-3 py-2"><Input type="number" min="0" step="0.01" value={spec.retail_price} onChange={(event) => updateSpec(index, { retail_price: event.target.value })} placeholder="0.00" /></td>
                  <td className="whitespace-nowrap px-3 py-2 font-medium">{formatYuan(margin)}</td>
                  <td className="px-3 py-2"><Switch checked={spec.visible} onCheckedChange={(checked) => updateSpec(index, { visible: checked })} aria-label={t("admin.specVisible")} /></td>
                  <td className="px-3 py-2">{formData.stock_mode === "MANUAL" ? <Input type="number" min="0" step="1" value={spec.manual_stock} onChange={(event) => updateSpec(index, { manual_stock: event.target.value })} placeholder="0" /> : <span className="text-muted-foreground">{spec.card_key_count ?? 0}</span>}</td>
                  <td className="px-3 py-2"><Button type="button" variant="ghost" size="icon" onClick={() => removeSpec(index)} aria-label={t("admin.removeSpec")}><Trash2 className="h-4 w-4 text-destructive" /></Button></td>
                </tr>
              })}
            </tbody>
          </table>
          <div className="border-t border-border-subtle bg-surface-muted/45 p-3"><Button type="button" variant="outline" onClick={() => setFormSpecs((current) => [...current, { id: createId(), name: "", cost_price: "", retail_price: "", visible: true, sort_order: String(current.length), manual_stock: "" }])}><Plus className="mr-2 h-4 w-4" />{t("admin.addSpec")}</Button></div>
        </div>
        {!formData.spec_enabled && formSpecs.length > 0 && <p className="mt-2 text-xs text-muted-foreground">{t("admin.storedSpecsHint")}</p>}
      </FormSection>

      <FormSection title={t("admin.priceMatrix")} description={t("admin.priceMatrixHint")}>
        {levelsLoading ? <div className="flex items-center gap-2 text-sm text-muted-foreground"><Loader2 className="h-4 w-4 animate-spin" />{t("common.loading")}</div> : levels.length === 0 ? <p className="text-sm text-muted-foreground">{t("admin.noUserLevels")}</p> : (
          <div className="overflow-x-auto rounded-card border border-border-subtle bg-surface">
            <table className="w-full min-w-[960px] text-sm">
              <thead className="bg-surface-muted"><tr><th className="px-3 py-2 text-left">{t("admin.userLevel")}</th><th className="px-3 py-2 text-left">{t("admin.target")}</th><th className="px-3 py-2 text-left">{t("admin.defaultPrice")}</th><th className="px-3 py-2 text-left">{t("admin.overridePrice")}</th><th className="px-3 py-2 text-left">{t("admin.finalPrice")}</th><th className="px-3 py-2 text-left">{t("admin.pricingSource")}</th></tr></thead>
              <tbody>{levels.filter((level) => level.enabled).flatMap((level) => targets.map((target) => {
                const specId = target.id || null
                const row = matrixRow(level.id, specId)
                const isMarkup = level.pricing_type === "COST_MARKUP"
                return <tr key={`${level.id}:${specId || "product"}`} className="border-t border-border-subtle">
                  <td className="px-3 py-2"><div className="font-medium">{level.name}</div><div className="text-xs text-muted-foreground">{isMarkup ? `${t("admin.costMarkup")} ${level.markup_rate}%` : t("admin.retailPricing")}</div></td>
                  <td className="px-3 py-2">{target.name}</td>
                  <td className="px-3 py-2">{row ? formatYuan(row.default_price) : "—"}</td>
                  <td className="px-3 py-2"><Input className="w-32" type="number" min="0.01" step="0.01" value={overrideValue(level.id, specId)} onChange={(event) => updateOverride(level.id, specId, event.target.value)} placeholder={t("admin.noOverride")} /></td>
                  <td className={cn("whitespace-nowrap px-3 py-2 font-medium", row?.below_cost && "text-amber-600")}>{row ? formatYuan(row.final_price) : "—"}{row?.retail_capped && <span className="ml-1 text-xs text-muted-foreground">({t("admin.retailCap")})</span>}</td>
                  <td className="px-3 py-2">{row?.pricing_source === "LEVEL_OVERRIDE" ? t("admin.pricingSourceOverride") : row?.pricing_source === "LEVEL_MARKUP" ? t("admin.pricingSourceMarkup") : t("admin.pricingSourceRetail")}</td>
                </tr>
              }))}</tbody>
            </table>
          </div>
        )}
        {previewLoading && <p className="mt-2 text-xs text-muted-foreground">{t("admin.previewUpdating")}</p>}
        {preview?.warnings.map((warning) => <div key={warning} className="mt-3 flex items-start gap-2 rounded-card border border-amber-500/20 bg-amber-500/[0.07] p-3 text-sm text-amber-800 dark:text-amber-200"><AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />{warning}</div>)}
      </FormSection>

      <FormSection title={t("admin.detailMd")} description={t("admin.detailMdHint")}>
        <MarkdownEditor value={formData.detail_md} onChange={(value) => setField("detail_md", value)} uploadImage={uploadImage} />
      </FormSection>

      <div className={cn(
        "flex flex-col-reverse gap-3 border-t border-border-subtle bg-surface-raised pt-5 sm:flex-row sm:justify-end",
        stickyActions && "sticky bottom-0 z-10 -mx-5 px-5 pb-1 sm:-mx-7 sm:px-7",
      )}>
        <Button type="button" variant="outline" onClick={onCancel} disabled={saving}>{t("admin.cancel")}</Button>
        <Button type="button" onClick={() => void handleSave()} disabled={saving}>{saving ? t("admin.saving") : t("admin.save")}</Button>
      </div>
    </div>
  )
}

export { type ProductFormData }
