"use client"

import { useState } from "react"
import { ExternalLink } from "lucide-react"
import { useLocale, useSiteConfig } from "@/lib/context"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import type { ContactItem } from "@/types"

export function StoreFooter() {
  const { t } = useLocale()
  const { config } = useSiteConfig()
  const [selectedContact, setSelectedContact] = useState<ContactItem | null>(null)
  const contactItems = (config?.contact_items ?? []).slice(0, 3)

  return (
    <footer className="border-t border-border bg-muted/40">
      <div className="mx-auto flex min-h-14 w-full max-w-7xl flex-wrap items-center justify-center gap-x-4 gap-y-2 px-4 py-3 lg:px-6">
        {config?.footer_text && (
          <p className="max-w-full break-words text-center text-sm text-muted-foreground">
            {config.footer_text}
          </p>
        )}
        {config?.contact_email && (
          <a
            href={`mailto:${config.contact_email}`}
            className="max-w-full break-all text-xs text-muted-foreground hover:text-foreground"
          >
            {config.contact_email}
          </a>
        )}
        {contactItems.length > 0 && (
          <div className="flex max-w-full flex-wrap items-center justify-center gap-x-4 gap-y-2">
            {contactItems.map((item) => item.qr_url ? (
              <button
                key={`${item.name}-${item.qr_url}`}
                type="button"
                className="max-w-full break-words text-xs text-muted-foreground underline-offset-4 hover:text-foreground hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
                onClick={() => setSelectedContact(item)}
              >
                {item.name}
              </button>
            ) : item.url ? (
              <a
                key={`${item.name}-${item.url}`}
                href={item.url}
                target="_blank"
                rel="noopener noreferrer"
                className="inline-flex max-w-full items-center gap-1 break-words text-xs text-muted-foreground underline-offset-4 hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
              >
                <span>{item.name}</span>
                <ExternalLink className="h-3 w-3 shrink-0" aria-hidden="true" />
              </a>
            ) : null)}
          </div>
        )}
      </div>

      <Dialog
        open={selectedContact !== null}
        onOpenChange={(open) => {
          if (!open) setSelectedContact(null)
        }}
      >
        {selectedContact && (
          <DialogContent className="w-[calc(100%-2rem)] max-h-[90vh] overflow-y-auto p-5 sm:p-6">
            <DialogHeader>
              <DialogTitle>{selectedContact.name}</DialogTitle>
              <DialogDescription>{t("footer.viewQr")}</DialogDescription>
            </DialogHeader>
            <div className="flex flex-col items-center gap-5">
              <div className="flex max-h-[65vh] w-full items-center justify-center rounded-xl bg-white p-3 sm:p-5">
                <img
                  src={selectedContact.qr_url}
                  alt={`${selectedContact.name} ${t("footer.viewQr")}`}
                  className="max-h-[55vh] max-w-full object-contain"
                />
              </div>
              {selectedContact.url && (
                <a
                  href={selectedContact.url}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="inline-flex min-h-10 w-full items-center justify-center gap-2 rounded-lg bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90 sm:w-auto"
                >
                  {t("footer.openLink")}
                  <ExternalLink className="h-4 w-4" aria-hidden="true" />
                </a>
              )}
            </div>
          </DialogContent>
        )}
      </Dialog>
    </footer>
  )
}
