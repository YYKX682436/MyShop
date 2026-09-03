import type { AdminProductDetail } from "../types/index.ts"

export type ProductPriceDraftSpec = {
  id: string
  name: string
  cost_price: string
  retail_price: string
}

export type ProductPriceDraft =
  | {
      spec_enabled: false
      cost_price: string
      retail_price: string
    }
  | {
      spec_enabled: true
      specs: ProductPriceDraftSpec[]
    }

export type ProductPriceDraftValidation =
  | { valid: true }
  | { valid: false; field: "cost_price" | "retail_price" }
  | { valid: false; field: "spec_cost_price" | "spec_retail_price"; spec_id: string }
  | { valid: false; field: "specs_required" }

export interface AdminProductPricesUpdatePayload {
  cost_price: number | null
  retail_price: number | null
  specs: {
    spec_id: string
    cost_price: number | null
    retail_price: number | null
  }[]
}

export type ProductStockQuickAction = "import-card-keys" | "display-only"

export function validateProductTitleDraft(value: string): "required" | "length" | null {
  const normalized = value.trim()
  if (!normalized) return "required"
  if (normalized.length > 255) return "length"
  return null
}

export function isValidMoneyDraft(value: string, positive: boolean): boolean {
  if (!value.trim() || !/^\d+(\.\d{1,2})?$/.test(value.trim())) return false
  const parsed = Number(value)
  return Number.isFinite(parsed) && (positive ? parsed > 0 : parsed >= 0)
}

export function isValidIntegerDraft(value: string, minimum = Number.NEGATIVE_INFINITY): boolean {
  if (!/^-?\d+$/.test(value.trim())) return false
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) && parsed >= minimum
}

export function parseIntegerDraft(value: string, minimum = Number.NEGATIVE_INFINITY): number | null {
  return isValidIntegerDraft(value, minimum) ? Number(value.trim()) : null
}

export function createProductPriceDraft(product: Pick<AdminProductDetail, "spec_enabled" | "cost_price" | "retail_price" | "specs">): ProductPriceDraft {
  if (!product.spec_enabled) {
    return {
      spec_enabled: false,
      cost_price: product.cost_price == null ? "" : String(product.cost_price),
      retail_price: String(product.retail_price),
    }
  }

  return {
    spec_enabled: true,
    specs: product.specs.map((spec) => ({
      id: spec.id,
      name: spec.name,
      cost_price: String(spec.cost_price),
      retail_price: String(spec.retail_price),
    })),
  }
}

export function validateProductPriceDraft(draft: ProductPriceDraft): ProductPriceDraftValidation {
  if (!draft.spec_enabled) {
    if (!isValidMoneyDraft(draft.cost_price, false)) return { valid: false, field: "cost_price" }
    if (!isValidMoneyDraft(draft.retail_price, true)) return { valid: false, field: "retail_price" }
    return { valid: true }
  }

  if (draft.specs.length === 0) return { valid: false, field: "specs_required" }
  for (const spec of draft.specs) {
    if (!isValidMoneyDraft(spec.cost_price, false)) {
      return { valid: false, field: "spec_cost_price", spec_id: spec.id }
    }
    if (!isValidMoneyDraft(spec.retail_price, true)) {
      return { valid: false, field: "spec_retail_price", spec_id: spec.id }
    }
  }
  return { valid: true }
}

export function buildProductPricesUpdatePayload(draft: ProductPriceDraft): AdminProductPricesUpdatePayload {
  if (!draft.spec_enabled) {
    return {
      cost_price: Number(draft.cost_price.trim()),
      retail_price: Number(draft.retail_price.trim()),
      specs: [],
    }
  }

  return {
    cost_price: null,
    retail_price: null,
    specs: draft.specs.map((spec) => ({
      spec_id: spec.id,
      cost_price: Number(spec.cost_price.trim()),
      retail_price: Number(spec.retail_price.trim()),
    })),
  }
}

export function getProductStockQuickAction(product: Pick<AdminProductDetail, "stock_mode">): ProductStockQuickAction {
  return product.stock_mode === "CARD_KEY" ? "import-card-keys" : "display-only"
}
