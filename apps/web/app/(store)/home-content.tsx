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

  const [selectedCategory, setSelectedCategory] = useState<string | null>(null)

  const filteredProducts = useMemo(() => {
    let result = [...products]

    if (selectedCategory) {
      result = result.filter((p) => p.category_id === selectedCategory)
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
  }, [products, selectedCategory, searchQuery, sortBy, inStockOnly, priceMin, priceMax])

  return (
    <div className="mx-auto flex w-full max-w-[1440px] flex-col gap-8">
      {/* Hero */}
      {(siteSlogan.trim() || siteDescription.trim()) && (
        <FadeIn>
          <section className="surface relative overflow-hidden px-5 py-6 sm:px-8 sm:py-8 lg:px-10">
            <div className="pointer-events-none absolute inset-y-0 right-0 hidden w-1/3 bg-primary/[0.035] sm:block" />
            <div className="relative max-w-2xl">
              {siteSlogan.trim() && (
                <h1 className="text-balance text-3xl font-semibold tracking-[-0.04em] text-foreground sm:text-4xl lg:text-5xl">
                  {siteSlogan}
                </h1>
              )}
              {siteDescription.trim() && (
                <p className={cn(
                  "max-w-xl text-sm leading-6 text-muted-foreground sm:text-base",
                  siteSlogan.trim() && "mt-4",
                )}>
                  {siteDescription}
                </p>
              )}
            </div>
          </section>
        </FadeIn>
      )}

      {/* Category controls */}
      {categories.length > 0 && (
        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            onClick={() => setSelectedCategory(null)}
            aria-pressed={selectedCategory === null}
            className={cn(
              "relative rounded-control px-3.5 py-2 text-sm font-semibold transition-colors duration-180 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2",
              selectedCategory === null ? "text-primary-foreground" : "text-muted-foreground hover:bg-surface-muted hover:text-foreground"
            )}
          >
            {selectedCategory === null && <motion.span layoutId="active-category" className="absolute inset-0 -z-0 rounded-control bg-primary shadow-sm" transition={{ type: "spring", stiffness: 420, damping: 32 }} />}
            <span className="relative z-10">{t("home.allCategories")}</span>
          </button>
          {categories.map((cat) => (
            <button
              type="button"
              key={cat.id}
              onClick={() => setSelectedCategory(cat.id === selectedCategory ? null : cat.id)}
              aria-pressed={selectedCategory === cat.id}
              className={cn(
                "relative rounded-control px-3.5 py-2 text-sm font-semibold transition-colors duration-180 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2",
                selectedCategory === cat.id ? "text-primary-foreground" : "text-muted-foreground hover:bg-surface-muted hover:text-foreground"
              )}
            >
              {selectedCategory === cat.id && <motion.span layoutId="active-category" className="absolute inset-0 -z-0 rounded-control bg-primary shadow-sm" transition={{ type: "spring", stiffness: 420, damping: 32 }} />}
              <span className="relative z-10">{cat.name}</span>
            </button>
          ))}
        </div>
      )}

      {/* Product Grid */}
      {productsError ? (
        <div id="products" className="flex flex-col items-center justify-center gap-4 rounded-card border border-dashed border-border-strong bg-surface-muted/50 py-24 text-muted-foreground">
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
        <div id="products" className="grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
          {filteredProducts.map((product) => (
            <div key={product.id} className="min-w-0">
              <ProductCard product={product} />
            </div>
          ))}
        </div>
      ) : (
        <div id="products" className="flex flex-col items-center justify-center rounded-card border border-dashed border-border-subtle bg-surface-muted/40 py-24 text-muted-foreground">
          <Search className="mb-3 h-10 w-10 opacity-20" />
          <p className="text-sm">{t("home.noProducts")}</p>
        </div>
      )}
    </div>
  )
}
