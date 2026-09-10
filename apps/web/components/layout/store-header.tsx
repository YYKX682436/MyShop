import Link from "next/link"

interface StoreHeaderProps {
  siteName?: string
}

/**
 * The storefront header intentionally only owns the brand lockup.
 * Primary navigation and actions live in the floating dock.
 */
export function StoreHeader({ siteName = "" }: StoreHeaderProps) {
  return (
    <header className="store-header-glass store-glass sticky top-0 z-30">
      <div className="mx-auto flex h-14 max-w-[1440px] items-center justify-center px-4 sm:px-6 lg:px-8">
        <Link
          href="/"
          className="inline-flex min-h-11 items-center justify-center rounded-control px-3 text-[15px] font-semibold tracking-tight text-foreground transition-colors duration-180 hover:text-foreground/70 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background"
        >
          {siteName}
        </Link>
      </div>
    </header>
  )
}
