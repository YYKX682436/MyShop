"use client"

import { useCallback, useEffect, useRef, useState, type RefObject } from "react"
import { AlertTriangle, Loader2, Plus, Trash2 } from "lucide-react"
import { toast } from "sonner"

import { ImageUploadDropzone } from "@/components/admin/image-upload-dropzone"
import { MarkdownEditor } from "@/components/admin/markdown-editor"
import { ConfirmAlert, FormSection } from "@/components/admin/shared"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Switch } from "@/components/ui/switch"
import { useLocale } from "@/lib/context"
import { adminProductApi, requestOrThrow } from "@/services/api"
import { cn, formatYuan } from "@/lib/utils"
import type { Category, ProductDetail } from "@/types"

type ProductFormData = {
  title: string
  description: string
  detail_md: string
  category_id: string
  base_price: string
  cover_url: string
  low_stock_threshold: string
  wholesale_enabled: boolean
  is_enabled: boolean
  initial_sales: string
  sort_order: string
  delivery_type: "AUTO" | "MANUAL"
  customer_input_fields: { id: string; label: string }[]
  stock_mode: "CARD_KEY" | "MANUAL" | "UNLIMITED"
  manual_stock: string
}

type ProductSpecDraft = {
  id?: string
  name: string
  price: string
  manual_stock?: string
  card_key_count?: number
}

type SpecSyncOperation = "load" | "delete" | "update" | "add"

type SpecSyncFailure = {
  productId: string
  operation: SpecSyncOperation
  specName: string
  message: string
}

class SpecSyncError extends Error {
  constructor(
    readonly operation: SpecSyncOperation,
    readonly specName: string,
    message: string,
  ) {
    super(message)
    this.name = "SpecSyncError"
  }
}

function getErrorMessage(error: unknown, fallback: string): string {
  if (error instanceof Error && error.message) return error.message
  if (typeof error === "string" && error.trim()) return error
  return fallback
}

function emptyForm(): ProductFormData {
  return {
    title: "",
    description: "",
    detail_md: "",
    category_id: "",
    base_price: "",
    cover_url: "",
    low_stock_threshold: "10",
    wholesale_enabled: false,
    is_enabled: true,
    initial_sales: "",
    sort_order: "",
    delivery_type: "AUTO",
    customer_input_fields: [],
    stock_mode: "CARD_KEY",
    manual_stock: "",
  }
}

function getFormData(product: ProductDetail | null): ProductFormData {
  if (!product) return emptyForm()
  return {
    title: product.title,
    description: product.description || "",
    detail_md: product.detail_md || "",
    category_id: product.category_id,
    base_price: String(product.base_price),
    cover_url: product.cover_url || "",
    low_stock_threshold: String(product.low_stock_threshold ?? 10),
    wholesale_enabled: product.wholesale_enabled === true,
    is_enabled: product.is_enabled !== false,
    initial_sales: String(product.initial_sales ?? ""),
    sort_order: String(product.sort_order ?? ""),
    delivery_type: product.delivery_type === "MANUAL" ? "MANUAL" : "AUTO",
    customer_input_fields: (product.customer_input_fields || []).map((field) => ({ id: field.id, label: field.label })),
    stock_mode: product.stock_mode || (product.delivery_type === "MANUAL" ? "MANUAL" : "CARD_KEY"),
    manual_stock: product.stock_mode === "MANUAL" && product.stock_available != null ? String(product.stock_available) : "",
  }
}

function getSpecs(product: ProductDetail | null): ProductSpecDraft[] {
  return (product?.specs || []).map((spec) => ({
    id: spec.id,
    name: spec.name,
    price: String(spec.price),
    manual_stock: spec.manual_stock != null ? String(spec.manual_stock) : "",
    card_key_count: spec.card_key_count,
  }))
}

interface ProductFormProps {
  product: ProductDetail | null
  categories: Category[]
  onSaved: () => void | Promise<void>
  onCancel: () => void
}

