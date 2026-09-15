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
  const panelRef = useRef<HTMLElement>(null)

  const sortOptions: { key: SortKey; label: string }[] = [
    { key: "default", label: t("home.sortDefault") },
    { key: "hot", label: t("home.sortHot") },
    { key: "price_low", label: t("home.sortPriceLow") },
    { key: "price_high", label: t("home.sortPriceHigh") },
    { key: "new", label: t("home.sortNew") },
  ]

  const activeFilterCount =
    (inStockOnly ? 1 : 0) + (priceMin ? 1 : 0) + (priceMax ? 1 : 0)
  const isVisible = searchOpen && pathname === "/"

  useEffect(() => {
    if (pathname === "/" || !searchOpen) return
    setSearchOpen(false)
  }, [pathname, searchOpen, setSearchOpen])

  useEffect(() => {
    if (!isVisible) return

    const frame = window.requestAnimationFrame(() => {
      searchInputRef.current?.focus()
    })
    return () => window.cancelAnimationFrame(frame)
  }, [isVisible])

  useEffect(() => {
    if (!isVisible) return

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") setSearchOpen(false)
    }

    const handlePointerDown = (event: PointerEvent) => {
      const target = event.target
      if (!(target instanceof Element)) return
      if (panelRef.current?.contains(target)) return
      if (target.closest("[data-store-search-trigger]")) return
      setSearchOpen(false)
    }

    document.addEventListener("keydown", handleKeyDown)
    document.addEventListener("pointerdown", handlePointerDown)
    return () => {
      document.removeEventListener("keydown", handleKeyDown)
      document.removeEventListener("pointerdown", handlePointerDown)
    }
  }, [isVisible, setSearchOpen])

  const resetFilters = () => {
    setInStockOnly(false)
    setPriceMin("")
    setPriceMax("")
  }

  return (
    <AnimatePresence initial={false}>
      {isVisible && (
        <div className="store-search-popover-viewport">
          <motion.section
            ref={panelRef}
            id="store-search-popover"
            initial={{ opacity: 0, y: -4, scale: 0.985 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, y: -4, scale: 0.985 }}
            transition={
              reducedMotion
                ? { duration: 0 }
                : { type: "spring", stiffness: 500, damping: 36, mass: 0.42 }
            }
            className="store-search-popover transient-glass"
            aria-label={t("nav.search")}
          >
            <div className="store-search-field">
              <Search className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
              <input
                ref={searchInputRef}
                type="search"
                value={searchQuery}
                onChange={(event) => setSearchQuery(event.target.value)}
                placeholder={t("home.search")}
                aria-label={t("nav.search")}
                className="min-w-0 flex-1 appearance-none bg-transparent text-sm text-foreground outline-none placeholder:text-muted-foreground"
              />
              {searchQuery && (
                <button
                  type="button"
                  onClick={() => setSearchQuery("")}
                  aria-label={t("home.clearSearch")}
                  className="inline-flex size-7 shrink-0 items-center justify-center rounded-full text-muted-foreground transition-colors hover:bg-foreground/[0.07] hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                >
                  <X className="size-4" aria-hidden="true" />
                </button>
              )}
            </div>

            <div className="store-search-controls">
              <div className="relative min-w-0 flex-1">
                <select
                  value={sortBy}
                  onChange={(event) => setSortBy(event.target.value as SortKey)}
                  aria-label={t("home.sortLabel")}
                  className="h-10 w-full appearance-none rounded-control border border-input bg-background/55 px-3 pr-8 text-sm font-medium text-foreground focus:outline-none focus:ring-2 focus:ring-ring/40"
                >
                  {sortOptions.map((option) => (
                    <option key={option.key} value={option.key}>
                      {option.label}
                    </option>
                  ))}
                </select>
                <ChevronDown
                  className="pointer-events-none absolute right-2.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground"
                  aria-hidden="true"
                />
              </div>
              <button
                type="button"
                onClick={() => setFilterOpen(!filterOpen)}
                aria-expanded={filterOpen}
                aria-controls="store-search-filters"
                className={cn(
                  "inline-flex h-10 shrink-0 items-center gap-1.5 rounded-control border px-3 text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/40",
                  filterOpen || activeFilterCount > 0
                    ? "border-primary/30 bg-primary/10 text-foreground"
                    : "border-input bg-background/55 text-muted-foreground hover:bg-foreground/[0.06] hover:text-foreground",
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
                    className="h-9 w-full rounded-control border border-input bg-background/55 px-2.5 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring/40"
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
                    className="h-9 w-full rounded-control border border-input bg-background/55 px-2.5 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring/40"
                  />
                </label>
                <label className="flex min-h-9 w-full cursor-pointer items-center gap-2 rounded-control px-1 text-xs font-medium text-foreground sm:w-auto">
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
                    className="min-h-9 rounded-control px-2 text-xs font-medium text-muted-foreground transition-colors hover:bg-foreground/[0.06] hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
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
