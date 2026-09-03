"use client"

import { useEffect, useRef, useState } from "react"

import { StatusBadge } from "@/components/admin/shared"
import { Popover, PopoverTrigger } from "@/components/ui/popover"
import { useLocale } from "@/lib/context"
import type { AdminProductDetail, Category } from "@/types"
import { QuickEditContent, QuickEditTrigger } from "./quick-edit"

export function ProductCategoryQuickEdit({
  product,
  categories,
  categoryName,
  saving,
  onSave,
}: {
  product: AdminProductDetail
  categories: Category[]
  categoryName: string
  saving: boolean
  onSave: (categoryId: string) => Promise<boolean>
}) {
  const { t } = useLocale()
  const [open, setOpen] = useState(false)
  const [selected, setSelected] = useState(product.category_id)
  const selectRef = useRef<HTMLSelectElement>(null)
  const saveInFlightRef = useRef(false)

  useEffect(() => {
    if (open) {
      setSelected(product.category_id)
      window.requestAnimationFrame(() => selectRef.current?.focus())
    }
  }, [open, product.category_id])

  const focusSelect = (event: Event) => {
    event.preventDefault()
    window.requestAnimationFrame(() => selectRef.current?.focus())
  }

  const handleChange = async (categoryId: string) => {
    if (saving || saveInFlightRef.current) return
    const category = categories.find((candidate) => candidate.id === categoryId)
    if (!category) {
      setSelected(product.category_id)
      return
    }
    setSelected(category.id)
    if (category.id === product.category_id) {
      setOpen(false)
      return
    }
    saveInFlightRef.current = true
    try {
      const success = await onSave(category.id)
      if (success) setOpen(false)
      else setSelected(product.category_id)
    } finally {
      saveInFlightRef.current = false
    }
  }

  return (
    <Popover open={open} onOpenChange={(nextOpen) => !saving && setOpen(nextOpen)}>
      <PopoverTrigger asChild>
        <QuickEditTrigger
          className="min-w-0"
          disabled={saving}
          aria-label={`${t("admin.editProductCategory")}: ${categoryName}`}
          title={categoryName}
        >
          <StatusBadge label={<span className="block max-w-[7rem] truncate">{categoryName}</span>} />
        </QuickEditTrigger>
      </PopoverTrigger>
      <QuickEditContent title={t("admin.editProductCategory")} onOpenAutoFocus={focusSelect}>
        {categories.length > 0 ? (
          <select
            ref={selectRef}
            className="h-10 w-full rounded-control border border-input bg-surface px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring/35 disabled:cursor-not-allowed disabled:opacity-50"
            value={selected}
            onChange={(event) => void handleChange(event.target.value)}
            disabled={saving}
            aria-label={t("admin.category")}
          >
            {categories.map((category) => <option key={category.id} value={category.id}>{category.name}</option>)}
          </select>
        ) : (
          <p className="text-sm text-muted-foreground">{t("admin.noCategoryData")}</p>
        )}
      </QuickEditContent>
    </Popover>
  )
}
