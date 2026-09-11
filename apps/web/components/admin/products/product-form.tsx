"use client"

import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { AlertTriangle, ChevronDown, ChevronUp, Loader2, Plus, Trash2 } from "lucide-react"
import { toast } from "sonner"

import { ImageUploadDropzone } from "@/components/admin/image-upload-dropzone"
import { MarkdownEditor } from "@/components/admin/markdown-editor"
import { CardKeyImportDialog } from "@/components/admin/products/card-key-import-dialog"
import { FormSection, StatusBadge } from "@/components/admin/shared"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Switch } from "@/components/ui/switch"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { useLocale } from "@/lib/context"
import { getCategoryChildren, getCategorySelection, getRootCategories } from "@/lib/categories"
import {
  buildPricingPreviewDraft,
  getCardKeyImportAvailability,
  getProductEditorTabForMessage,
  PRODUCT_EDITOR_TABS,
  reorderSpecs,
  shouldResetProductEditorTab,
  toNumberOrNull,
  withCurrentSpecOrder,
  type ProductEditorTab,
} from "@/lib/product-editor"
import { isValidIntegerDraft, isValidMoneyDraft, validateProductTitleDraft } from "@/lib/product-quick-edit"
import { cn, formatYuan } from "@/lib/utils"
import { adminProductApi, adminUserLevelApi, requestOrThrow, type AdminProductUpsertPayload } from "@/services/api"
import type {
  AdminPricingPreview,
  AdminProductDetail,
  Category,
  UserLevel,
} from "@/types"

type DeliveryType = "AUTO" | "MANUAL"
type StockMode = "CARD_KEY" | "MANUAL" | "UNLIMITED"

export type ProductSpecDraft = {
  id: string
  name: string
  cost_price: string
  retail_price: string
  visible: boolean
  manual_stock: string
  card_key_count?: number
}

export type OverrideDraft = {
  user_level_id: string
  spec_id: string | null
  price: string
}

/** One aggregate draft owns every editable product value across all tabs. */
export type ProductFormData = {
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
  sort_weight: string
  delivery_type: DeliveryType
  customer_input_fields: { id: string; label: string }[]
  stock_mode: StockMode
  manual_stock: string
  specs: ProductSpecDraft[]
  overrides: OverrideDraft[]
}

type ValidationFailure = {
  tab: ProductEditorTab
  field: string
  message: string
}

function createId() {
  return globalThis.crypto?.randomUUID?.()
    || `xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx`.replace(/[xy]/g, (character) => {
      const random = Math.random() * 16 | 0
      const value = character === "x" ? random : (random & 0x3 | 0x8)
      return value.toString(16)
    })
}

function emptySpec(): ProductSpecDraft {
  return { id: createId(), name: "", cost_price: "", retail_price: "", visible: true, manual_stock: "" }
}

function emptyForm(): Omit<ProductFormData, "specs" | "overrides"> {
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
    sort_weight: "0",
    delivery_type: "AUTO",
    customer_input_fields: [],
    stock_mode: "CARD_KEY",
    manual_stock: "",
  }
}

