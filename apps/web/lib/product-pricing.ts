import type { AdminProductDetail, AdminProductSpec } from "../types/index.ts"

export interface PriceRange {
  min: number
  max: number
}

export type ProductPriceTarget = "cost" | "retail"

type PriceProduct = Pick<AdminProductDetail, "spec_enabled" | "cost_price" | "retail_price"> & {
  specs?: AdminProductSpec[] | null
}

function toPriceRange(values: Array<number | null | undefined>): PriceRange | null {
  const validValues = values.filter((value): value is number => typeof value === "number" && Number.isFinite(value))
  if (validValues.length === 0) return null
  return { min: Math.min(...validValues), max: Math.max(...validValues) }
}

/**
 * Returns the admin list's cost and retail ranges from one effective spec set.
 * Visible undeleted specs take precedence; when none are visible, every
 * undeleted spec is used. The root prices are used only for products without
 * specs.
 */
export function getAdminProductPriceRanges(product: PriceProduct): {
  cost: PriceRange | null
  retail: PriceRange | null
} {
  if (!product.spec_enabled) {
    return {
      cost: toPriceRange([product.cost_price]),
      retail: toPriceRange([product.retail_price]),
    }
  }

  const undeletedSpecs = (product.specs || []).filter((spec) => spec.is_deleted === 0)
  const visibleSpecs = undeletedSpecs.filter((spec) => spec.is_visible === true)
  const effectiveSpecs = visibleSpecs.length > 0 ? visibleSpecs : undeletedSpecs

  return {
    cost: toPriceRange(effectiveSpecs.map((spec) => spec.cost_price)),
    retail: toPriceRange(effectiveSpecs.map((spec) => spec.retail_price)),
  }
}

export function getAdminProductPriceRange(product: PriceProduct, target: ProductPriceTarget): PriceRange | null {
  const ranges = getAdminProductPriceRanges(product)
  return target === "cost" ? ranges.cost : ranges.retail
}

export function getAdminProductPriceFocusTarget(
  product: Pick<AdminProductDetail, "spec_enabled" | "specs">,
  target: ProductPriceTarget,
): { target: ProductPriceTarget; key: string } | null {
  const key = product.spec_enabled ? product.specs[0]?.id : "root"
  return key ? { target, key } : null
}
