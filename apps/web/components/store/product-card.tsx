"use client"

import Link from "next/link"
import { Package } from "lucide-react"
import { ProductImage } from "@/components/store/product-image"
import type { ProductCard as ProductCardType } from "@/types"
import { useLocale } from "@/lib/context"
import { formatYuan } from "@/lib/utils"

interface ProductCardProps {
  product: ProductCardType
  priority?: boolean
}

export function ProductCard({ product, priority = false }: ProductCardProps) {
  const { t } = useLocale()
  const inStock = product.unlimited || (product.stock_available ?? 0) > 0
  const productUrl = `/product/${product.id}`

  return (
    <Link
      href={productUrl}
      className="product-card group/card focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background"
      aria-label={product.title}
    >
      <div className={`product-image-stage product-image-stage--card ${!inStock ? "opacity-75" : ""}`}>
        {product.cover_url ? (
          <ProductImage
            src={product.cover_url}
            variant="thumb"
            alt=""
            priority={priority}
            sizes="(min-width: 1536px) 220px, (min-width: 1280px) calc(20vw - 26px), (min-width: 1024px) calc(25vw - 28px), (min-width: 640px) calc(33.333vw - 24px), calc(50vw - 22px)"
            className="product-image-stage-image"
          />
        ) : (
          <div className="flex h-full w-full items-center justify-center">
            <Package className="size-8 text-muted-foreground/25" aria-hidden="true" />
          </div>
        )}
        {!inStock && (
          <span className="pointer-events-none absolute right-2 top-2 rounded-full border border-border-subtle bg-surface/95 px-2 py-1 text-[10px] font-medium text-muted-foreground shadow-xs">
            {t("product.soldOut")}
          </span>
        )}
      </div>

      <div className="product-card-copy">
        <h3 className="product-card-title line-clamp-2 transition-colors group-hover/card:text-primary">
          {product.title}
        </h3>

        <div className="flex min-w-0 items-baseline gap-1">
          <span className={`product-card-price min-w-0 break-all ${inStock ? "text-foreground" : "text-muted-foreground"}`}>
            {formatYuan(product.price)}
          </span>
          {product.has_specs && <span className="shrink-0 text-xs text-muted-foreground">{t("home.startFrom")}</span>}
        </div>
      </div>
    </Link>
  )
}
