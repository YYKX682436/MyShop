"use client"

import { useEffect, useRef, useState } from "react"
import { toast } from "sonner"

import { Input } from "@/components/ui/input"
import { Popover, PopoverTrigger } from "@/components/ui/popover"
import { useLocale } from "@/lib/context"
import { validateProductTitleDraft } from "@/lib/product-quick-edit"
import type { AdminProductDetail } from "@/types"
import { QuickEditActions, QuickEditContent, QuickEditTrigger } from "@/components/admin/shared/quick-edit"

export function ProductTitleQuickEdit({
  product,
  saving,
  onSave,
}: {
  product: AdminProductDetail
  saving: boolean
  onSave: (title: string) => Promise<boolean>
}) {
  const { t } = useLocale()
  const [open, setOpen] = useState(false)
  const [draft, setDraft] = useState(product.title)
  const inputRef = useRef<HTMLInputElement>(null)
  const saveInFlightRef = useRef(false)

  useEffect(() => {
    if (open) {
      setDraft(product.title)
      window.requestAnimationFrame(() => inputRef.current?.focus())
    }
  }, [open, product.title])

  const focusInput = (event: Event) => {
    event.preventDefault()
    window.requestAnimationFrame(() => inputRef.current?.focus())
  }

  const handleSave = async () => {
    if (saving || saveInFlightRef.current) return
    const validation = validateProductTitleDraft(draft)
    if (validation === "required") {
      toast.error(t("admin.productNameRequired"))
      return
    }
    if (validation === "length") {
      toast.error(t("admin.productNameLength"))
      return
    }
    const title = draft.trim()
    if (title === product.title) {
      setOpen(false)
      return
    }
    saveInFlightRef.current = true
    try {
      const success = await onSave(title)
      if (success) setOpen(false)
      else setDraft(product.title)
    } finally {
      saveInFlightRef.current = false
    }
  }

  return (
    <Popover open={open} onOpenChange={(nextOpen) => !saving && setOpen(nextOpen)}>
      <PopoverTrigger asChild>
        <QuickEditTrigger
          className="block w-full truncate px-0 py-0.5 font-medium text-foreground hover:text-primary"
          disabled={saving}
          aria-label={`${t("admin.editProductTitle")}: ${product.title}`}
          title={product.title}
        >
          {product.title}
        </QuickEditTrigger>
      </PopoverTrigger>
      <QuickEditContent
        title={t("admin.editProductTitle")}
        description={t("admin.quickEditTitleHint")}
        onOpenAutoFocus={focusInput}
        footer={<QuickEditActions onCancel={() => setOpen(false)} onSave={() => void handleSave()} saving={saving} />}
      >
        <Input
          ref={inputRef}
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          aria-label={t("admin.productName")}
          disabled={saving}
          onKeyDown={(event) => {
            if (event.key === "Enter") {
              event.preventDefault()
              void handleSave()
            }
            if (event.key === "Escape") {
              event.preventDefault()
              setOpen(false)
            }
          }}
        />
      </QuickEditContent>
    </Popover>
  )
}
