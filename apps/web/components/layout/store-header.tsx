import Link from "next/link"
import Image from "next/image"
import { StoreHeaderSearch } from "./store-header-search"

interface StoreHeaderProps {
  siteName?: string
  logoUrl?: string
}

/**
 * The brand remains visually centered while the search action is anchored to
 * the header's right edge so it never changes the title's optical position.
 */
export function StoreHeader({ siteName = "", logoUrl }: StoreHeaderProps) {
  return (
    <div className="store-header-layer">
      <header className="store-header-frame static-material">
        <div className="relative mx-auto flex h-14 max-w-[1440px] items-center justify-center px-4 sm:px-6 lg:px-8">
          <Link
            href="/"
            className="absolute left-1/2 inline-flex min-h-11 max-w-[calc(100%-7rem)] -translate-x-1/2 items-center justify-center truncate rounded-control px-3 text-[15px] font-semibold tracking-tight text-foreground transition-colors duration-180 hover:text-foreground/70 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background"
          >
            {logoUrl && <Image src={logoUrl} alt="" width={28} height={28} unoptimized className="mr-2 size-7 shrink-0 object-contain" />}
            <span className="truncate">{siteName}</span>
          </Link>
          <StoreHeaderSearch />
        </div>
      </header>
    </div>
  )
}