export function ProductForm({ product, categories, onSaved, onCancel }: ProductFormProps) {
  const { t } = useLocale()
  const [formData, setFormData] = useState<ProductFormData>(() => getFormData(product))
  const [formSpecs, setFormSpecs] = useState<ProductSpecDraft[]>(() => getSpecs(product))
  const [specsEnabled, setSpecsEnabled] = useState(product?.spec_enabled === true)
  const [formErrors, setFormErrors] = useState<Record<string, boolean>>({})
  const [saving, setSaving] = useState(false)
  const [retryingSpecSync, setRetryingSpecSync] = useState(false)
  const [specSyncFailure, setSpecSyncFailure] = useState<SpecSyncFailure | null>(null)
  const [specDeleteConfirm, setSpecDeleteConfirm] = useState<{ idx: number; name: string; count: number } | null>(null)
  const titleRef = useRef<HTMLInputElement>(null)
  const categoryRef = useRef<HTMLSelectElement>(null)
  const basePriceRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    setFormData(getFormData(product))
    setFormSpecs(getSpecs(product))
    setSpecsEnabled(product?.spec_enabled === true)
    setFormErrors({})
    setSpecSyncFailure(null)
  }, [product])

  const uploadImage = useCallback(async (file: File) => {
    const result = await adminProductApi.uploadImage(file)
    return result.url
  }, [])

  const setField = <K extends keyof ProductFormData>(key: K, value: ProductFormData[K]) => {
    setFormData((current) => ({ ...current, [key]: value }))
  }

  const focusFirstError = (errors: Record<string, boolean>, refMap: Record<string, RefObject<HTMLElement | null>>) => {
    for (const key of Object.keys(errors)) {
      if (errors[key] && refMap[key]?.current) {
        refMap[key].current.focus()
        refMap[key].current.scrollIntoView({ behavior: "smooth", block: "center" })
        break
      }
    }
  }

  const syncProductSpecs = async (productId: string) => {
    if (!specsEnabled) return

    let existingSpecs: ProductDetail["specs"]
    try {
      existingSpecs = await requestOrThrow(() => adminProductApi.getSpecs(productId))
    } catch (error: unknown) {
      throw new SpecSyncError("load", t("admin.productSpecs"), getErrorMessage(error, t("admin.saveFailed")))
    }
    const existingIds = new Set(existingSpecs.map((spec) => spec.id))
    const keepIds = new Set(formSpecs.filter((spec) => spec.id).map((spec) => spec.id as string))
    const keepDraftNames = new Set(formSpecs.filter((spec) => !spec.id).map((spec) => spec.name.trim()))
    const runSpecOperation = async (operation: SpecSyncOperation, specName: string, action: () => Promise<unknown>) => {
      try {
        await requestOrThrow(action)
      } catch (error: unknown) {
        throw new SpecSyncError(operation, specName, getErrorMessage(error, t("admin.saveFailed")))
      }
    }

    for (const oldSpec of existingSpecs) {
      if (!keepIds.has(oldSpec.id) && !keepDraftNames.has(oldSpec.name.trim())) {
        await runSpecOperation("delete", oldSpec.name, () => adminProductApi.deleteSpec(productId, oldSpec.id))
      }
    }

    for (const spec of formSpecs) {
      const manualStock = formData.stock_mode === "MANUAL"
        ? Math.max(0, Number.parseInt(spec.manual_stock || "0", 10) || 0)
        : undefined
      if (spec.id && existingIds.has(spec.id)) {
        const old = existingSpecs.find((candidate) => candidate.id === spec.id)
        if (old && (old.name !== spec.name || String(old.price) !== spec.price || String(old.manual_stock ?? "") !== String(spec.manual_stock ?? ""))) {
          await runSpecOperation("update", spec.name, () => adminProductApi.updateSpec(productId, spec.id as string, { name: spec.name.trim(), price: Number.parseFloat(spec.price), manual_stock: manualStock }))
        }
      } else {
        const existingDraft = existingSpecs.find((candidate) => candidate.name.trim() === spec.name.trim())
        if (existingDraft) {
          if (String(existingDraft.price) !== spec.price || String(existingDraft.manual_stock ?? "") !== String(spec.manual_stock ?? "")) {
            await runSpecOperation("update", spec.name, () => adminProductApi.updateSpec(productId, existingDraft.id, { name: spec.name.trim(), price: Number.parseFloat(spec.price), manual_stock: manualStock }))
          }
        } else {
          await runSpecOperation("add", spec.name, () => adminProductApi.addSpec(productId, { name: spec.name.trim(), price: Number.parseFloat(spec.price), manual_stock: manualStock }))
        }
      }
    }
  }

  const buildSpecSyncFailure = (productId: string, error: unknown): SpecSyncFailure => {
    if (error instanceof SpecSyncError) {
      return { productId, operation: error.operation, specName: error.specName, message: error.message }
    }
    return { productId, operation: "update", specName: t("admin.unnamed"), message: getErrorMessage(error, t("admin.saveFailed")) }
  }

  const specSyncOperationLabel = (operation: SpecSyncOperation) => {
    if (operation === "load") return t("admin.specSyncLoad")
    if (operation === "delete") return t("admin.specSyncDelete")
    if (operation === "add") return t("admin.specSyncAdd")
    return t("admin.specSyncUpdate")
  }

  const reportSpecSyncFailure = (productId: string, error: unknown) => {
    const failure = buildSpecSyncFailure(productId, error)
    setSpecSyncFailure(failure)
    toast.error(`${t("admin.productBaseSavedSpecSyncFailed")} · ${t("admin.specSyncStep").replace("{action}", specSyncOperationLabel(failure.operation)).replace("{name}", failure.specName)} · ${failure.message}`)
  }

  const handleSave = async () => {
    const errors: Record<string, boolean> = {}
    if (!formData.title.trim()) errors.title = true
    if (!formData.category_id) errors.category_id = true
    if (!specsEnabled && (!formData.base_price || Number.parseFloat(formData.base_price) <= 0)) errors.base_price = true

    if (Object.keys(errors).length > 0) {
      setFormErrors(errors)
      const messages: string[] = []
      if (errors.title) messages.push(t("admin.productNameRequired"))
      if (errors.category_id) messages.push(t("admin.categoryRequired"))
      if (errors.base_price) messages.push(t("admin.basePriceRequired"))
      toast.error(messages.join("; "))
      focusFirstError(errors, { title: titleRef, category_id: categoryRef, base_price: basePriceRef })
      return
    }
    setFormErrors({})

    if (formData.customer_input_fields.length > 20) {
      toast.error(t("admin.customerInputLimit"))
      return
    }
    const customerInputLabels = new Set<string>()
    for (const field of formData.customer_input_fields) {
      const label = field.label.trim()
      if (!label) {
        toast.error(t("admin.customerInputNameRequired"))
        return
      }
      if (label.length > 50) {
        toast.error(t("admin.customerInputNameLength"))
        return
      }
      if ([...label].some((character) => /\p{C}/u.test(character))) {
        toast.error(t("admin.customerInputNameControl"))
        return
      }
      const normalizedLabel = label.toLocaleLowerCase()
      if (customerInputLabels.has(normalizedLabel)) {
        toast.error(t("admin.customerInputDuplicate").replace("{label}", label))
        return
      }
      customerInputLabels.add(normalizedLabel)
    }

    if (specsEnabled) {
      const specNames = new Set<string>()
      for (const spec of formSpecs) {
        if (!spec.name.trim()) {
          toast.error(t("admin.specNameRequired"))
          return
        }
        if (!spec.price || Number.parseFloat(spec.price) <= 0) {
          toast.error(t("admin.specPriceInvalid").replace("{name}", spec.name || t("admin.unnamed")))
          return
        }
        const normalizedName = spec.name.trim()
        if (specNames.has(normalizedName)) {
          toast.error(t("admin.specNameDuplicate").replace("{name}", normalizedName))
          return
        }
        specNames.add(normalizedName)
      }
    }

    if (formData.stock_mode === "MANUAL") {
      const rootStock = Number(formData.manual_stock || 0)
      if (!Number.isInteger(rootStock) || rootStock < 0) {
        toast.error(t("admin.manualStockInvalid"))
        return
      }
      for (const spec of formSpecs) {
        if (spec.manual_stock && (!Number.isInteger(Number(spec.manual_stock)) || Number(spec.manual_stock) < 0)) {
          toast.error(t("admin.specManualStockInvalid").replace("{name}", spec.name || t("admin.unnamed")))
          return
        }
      }
    }

    setSaving(true)
    try {
      let basePrice = Number.parseFloat(formData.base_price) || 0
      if (specsEnabled && formSpecs.length > 0) {
        const specPrices = formSpecs.map((spec) => Number.parseFloat(spec.price)).filter((price) => price > 0)
        if (specPrices.length > 0) basePrice = Math.min(...specPrices)
      }

      const payload = {
        title: formData.title.trim(),
        description: formData.description || undefined,
        detail_md: formData.detail_md || undefined,
        category_id: formData.category_id,
        base_price: basePrice,
        cover_url: formData.cover_url || undefined,
        low_stock_threshold: Number.parseInt(formData.low_stock_threshold, 10) || 10,
        wholesale_enabled: false,
        spec_enabled: specsEnabled,
        is_enabled: formData.is_enabled,
        initial_sales: Number.parseInt(formData.initial_sales, 10) || 0,
        sort_order: Number.parseInt(formData.sort_order, 10) || undefined,
        delivery_type: formData.delivery_type,
        customer_input_fields: formData.customer_input_fields.map((field) => ({ id: field.id, label: field.label.trim() })),
        stock_mode: formData.stock_mode,
        manual_stock: formData.stock_mode === "MANUAL" ? Math.max(0, Number.parseInt(formData.manual_stock, 10) || 0) : undefined,
      }

      const productId = product
        ? (await requestOrThrow(() => adminProductApi.update(product.id, payload)), product.id)
        : (await requestOrThrow(() => adminProductApi.create(payload))).id

      if (specsEnabled) {
        try {
          await syncProductSpecs(productId)
        } catch (specError: unknown) {
          reportSpecSyncFailure(productId, specError)
          return
        }
      }

      setSpecSyncFailure(null)
      toast.success(t("admin.productSaved"))
      await onSaved()
    } catch (error: unknown) {
      toast.error(error instanceof Error ? error.message : t("admin.saveFailed"))
    } finally {
      setSaving(false)
    }
  }

  const retrySpecSync = async () => {
    const failure = specSyncFailure
    if (!failure) return
    if (!specsEnabled) {
      toast.error(t("admin.specSyncRetryRequiresSpecs"))
      return
    }

    setRetryingSpecSync(true)
    try {
      await syncProductSpecs(failure.productId)
      setSpecSyncFailure(null)
      toast.success(t("admin.specSyncCompleted"))
      await onSaved()
    } catch (specError: unknown) {
      reportSpecSyncFailure(failure.productId, specError)
    } finally {
      setRetryingSpecSync(false)
    }
  }

  return (
    <div className="flex min-w-0 flex-col gap-6">
      <FormSection title={t("admin.productBasics")} description={t("admin.productBasicsHint")}>
        <div className="grid gap-5 lg:grid-cols-2">
          <label className="flex min-w-0 flex-col gap-1.5 text-sm font-medium lg:col-span-2">
            {t("admin.productNameReq")}
            <Input
              ref={titleRef}
              className={cn(formErrors.title && "border-destructive ring-2 ring-destructive/20")}
              placeholder={t("admin.productNamePlaceholder")}
              value={formData.title}
              onChange={(event) => {
                setField("title", event.target.value)
                setFormErrors((current) => ({ ...current, title: false }))
              }}
            />
          </label>
          <label className="flex min-w-0 flex-col gap-1.5 text-sm font-medium lg:col-span-2">
            {t("admin.productBrief")}
            <Input placeholder={t("admin.productBriefPlaceholder")} value={formData.description} onChange={(event) => setField("description", event.target.value)} />
          </label>
          <label className="flex min-w-0 flex-col gap-1.5 text-sm font-medium">
            {t("admin.categoryReq")}
            <select
              ref={categoryRef}
              className={cn("h-10 rounded-md border border-input bg-background px-3 text-sm font-normal text-foreground focus:outline-none focus:ring-2 focus:ring-ring", formErrors.category_id && "border-destructive ring-2 ring-destructive/20")}
              value={formData.category_id}
              onChange={(event) => {
                setField("category_id", event.target.value)
                setFormErrors((current) => ({ ...current, category_id: false }))
              }}
            >
              <option value="">{t("admin.categoryPlaceholder")}</option>
              {categories.map((category) => <option key={category.id} value={category.id}>{category.name}</option>)}
            </select>
          </label>
          <label className="flex min-w-0 flex-col gap-1.5 text-sm font-medium">
            {t("admin.basePriceReq")}
            <Input
              ref={basePriceRef}
              type="number"
              min="0"
              step="0.01"
              className={cn(formErrors.base_price && "border-destructive ring-2 ring-destructive/20")}
              placeholder="0.00"
              value={formData.base_price}
              onChange={(event) => {
                setField("base_price", event.target.value)
                setFormErrors((current) => ({ ...current, base_price: false }))
              }}
              disabled={specsEnabled}
            />
            {specsEnabled && <span className="text-xs font-normal text-muted-foreground">{t("admin.basePriceFromSpecs")}</span>}
          </label>
          <label className="flex min-w-0 flex-col gap-1.5 text-sm font-medium lg:col-span-2">
            {t("admin.coverUrl")}
            <ImageUploadDropzone value={formData.cover_url} onChange={(value) => setField("cover_url", value)} uploadImage={uploadImage} disabled={saving || retryingSpecSync} />
          </label>
        </div>
      </FormSection>

      <FormSection title={t("admin.inventorySettings")} description={t("admin.inventorySettingsHint")}>
        <div className="grid gap-5 lg:grid-cols-2">
          <label className="flex min-w-0 flex-col gap-1.5 text-sm font-medium">
            {t("admin.deliveryMethod")}
            <div className="grid grid-cols-2 gap-2">
              {(["AUTO", "MANUAL"] as const).map((deliveryType) => (
                <Button
                  key={deliveryType}
                  type="button"
                  variant={formData.delivery_type === deliveryType ? "default" : "outline"}
                  className="h-10"
                  onClick={() => setFormData((current) => ({ ...current, delivery_type: deliveryType, stock_mode: deliveryType === "AUTO" ? "CARD_KEY" : current.stock_mode === "UNLIMITED" ? "UNLIMITED" : "MANUAL" }))}
                >
                  {deliveryType === "AUTO" ? t("admin.autoDelivery") : t("admin.manualDelivery")}
                </Button>
              ))}
            </div>
          </label>
          <label className="flex min-w-0 flex-col gap-1.5 text-sm font-medium">
            {t("admin.stockMode")}
            <Select value={formData.stock_mode} onValueChange={(value) => setField("stock_mode", value as ProductFormData["stock_mode"])}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent>
                {formData.delivery_type === "AUTO" && <SelectItem value="CARD_KEY">{t("admin.cardKeyStock")}</SelectItem>}
                {formData.delivery_type === "MANUAL" && (
                  <>
                    <SelectItem value="MANUAL">{t("admin.manualStock")}</SelectItem>
                    <SelectItem value="UNLIMITED">{t("admin.unlimitedStock")}</SelectItem>
                  </>
                )}
              </SelectContent>
            </Select>
            <span className="text-xs font-normal text-muted-foreground">{t("admin.stockModeHint")}</span>
          </label>
          <div className="grid gap-5 sm:grid-cols-2 lg:col-span-2">
            <label className="flex min-w-0 flex-col gap-1.5 text-sm font-medium">
              {t("admin.sortOrderLabel")}
              <Input type="number" placeholder="0" value={formData.sort_order} onChange={(event) => setField("sort_order", event.target.value)} />
              <span className="text-xs font-normal text-muted-foreground">{t("admin.sortOrderDefaultHint")}</span>
            </label>
            <label className="flex min-w-0 flex-col gap-1.5 text-sm font-medium">
              {t("admin.lowStockAlert")}
              <Input type="number" min="0" placeholder="10" value={formData.low_stock_threshold} onChange={(event) => setField("low_stock_threshold", event.target.value)} />
            </label>
            <div className="flex items-center justify-between rounded-lg border border-border px-3 py-2.5 sm:col-span-2">
              <div>
                <p className="text-sm font-medium text-foreground">{t("admin.listingStatus")}</p>
                <p className="text-xs text-muted-foreground">{formData.is_enabled ? t("admin.active") : t("admin.inactive")}</p>
              </div>
              <Switch checked={formData.is_enabled} onCheckedChange={(checked) => setField("is_enabled", checked)} aria-label={t("admin.listingStatus")} />
            </div>
            <label className="flex min-w-0 flex-col gap-1.5 text-sm font-medium sm:col-span-2">
              {t("admin.initialSales")}
              <Input type="number" min="0" placeholder="0" value={formData.initial_sales} onChange={(event) => setField("initial_sales", event.target.value)} />
              <span className="text-xs font-normal text-muted-foreground">{t("admin.initialSalesHint")}</span>
            </label>
          </div>
          {formData.delivery_type === "MANUAL" && formData.stock_mode === "MANUAL" && (
            <div className="rounded-lg border border-amber-500/30 bg-amber-500/5 p-4 lg:col-span-2">
              <label className="flex flex-col gap-1.5 text-sm font-medium">
                {specsEnabled ? t("admin.defaultManualStock") : t("admin.availableManualStock")}
                <Input type="number" min="0" step="1" placeholder="0" value={formData.manual_stock} onChange={(event) => setField("manual_stock", event.target.value)} />
              </label>
              <p className="mt-2 text-xs text-muted-foreground">{t("admin.manualStockHint")}</p>
            </div>
          )}
        </div>
      </FormSection>

      <FormSection title={t("admin.customerInputFields")} description={`${formData.customer_input_fields.length}/20`}>
        <div className="space-y-2">
          {formData.customer_input_fields.map((field, index) => (
            <div key={field.id} className="flex items-center gap-2">
              <Input
                maxLength={50}
                value={field.label}
                placeholder={t("admin.customerInputPlaceholder")}
                onChange={(event) => setFormData((current) => ({
                  ...current,
                  customer_input_fields: current.customer_input_fields.map((item, itemIndex) => itemIndex === index ? { ...item, label: event.target.value } : item),
                }))}
              />
              <Button type="button" variant="ghost" size="icon" onClick={() => setFormData((current) => ({ ...current, customer_input_fields: current.customer_input_fields.filter((_, itemIndex) => itemIndex !== index) }))} title={t("admin.removeCustomerInput")} aria-label={t("admin.removeCustomerInput")}>
                <Trash2 className="h-4 w-4 text-destructive" aria-hidden="true" />
              </Button>
            </div>
          ))}
          <Button
            type="button"
            variant="outline"
            className="border-dashed"
            disabled={formData.customer_input_fields.length >= 20}
            onClick={() => setFormData((current) => ({
              ...current,
              customer_input_fields: [...current.customer_input_fields, { id: globalThis.crypto?.randomUUID?.() || `field-${Date.now()}-${current.customer_input_fields.length}`, label: "" }],
            }))}
          >
            <Plus className="mr-2 h-4 w-4" aria-hidden="true" />{t("admin.addCustomerInput")}
          </Button>
          {formData.customer_input_fields.length === 0 && <p className="text-xs text-muted-foreground">{t("admin.noCustomerInputFields")}</p>}
        </div>
      </FormSection>

      <FormSection title={t("admin.productSpecs")} description={t("admin.specsHint")}>
        <div className="flex items-center justify-between rounded-lg border border-border px-3 py-2.5">
          <span className="text-sm font-medium text-foreground">{t("admin.enableMultiSpec")}</span>
          <Switch
            checked={specsEnabled}
            onCheckedChange={(checked) => {
              setSpecsEnabled(checked)
              if (checked && formSpecs.length === 0) setFormSpecs([{ name: "", price: "" }])
            }}
            aria-label={t("admin.enableMultiSpec")}
          />
        </div>
        {specsEnabled && (
          <div className="mt-4 space-y-2 rounded-lg border border-border bg-muted/20 p-3">
            {formSpecs.map((spec, index) => (
              <div key={spec.id || index} className="flex flex-wrap items-center gap-2">
                <Input className="min-w-[12rem] flex-1" placeholder={t("admin.specNamePlaceholder")} value={spec.name} onChange={(event) => setFormSpecs((current) => current.map((item, itemIndex) => itemIndex === index ? { ...item, name: event.target.value } : item))} />
                <Input className="w-36" type="number" step="0.01" placeholder={t("admin.specPricePlaceholder")} value={spec.price} onChange={(event) => setFormSpecs((current) => current.map((item, itemIndex) => itemIndex === index ? { ...item, price: event.target.value } : item))} />
                {formData.stock_mode === "MANUAL" && <Input className="w-28" type="number" min="0" step="1" placeholder={t("admin.specStockPlaceholder")} value={spec.manual_stock || ""} onChange={(event) => setFormSpecs((current) => current.map((item, itemIndex) => itemIndex === index ? { ...item, manual_stock: event.target.value } : item))} />}
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  title={t("admin.removeSpec")}
                  aria-label={t("admin.removeSpec")}
                  onClick={() => {
                    const current = formSpecs[index]
                    if (current.id && current.card_key_count && current.card_key_count > 0) setSpecDeleteConfirm({ idx: index, name: current.name, count: current.card_key_count })
                    else setFormSpecs((items) => items.filter((_, itemIndex) => itemIndex !== index))
                  }}
                >
                  <Trash2 className="h-4 w-4 text-destructive" aria-hidden="true" />
                </Button>
              </div>
            ))}
            <Button type="button" variant="outline" className="w-full border-dashed" onClick={() => setFormSpecs((current) => [...current, { name: "", price: "" }])}>
              <Plus className="mr-2 h-4 w-4" aria-hidden="true" />{t("admin.addSpec")}
            </Button>
          </div>
        )}
        {!specsEnabled && formSpecs.length > 0 && formSpecs.some((spec) => spec.id) && (
          <div className="mt-4 space-y-2 rounded-lg border border-border bg-muted/30 p-3 opacity-70">
            <p className="text-xs text-muted-foreground">{t("admin.storedSpecsHint")}</p>
            {formSpecs.filter((spec) => spec.id).map((spec) => (
              <div key={spec.id} className="flex items-center gap-2 text-sm text-muted-foreground">
                <span className="min-w-0 flex-1 truncate rounded-md border border-border bg-muted px-3 py-2">{spec.name}</span>
                <span className="w-36 rounded-md border border-border bg-muted px-3 py-2">{formatYuan(Number.parseFloat(spec.price) || 0)}</span>
              </div>
            ))}
          </div>
        )}
      </FormSection>

      {specSyncFailure && (
        <div role="alert" className="flex flex-col gap-3 rounded-xl border border-amber-500/30 bg-amber-500/5 p-4 text-sm text-amber-800 dark:text-amber-200">
          <div className="flex items-start gap-3">
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
            <div className="min-w-0 space-y-1">
              <p className="font-medium">{t("admin.productBaseSavedSpecSyncFailed")}</p>
              <p>{t("admin.specSyncStep").replace("{action}", specSyncOperationLabel(specSyncFailure.operation)).replace("{name}", specSyncFailure.specName)}</p>
              <p className="break-words text-xs opacity-80">{specSyncFailure.message}</p>
            </div>
          </div>
          <div className="flex justify-end">
            <Button type="button" variant="outline" onClick={() => void retrySpecSync()} disabled={saving || retryingSpecSync || !specsEnabled}>
              {retryingSpecSync && <Loader2 className="mr-2 h-4 w-4 animate-spin" aria-hidden="true" />}
              {retryingSpecSync ? t("admin.retryingSpecSync") : t("admin.retrySpecSync")}
            </Button>
          </div>
        </div>
      )}

      <FormSection title={t("admin.detailMd")} description={t("admin.detailMdHint")}>
        <MarkdownEditor value={formData.detail_md} onChange={(value) => setField("detail_md", value)} uploadImage={uploadImage} />
      </FormSection>

      <div className="flex flex-col-reverse gap-3 border-t border-border pt-5 sm:flex-row sm:justify-end">
        <Button type="button" variant="outline" onClick={onCancel} disabled={saving || retryingSpecSync}>{t("admin.cancel")}</Button>
        <Button type="button" onClick={() => void handleSave()} disabled={saving || retryingSpecSync}>{saving ? t("admin.saving") : t("admin.save")}</Button>
      </div>

      <ConfirmAlert
        open={specDeleteConfirm !== null}
        onOpenChange={(open) => { if (!open) setSpecDeleteConfirm(null) }}
        title={t("admin.deleteSpecConfirm")}
        description={specDeleteConfirm ? t("admin.deleteSpecDescription").replace("{name}", specDeleteConfirm.name).replace("{count}", String(specDeleteConfirm.count)) : undefined}
        confirmLabel={t("admin.delete")}
        cancelLabel={t("admin.cancel")}
        destructive
        onConfirm={() => {
          if (!specDeleteConfirm) return
          setFormSpecs((current) => current.filter((_, index) => index !== specDeleteConfirm.idx))
          setSpecDeleteConfirm(null)
        }}
      />
    </div>
  )
}

export { type ProductFormData }
