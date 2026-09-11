"use client"

import { useCallback, useEffect, useRef } from "react"
import { usePathname, useRouter } from "next/navigation"
import { Search } from "lucide-react"
import { useLocale, useSearch } from "@/lib/context"

export function StoreHeaderSearch() {
  const pathname = usePathname()
  const router = useRouter()
  const { t } = useLocale()
  const { searchOpen, setSearchOpen } = useSearch()
  const pendingOpenRef = useRef(false)

  useEffect(() => {
    if (pathname !== "/" || !pendingOpenRef.current) return
    pendingOpenRef.current = false
    setSearchOpen(true)
  }, [pathname, setSearchOpen])

  const handleClick = useCallback(() => {
    if (pathname === "/") {
      setSearchOpen(!searchOpen)
      return
    }

    pendingOpenRef.current = true
    router.push("/")
  }, [pathname, router, searchOpen, setSearchOpen])

  return (
    <button
      type="button"
      data-store-search-trigger="true"
      onClick={handleClick}
      aria-label={t("nav.search")}
      aria-expanded={pathname === "/" ? searchOpen : undefined}
      aria-controls="store-search-popover"
      className="store-header-search absolute right-2 inline-flex size-10 items-center justify-center rounded-full text-muted-foreground transition-colors hover:bg-foreground/[0.06] hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring sm:right-4"
    >
      <Search className="size-[18px]" strokeWidth={1.9} aria-hidden="true" />
    </button>
  )
}
