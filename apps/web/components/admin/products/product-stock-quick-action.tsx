"use client"

import { useState, type ReactNode } from "react"

import { CardKeyImportDialog } from "@/components/admin/products/card-key-import-dialog"
import { useLocale } from "@/lib/context"
import { getProductStockQuickAction } from "@/lib/product-quick-edit"
import { cn } from "@/lib/utils"
import type { AdminProductDetail } from "@/types"

export function ProductStockQuickAction({
  product,
  children,
  className,
  onImported,
}: {
  product: AdminProductDetail
  children: ReactNode
  className?: string
  onImported: () => void | Promise<void>
}) {
  const { t } = useLocale()
  const [open, setOpen] = useState(false)
  const action = getProductStockQuickAction(product)

  if (action !== "import-card-keys") {
    return <span className={cn(className)}>{children}</span>
  }

  return (
    <>
      <button
        type="button"
        className={cn("rounded-control px-2 py-1 text-left transition-colors hover:bg-surface-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/35 focus-visible:ring-offset-1", className)}
        aria-label={`${t("admin.importKeys")}: ${product.title}`}
        title={t("admin.importKeys")}
        onClick={() => setOpen(true)}
      >
        {children}
      </button>
      <CardKeyImportDialog
        product={product}
        open={open}
        onOpenChange={setOpen}
        onImported={onImported}
      />
    </>
  )
}