function getFormData(product: AdminProductDetail | null): Omit<ProductFormData, "specs" | "overrides"> {
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
    sort_weight: String(product.sort_weight ?? 0),
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

function getProductDraft(product: AdminProductDetail | null): ProductFormData {
  return { ...getFormData(product), specs: getSpecs(product), overrides: getOverrides(product) }
}

function getInitialPreview(product: AdminProductDetail | null): AdminPricingPreview | null {
  if (!product?.pricing_matrix?.length) return null
  return {
    pricing_matrix: product.pricing_matrix.map((row) => ({
      user_level_id: row.user_level_id,
      user_level_name: row.user_level_name,
      pricing_type: row.pricing_type,
      markup_rate: row.markup_rate,
      spec_id: row.spec_id,
      rule_price: row.default_price,
      override_price: row.override_price,
      final_price: row.final_price,
      pricing_status: "READY",
      retail_capped: row.retail_capped,
      below_cost: row.below_cost,
    })),
    warnings: product.warnings || [],
  }
}

function formatMargin(costValue: string, retailValue: string) {
  const cost = toNumberOrNull(costValue)
  const retail = toNumberOrNull(retailValue)
  if (cost === null || retail === null) return null
  return Number((retail - cost).toFixed(2))
}

function formatMarginRate(costValue: string, retailValue: string) {
  const margin = formatMargin(costValue, retailValue)
  const retail = toNumberOrNull(retailValue)
  if (margin === null || retail === null || retail <= 0) return null
  return (margin / retail) * 100
}

interface ProductFormProps {
  product: AdminProductDetail | null
  categories: Category[]
  onSaved: () => void | Promise<void>
  onCancel: () => void
  onDirtyChange?: (dirty: boolean) => void
  onProductRefresh?: () => void | Promise<void>
}

export function ProductForm({ product, categories, onSaved, onCancel, onDirtyChange, onProductRefresh }: ProductFormProps) {
  const { t } = useLocale()
  const [draft, setDraft] = useState<ProductFormData>(() => getProductDraft(product))
  const [activeTab, setActiveTab] = useState<ProductEditorTab>("basic")
  const [tabErrors, setTabErrors] = useState<Partial<Record<ProductEditorTab, boolean>>>({})
  const [levels, setLevels] = useState<UserLevel[]>([])
  const [levelsLoading, setLevelsLoading] = useState(true)
  const [preview, setPreview] = useState<AdminPricingPreview | null>(() => getInitialPreview(product))
  const [previewLoading, setPreviewLoading] = useState(false)
  const [previewError, setPreviewError] = useState(false)
  const [saving, setSaving] = useState(false)
  const [dirty, setDirtyState] = useState(false)
  const [cardKeyImportOpen, setCardKeyImportOpen] = useState(false)
  const dirtyRef = useRef(false)
  const onDirtyChangeRef = useRef(onDirtyChange)
  const fieldRefs = useRef<Record<string, HTMLElement | null>>({})
  const previousProductIdRef = useRef<string | null | undefined>(undefined)
  const categorySelection = getCategorySelection(categories, draft.category_id)
  const rootCategories = getRootCategories(categories)
  const secondaryCategories = getCategoryChildren(categories, categorySelection.primaryId)

  const setDirty = useCallback((nextDirty: boolean) => {
    if (dirtyRef.current === nextDirty) return
    dirtyRef.current = nextDirty
    setDirtyState(nextDirty)
    onDirtyChangeRef.current?.(nextDirty)
  }, [])

  useEffect(() => {
    onDirtyChangeRef.current = onDirtyChange
  }, [onDirtyChange])

  useEffect(() => {
    const nextProductId = product?.id ?? null
    setDraft(getProductDraft(product))
    if (shouldResetProductEditorTab(previousProductIdRef.current, nextProductId)) {
      setActiveTab("basic")
    }
    previousProductIdRef.current = nextProductId
    setTabErrors({})
    setDirty(false)
    setPreview(getInitialPreview(product))
    setPreviewError(false)
    setCardKeyImportOpen(false)
  }, [product, setDirty])

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

  const uploadImage = useCallback(async (file: File) => (await adminProductApi.uploadImage(file)).url, [])

  const markDirty = useCallback(() => {
    setDirty(true)
    setTabErrors({})
  }, [setDirty])

  const setField = useCallback(function updateField<K extends keyof ProductFormData>(key: K, value: ProductFormData[K]) {
    markDirty()
    setDraft((current) => ({ ...current, [key]: value }))
  }, [markDirty])

  const registerField = useCallback((field: string) => (node: HTMLElement | null) => {
    fieldRefs.current[field] = node
  }, [])

  const focusField = useCallback((field: string) => {
    window.requestAnimationFrame(() => {
      const node = fieldRefs.current[field]
      node?.scrollIntoView({ block: "center", behavior: "smooth" })
      node?.focus()
    })
  }, [])

  const showValidationFailure = useCallback((failure: ValidationFailure) => {
    setTabErrors({ [failure.tab]: true })
    setActiveTab(failure.tab)
    toast.error(failure.message)
    window.setTimeout(() => focusField(failure.field), 0)
  }, [focusField])

  const handleCoverChange = useCallback((value: string) => setField("cover_url", value), [setField])
  const handleDetailChange = useCallback((value: string) => setField("detail_md", value), [setField])

  const pricingSpecSignature = draft.specs
    .map((spec) => `${spec.id}:${spec.cost_price}:${spec.retail_price}`)
    .sort()
    .join("|")
  const pricingOverrideSignature = draft.overrides
    .map((override) => `${override.user_level_id}:${override.spec_id || ""}:${override.price}`)
    .sort()
    .join("|")
  const pricingDraftSignature = `${draft.spec_enabled}|${draft.cost_price}|${draft.retail_price}|${pricingSpecSignature}|${pricingOverrideSignature}`
  const previewDraft = useMemo(() => buildPricingPreviewDraft({
    spec_enabled: draft.spec_enabled,
    cost_price: draft.cost_price,
    retail_price: draft.retail_price,
    specs: draft.specs,
    overrides: draft.overrides,
  }), [draft.cost_price, draft.overrides, draft.retail_price, draft.spec_enabled, draft.specs])
  const previewDraftRef = useRef(previewDraft)

  useEffect(() => {
    previewDraftRef.current = previewDraft
  }, [previewDraft])

  useEffect(() => {
    if (levelsLoading) {
      setPreviewLoading(false)
      return
    }
    setPreviewLoading(true)
    setPreviewError(false)
    const controller = new AbortController()
    const timer = window.setTimeout(() => {
      void adminProductApi.previewPricing(previewDraftRef.current, controller.signal).then((nextPreview) => {
        if (!controller.signal.aborted) setPreview(nextPreview)
      }).catch(() => {
        if (!controller.signal.aborted) setPreviewError(true)
      }).finally(() => {
        if (!controller.signal.aborted) setPreviewLoading(false)
      })
    }, 250)
    return () => {
      window.clearTimeout(timer)
      controller.abort()
    }
  }, [levelsLoading, pricingDraftSignature])

  const payload = useMemo<AdminProductUpsertPayload>(() => {
    const visibleRetails = draft.specs
      .filter((spec) => spec.visible)
      .map((spec) => toNumberOrNull(spec.retail_price))
      .filter((price): price is number => price !== null && price > 0)
    const rootRetail = draft.spec_enabled
      ? visibleRetails.length ? Math.min(...visibleRetails) : toNumberOrNull(draft.retail_price)
      : toNumberOrNull(draft.retail_price)
    return {
      title: draft.title.trim(),
      description: draft.description || undefined,
      detail_md: draft.detail_md || undefined,
      cover_url: draft.cover_url || undefined,
      cost_price: draft.spec_enabled ? null : toNumberOrNull(draft.cost_price),
      retail_price: rootRetail,
      category_id: draft.category_id,
      low_stock_threshold: toNumberOrNull(draft.low_stock_threshold),
      spec_enabled: draft.spec_enabled,
      is_enabled: draft.is_enabled,
      initial_sales: toNumberOrNull(draft.initial_sales),
      sort_weight: toNumberOrNull(draft.sort_weight),
      delivery_type: draft.delivery_type,
      customer_input_fields: draft.customer_input_fields,
      stock_mode: draft.stock_mode,
      manual_stock: draft.stock_mode === "MANUAL" && !draft.spec_enabled
        ? toNumberOrNull(draft.manual_stock) ?? undefined : undefined,
      specs: withCurrentSpecOrder(draft.specs).map((spec) => ({
        id: spec.id,
        name: spec.name.trim(),
        cost_price: toNumberOrNull(spec.cost_price),
        retail_price: toNumberOrNull(spec.retail_price),
        visible: spec.visible,
        sort_order: spec.sort_order,
        manual_stock: draft.stock_mode === "MANUAL" ? toNumberOrNull(spec.manual_stock) ?? undefined : undefined,
      })),
      overrides: draft.overrides.map((override) => ({
        user_level_id: override.user_level_id,
        spec_id: override.spec_id,
        price: toNumberOrNull(override.price),
      })),
    }
  }, [draft])

  const updateSpec = (index: number, patch: Partial<ProductSpecDraft>) => {
    markDirty()
    setDraft((current) => ({
      ...current,
      specs: current.specs.map((spec, currentIndex) => currentIndex === index ? { ...spec, ...patch } : spec),
    }))
  }

  const moveSpec = (index: number, direction: -1 | 1) => {
    markDirty()
    setDraft((current) => ({ ...current, specs: reorderSpecs(current.specs, index, direction) }))
  }

  const removeSpec = (index: number) => {
    const spec = draft.specs[index]
    if (!spec) return
    if (spec.card_key_count && spec.card_key_count > 0
      && !window.confirm(t("admin.deleteSpecDescription").replace("{name}", spec.name).replace("{count}", String(spec.card_key_count)))) return
    markDirty()
    setDraft((current) => ({
      ...current,
      specs: current.specs.filter((_, currentIndex) => currentIndex !== index),
      overrides: current.overrides.filter((override) => override.spec_id !== spec.id),
    }))
  }

  const updateOverride = (userLevelId: string, specId: string | null, value: string) => {
    markDirty()
    setDraft((current) => {
      const index = current.overrides.findIndex((override) => override.user_level_id === userLevelId && override.spec_id === specId)
      if (!value.trim()) return index < 0 ? current : { ...current, overrides: current.overrides.filter((_, currentIndex) => currentIndex !== index) }
      const next = { user_level_id: userLevelId, spec_id: specId, price: value }
      if (index < 0) return { ...current, overrides: [...current.overrides, next] }
      return { ...current, overrides: current.overrides.map((override, currentIndex) => currentIndex === index ? next : override) }
    })
  }

  const overrideValue = (userLevelId: string, specId: string | null) =>
    draft.overrides.find((override) => override.user_level_id === userLevelId && override.spec_id === specId)?.price || ""

  const validateCustomerFields = (): ValidationFailure | null => {
    if (draft.customer_input_fields.length > 20) return { tab: "inventory", field: "customer-inputs", message: t("admin.customerInputLimit") }
    const labels = new Set<string>()
    for (const field of draft.customer_input_fields) {
      const label = field.label.trim()
      if (!label) return { tab: "inventory", field: `customer-${field.id}`, message: t("admin.customerInputNameRequired") }
      if (label.length > 50) return { tab: "inventory", field: `customer-${field.id}`, message: t("admin.customerInputNameLength") }
      if ([...label].some((character) => /\p{C}/u.test(character))) {
        return { tab: "inventory", field: `customer-${field.id}`, message: t("admin.customerInputNameControl") }
      }
      const normalized = label.toLocaleLowerCase()
      if (!labels.add(normalized)) {
        return {
          tab: "inventory",
          field: `customer-${field.id}`,
          message: t("admin.customerInputDuplicate").replace("{label}", label),
        }
      }
    }
    return null
  }

  const validate = (): ValidationFailure | null => {
    const titleError = validateProductTitleDraft(draft.title)
    if (titleError === "required") return { tab: "basic", field: "title", message: t("admin.productNameRequired") }
    if (titleError === "length") return { tab: "basic", field: "title", message: t("admin.productNameLength") }
    if (!draft.category_id) return { tab: "basic", field: "category_id", message: t("admin.categoryRequired") }
    if (!isValidIntegerDraft(draft.initial_sales, 0)) return { tab: "basic", field: "initial_sales", message: t("admin.initialSalesInvalid") }
    if (!isValidIntegerDraft(draft.sort_weight)) return { tab: "basic", field: "sort_weight", message: t("admin.sortWeightInvalid") }

    const customerError = validateCustomerFields()
    if (customerError) return customerError
    if (!isValidIntegerDraft(draft.low_stock_threshold, 0)) {
      return { tab: "inventory", field: "low_stock_threshold", message: t("admin.lowStockThresholdInvalid") }
    }

    if (!draft.spec_enabled) {
      if (!isValidMoneyDraft(draft.cost_price, false)) return { tab: "pricing", field: "cost_price", message: t("admin.costPriceRequired") }
      if (!isValidMoneyDraft(draft.retail_price, true)) return { tab: "pricing", field: "retail_price", message: t("admin.retailPriceRequired") }
    }

    if (draft.spec_enabled && draft.is_enabled && (draft.specs.length === 0 || !draft.specs.some((spec) => spec.visible))) {
      return { tab: "specs", field: "spec-mode", message: t("admin.visibleSpecRequired") }
    }
    const names = new Set<string>()
    for (const spec of draft.specs) {
      if (!spec.name.trim()) return { tab: "specs", field: `spec-name-${spec.id}`, message: t("admin.specNameRequired") }
      const normalizedName = spec.name.trim().toLocaleLowerCase()
      if (!names.add(normalizedName)) {
        return {
          tab: "specs",
          field: `spec-name-${spec.id}`,
          message: t("admin.specNameDuplicate").replace("{name}", spec.name.trim()),
        }
      }
      if (!isValidMoneyDraft(spec.cost_price, false)) {
        return {
          tab: "pricing",
          field: `spec-cost-${spec.id}`,
          message: t("admin.specCostInvalid").replace("{name}", spec.name || t("admin.unnamed")),
        }
      }
      if (!isValidMoneyDraft(spec.retail_price, true)) {
        return {
          tab: "pricing",
          field: `spec-retail-${spec.id}`,
          message: t("admin.specRetailInvalid").replace("{name}", spec.name || t("admin.unnamed")),
        }
      }
      if (draft.stock_mode === "MANUAL" && !isValidIntegerDraft(spec.manual_stock, 0)) {
        return {
          tab: "inventory",
          field: `spec-stock-${spec.id}`,
          message: t("admin.specManualStockInvalid").replace("{name}", spec.name || t("admin.unnamed")),
        }
      }
    }

    if (draft.stock_mode === "MANUAL" && !draft.spec_enabled && !isValidIntegerDraft(draft.manual_stock, 0)) {
      return { tab: "inventory", field: "manual_stock", message: t("admin.manualStockInvalid") }
    }

    const specIds = new Set(draft.specs.map((spec) => spec.id))
    const keys = new Set<string>()
    for (const override of draft.overrides) {
      if (!override.user_level_id || !isValidMoneyDraft(override.price, true)) {
        return { tab: "pricing", field: `override-${override.user_level_id}-${override.spec_id || "product"}`, message: t("admin.overridePriceInvalid") }
      }
      if (draft.spec_enabled && (!override.spec_id || !specIds.has(override.spec_id))) {
        return { tab: "pricing", field: `override-${override.user_level_id}-${override.spec_id || "product"}`, message: t("admin.overrideSpecInvalid") }
      }
      if (!draft.spec_enabled && override.spec_id) {
        return { tab: "pricing", field: `override-${override.user_level_id}-${override.spec_id}`, message: t("admin.overrideSpecInvalid") }
      }
      const target = draft.spec_enabled ? draft.specs.find((spec) => spec.id === override.spec_id) : null
      const retail = target ? toNumberOrNull(target.retail_price) : toNumberOrNull(draft.retail_price)
      if (retail !== null && Number(override.price) > retail) {
        return { tab: "pricing", field: `override-${override.user_level_id}-${override.spec_id || "product"}`, message: t("admin.overrideAboveRetail") }
      }
      const key = `${override.user_level_id}:${override.spec_id || ""}`
      if (!keys.add(key)) return { tab: "pricing", field: `override-${override.user_level_id}-${override.spec_id || "product"}`, message: t("admin.overrideDuplicate") }
    }
    return null
  }

  const defaultFieldForTab = (tab: ProductEditorTab) => {
    if (tab === "basic") return "title"
    if (tab === "specs") return draft.specs[0] ? `spec-name-${draft.specs[0].id}` : "spec-mode"
    if (tab === "pricing") return draft.spec_enabled && draft.specs[0] ? `spec-cost-${draft.specs[0].id}` : "cost_price"
    if (tab === "inventory") return "low_stock_threshold"
    return "detail_md"
  }

  const handleSave = async () => {
    const failure = validate()
    if (failure) {
      showValidationFailure(failure)
      return
    }
    setSaving(true)
    try {
      if (product) await requestOrThrow(() => adminProductApi.update(product.id, payload))
      else await requestOrThrow(() => adminProductApi.create(payload))
      toast.success(t("admin.productSaved"))
      setDirty(false)
      await onSaved()
    } catch (requestError: unknown) {
      const message = requestError instanceof Error ? requestError.message : t("admin.saveFailed")
      const tab = getProductEditorTabForMessage(message)
      setTabErrors({ [tab]: true })
      setActiveTab(tab)
      toast.error(message)
      window.setTimeout(() => focusField(defaultFieldForTab(tab)), 0)
    } finally {
      setSaving(false)
    }
  }

  const handleDeliveryChange = (deliveryType: DeliveryType) => {
    markDirty()
    setDraft((current) => ({
      ...current,
      delivery_type: deliveryType,
      stock_mode: deliveryType === "AUTO" ? "CARD_KEY" : current.stock_mode === "CARD_KEY" ? "MANUAL" : current.stock_mode,
    }))
  }

  const toggleSpecMode = (checked: boolean) => {
    if (checked !== draft.spec_enabled && product && !window.confirm(t("admin.specModeSwitchWarning"))) return
    markDirty()
    setDraft((current) => ({
      ...current,
      spec_enabled: checked,
      specs: checked && current.specs.length === 0 ? [emptySpec()] : current.specs,
      overrides: current.overrides.filter((override) => checked ? override.spec_id !== null : override.spec_id === null),
    }))
  }

  const updateCustomerField = (index: number, label: string) => {
    markDirty()
    setDraft((current) => ({
      ...current,
      customer_input_fields: current.customer_input_fields.map((item, itemIndex) => itemIndex === index ? { ...item, label } : item),
    }))
  }

  const removeCustomerField = (index: number) => {
    markDirty()
    setDraft((current) => ({
      ...current,
      customer_input_fields: current.customer_input_fields.filter((_, itemIndex) => itemIndex !== index),
    }))
  }

  const addCustomerField = () => {
    markDirty()
    setDraft((current) => ({
      ...current,
      customer_input_fields: [...current.customer_input_fields, { id: createId(), label: "" }],
    }))
  }

  const addSpec = () => {
    markDirty()
    setDraft((current) => ({ ...current, specs: [...current.specs, emptySpec()] }))
  }

  const pricingTargets = draft.spec_enabled
    ? draft.specs.map((spec) => ({ id: spec.id, name: spec.name || t("admin.unnamed") }))
    : [{ id: null, name: t("admin.product") }]
  const matrixRow = (levelId: string, specId: string | null) => preview?.pricing_matrix.find((row) => row.user_level_id === levelId && row.spec_id === specId)

  const tabItems: { value: ProductEditorTab; label: string }[] = [
    { value: "basic", label: t("admin.productTabBasic") },
    { value: "specs", label: t("admin.productTabSpecs") },
    { value: "pricing", label: t("admin.productTabPricing") },
    { value: "inventory", label: t("admin.productTabInventory") },
    { value: "details", label: t("admin.productTabDetails") },
  ]
  const cardKeyImportAvailability = getCardKeyImportAvailability(product, draft.stock_mode, dirty)

  return (
    <form className="flex min-w-0 flex-col gap-5" onSubmit={(event) => { event.preventDefault(); void handleSave() }}>
      <Tabs value={activeTab} onValueChange={(value) => {
          if (PRODUCT_EDITOR_TABS.includes(value as ProductEditorTab)) setActiveTab(value as ProductEditorTab)
        }}>
        <div className="-mx-1 border-b border-border-subtle px-1 py-2">
          <TabsList className="flex h-auto w-full max-w-full justify-start overflow-x-auto p-1" aria-label={t("admin.productEditorHint")}>
            {tabItems.map((tab) => (
              <TabsTrigger key={tab.value} value={tab.value} className="min-w-max gap-2 px-4 py-2">
                {tab.label}
                {tabErrors[tab.value] && <span className="inline-flex h-4 min-w-4 items-center justify-center rounded-full bg-destructive px-1 text-[10px] leading-none text-destructive-foreground" aria-label={t("admin.tabError")}>!</span>}
              </TabsTrigger>
            ))}
          </TabsList>
        </div>

        <TabsContent value="basic" forceMount className="data-[state=inactive]:hidden">
            <FormSection title={t("admin.productBasics")} description={t("admin.productBasicsHint")}>
              <div className="mb-5">
                <div className="mb-2 text-sm font-medium">{t("admin.coverUrl")}</div>
                <ImageUploadDropzone value={draft.cover_url} onChange={handleCoverChange} uploadImage={uploadImage} disabled={saving} />
              </div>
              <div className="grid gap-5 lg:grid-cols-2">
                <label className="flex min-w-0 flex-col gap-1.5 text-sm font-medium lg:col-span-2">
                  {t("admin.productNameReq")}
                  <Input ref={registerField("title")} value={draft.title} onChange={(event) => setField("title", event.target.value)} placeholder={t("admin.productNamePlaceholder")} />
                </label>
                <label className="flex min-w-0 flex-col gap-1.5 text-sm font-medium lg:col-span-2">
                  {t("admin.productBrief")}
                  <Input value={draft.description} onChange={(event) => setField("description", event.target.value)} placeholder={t("admin.productBriefPlaceholder")} />
                </label>
                <div className="grid min-w-0 gap-3 sm:grid-cols-2 lg:col-span-2">
                  <label className="flex min-w-0 flex-col gap-1.5 text-sm font-medium">
                    {t("admin.categoryRootLevel")} *
                    <select ref={registerField("category_id")} className="h-11 rounded-control border border-input bg-surface px-3 text-sm font-normal" value={categorySelection.primaryId} onChange={(event) => setField("category_id", event.target.value)}>
                      <option value="">{t("admin.categoryPlaceholder")}</option>
                      {rootCategories.map((category) => <option key={category.id} value={category.id}>{category.name}</option>)}
                    </select>
                  </label>
                  <label className="flex min-w-0 flex-col gap-1.5 text-sm font-medium">
                    {t("admin.categoryChildLevel")}
                    <select className="h-11 rounded-control border border-input bg-surface px-3 text-sm font-normal disabled:cursor-not-allowed disabled:opacity-60" value={categorySelection.secondaryId} onChange={(event) => setField("category_id", event.target.value || categorySelection.primaryId)} disabled={!categorySelection.primaryId || secondaryCategories.length === 0} aria-label={t("admin.categoryChildLevel")}>
                      <option value="">{secondaryCategories.length === 0 ? t("admin.categoryNoChildren") : t("admin.categoryRootOption")}</option>
                      {secondaryCategories.map((category) => <option key={category.id} value={category.id}>{category.name}</option>)}
                    </select>
                  </label>
                </div>
                <div className="grid gap-3 sm:grid-cols-2">
                  <label className="flex min-w-0 flex-col gap-1.5 text-sm font-medium">
                    {t("admin.initialSales")}
                    <Input ref={registerField("initial_sales")} type="number" min="0" step="1" value={draft.initial_sales} onChange={(event) => setField("initial_sales", event.target.value)} />
                    <span className="text-xs font-normal text-muted-foreground">{t("admin.initialSalesHint")}</span>
                  </label>
                  <label className="flex min-w-0 flex-col gap-1.5 text-sm font-medium">
                    {t("admin.sortWeightLabel")}
                    <Input ref={registerField("sort_weight")} type="number" step="1" value={draft.sort_weight} onChange={(event) => setField("sort_weight", event.target.value)} />
                    <span className="text-xs font-normal text-muted-foreground">{t("admin.sortWeightHint")}</span>
                  </label>
                </div>
                <label className="flex items-center justify-between rounded-control border border-border-subtle bg-surface-muted px-3 py-2.5 lg:col-span-2">
                  <span className="text-sm font-medium">{t("admin.listingStatus")}</span>
                  <Switch checked={draft.is_enabled} onCheckedChange={(checked) => setField("is_enabled", checked)} aria-label={t("admin.listingStatus")} />
                </label>
              </div>
            </FormSection>
        </TabsContent>

        <TabsContent value="specs" forceMount className="data-[state=inactive]:hidden">
            <FormSection title={t("admin.productSpecs")} description={t("admin.specsHint")}>
              <div className="flex items-center justify-between rounded-control border border-border-subtle bg-surface-muted px-3 py-2.5">
                <span className="text-sm font-medium">{t("admin.enableMultiSpec")}</span>
                <Switch ref={registerField("spec-mode")} checked={draft.spec_enabled} onCheckedChange={toggleSpecMode} aria-label={t("admin.enableMultiSpec")} />
              </div>
              <div className={cn("mt-4 overflow-x-auto rounded-card border border-border-subtle bg-surface", !draft.spec_enabled && "opacity-70")}>
                <table className="w-full min-w-[640px] text-sm">
                  <thead className="bg-surface-muted">
                    <tr>
                      <th className="w-24 px-3 py-2 text-left">{t("admin.specOrder")}</th>
                      <th className="px-3 py-2 text-left">{t("admin.specName")}</th>
                      <th className="w-28 px-3 py-2 text-left">{t("admin.specVisible")}</th>
                      <th className="w-28 px-3 py-2 text-right">{t("admin.actions")}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {draft.specs.map((spec, index) => (
                      <tr key={spec.id} className="border-t border-border-subtle">
                        <td className="px-3 py-2">
                          <div className="flex items-center gap-1">
                            <Button type="button" variant="ghost" size="icon" disabled={index === 0} onClick={() => moveSpec(index, -1)} title={t("admin.moveSpecUp")} aria-label={t("admin.moveSpecUp")}><ChevronUp className="h-4 w-4" aria-hidden="true" /></Button>
                            <span className="w-5 text-center text-xs text-muted-foreground">{index + 1}</span>
                            <Button type="button" variant="ghost" size="icon" disabled={index === draft.specs.length - 1} onClick={() => moveSpec(index, 1)} title={t("admin.moveSpecDown")} aria-label={t("admin.moveSpecDown")}><ChevronDown className="h-4 w-4" aria-hidden="true" /></Button>
                          </div>
                        </td>
                        <td className="px-3 py-2"><Input ref={registerField(`spec-name-${spec.id}`)} value={spec.name} onChange={(event) => updateSpec(index, { name: event.target.value })} placeholder={t("admin.specNamePlaceholder")} /></td>
                        <td className="px-3 py-2"><Switch checked={spec.visible} onCheckedChange={(checked) => updateSpec(index, { visible: checked })} aria-label={`${t("admin.specVisible")} ${spec.name || index + 1}`} /></td>
                        <td className="px-3 py-2 text-right"><Button type="button" variant="ghost" size="icon" onClick={() => removeSpec(index)} aria-label={t("admin.removeSpec")} title={t("admin.removeSpec")}><Trash2 className="h-4 w-4 text-destructive" aria-hidden="true" /></Button></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                {draft.specs.length === 0 && <p className="border-t border-border-subtle px-3 py-4 text-sm text-muted-foreground">{t("admin.noSpecs")}</p>}
                <div className="border-t border-border-subtle bg-surface-muted/45 p-3"><Button type="button" variant="outline" onClick={addSpec}><Plus className="mr-2 h-4 w-4" aria-hidden="true" />{t("admin.addSpec")}</Button></div>
              </div>
              {!draft.spec_enabled && draft.specs.length > 0 && <p className="mt-2 text-xs text-muted-foreground">{t("admin.storedSpecsHint")}</p>}
            </FormSection>
        </TabsContent>

        <TabsContent value="pricing" forceMount className="data-[state=inactive]:hidden">
            <FormSection title={t("admin.productTabPricing")} description={t("admin.pricingHint")}>
              <div className="overflow-x-auto rounded-card border border-border-subtle bg-surface">
                <table className="w-full min-w-[720px] text-sm">
                  <thead className="bg-surface-muted">
                    <tr>
                      <th className="px-3 py-2 text-left">{t("admin.specification")}</th>
                      <th className="px-3 py-2 text-left">{t("admin.costPriceShort")}</th>
                      <th className="px-3 py-2 text-left">{t("admin.retailPriceShort")}</th>
                      <th className="px-3 py-2 text-left">{t("admin.marginAmount")}</th>
                      <th className="px-3 py-2 text-left">{t("admin.marginRate")}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {!draft.spec_enabled && <tr className="border-t border-border-subtle">
                      <td className="px-3 py-2 font-medium">{t("admin.product")}</td>
                      <td className="px-3 py-2"><Input ref={registerField("cost_price")} type="number" min="0" step="0.01" value={draft.cost_price} onChange={(event) => setField("cost_price", event.target.value)} placeholder="0.00" /></td>
                      <td className="px-3 py-2"><Input ref={registerField("retail_price")} type="number" min="0" step="0.01" value={draft.retail_price} onChange={(event) => setField("retail_price", event.target.value)} placeholder="0.00" /></td>
                      <td className={cn("whitespace-nowrap px-3 py-2 font-medium", (formatMargin(draft.cost_price, draft.retail_price) ?? 0) < 0 && "text-amber-600")}>{formatMargin(draft.cost_price, draft.retail_price) === null ? "—" : formatYuan(formatMargin(draft.cost_price, draft.retail_price) || 0)}</td>
                      <td className="whitespace-nowrap px-3 py-2">{formatMarginRate(draft.cost_price, draft.retail_price) === null ? "—" : `${formatMarginRate(draft.cost_price, draft.retail_price)?.toFixed(2)}%`}</td>
                    </tr>}
                    {draft.spec_enabled && draft.specs.map((spec) => {
                      const margin = formatMargin(spec.cost_price, spec.retail_price)
                      const marginRate = formatMarginRate(spec.cost_price, spec.retail_price)
                      return <tr key={spec.id} className="border-t border-border-subtle">
                        <td className="px-3 py-2 font-medium">{spec.name || t("admin.unnamed")}</td>
                        <td className="px-3 py-2"><Input ref={registerField(`spec-cost-${spec.id}`)} type="number" min="0" step="0.01" value={spec.cost_price} onChange={(event) => updateSpec(draft.specs.findIndex((candidate) => candidate.id === spec.id), { cost_price: event.target.value })} placeholder="0.00" /></td>
                        <td className="px-3 py-2"><Input ref={registerField(`spec-retail-${spec.id}`)} type="number" min="0" step="0.01" value={spec.retail_price} onChange={(event) => updateSpec(draft.specs.findIndex((candidate) => candidate.id === spec.id), { retail_price: event.target.value })} placeholder="0.00" /></td>
                        <td className={cn("whitespace-nowrap px-3 py-2 font-medium", margin !== null && margin < 0 && "text-amber-600")}>{margin === null ? "—" : formatYuan(margin)}</td>
                        <td className="whitespace-nowrap px-3 py-2">{marginRate === null ? "—" : `${marginRate.toFixed(2)}%`}</td>
                      </tr>
                    })}
                  </tbody>
                </table>
                {draft.spec_enabled && draft.specs.length === 0 && <p className="border-t border-border-subtle px-3 py-4 text-sm text-muted-foreground">{t("admin.noSpecs")}</p>}
              </div>

              <div className="mt-8 border-t border-border-subtle pt-6">
                <h3 className="text-sm font-semibold text-foreground">{t("admin.pricingSection")}</h3>
                <p className="mt-1 text-sm text-muted-foreground">{t("admin.pricingHint")}</p>
                {levelsLoading ? <div className="mt-4 flex items-center gap-2 text-sm text-muted-foreground"><Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />{t("common.loading")}</div> : levels.length === 0 ? <p className="mt-4 text-sm text-muted-foreground">{t("admin.noUserLevels")}</p> : (
                  <div className="mt-4 overflow-x-auto rounded-card border border-border-subtle bg-surface">
                    <table className="w-full min-w-[1050px] text-sm">
                      <thead className="bg-surface-muted">
                        <tr>
                          <th className="px-3 py-2 text-left">{t("admin.userLevel")}</th>
                          <th className="px-3 py-2 text-left">{t("admin.specification")}</th>
                          <th className="px-3 py-2 text-left">{t("admin.pricingRule")}</th>
                          <th className="px-3 py-2 text-left">{t("admin.rulePrice")}</th>
                          <th className="px-3 py-2 text-left">{t("admin.customPrice")}</th>
                          <th className="px-3 py-2 text-left">{t("admin.actualPrice")}</th>
                        </tr>
                      </thead>
                      <tbody>{levels.filter((level) => level.enabled).flatMap((level) => pricingTargets.map((target) => {
                        const row = matrixRow(level.id, target.id)
                        const isMarkup = level.pricing_type === "COST_MARKUP"
                        const overrideField = `override-${level.id}-${target.id || "product"}`
                        const pendingLabel = row?.pricing_status === "WAITING_COST" ? t("admin.pricingWaitingCost") : t("admin.pricingWaitingRetail")
                        return <tr key={`${level.id}:${target.id || "product"}`} className="border-t border-border-subtle">
                          <td className="px-3 py-2 font-medium">{level.name}</td>
                          <td className="px-3 py-2">{target.name}</td>
                          <td className="px-3 py-2">{isMarkup ? `${t("admin.costMarkup")} ${level.markup_rate ?? 0}%` : t("admin.retailPricing")}</td>
                          <td className="whitespace-nowrap px-3 py-2">{row?.rule_price == null ? <StatusBadge tone="neutral" label={pendingLabel} /> : formatYuan(row.rule_price)}</td>
                          <td className="px-3 py-2"><Input ref={registerField(overrideField)} className="w-36" type="number" min="0.01" step="0.01" value={overrideValue(level.id, target.id)} onChange={(event) => updateOverride(level.id, target.id, event.target.value)} placeholder={t("admin.noCustomPrice")} /></td>
                          <td className={cn("whitespace-nowrap px-3 py-2 font-medium", row?.below_cost && "text-amber-600")}>
                            {row?.final_price == null ? <StatusBadge tone="neutral" label={pendingLabel} /> : <span>{formatYuan(row.final_price)}</span>}
                            <span className="ml-2 inline-flex gap-1 align-middle">
                              {row?.retail_capped && <StatusBadge tone="info" label={t("admin.retailCap")} />}
                              {row?.below_cost && <StatusBadge tone="warning" label={t("admin.belowCost")} />}
                            </span>
                          </td>
                        </tr>
                      }))}</tbody>
                    </table>
                  </div>
                )}
                {previewLoading && <p className="mt-2 text-xs text-muted-foreground">{t("admin.previewUpdating")}</p>}
                {previewError && <p className="mt-2 text-xs text-amber-700 dark:text-amber-300">{t("admin.pricingPreviewFailed")}</p>}
                {preview?.warnings.map((warning) => <div key={warning} className="mt-3 flex items-start gap-2 rounded-card border border-amber-500/20 bg-amber-500/[0.07] p-3 text-sm text-amber-800 dark:text-amber-200"><AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />{warning}</div>)}
              </div>
            </FormSection>
        </TabsContent>

        <TabsContent value="inventory" forceMount className="data-[state=inactive]:hidden">
            <FormSection title={t("admin.inventorySettings")} description={t("admin.inventorySettingsHint")}>
              <div className="grid gap-5 lg:grid-cols-2">
                <div className="flex flex-col gap-1.5 text-sm font-medium">
                  {t("admin.deliveryMethod")}
                  <div className="grid grid-cols-2 gap-2">
                    {(["AUTO", "MANUAL"] as const).map((deliveryType) => <Button key={deliveryType} type="button" variant={draft.delivery_type === deliveryType ? "default" : "outline"} onClick={() => handleDeliveryChange(deliveryType)}>{deliveryType === "AUTO" ? t("admin.autoDelivery") : t("admin.manualDelivery")}</Button>)}
                  </div>
                </div>
                <label className="flex flex-col gap-1.5 text-sm font-medium">
                  {t("admin.stockMode")}
                  <select className="h-11 rounded-control border border-input bg-surface px-3 text-sm" value={draft.stock_mode} onChange={(event) => setField("stock_mode", event.target.value as StockMode)}>
                    {draft.delivery_type === "AUTO" && <option value="CARD_KEY">{t("admin.cardKeyStock")}</option>}
                    {draft.delivery_type === "MANUAL" && <><option value="MANUAL">{t("admin.manualStock")}</option><option value="UNLIMITED">{t("admin.unlimitedStock")}</option></>}
                  </select>
                  <span className="text-xs font-normal text-muted-foreground">{t("admin.stockModeHint")}</span>
                </label>
                <label className="flex flex-col gap-1.5 text-sm font-medium">
                  {t("admin.lowStockAlert")}
                  <Input ref={registerField("low_stock_threshold")} type="number" min="0" step="1" value={draft.low_stock_threshold} onChange={(event) => setField("low_stock_threshold", event.target.value)} />
                </label>
                {draft.stock_mode === "MANUAL" && !draft.spec_enabled && <label className="flex flex-col gap-1.5 text-sm font-medium">
                  {t("admin.availableManualStock")}
                  <Input ref={registerField("manual_stock")} type="number" min="0" step="1" value={draft.manual_stock} onChange={(event) => setField("manual_stock", event.target.value)} placeholder="0" />
                  <span className="text-xs font-normal text-muted-foreground">{t("admin.manualStockHint")}</span>
                </label>}
              </div>

              {draft.stock_mode === "MANUAL" && draft.spec_enabled && <div className="mt-6 rounded-card border border-border-subtle bg-surface-muted/45 p-4">
                <h3 className="text-sm font-semibold">{t("admin.manualStockBySpec")}</h3>
                <div className="mt-3 space-y-3">
                  {draft.specs.length === 0 && <p className="text-sm text-muted-foreground">{t("admin.noSpecs")}</p>}
                  {draft.specs.map((spec, index) => <label key={spec.id} className="flex items-center gap-3 text-sm">
                    <span className="min-w-0 flex-1 truncate">{spec.name || t("admin.unnamed")}</span>
                    <Input ref={registerField(`spec-stock-${spec.id}`)} className="w-32" type="number" min="0" step="1" value={spec.manual_stock} onChange={(event) => updateSpec(index, { manual_stock: event.target.value })} placeholder="0" />
                  </label>)}
                </div>
                <p className="mt-3 text-xs text-muted-foreground">{t("admin.manualStockHint")}</p>
              </div>}

              {draft.stock_mode === "CARD_KEY" && <div className="mt-6 rounded-card border border-border-subtle bg-surface-muted/45 p-4">
                <h3 className="text-sm font-semibold">{t("admin.cardKeyStock")}</h3>
                {draft.spec_enabled ? <div className="mt-3 space-y-2">
                  {draft.specs.length === 0 && <p className="text-sm text-muted-foreground">{t("admin.noSpecs")}</p>}
                  {draft.specs.map((spec) => <div key={spec.id} className="flex items-center justify-between gap-3 text-sm"><span className="min-w-0 truncate">{spec.name || t("admin.unnamed")}</span><StatusBadge tone="info" label={`${t("admin.cardKeyCount")}: ${spec.card_key_count ?? 0}`} /></div>)}
                </div> : <div className="mt-3 flex items-center justify-between text-sm"><span>{t("admin.cardKeyCount")}</span><StatusBadge tone="info" label={product?.stock_available ?? 0} /></div>}
                <p className="mt-3 text-xs text-muted-foreground">{t("admin.stockModeHint")}</p>
                {cardKeyImportAvailability !== "hidden" && <div className="mt-4 flex flex-wrap items-center gap-3">
                  <Button
                    type="button"
                    variant="outline"
                    onClick={() => setCardKeyImportOpen(true)}
                    disabled={saving || cardKeyImportAvailability !== "ready"}
                  >
                    {t("admin.importKeys")}
                  </Button>
                  {cardKeyImportAvailability === "save-product" && <span className="text-xs text-muted-foreground">{t("admin.saveBeforeImportKeys")}</span>}
                  {cardKeyImportAvailability === "save-changes" && <span className="text-xs text-muted-foreground">{t("admin.saveChangesBeforeImportKeys")}</span>}
                </div>}
              </div>}

              {draft.stock_mode === "UNLIMITED" && <div className="mt-6 rounded-card border border-border-subtle bg-surface-muted/45 p-4 text-sm text-muted-foreground">{t("admin.unlimitedStockHint")}</div>}

              <div className="mt-8 border-t border-border-subtle pt-6">
                <h3 className="text-sm font-semibold">{t("admin.customerInputFields")}</h3>
                <p className="mt-1 text-sm text-muted-foreground">{t("admin.customerInputHint")} · {draft.customer_input_fields.length}/20</p>
                <div ref={registerField("customer-inputs")} tabIndex={-1} className="mt-4 space-y-2">
                  {draft.customer_input_fields.map((field, index) => <div className="flex items-center gap-2" key={field.id}>
                    <Input ref={registerField(`customer-${field.id}`)} maxLength={50} value={field.label} onChange={(event) => updateCustomerField(index, event.target.value)} placeholder={t("admin.customerInputPlaceholder")} />
                    <Button type="button" variant="ghost" size="icon" onClick={() => removeCustomerField(index)} aria-label={t("admin.removeCustomerInput")} title={t("admin.removeCustomerInput")}><Trash2 className="h-4 w-4 text-destructive" aria-hidden="true" /></Button>
                  </div>)}
                  <Button type="button" variant="outline" onClick={addCustomerField} disabled={draft.customer_input_fields.length >= 20}><Plus className="mr-2 h-4 w-4" aria-hidden="true" />{t("admin.addCustomerInput")}</Button>
                </div>
                {draft.customer_input_fields.length === 0 && <p className="mt-2 text-xs text-muted-foreground">{t("admin.noCustomerInputFields")}</p>}
              </div>
            </FormSection>
        </TabsContent>

        <TabsContent value="details" forceMount className="data-[state=inactive]:hidden">
            <FormSection title={t("admin.detailMd")} description={t("admin.detailMdHint")}>
              <MarkdownEditor value={draft.detail_md} onChange={handleDetailChange} uploadImage={uploadImage} />
            </FormSection>
        </TabsContent>
      </Tabs>

      <div className="flex flex-col-reverse gap-3 border-t border-border-subtle pt-4 sm:flex-row sm:justify-end">
        <Button type="button" variant="outline" onClick={onCancel} disabled={saving}>{t("admin.cancel")}</Button>
        <Button type="submit" disabled={saving}>{saving ? t("admin.saving") : t("admin.save")}</Button>
      </div>
      <CardKeyImportDialog
        product={product}
        open={cardKeyImportOpen && cardKeyImportAvailability === "ready"}
        onOpenChange={setCardKeyImportOpen}
        onImported={async () => {
          setCardKeyImportOpen(false)
          try {
            await onProductRefresh?.()
          } catch {
            toast.error(t("admin.loadProductFailed"))
          }
        }}
      />
    </form>
  )
}
