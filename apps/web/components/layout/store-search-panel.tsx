"use client"

import { useEffect, useRef } from "react"
import { usePathname } from "next/navigation"
import { AnimatePresence, motion, useReducedMotion } from "motion/react"
import { ChevronDown, Search, SlidersHorizontal, X } from "lucide-react"
import { useLocale, useSearch, type SortKey } from "@/lib/context"
import { cn } from "@/lib/utils"

export function StoreSearchPanel() {
  const pathname = usePathname()
  const reducedMotion = useReducedMotion()
  const { t } = useLocale()
  const {
    searchQuery,
    setSearchQuery,
    searchOpen,
    setSearchOpen,
    sortBy,
    setSortBy,
    filterOpen,
    setFilterOpen,
    inStockOnly,
    setInStockOnly,
    priceMin,
    setPriceMin,
    priceMax,
    setPriceMax,
  } = useSearch()
  const searchInputRef = useRef<HTMLInputElement>(null)

  const sortOptions: { key: SortKey; label: string }[] = [
    { key: "default", label: t("home.sortDefault") },
    { key: "hot", label: t("home.sortHot") },
    { key: "price_low", label: t("home.sortPriceLow") },
    { key: "price_high", label: t("home.sortPriceHigh") },
    { key: "new", label: t("home.sortNew") },
  ]

  const activeFilterCount =
    (inStockOnly ? 1 : 0) + (priceMin ? 1 : 0) + (priceMax ? 1 : 0)

  useEffect(() => {
    if (!searchOpen || pathname !== "/") return

    const frame = window.requestAnimationFrame(() => {
      searchInputRef.current?.focus()
    })
    return () => window.cancelAnimationFrame(frame)
  }, [pathname, searchOpen])

  useEffect(() => {
    if (!searchOpen || pathname !== "/") return

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") setSearchOpen(false)
    }

    document.addEventListener("keydown", handleKeyDown)
    return () => document.removeEventListener("keydown", handleKeyDown)
  }, [pathname, searchOpen, setSearchOpen])

  const resetFilters = () => {
    setInStockOnly(false)
    setPriceMin("")
    setPriceMax("")
  }

  const isVisible = searchOpen && pathname === "/"

  return (
    <AnimatePresence initial={false}>
      {isVisible && (
        <div className="store-search-viewport">
          <motion.section
            initial={{ opacity: 0, y: 10, scale: 0.985 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, y: 8, scale: 0.985 }}
            transition={
              reducedMotion
                ? { duration: 0 }
                : { type: "spring", stiffness: 430, damping: 34, mass: 0.55 }
            }
            className="store-search-panel store-glass"
            aria-label={t("nav.search")}
          >
            <div className="flex items-start justify-between gap-3">
              <div>
                <p className="text-sm font-semibold text-foreground">{t("nav.search")}</p>
                <p className="mt-0.5 text-xs text-muted-foreground">{t("home.search")}</p>
              </div>
              <button
                type="button"
                onClick={() => setSearchOpen(false)}
                aria-label={t("common.close")}
                className="inline-flex size-9 shrink-0 items-center justify-center rounded-full text-muted-foreground transition-colors hover:bg-foreground/[0.06] hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              >
                <X className="size-4" aria-hidden="true" />
              </button>
            </div>

            <div className="relative mt-3">
              <Search
                className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground"
                aria-hidden="true"
              />
              <input
                ref={searchInputRef}
                type="search"
                value={searchQuery}
                onChange={(event) => setSearchQuery(event.target.value)}
                placeholder={t("home.search")}
                aria-label={t("nav.search")}
                className="h-11 w-full rounded-control border border-input bg-background/60 px-9 pr-10 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring/40"
              />
              {searchQuery && (
                <button
                  type="button"
                  onClick={() => setSearchQuery("")}
                  aria-label={t("home.clearSearch")}
                  className="absolute right-2 top-1/2 inline-flex size-8 -translate-y-1/2 items-center justify-center rounded-full text-muted-foreground transition-colors hover:bg-foreground/[0.06] hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                >
                  <X className="size-4" aria-hidden="true" />
                </button>
              )}
            </div>

            <div className="mt-2 flex gap-2">
              <div className="relative min-w-0 flex-1">
                <select
                  value={sortBy}
                  onChange={(event) => setSortBy(event.target.value as SortKey)}
                  aria-label={t("home.sortLabel")}
                  className="h-11 w-full appearance-none rounded-control border border-input bg-background/60 px-3 pr-9 text-sm font-medium text-foreground focus:outline-none focus:ring-2 focus:ring-ring/40"
                >
                  {sortOptions.map((option) => (
                    <option key={option.key} value={option.key}>
                      {option.label}
                    </option>
                  ))}
                </select>
                <ChevronDown
                  className="pointer-events-none absolute right-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground"
                  aria-hidden="true"
                />
              </div>
              <button
                type="button"
                onClick={() => setFilterOpen(!filterOpen)}
                aria-expanded={filterOpen}
                aria-controls="store-search-filters"
                className={cn(
                  "inline-flex h-11 shrink-0 items-center gap-1.5 rounded-control border px-3 text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/40",
                  filterOpen || activeFilterCount > 0
                    ? "border-primary/30 bg-primary/10 text-foreground"
                    : "border-input bg-background/60 text-muted-foreground hover:bg-foreground/[0.06] hover:text-foreground",
                )}
              >
                <SlidersHorizontal className="size-4" aria-hidden="true" />
                <span>{t("home.filterTitle")}</span>
                {activeFilterCount > 0 && (
                  <span className="inline-flex size-4 items-center justify-center rounded-full bg-primary text-[10px] font-bold text-primary-foreground">
                    {activeFilterCount}
                  </span>
                )}
              </button>
            </div>

            {filterOpen && (
              <div
                id="store-search-filters"
                className="mt-2 flex flex-wrap items-end gap-2 rounded-card border border-border-subtle bg-background/35 p-3"
              >
                <label className="min-w-[110px] flex-1 text-xs font-medium text-muted-foreground">
                  <span className="mb-1.5 block">{t("home.filterMin")}</span>
                  <input
                    type="number"
                    inputMode="decimal"
                    min="0"
                    value={priceMin}
                    onChange={(event) => setPriceMin(event.target.value)}
                    placeholder={t("home.filterMin")}
                    aria-label={t("home.filterMin")}
                    className="h-10 w-full rounded-control border border-input bg-background/55 px-2.5 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring/40"
                  />
                </label>
                <label className="min-w-[110px] flex-1 text-xs font-medium text-muted-foreground">
                  <span className="mb-1.5 block">{t("home.filterMax")}</span>
                  <input
                    type="number"
                    inputMode="decimal"
                    min="0"
                    value={priceMax}
                    onChange={(event) => setPriceMax(event.target.value)}
                    placeholder={t("home.filterMax")}
                    aria-label={t("home.filterMax")}
                    className="h-10 w-full rounded-control border border-input bg-background/55 px-2.5 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring/40"
                  />
                </label>
                <label className="flex min-h-10 w-full cursor-pointer items-center gap-2 rounded-control px-1 text-xs font-medium text-foreground sm:w-auto">
                  <input
                    type="checkbox"
                    checked={inStockOnly}
                    onChange={(event) => setInStockOnly(event.target.checked)}
                    className="size-4 rounded border-input accent-foreground"
                  />
                  <span>{t("home.filterInStock")}</span>
                </label>
                {activeFilterCount > 0 && (
                  <button
                    type="button"
                    onClick={resetFilters}
                    className="min-h-10 rounded-control px-2 text-xs font-medium text-muted-foreground transition-colors hover:bg-foreground/[0.06] hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                  >
                    {t("home.filterReset")}
                  </button>
                )}
              </div>
            )}
          </motion.section>
        </div>
      )}
    </AnimatePresence>
  )
}
