"use client"

import { useSiteConfig } from "@/lib/context"
import { ContactMethods } from "@/components/shared/contact-methods"
import { cn } from "@/lib/utils"

export function StoreFooter({ reserveForDock = false }: { reserveForDock?: boolean }) {
  const { config } = useSiteConfig()

  return (
    <footer className={cn(
      "storefront-footer border-t border-border-subtle bg-surface-muted/55",
      reserveForDock && "storefront-footer--with-dock",
    )}>
      <div className="mx-auto flex min-h-16 w-full max-w-[1440px] flex-wrap items-center justify-center gap-x-5 gap-y-2 px-4 py-4 sm:justify-between sm:px-6 lg:px-8">
        {config?.footer_text && (
          <p className="max-w-full break-words text-center text-sm text-muted-foreground">
            {config.footer_text}
          </p>
        )}
        <ContactMethods email={config?.contact_email} items={config?.contact_items} />
      </div>
    </footer>
  )
}
