"use client"

import { useState, useMemo } from "react"
import {
  Search,
  RefreshCw,
} from "lucide-react"
import { motion } from "motion/react"
import { useLocale, useSearch } from "@/lib/context"
import { ProductCard } from "@/components/store/product-card"
import { FadeIn } from "@/components/ui/motion"
import { getRootCategories, matchesCategory } from "@/lib/categories"
import { cn } from "@/lib/utils"
import type { ProductCard as ProductCardType, Category } from "@/types"

interface HomeContentProps {
  products: ProductCardType[]
  categories: Category[]
  siteSlogan: string
  siteDescription: string
  productsError?: boolean
}

export function HomeContent({ products, categories, siteSlogan, siteDescription, productsError = false }: HomeContentProps) {
  const { t } = useLocale()
  const { searchQuery, sortBy, inStockOnly, priceMin, priceMax } = useSearch()

  const [selectedRootCategory, setSelectedRootCategory] = useState<string | null>(null)
  const [selectedChildCategory, setSelectedChildCategory] = useState<string | null>(null)
  const rootCategories = getRootCategories(categories)
  const selectedRoot = rootCategories.find((category) => category.id === selectedRootCategory) ?? null
  const selectedChildren = selectedRoot?.children ?? []

  const filteredProducts = useMemo(() => {
    let result = [...products]

    const selectedCategory = selectedChildCategory ?? selectedRootCategory
    if (selectedCategory) {
      result = result.filter((p) => matchesCategory(p.category_id, selectedCategory, categories))
    }
    if (searchQuery.trim()) {
      const q = searchQuery.toLowerCase()
      result = result.filter((p) => p.title.toLowerCase().includes(q))
    }
    if (inStockOnly) {
      result = result.filter((p) => p.unlimited || (p.stock_available ?? 0) > 0)
    }

    const min = Number.parseFloat(priceMin)
    const max = Number.parseFloat(priceMax)
    if (!Number.isNaN(min)) result = result.filter((p) => p.price >= min)
    if (!Number.isNaN(max)) result = result.filter((p) => p.price <= max)

    switch (sortBy) {
      case "hot":
        result.sort((a, b) => ((b.sales_count ?? 0) + (b.initial_sales ?? 0)) - ((a.sales_count ?? 0) + (a.initial_sales ?? 0)))
        break
      case "price_low":
        result.sort((a, b) => a.price - b.price)
        break
      case "price_high":
        result.sort((a, b) => b.price - a.price)
        break
      case "new":
        result.sort(
          (a, b) =>
            new Date(b.created_at ?? 0).getTime() - new Date(a.created_at ?? 0).getTime()
        )
        break
    }

    return result
  }, [products, categories, selectedRootCategory, selectedChildCategory, searchQuery, sortBy, inStockOnly, priceMin, priceMax])

  const selectRootCategory = (categoryId: string | null) => {
    if (categoryId === selectedRootCategory) {
      setSelectedRootCategory(null)
    } else {
      setSelectedRootCategory(categoryId)
    }
    setSelectedChildCategory(null)
  }

  const selectChildCategory = (categoryId: string | null) => {
    setSelectedChildCategory(categoryId === selectedChildCategory ? null : categoryId)
  }

  return (
    <div className="store-home">
      {/* Hero */}
      {(siteSlogan.trim() || siteDescription.trim()) && (
        <FadeIn>
          <section className="store-home-intro">
            <div className="store-home-hero">
              <div className="store-home-hero-copy">
                {siteSlogan.trim() && (
                  <h1 className="store-home-hero-title text-balance">
                    {siteSlogan}
                  </h1>
                )}
                {siteDescription.trim() && (
                  <p className="store-home-hero-description">
                    {siteDescription}
                  </p>
                )}
              </div>
            </div>
          </section>
        </FadeIn>
      )}

      <section className="store-catalog" aria-label={t("home.allCategories")}>
      {/* Category controls */}
      {categories.length > 0 && (
        <div className="store-catalog-controls space-y-2">
          <div className="store-catalog-header">
            <div className="segmented-control w-full sm:w-auto" role="group" aria-label={t("admin.categoryRootLevel")}>
            <button
              type="button"
              onClick={() => selectRootCategory(null)}
              aria-pressed={selectedRootCategory === null}
              data-active={selectedRootCategory === null}
              className={cn(
                "segmented-control-item relative focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2",
                selectedRootCategory === null ? "text-primary-foreground" : "text-muted-foreground hover:bg-surface-muted hover:text-foreground"
              )}
            >
              {selectedRootCategory === null && <motion.span layoutId="active-root-category" className="absolute inset-0 -z-0 rounded-pill bg-primary shadow-sm" transition={{ type: "spring", stiffness: 420, damping: 32 }} />}
              <span className="relative z-10">{t("home.allCategories")}</span>
            </button>
            {rootCategories.map((category) => (
              <button
                type="button"
                key={category.id}
                onClick={() => selectRootCategory(category.id)}
                aria-pressed={selectedRootCategory === category.id}
                data-active={selectedRootCategory === category.id}
                className={cn(
                  "segmented-control-item relative focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2",
                  selectedRootCategory === category.id ? "text-primary-foreground" : "text-muted-foreground hover:bg-surface-muted hover:text-foreground"
                )}
              >
                {selectedRootCategory === category.id && <motion.span layoutId="active-root-category" className="absolute inset-0 -z-0 rounded-pill bg-primary shadow-sm" transition={{ type: "spring", stiffness: 420, damping: 32 }} />}
                <span className="relative z-10">{category.name}</span>
              </button>
            ))}
            </div>
          </div>
          {selectedRoot && selectedChildren.length > 0 && (
            <div className="flex max-w-full items-center gap-1 overflow-x-auto pb-0.5 pl-1" role="group" aria-label={t("home.allSubcategories")}>
              <button
                type="button"
                onClick={() => selectChildCategory(null)}
                aria-pressed={selectedChildCategory === null}
                data-active={selectedChildCategory === null}
                className={cn(
                  "segmented-control-item relative min-h-9 px-3 py-1.5 text-xs focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2",
                  selectedChildCategory === null ? "text-primary-foreground" : "text-muted-foreground hover:bg-surface-muted hover:text-foreground"
                )}
              >
                {selectedChildCategory === null && <motion.span layoutId="active-child-category" className="absolute inset-0 -z-0 rounded-pill bg-primary shadow-sm" transition={{ type: "spring", stiffness: 420, damping: 32 }} />}
                <span className="relative z-10">{t("home.allSubcategories")}</span>
              </button>
              {selectedChildren.map((child) => (
                <button
                  type="button"
                  key={child.id}
                  onClick={() => selectChildCategory(child.id)}
                  aria-pressed={selectedChildCategory === child.id}
                  data-active={selectedChildCategory === child.id}
                  className={cn(
                    "segmented-control-item relative min-h-9 px-3 py-1.5 text-xs focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2",
                    selectedChildCategory === child.id ? "text-primary-foreground" : "text-muted-foreground hover:bg-surface-muted hover:text-foreground"
                  )}
                >
                  {selectedChildCategory === child.id && <motion.span layoutId="active-child-category" className="absolute inset-0 -z-0 rounded-pill bg-primary shadow-sm" transition={{ type: "spring", stiffness: 420, damping: 32 }} />}
                  <span className="relative z-10">{child.name}</span>
                </button>
              ))}
            </div>
          )}
        </div>
      )}

      {/* Product Grid */}
      {productsError ? (
        <div id="products" className="store-catalog-empty flex flex-col items-center justify-center gap-4 py-24 text-muted-foreground">
          <Search className="h-10 w-10 opacity-20" />
          <p className="text-sm">{t("home.loadFailed")}</p>
          <button
            type="button"
            onClick={() => window.location.reload()}
            className="inline-flex items-center gap-2 rounded-control border border-border-strong bg-surface px-4 py-2 text-sm font-medium text-foreground transition-colors hover:bg-surface-muted"
          >
            <RefreshCw className="h-4 w-4" />
            {t("common.retry")}
          </button>
        </div>
      ) : filteredProducts.length > 0 ? (
        <div id="products" className="store-product-grid">
          {filteredProducts.map((product) => (
            <div key={product.id} className="min-w-0">
              <ProductCard product={product} />
            </div>
          ))}
        </div>
      ) : (
        <div id="products" className="store-catalog-empty flex flex-col items-center justify-center py-24 text-muted-foreground">
          <Search className="mb-3 h-10 w-10 opacity-20" />
          <p className="text-sm">{t("home.noProducts")}</p>
        </div>
      )}
      </section>
    </div>
  )
}
