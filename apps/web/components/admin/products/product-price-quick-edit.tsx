"use client"

import { useCallback, useEffect, useRef, useState, type KeyboardEvent } from "react"
import { toast } from "sonner"

import { StatusBadge } from "@/components/admin/shared"
import { Input } from "@/components/ui/input"
import { Popover, PopoverTrigger } from "@/components/ui/popover"
import { useLocale } from "@/lib/context"
import { getAdminProductPriceFocusTarget, getAdminProductPriceRange, type ProductPriceTarget } from "@/lib/product-pricing"
import {
  buildProductPricesUpdatePayload,
  createProductPriceDraft,
  validateProductPriceDraft,
  type AdminProductPricesUpdatePayload,
  type ProductPriceDraft,
} from "@/lib/product-quick-edit"
import { formatYuan } from "@/lib/utils"
import type { AdminProductDetail, AdminProductSpec } from "@/types"
import { QuickEditActions, QuickEditContent, QuickEditTrigger } from "./quick-edit"

export function ProductPriceQuickEdit({
  product,
  target,
  saving,
  onSave,
}: {
  product: AdminProductDetail
  target: ProductPriceTarget
  saving: boolean
  onSave: (payload: AdminProductPricesUpdatePayload) => Promise<boolean>
}) {
  const { t } = useLocale()
  const [open, setOpen] = useState(false)
  const [draft, setDraft] = useState<ProductPriceDraft>(() => createProductPriceDraft(product))
  const costInputRefs = useRef<Record<string, HTMLInputElement | null>>({})
  const retailInputRefs = useRef<Record<string, HTMLInputElement | null>>({})
  const saveInFlightRef = useRef(false)

  const focusPriceInput = useCallback(() => {
    const focusTarget = getAdminProductPriceFocusTarget(product, target)
    if (!focusTarget) return
    const ref = focusTarget.target === "cost"
      ? costInputRefs.current[focusTarget.key]
      : retailInputRefs.current[focusTarget.key]
    ref?.focus()
  }, [product, target])

  useEffect(() => {
    if (open) {
      setDraft(createProductPriceDraft(product))
      window.requestAnimationFrame(focusPriceInput)
    }
  }, [focusPriceInput, open, product])

  const formatPriceRange = (range: { min: number; max: number } | null) => {
    if (range == null) return t("admin.systemNoData")
    return range.min === range.max
      ? formatYuan(range.min)
      : `${formatYuan(range.min)} – ${formatYuan(range.max)}`
  }

  const openEditor = () => {
    setDraft(createProductPriceDraft(product))
    setOpen(true)
    window.requestAnimationFrame(focusPriceInput)
  }

  const handleOpenAutoFocus = (event: Event) => {
    event.preventDefault()
    window.requestAnimationFrame(() => focusPriceInput())
  }

  const updateRootPrice = (field: "cost_price" | "retail_price", value: string) => {
    setDraft((current) => current.spec_enabled ? current : { ...current, [field]: value })
  }

  const updateSpecPrice = (specId: string, field: "cost_price" | "retail_price", value: string) => {
    setDraft((current) => current.spec_enabled
      ? { ...current, specs: current.specs.map((spec) => spec.id === specId ? { ...spec, [field]: value } : spec) }
      : current)
  }

  const showValidationError = (validation: ReturnType<typeof validateProductPriceDraft>) => {
    if (validation.valid) return
    if (validation.field === "cost_price") {
      toast.error(t("admin.costPriceRequired"))
    } else if (validation.field === "retail_price") {
      toast.error(t("admin.retailPriceRequired"))
    } else if (validation.field === "specs_required") {
      toast.error(t("admin.quickPriceSpecsRequired"))
    } else if ("spec_id" in validation) {
      const specName = product.specs.find((spec) => spec.id === validation.spec_id)?.name || t("admin.unnamed")
      toast.error(t(validation.field === "spec_cost_price" ? "admin.specCostInvalid" : "admin.specRetailInvalid").replace("{name}", specName))
    }
  }

  const handleSave = async () => {
    if (saving || saveInFlightRef.current) return
    const validation = validateProductPriceDraft(draft)
    if (!validation.valid) {
      showValidationError(validation)
      return
    }
    saveInFlightRef.current = true
    try {
      const success = await onSave(buildProductPricesUpdatePayload(draft))
      if (success) setOpen(false)
      else setDraft(createProductPriceDraft(product))
    } finally {
      saveInFlightRef.current = false
    }
  }

  const handlePriceKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === "Enter") {
      event.preventDefault()
      void handleSave()
    }
    if (event.key === "Escape") {
      event.preventDefault()
      setOpen(false)
    }
  }

  const stockLabel = (spec: AdminProductSpec) => spec.unlimited ? t("admin.unlimited") : String(spec.stock_available ?? 0)
  const displayRange = getAdminProductPriceRange(product, target)

  return (
    <Popover open={open} onOpenChange={(nextOpen) => !saving && setOpen(nextOpen)}>
      <PopoverTrigger asChild>
        <QuickEditTrigger
          className="w-full justify-end whitespace-nowrap text-right"
          disabled={saving}
          aria-label={`${t("admin.editProductPrice")}: ${target === "cost" ? t("admin.costPriceShort") : t("admin.retailPriceShort")} · ${product.title}`}
          onClick={openEditor}
        >
          <span className="font-medium">{formatPriceRange(displayRange)}</span>
        </QuickEditTrigger>
      </PopoverTrigger>
      <QuickEditContent
        title={t("admin.editProductPrice")}
        description={t("admin.quickEditPriceHint")}
        className="max-h-[min(80vh,42rem)] w-[min(48rem,calc(100vw-2rem))] overflow-y-auto"
        onOpenAutoFocus={handleOpenAutoFocus}
        footer={<QuickEditActions onCancel={() => setOpen(false)} onSave={() => void handleSave()} saving={saving} />}
      >
        {!product.spec_enabled ? (
          <div className="grid gap-3 sm:grid-cols-2">
            <label className="flex flex-col gap-1.5 text-xs font-medium text-muted-foreground">
              {t("admin.costPriceShort")}
              <Input
                ref={(node) => { costInputRefs.current.root = node }}
                type="text"
                inputMode="decimal"
                value={draft.spec_enabled ? "" : draft.cost_price}
                onChange={(event) => updateRootPrice("cost_price", event.target.value)}
                onKeyDown={handlePriceKeyDown}
                aria-label={t("admin.costPriceShort")}
                disabled={saving}
              />
            </label>
            <label className="flex flex-col gap-1.5 text-xs font-medium text-muted-foreground">
              {t("admin.retailPriceShort")}
              <Input
                ref={(node) => { retailInputRefs.current.root = node }}
                type="text"
                inputMode="decimal"
                value={draft.spec_enabled ? "" : draft.retail_price}
                onChange={(event) => updateRootPrice("retail_price", event.target.value)}
                onKeyDown={handlePriceKeyDown}
                aria-label={t("admin.retailPriceShort")}
                disabled={saving}
              />
            </label>
          </div>
        ) : (
          <div className="space-y-3">
            {product.specs.length === 0 && <p className="text-sm text-muted-foreground">{t("admin.noSpecs")}</p>}
            {product.specs.map((spec) => {
              const current = draft.spec_enabled ? draft.specs.find((candidate) => candidate.id === spec.id) : undefined
              return (
                <div key={spec.id} className="rounded-control border border-border-subtle bg-surface-muted/45 p-3">
                  <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs">
                    <span className="min-w-0 flex-1 truncate text-sm font-semibold text-foreground">{spec.name || t("admin.unnamed")}</span>
                    <StatusBadge tone={spec.is_visible ? "success" : "neutral"} label={spec.is_visible ? t("admin.specVisible") : t("admin.specHidden")} />
                    <span className="text-muted-foreground">{t("admin.stockLabel")}: {stockLabel(spec)}</span>
                    <span className="text-muted-foreground">{t("admin.specOrder")}: {spec.sort_order + 1}</span>
                  </div>
                  <div className="mt-3 grid gap-3 sm:grid-cols-2">
                    <label className="flex flex-col gap-1.5 text-xs font-medium text-muted-foreground">
                      {t("admin.costPriceShort")}
                      <Input
                        ref={(node) => { costInputRefs.current[spec.id] = node }}
                        type="text"
                        inputMode="decimal"
                        value={current?.cost_price || ""}
                        onChange={(event) => updateSpecPrice(spec.id, "cost_price", event.target.value)}
                        onKeyDown={handlePriceKeyDown}
                        aria-label={`${t("admin.costPriceShort")}: ${spec.name}`}
                        disabled={saving}
                      />
                    </label>
                    <label className="flex flex-col gap-1.5 text-xs font-medium text-muted-foreground">
                      {t("admin.retailPriceShort")}
                      <Input
                        ref={(node) => { retailInputRefs.current[spec.id] = node }}
                        type="text"
                        inputMode="decimal"
                        value={current?.retail_price || ""}
                        onChange={(event) => updateSpecPrice(spec.id, "retail_price", event.target.value)}
                        onKeyDown={handlePriceKeyDown}
                        aria-label={`${t("admin.retailPriceShort")}: ${spec.name}`}
                        disabled={saving}
                      />
                    </label>
                  </div>
                </div>
              )
            })}
          </div>
        )}
        <p className="mt-3 text-xs text-muted-foreground">{t("admin.quickEditEnterEscHint")}</p>
      </QuickEditContent>
    </Popover>
  )
}
