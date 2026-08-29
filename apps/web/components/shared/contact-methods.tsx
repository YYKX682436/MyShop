"use client"

import { useState } from "react"
import { ExternalLink, HelpCircle, Mail } from "lucide-react"
import { useLocale } from "@/lib/context"
import {
  getContactItemAction,
  getDisplayContactItems,
  hasContactMethods,
} from "@/lib/contact-items"
import { cn } from "@/lib/utils"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import type { ContactItem } from "@/types"

type ContactMethodsVariant = "footer" | "support"

interface ContactQrDialogProps {
  contact: ContactItem | null
  open: boolean
  onOpenChange: (open: boolean) => void
}

/** Shared QR preview and optional link dialog used by every storefront contact surface. */
export function ContactQrDialog({ contact, open, onOpenChange }: ContactQrDialogProps) {
  const { t } = useLocale()

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      {contact && (
        <DialogContent className="max-h-[90vh] w-[calc(100%-2rem)] overflow-y-auto p-5 sm:p-6">
          <DialogHeader>
            <DialogTitle>{contact.name}</DialogTitle>
            <DialogDescription>{t("footer.viewQr")}</DialogDescription>
          </DialogHeader>
          <div className="flex flex-col items-center gap-5">
            <div className="flex max-h-[65vh] w-full items-center justify-center rounded-xl bg-white p-3 sm:p-5">
              <img
                src={contact.qr_url ?? ""}
                alt={`${contact.name} ${t("footer.viewQr")}`}
                className="max-h-[55vh] max-w-full object-contain"
              />
            </div>
            {contact.url && (
              <a
                href={contact.url}
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
  )
}

interface ContactItemsProps {
  items?: readonly ContactItem[] | null
  variant?: ContactMethodsVariant
  className?: string
}

function ContactItemAction({
  item,
  index,
  variant,
  onOpenQr,
}: {
  item: ContactItem
  index: number
  variant: ContactMethodsVariant
  onOpenQr: (item: ContactItem) => void
}) {
  const action = getContactItemAction(item)
  const actionClassName = cn(
    "max-w-full break-words transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2",
    variant === "footer"
      ? "text-xs text-muted-foreground underline-offset-4 hover:text-foreground hover:underline"
      : "inline-flex min-h-10 items-center gap-2 rounded-lg border border-border bg-background px-3 py-2 text-sm font-medium text-foreground hover:bg-accent",
  )
  const key = `${item.name}-${item.url ?? ""}-${item.qr_url ?? ""}-${index}`

  if (action === "qr") {
    return (
      <button
        key={key}
        type="button"
        className={actionClassName}
        onClick={() => onOpenQr(item)}
      >
        {item.name}
      </button>
    )
  }

  if (action === "link") {
    return (
      <a
        key={key}
        href={item.url}
        target="_blank"
        rel="noopener noreferrer"
        className={cn(actionClassName, "inline-flex items-center gap-1")}
      >
        <span>{item.name}</span>
        <ExternalLink className="h-3 w-3 shrink-0" aria-hidden="true" />
      </a>
    )
  }

  return null
}

/** Shared contact_items controls; QR+link always follows the QR-first footer contract. */
export function ContactItems({ items, variant = "footer", className }: ContactItemsProps) {
  const displayItems = getDisplayContactItems(items)
  const [selectedContact, setSelectedContact] = useState<ContactItem | null>(null)

  if (displayItems.length === 0) return null

  return (
    <>
      <div className={cn("flex max-w-full flex-wrap items-center justify-center gap-x-4 gap-y-2", className)}>
        {displayItems.map((item, index) => (
          <ContactItemAction
            key={`${item.name}-${index}`}
            item={item}
            index={index}
            variant={variant}
            onOpenQr={setSelectedContact}
          />
        ))}
      </div>
      <ContactQrDialog
        contact={selectedContact}
        open={selectedContact !== null}
        onOpenChange={(open) => {
          if (!open) setSelectedContact(null)
        }}
      />
    </>
  )
}

interface ContactMethodsProps {
  email?: string | null
  items?: readonly ContactItem[] | null
  variant?: ContactMethodsVariant
  emailLabel?: string
  className?: string
}

/** Shared email + contact_items surface. It returns nothing when support is unavailable. */
export function ContactMethods({
  email,
  items,
  variant = "footer",
  emailLabel,
  className,
}: ContactMethodsProps) {
  const normalizedEmail = email?.trim()
  const displayItems = getDisplayContactItems(items)

  if (!hasContactMethods(normalizedEmail, displayItems)) return null

  if (variant === "support") {
    return (
      <div className={cn("flex flex-wrap items-center justify-center gap-2", className)}>
        {normalizedEmail && (
          <a
            href={`mailto:${normalizedEmail}`}
            className="inline-flex min-h-10 items-center gap-2 rounded-lg bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90"
          >
            <Mail className="h-4 w-4" aria-hidden="true" />
            {emailLabel ?? normalizedEmail}
          </a>
        )}
        <ContactItems items={displayItems} variant="support" />
      </div>
    )
  }

  return (
    <div className={cn("flex max-w-full flex-wrap items-center justify-center gap-x-4 gap-y-2", className)}>
      {normalizedEmail && (
        <a
          href={`mailto:${normalizedEmail}`}
          className="max-w-full break-all text-xs text-muted-foreground hover:text-foreground"
        >
          {normalizedEmail}
        </a>
      )}
      <ContactItems items={displayItems} variant="footer" />
    </div>
  )
}

interface ContactSupportProps {
  title: string
  email?: string | null
  items?: readonly ContactItem[] | null
  emailLabel: string
  className?: string
}

/** Shared help section for payment and maintenance pages. */
export function ContactSupport({ title, email, items, emailLabel, className }: ContactSupportProps) {
  if (!hasContactMethods(email, items)) return null

  return (
    <section className={cn("flex flex-col items-center gap-2 text-sm", className)}>
      <div className="flex items-center gap-1 text-muted-foreground">
        <HelpCircle className="h-3.5 w-3.5" aria-hidden="true" />
        <span>{title}</span>
      </div>
      <ContactMethods email={email} items={items} variant="support" emailLabel={emailLabel} />
    </section>
  )
}
