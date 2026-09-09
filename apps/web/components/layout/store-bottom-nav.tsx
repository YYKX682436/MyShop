"use client"

import Link from "next/link"
import { usePathname, useRouter } from "next/navigation"
import { House, ReceiptText, Search, UserRound, type LucideIcon } from "lucide-react"
import { motion, useReducedMotion } from "motion/react"
import { useLocale, useSearch } from "@/lib/context"
import { getStorefrontActiveTab, type StorefrontNavKey } from "@/lib/storefront-routes"
import { cn } from "@/lib/utils"

interface StoreNavItem {
  key: StorefrontNavKey
  href: string
  label: string
  icon: LucideIcon
}

export function StoreBottomNav() {
  const pathname = usePathname()
  const router = useRouter()
  const reducedMotion = useReducedMotion()
  const { t } = useLocale()
  const {
    searchQuery,
    searchOpen,
    setSearchOpen,
    sortBy,
    inStockOnly,
    priceMin,
    priceMax,
  } = useSearch()

  const activeTab = getStorefrontActiveTab(pathname)
  const hasActiveSearch =
    searchQuery.trim().length > 0 ||
    sortBy !== "default" ||
    inStockOnly ||
    priceMin.trim().length > 0 ||
    priceMax.trim().length > 0

  const navItems: StoreNavItem[] = [
    { key: "home", href: "/", label: t("nav.home"), icon: House },
    { key: "orders", href: "/my/orders", label: t("nav.orders"), icon: ReceiptText },
    { key: "me", href: "/my", label: t("nav.me"), icon: UserRound },
  ]

  const handleSearchClick = () => {
    if (pathname === "/") {
      setSearchOpen(!searchOpen)
      return
    }

    // The search results are owned by HomeContent. Keep the shared search state
    // open while navigating so the panel appears as soon as / is active.
    setSearchOpen(true)
    router.push("/")
  }

  return (
    <div className="store-dock-viewport">
      <nav className="store-dock" aria-label={t("nav.main")}>
        <div className="store-dock-pill store-glass">
          {navItems.map((item) => {
            const Icon = item.icon
            const isActive = activeTab === item.key

            return (
              <Link
                key={item.key}
                href={item.href}
                onClick={() => setSearchOpen(false)}
                aria-current={isActive ? "page" : undefined}
                className={cn(
                  "store-dock-item text-muted-foreground",
                  isActive && "text-foreground",
                )}
              >
                {isActive && (
                  <motion.span
                    layoutId="store-dock-active"
                    className="store-dock-active"
                    transition={
                      reducedMotion
                        ? { duration: 0 }
                        : { type: "spring", stiffness: 520, damping: 42, mass: 0.45 }
                    }
                    aria-hidden="true"
                  />
                )}
                <span className="store-dock-item-content">
                  <Icon className="size-[18px]" strokeWidth={1.9} aria-hidden="true" />
                  <span className="store-dock-label">{item.label}</span>
                </span>
              </Link>
            )
          })}
        </div>

        <button
          type="button"
          onClick={handleSearchClick}
          aria-label={
            hasActiveSearch
              ? `${t("nav.search")} · ${t("home.searchActive")}`
              : t("nav.search")
          }
          aria-expanded={searchOpen}
          title={t("nav.search")}
          className={cn(
            "store-dock-search store-glass text-foreground",
            searchOpen && "store-dock-search-open",
          )}
        >
          <Search className="size-[21px]" strokeWidth={1.9} aria-hidden="true" />
          {hasActiveSearch && <span className="store-dock-search-indicator" aria-hidden="true" />}
        </button>
      </nav>
    </div>
  )
}
