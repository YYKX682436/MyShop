"use client"

import { useSiteConfig } from "@/lib/context"
import { ContactMethods } from "@/components/shared/contact-methods"

export function StoreFooter() {
  const { config } = useSiteConfig()

  return (
    <footer className="border-t border-border bg-muted/40">
      <div className="mx-auto flex min-h-14 w-full max-w-7xl flex-wrap items-center justify-center gap-x-4 gap-y-2 px-4 py-3 lg:px-6">
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
