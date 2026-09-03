export type ProductEditorTab = "basic" | "specs" | "pricing" | "inventory" | "details"

export const PRODUCT_EDITOR_TABS: readonly ProductEditorTab[] = [
  "basic",
  "specs",
  "pricing",
  "inventory",
  "details",
]

export interface PricingDraftSpec {
  id: string
  cost_price: string
  retail_price: string
}

export interface PricingDraftOverride {
  user_level_id: string
  spec_id: string | null
  price: string
}

export interface PricingDraftInput {
  spec_enabled: boolean
  cost_price: string
  retail_price: string
  specs: PricingDraftSpec[]
  overrides: PricingDraftOverride[]
}

export interface PricingPreviewDraftPayload {
  cost_price: number | null
  retail_price: number | null
  spec_enabled: boolean
  specs: {
    id: string
    cost_price: number | null
    retail_price: number | null
  }[]
  overrides: {
    user_level_id: string
    spec_id: string | null
    price: number | null
  }[]
}

export type CardKeyImportAvailability = "hidden" | "save-product" | "save-changes" | "ready"

export function getCardKeyImportAvailability(
  product: { id: string } | null,
  stockMode: string,
  dirty: boolean,
): CardKeyImportAvailability {
  if (stockMode !== "CARD_KEY") return "hidden"
  if (!product) return "save-product"
  if (dirty) return "save-changes"
  return "ready"
}

export function toNumberOrNull(value: string | number | null | undefined): number | null {
  if (value === null || value === undefined || String(value).trim() === "") return null
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}

export function buildPricingPreviewDraft(source: PricingDraftInput): PricingPreviewDraftPayload {
  return {
    cost_price: toNumberOrNull(source.cost_price),
    retail_price: toNumberOrNull(source.retail_price),
    spec_enabled: source.spec_enabled,
    specs: source.specs.map((spec) => ({
      id: spec.id,
      cost_price: toNumberOrNull(spec.cost_price),
      retail_price: toNumberOrNull(spec.retail_price),
    })),
    overrides: source.overrides.map((override) => ({
      user_level_id: override.user_level_id,
      spec_id: override.spec_id,
      price: toNumberOrNull(override.price),
    })),
  }
}

export function reorderSpecs<T>(items: readonly T[], index: number, direction: -1 | 1): T[] {
  const nextIndex = index + direction
  if (index < 0 || index >= items.length || nextIndex < 0 || nextIndex >= items.length) {
    return [...items]
  }
  const result = [...items]
  const current = result[index]
  result[index] = result[nextIndex]
  result[nextIndex] = current
  return result
}

export function withCurrentSpecOrder<T>(items: readonly T[]): Array<T & { sort_order: number }> {
  return items.map((item, index) => ({ ...item, sort_order: index }))
}

export function getProductEditorTabForMessage(message: string): ProductEditorTab {
  const value = message.toLowerCase()
  if (/(价格|售价|成本价|零售价|特殊价|等级|定价|加价|price|retail|cost|override|markup)/i.test(value)) {
    return "pricing"
  }
  if (/(规格|spec|specification)/i.test(value)) return "specs"
  if (/(库存|发货|交付|客户填写|低库存|阈值|stock|delivery|customer|threshold)/i.test(value)) {
    return "inventory"
  }
  if (/(详情|markdown|detail)/i.test(value)) return "details"
  return "basic"
}
