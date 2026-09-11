import type { Category } from "@/types"

export type CategorySelection = {
  primaryId: string
  secondaryId: string
}

export type FlattenedCategory = {
  category: Category
  depth: 0 | 1
  parent: Category | null
}

/** Return root nodes in the API's deterministic order. */
export function getRootCategories(categories: Category[]): Category[] {
  return categories.filter((category) => category.parent_id === null)
}

/** Flatten a two-level category tree while preserving root/child order. */
export function flattenCategories(categories: Category[]): FlattenedCategory[] {
  return getRootCategories(categories).flatMap((root) => [
    { category: root, depth: 0 as const, parent: null },
    ...root.children.map((child) => ({ category: child, depth: 1 as const, parent: root })),
  ])
}

export function findCategory(categories: Category[], categoryId: string | null | undefined): Category | null {
  if (!categoryId) return null
  for (const root of getRootCategories(categories)) {
    if (root.id === categoryId) return root
    const child = root.children.find((candidate) => candidate.id === categoryId)
    if (child) return child
  }
  return null
}

export function getCategoryChildren(categories: Category[], rootId: string | null | undefined): Category[] {
  return getRootCategories(categories).find((category) => category.id === rootId)?.children ?? []
}

export function getCategorySelection(categories: Category[], categoryId: string | null | undefined): CategorySelection {
  const category = findCategory(categories, categoryId)
  if (!category) return { primaryId: "", secondaryId: "" }
  return category.parent_id
    ? { primaryId: category.parent_id, secondaryId: category.id }
    : { primaryId: category.id, secondaryId: "" }
}

export function getCategoryPath(categories: Category[], categoryId: string | null | undefined): Category[] {
  const category = findCategory(categories, categoryId)
  if (!category) return []
  if (!category.parent_id) return [category]
  const parent = findCategory(categories, category.parent_id)
  return parent ? [parent, category] : [category]
}

export function getCategoryPathLabel(categories: Category[], categoryId: string | null | undefined): string {
  return getCategoryPath(categories, categoryId).map((category) => category.name).join(" / ")
}

/** Match the API semantics: a root includes its direct products and active children. */
export function matchesCategory(categoryId: string, selectedCategoryId: string | null | undefined, categories: Category[]): boolean {
  if (!selectedCategoryId) return true
  const selected = findCategory(categories, selectedCategoryId)
  if (!selected) return false
  if (selected.parent_id !== null) return categoryId === selected.id
  return categoryId === selected.id || selected.children.some((child) => child.id === categoryId)
}

export function getProductCategoryPathLabel(
  product: {
    category_id: string
    primary_category_name?: string | null
    secondary_category_name?: string | null
    category_name?: string | null
  },
  categories: Category[],
): string {
  const fromTree = getCategoryPathLabel(categories, product.category_id)
  if (fromTree) return fromTree
  return [product.primary_category_name, product.secondary_category_name]
    .filter((name): name is string => Boolean(name))
    .join(" / ") || product.category_name || ""
}
