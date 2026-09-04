"use client"

import { useEffect, useRef, useState } from "react"
import { toast } from "sonner"

import { Input } from "@/components/ui/input"
import { Popover, PopoverTrigger } from "@/components/ui/popover"
import { useLocale } from "@/lib/context"
import { parseIntegerDraft } from "@/lib/product-quick-edit"
import type { AdminProductDetail } from "@/types"
import { QuickEditActions, QuickEditContent, QuickEditTrigger } from "@/components/admin/shared/quick-edit"

export function ProductWeightQuickEdit({
  product,
  saving,
  onSave,
}: {
  product: AdminProductDetail
  saving: boolean
  onSave: (weight: number) => Promise<boolean>
}) {
  const { t } = useLocale()
  const [open, setOpen] = useState(false)
  const [draft, setDraft] = useState(String(product.sort_weight ?? 0))
  const inputRef = useRef<HTMLInputElement>(null)
  const saveInFlightRef = useRef(false)

  useEffect(() => {
    if (open) {
      setDraft(String(product.sort_weight ?? 0))
      window.requestAnimationFrame(() => inputRef.current?.focus())
    }
  }, [open, product.sort_weight])

  const focusInput = (event: Event) => {
    event.preventDefault()
    window.requestAnimationFrame(() => inputRef.current?.focus())
  }

  const handleSave = async () => {
    if (saving || saveInFlightRef.current) return
    const nextWeight = parseIntegerDraft(draft)
    if (nextWeight === null) {
      toast.error(t("admin.sortWeightInvalid"))
      return
    }
    if (nextWeight === product.sort_weight) {
      setOpen(false)
      return
    }
    saveInFlightRef.current = true
    try {
      const success = await onSave(nextWeight)
      if (success) setOpen(false)
      else setDraft(String(product.sort_weight ?? 0))
    } finally {
      saveInFlightRef.current = false
    }
  }

  return (
    <Popover open={open} onOpenChange={(nextOpen) => !saving && setOpen(nextOpen)}>
      <PopoverTrigger asChild>
        <QuickEditTrigger
          disabled={saving}
          aria-label={`${t("admin.editSortWeight")}: ${product.title}`}
          title={t("admin.editSortWeight")}
        >
          <span className="font-medium">{product.sort_weight ?? 0}</span>
        </QuickEditTrigger>
      </PopoverTrigger>
      <QuickEditContent
        title={t("admin.editSortWeight")}
        description={t("admin.quickEditWeightHint")}
        onOpenAutoFocus={focusInput}
        footer={<QuickEditActions onCancel={() => setOpen(false)} onSave={() => void handleSave()} saving={saving} />}
      >
        <Input
          ref={inputRef}
          type="text"
          inputMode="numeric"
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          aria-label={t("admin.sortWeightLabel")}
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
          disabled={saving}
        />
      </QuickEditContent>
    </Popover>
  )
}
