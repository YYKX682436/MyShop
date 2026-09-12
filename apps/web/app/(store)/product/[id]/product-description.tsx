"use client"

import { useLocale } from "@/lib/context"
import { MarkdownContent } from "@/components/shared/markdown-content"
import type { ProductDetail } from "@/types"

export function ProductDescription({ product }: { product: ProductDetail }) {
  const { t } = useLocale()

  return (
    <section className="surface-raised p-5 sm:p-7" aria-labelledby="product-description-title">
      <div className="mb-6 flex items-center gap-2 border-b border-border-subtle pb-4">
        <svg className="h-5 w-5 text-primary" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
        </svg>
        <h2 id="product-description-title" className="text-lg font-semibold tracking-tight text-foreground">
          {t("product.description")}
        </h2>
      </div>

      {product.detail_md ? (
        <MarkdownContent value={product.detail_md} />
      ) : (
        <p className="text-sm text-muted-foreground">{product.description}</p>
      )}
    </section>
  )
}
