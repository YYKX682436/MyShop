"use client"

import { useSiteConfig } from "@/lib/context"
import { ContactMethods } from "@/components/shared/contact-methods"
import { cn } from "@/lib/utils"
import Link from "next/link"

export function StoreFooter({ reserveForDock = false }: { reserveForDock?: boolean }) {
  const { config } = useSiteConfig()

  return (
    <footer className={cn(
      "storefront-footer border-t border-border-subtle bg-surface-muted/55",
      reserveForDock && "storefront-footer--with-dock",
    )}>
      <div className="mx-auto flex min-h-12 w-full max-w-[1440px] flex-wrap items-center justify-center gap-x-5 gap-y-2 px-4 py-3 sm:min-h-16 sm:justify-between sm:px-6 sm:py-4 lg:px-8">
        {config?.footer_text && (
          <p className="max-w-full break-words text-center text-xs sm:text-sm text-muted-foreground">
            {config.footer_text}
          </p>
        )}
        <div className="flex flex-wrap items-center gap-4"><Link href="/messages/support/new" className="text-sm text-primary">联系主站客服</Link><ContactMethods email={config?.contact_email} items={config?.contact_items} customerService={config?.customer_service} /></div>
      </div>
    </footer>
  )
}
