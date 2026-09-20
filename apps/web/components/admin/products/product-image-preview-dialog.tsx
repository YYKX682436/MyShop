"use client"

import { useState } from "react"
import Image from "next/image"

import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { useLocale } from "@/lib/context"
import type { AdminProductDetail } from "@/types"

export function ProductImagePreviewDialog({ product }: { product: AdminProductDetail }) {
  const { t } = useLocale()
  const [open, setOpen] = useState(false)

  return (
    <>
      <button
        type="button"
        className="relative h-10 w-10 shrink-0 overflow-hidden rounded-control border border-border-subtle bg-surface-muted transition-colors hover:bg-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/35 focus-visible:ring-offset-1"
        aria-label={`${t("admin.previewProductImage")}: ${product.title}`}
        title={t("admin.previewProductImage")}
        onClick={() => setOpen(true)}
      >
        {product.cover_url ? (
          <Image src={product.cover_url} alt={product.title} fill unoptimized sizes="40px" className="product-image-stage-image" />
        ) : (
          <span className="flex h-full w-full items-center justify-center text-xs text-muted-foreground">
            {t("admin.systemNoData")}
          </span>
        )}
      </button>

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="flex max-h-[90vh] max-w-4xl flex-col overflow-hidden">
          <DialogHeader>
            <DialogTitle>{t("admin.productImagePreview")}</DialogTitle>
            <DialogDescription>{product.title}</DialogDescription>
          </DialogHeader>
          <div className="relative flex h-[min(70vh,40rem)] min-h-0 w-full items-center justify-center overflow-hidden rounded-card border border-border-subtle bg-surface-muted/50">
            {product.cover_url ? (
              <Image src={product.cover_url} alt={product.title} fill unoptimized sizes="(max-width: 896px) 90vw, 56rem" className="object-contain" />
            ) : (
              <span className="text-sm text-muted-foreground">{t("admin.systemNoData")}</span>
            )}
          </div>
        </DialogContent>
      </Dialog>
    </>
  )
}
