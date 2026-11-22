"use client"

import { useState, useMemo, useEffect, useRef } from "react"
import {
  Search,
  RefreshCw,
  Loader2,
} from "lucide-react"
import { motion } from "motion/react"
import { useLocale } from "@/lib/context"
import { ProductCard } from "@/components/store/product-card"
import { FadeIn } from "@/components/ui/motion"
import { Pagination } from "@/components/shared/pagination"
import { getRootCategories } from "@/lib/categories"
import { productApi } from "@/services/api"
import { cn } from "@/lib/utils"
import type { ProductCard as ProductCardType, Category, PaginatedData, ProductListQuery } from "@/types"

interface HomeContentProps {
  initialProducts: PaginatedData<ProductCardType>
  categories: Category[]
  siteName: string
  siteSlogan: string
  siteDescription: string
  productsError?: boolean
}

export function HomeContent({ initialProducts, categories, siteName, siteSlogan, siteDescription, productsError = false }: HomeContentProps) {
  const { t } = useLocale()

  const [selectedRootCategory, setSelectedRootCategory] = useState<string | null>(null)
  const [selectedChildCategory, setSelectedChildCategory] = useState<string | null>(null)
  const rootCategories = getRootCategories(categories)
  const selectedRoot = rootCategories.find((category) => category.id === selectedRootCategory) ?? null
  const selectedChildren = selectedRoot?.children ?? []

  const filters = useMemo<ProductListQuery>(() => ({
    category_id: selectedChildCategory ?? selectedRootCategory ?? undefined,
    sort: "default",
  }), [selectedRootCategory, selectedChildCategory])
  const filterKey = JSON.stringify(filters)
  const [pageSelection, setPageSelection] = useState({ filterKey, page: 1 })
  useEffect(() => {
    setPageSelection(current => current.filterKey === filterKey ? current : { filterKey, page: 1 })
  }, [filterKey])
  // Changing any filter starts at page one without issuing a request for the old page.
  const page = pageSelection.filterKey === filterKey ? pageSelection.page : 1
  const params = useMemo(() => ({ ...filters, page, page_size: 24 }), [filters, page])
  const [catalog, setCatalog] = useState(initialProducts)
  const [loading, setLoading] = useState(false)
  const [loadError, setLoadError] = useState(productsError)
  const [retry, setRetry] = useState(0)
  const firstRequest = useRef(true)

  useEffect(() => {
    if (firstRequest.current) {
      firstRequest.current = false
      // The server has already rendered the unfiltered first page with viewer prices.
      if (!filters.category_id && page === 1) return
    }
    const controller = new AbortController()
    setLoading(true)
    setLoadError(false)
    void productApi.getList(params, { signal: controller.signal })
      .then(result => {
        if (controller.signal.aborted) return
        const lastPage = Math.max(1, Math.ceil(result.pagination.total / result.pagination.page_size))
        if (page > lastPage) {
          setPageSelection({ filterKey: JSON.stringify(filters), page: lastPage })
          return
        }
        setCatalog(result)
      })
      .catch(() => {
        if (!controller.signal.aborted) setLoadError(true)
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false)
      })
    return () => controller.abort()
  }, [filters, page, params, retry])

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
                <p className="store-home-brand">{siteName}</p>
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

      <section className="store-catalog" aria-label={t("home.allCategories")} aria-busy={loading}>
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
      {loading && <div className="flex items-center gap-2 text-sm text-muted-foreground" role="status"><Loader2 className="size-4 animate-spin" aria-hidden="true" />{t("common.loading")}</div>}
      {loadError ? (
        <div id="products" className="store-catalog-empty flex flex-col items-center justify-center gap-4 py-24 text-muted-foreground">
          <Search className="h-10 w-10 opacity-20" />
          <p className="text-sm">{t("home.loadFailed")}</p>
          <button
            type="button"
            onClick={() => setRetry(value => value + 1)}
            className="inline-flex items-center gap-2 rounded-control border border-border-strong bg-surface px-4 py-2 text-sm font-medium text-foreground transition-colors hover:bg-surface-muted"
          >
            <RefreshCw className="h-4 w-4" />
            {t("common.retry")}
          </button>
        </div>
      ) : catalog.list.length > 0 ? (
        <div id="products" className={cn("store-product-grid", loading && "pointer-events-none opacity-50")}>
          {catalog.list.map((product, index) => (
            <div key={product.id} className="min-w-0">
              <ProductCard product={product} priority={index === catalog.list.findIndex((item) => !!item.cover_url)} />
            </div>
          ))}
        </div>
      ) : (
        <div id="products" className="store-catalog-empty flex flex-col items-center justify-center py-24 text-muted-foreground">
          <Search className="mb-3 h-10 w-10 opacity-20" />
          <p className="text-sm">{t("home.noProducts")}</p>
        </div>
      )}
      {!loadError && <Pagination
        page={page}
        pageSize={24}
        total={catalog.pagination.total}
        disabled={loading}
        onChange={nextPage => setPageSelection({ filterKey, page: nextPage })}
      />}
      </section>
    </div>
  )
}
