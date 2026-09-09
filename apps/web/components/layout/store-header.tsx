"use client"

import { useCallback, useRef, useState, useEffect } from "react"
import { usePathname } from "next/navigation"
import Link from "next/link"
import Image from "next/image"
import { User, LogIn, Menu, X, Globe, Moon, Sun, Package, Search, Settings, ChevronDown, SlidersHorizontal, LogOut } from "lucide-react"
import { useAuth, useLocale, useTheme, useSearch, useSiteConfig, type SortKey } from "@/lib/context"
import { cn } from "@/lib/utils"
import { isAdminRole } from "@/lib/hooks"

interface StoreHeaderProps {
  siteName?: string
}

export function StoreHeader({ siteName }: StoreHeaderProps) {
  const { t, locale, setLocale } = useLocale()
  const { setTheme, resolvedTheme } = useTheme()
  const { isLoggedIn, user, logout, authLoaded } = useAuth()
  const { config: siteConfig } = useSiteConfig()
  const {
    searchQuery, setSearchQuery,
    searchOpen, setSearchOpen,
    sortBy, setSortBy,
    filterOpen, setFilterOpen,
    inStockOnly, setInStockOnly,
    priceMin, setPriceMin,
    priceMax, setPriceMax,
  } = useSearch()
  const pathname = usePathname()
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false)
  const [userMenuOpen, setUserMenuOpen] = useState(false)
  const [sortOpen, setSortOpen] = useState(false)
  const searchInputRef = useRef<HTMLInputElement>(null)
  const sortRef = useRef<HTMLDivElement>(null)

  const openSearch = useCallback(() => {
    setSearchOpen(true)
  }, [setSearchOpen])

  const closeSearch = useCallback(() => {
    setSearchOpen(false)
  }, [setSearchOpen])

  useEffect(() => {
    if (searchOpen) searchInputRef.current?.focus()
  }, [searchOpen])

  // Escape to close search
  useEffect(() => {
    function handleKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape" && searchOpen) closeSearch()
    }
    document.addEventListener("keydown", handleKeyDown)
    return () => document.removeEventListener("keydown", handleKeyDown)
  }, [searchOpen, closeSearch])

  // Close sort dropdown on outside click
  useEffect(() => {
    function handleClick(e: MouseEvent) {
      if (sortRef.current && !sortRef.current.contains(e.target as Node)) {
        setSortOpen(false)
      }
    }
    document.addEventListener("mousedown", handleClick)
    return () => document.removeEventListener("mousedown", handleClick)
  }, [])

  const navLinks = [
    { href: "/", label: t("nav.home") },
  ]

  const toggleTheme = () => setTheme(resolvedTheme === "dark" ? "light" : "dark")
  const toggleLocale = () => setLocale(locale === "zh" ? "en" : "zh")

  const sortOptions: { key: SortKey; label: string }[] = [
    { key: "default", label: t("home.sortDefault") },
    { key: "hot", label: t("home.sortHot") },
    { key: "price_low", label: t("home.sortPriceLow") },
    { key: "price_high", label: t("home.sortPriceHigh") },
    { key: "new", label: t("home.sortNew") },
  ]

  const activeFilterCount =
    (inStockOnly ? 1 : 0) + (priceMin ? 1 : 0) + (priceMax ? 1 : 0)

  const resetFilters = () => {
    setInStockOnly(false)
    setPriceMin("")
    setPriceMax("")
  }

  return (
    <header className="sticky top-0 z-50 border-b border-border-subtle bg-surface">
      <div className="mx-auto flex h-16 max-w-[1440px] items-center px-4 sm:px-6 lg:px-8">
        {/* Logo -- fixed width to balance right actions */}
        <div className="flex min-w-0 flex-1 items-center">
          <Link href="/" className="group/brand flex shrink-0 items-center gap-2.5 text-foreground">
            {siteConfig?.logo_url ? (
              <Image src={siteConfig.logo_url} alt={siteName || siteConfig?.site_name || ""} width={24} height={24} unoptimized className="h-6 w-6 rounded object-contain" />
            ) : (
              <span className="flex size-8 items-center justify-center rounded-control bg-primary/10 text-primary transition-transform duration-180 group-hover/brand:scale-[1.03]"><Package className="size-4" /></span>
            )}
            <span className="hidden text-[15px] font-semibold tracking-tight sm:inline">{siteName || siteConfig?.site_name}</span>
          </Link>
        </div>

        {/* Desktop Nav -- absolute center */}
        <nav className="hidden items-center gap-1 md:flex" aria-label="Main navigation">
          {navLinks.map((link) => {
            return (
              <Link
                key={link.href}
                href={link.href}
                className={cn(
                  "relative rounded-control px-3 py-2 text-sm font-medium transition-colors duration-180 hover:bg-surface-muted hover:text-foreground",
                  pathname === link.href
                    ? "bg-primary/10 text-foreground"
                    : "text-muted-foreground"
                )}
              >
                {link.label}
              </Link>
            )
          })}
        </nav>

        {/* Right Actions -- flex-1 to match logo side */}
        <div className="flex min-w-0 flex-1 items-center justify-end gap-1.5">
          {/* Search Toggle -- prominent icon button */}
          <button
            onClick={searchOpen ? closeSearch : openSearch}
            className={cn(
              "inline-flex h-9 items-center gap-1.5 rounded-control px-2.5 text-sm font-medium transition-colors duration-180",
              searchOpen
                ? "bg-primary/10 text-primary"
                : "text-muted-foreground hover:bg-surface-muted hover:text-foreground"
            )}
            aria-label="Search"
          >
            {searchOpen ? <X className="h-[18px] w-[18px]" /> : <Search className="h-[18px] w-[18px]" />}
            {!searchOpen && (
              <span className="hidden text-xs font-semibold sm:inline">
                {t("home.searchButton")}
              </span>
            )}
          </button>

          {/* Theme Toggle */}
          <button
            onClick={toggleTheme}
            className="inline-flex h-9 w-9 items-center justify-center rounded-control text-muted-foreground transition-colors duration-180 hover:bg-surface-muted hover:text-foreground"
            aria-label="Toggle theme"
          >
            <Sun className="h-4 w-4 dark:hidden" />
            <Moon className="h-4 w-4 hidden dark:block" />
          </button>

          {/* Locale Toggle */}
          <button
            onClick={toggleLocale}
            className="inline-flex h-9 w-9 items-center justify-center rounded-control text-muted-foreground transition-colors duration-180 hover:bg-surface-muted hover:text-foreground"
            aria-label="Toggle language"
          >
            <Globe className="h-4 w-4" />
          </button>

          {/* User / Auth — auth 未就绪时显示占位，避免登录按钮闪烁 */}
          {!authLoaded ? (
            <div className="h-9 w-9 animate-pulse rounded-control bg-surface-muted" />
          ) : isLoggedIn ? (
            <div className="relative">
              <button
                onClick={() => setUserMenuOpen(!userMenuOpen)}
                className="inline-flex h-9 items-center gap-1.5 rounded-control px-2 text-sm font-medium text-muted-foreground transition-colors duration-180 hover:bg-surface-muted hover:text-foreground"
              >
                <User className="h-4 w-4" />
                <span className="hidden sm:inline">{user?.username}</span>
              </button>
              {userMenuOpen && (
                <>
                  <div className="fixed inset-0 z-40" onClick={() => setUserMenuOpen(false)} />
                  <div className="absolute right-0 z-50 mt-2 w-52 rounded-card border border-border-subtle bg-surface-raised p-1.5 shadow-md">
                    <Link href="/profile" onClick={() => setUserMenuOpen(false)} className="flex w-full items-center gap-2 rounded-control px-2.5 py-2 text-sm text-foreground transition-colors hover:bg-surface-muted">
                      <User className="h-4 w-4" />{t("nav.profile")}
                    </Link>
                    <Link href="/my/orders" onClick={() => setUserMenuOpen(false)} className="flex w-full items-center gap-2 rounded-control px-2.5 py-2 text-sm text-foreground transition-colors hover:bg-surface-muted">
                      <Search className="h-4 w-4" />{t("nav.myOrders")}
                    </Link>
                    {isAdminRole(user?.role) && (
                      <Link href="/admin/dashboard" onClick={() => setUserMenuOpen(false)} className="flex w-full items-center gap-2 rounded-control px-2.5 py-2 text-sm text-foreground transition-colors hover:bg-surface-muted">
                        <Settings className="h-4 w-4" />{t("nav.admin")}
                      </Link>
                    )}
                    <hr className="my-1.5 border-border-subtle" />
                    <button onClick={() => { logout(); setUserMenuOpen(false) }} className="flex w-full items-center gap-2 rounded-control px-2.5 py-2 text-sm text-destructive transition-colors hover:bg-destructive/10">
                      <LogOut className="h-4 w-4" />{t("nav.logout")}
                    </button>
                  </div>
                </>
              )}
            </div>
          ) : (
            <Link href="/login" className="inline-flex h-9 items-center rounded-control bg-primary px-2.5 text-sm font-semibold text-primary-foreground shadow-sm transition-[background-color,transform] duration-180 hover:bg-primary/90 active:scale-[.98] sm:px-4">
              <LogIn className="h-4 w-4 sm:hidden" />
              <span className="hidden sm:inline">{t("nav.login")}</span>
            </Link>
          )}

          {/* Mobile Menu Toggle */}
          <button
            onClick={() => setMobileMenuOpen(!mobileMenuOpen)}
            className="inline-flex h-9 w-9 items-center justify-center rounded-control text-muted-foreground transition-colors duration-180 hover:bg-surface-muted hover:text-foreground md:hidden"
            aria-label="Toggle menu"
          >
            {mobileMenuOpen ? <X className="h-4 w-4" /> : <Menu className="h-4 w-4" />}
          </button>
        </div>
      </div>

      {/* Expanded Search Bar + Sort/Filter */}
      {searchOpen && (
        <div className="border-t border-border-subtle bg-surface-muted">
          <div className="mx-auto max-w-[1440px] px-4 py-3.5 sm:px-6 lg:px-8">
            <div className="flex items-center gap-2">
              {/* Search Input */}
              <div className="relative flex-1">
                <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
                <input
                  ref={searchInputRef}
                  type="text"
                  placeholder={t("home.search")}
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  className="h-11 w-full rounded-control border border-input bg-surface pl-9 pr-10 text-sm text-foreground placeholder:text-muted-foreground shadow-sm focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2 focus:ring-offset-background"
                />
                {searchQuery && (
                  <button
                    onClick={() => setSearchQuery("")}
                    className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
                  >
                    <X className="h-4 w-4" />
                  </button>
                )}
              </div>

              {/* Sort dropdown */}
              <div ref={sortRef} className="relative">
                <button
                  onClick={() => setSortOpen(!sortOpen)}
                  className="inline-flex h-11 items-center gap-1.5 rounded-control border border-input bg-surface px-3 text-sm font-medium text-foreground transition-colors hover:bg-surface-muted"
                >
                  <span className="hidden sm:inline">
                    {sortOptions.find((s) => s.key === sortBy)?.label}
                  </span>
                  <ChevronDown className={cn("h-3.5 w-3.5 text-muted-foreground transition-transform", sortOpen && "rotate-180")} />
                </button>
                {sortOpen && (
                  <div className="absolute right-0 z-30 mt-2 w-44 overflow-hidden rounded-card border border-border-subtle bg-surface-raised p-1 shadow-md">
                    {sortOptions.map((opt) => (
                      <button
                        key={opt.key}
                        onClick={() => { setSortBy(opt.key); setSortOpen(false) }}
                        className={cn(
                          "flex w-full items-center rounded-control px-3 py-2 text-sm transition-colors hover:bg-surface-muted",
                          sortBy === opt.key ? "bg-primary/10 font-medium text-foreground" : "text-muted-foreground"
                        )}
                      >
                        {opt.label}
                      </button>
                    ))}
                  </div>
                )}
              </div>

              {/* Filter toggle */}
              <button
                onClick={() => setFilterOpen(!filterOpen)}
                className={cn(
                  "relative inline-flex h-11 items-center gap-1.5 rounded-control border px-3 text-sm font-medium transition-colors",
                  filterOpen || activeFilterCount > 0
                    ? "border-primary/25 bg-primary/10 text-foreground"
                    : "border-input bg-surface text-muted-foreground hover:bg-surface-muted hover:text-foreground"
                )}
              >
                <SlidersHorizontal className="h-3.5 w-3.5" />
                <span className="hidden sm:inline">{t("home.filterTitle")}</span>
                {activeFilterCount > 0 && (
                  <span className="flex h-4 w-4 items-center justify-center rounded-full bg-primary text-[10px] font-bold text-primary-foreground">
                    {activeFilterCount}
                  </span>
                )}
              </button>
            </div>

            {/* Filter panel */}
            {filterOpen && (
              <div className="mt-3 flex flex-wrap items-center gap-3 rounded-card border border-border-subtle bg-surface px-4 py-3.5 shadow-sm">
                <div className="flex items-center gap-2">
                  <span className="text-xs font-medium text-muted-foreground">{t("home.filterPrice")}:</span>
                  <input type="number" placeholder="Min" value={priceMin} onChange={(e) => setPriceMin(e.target.value)} className="h-9 w-20 rounded-control border border-input bg-surface-muted px-2 text-xs text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-ring" />
                  <span className="text-xs text-muted-foreground">-</span>
                  <input type="number" placeholder="Max" value={priceMax} onChange={(e) => setPriceMax(e.target.value)} className="h-9 w-20 rounded-control border border-input bg-surface-muted px-2 text-xs text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-ring" />
                </div>
                <label className="flex cursor-pointer items-center gap-1.5">
                  <input type="checkbox" checked={inStockOnly} onChange={(e) => setInStockOnly(e.target.checked)} className="h-3.5 w-3.5 rounded border-input accent-foreground" />
                  <span className="text-xs font-medium text-muted-foreground">{t("home.filterInStock")}</span>
                </label>
                {activeFilterCount > 0 && (
                  <button onClick={resetFilters} className="ml-auto inline-flex items-center gap-1 text-xs font-medium text-muted-foreground transition-colors hover:text-foreground">
                    <X className="h-3 w-3" />{t("home.filterReset")}
                  </button>
                )}
              </div>
            )}
          </div>
        </div>
      )}

      {/* Mobile Nav */}
      {mobileMenuOpen && (
        <nav className="border-t border-border px-4 py-3 md:hidden" aria-label="Mobile navigation">
          <div className="flex flex-col gap-1">
            {navLinks.map((link) => (
                <Link
                  key={link.href}
                  href={link.href}
                  onClick={() => setMobileMenuOpen(false)}
                  className={cn(
                    "flex items-center gap-2 rounded-control px-3 py-2.5 text-sm font-medium transition-colors",
                    pathname === link.href
                      ? "bg-primary/10 text-foreground"
                      : "text-muted-foreground hover:bg-surface-muted hover:text-foreground"
                  )}
                >
                  {link.label}
                </Link>
            ))}
          </div>
        </nav>
      )}
    </header>
  )
}
