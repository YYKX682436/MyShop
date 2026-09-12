"use client"

import Link from "next/link"
import Image from "next/image"
import { Package } from "lucide-react"
import type { ProductCard as ProductCardType } from "@/types"
import { useLocale } from "@/lib/context"
import { formatYuan } from "@/lib/utils"

interface ProductCardProps {
  product: ProductCardType
}

export function ProductCard({ product }: ProductCardProps) {
  const { t } = useLocale()
  const inStock = product.unlimited || (product.stock_available ?? 0) > 0
  const productUrl = `/product/${product.id}`

  return (
    <Link
      href={productUrl}
      className="group/card flex min-w-0 flex-col overflow-hidden rounded-card border border-border-subtle bg-surface shadow-sm transition-[transform,border-color,box-shadow] duration-200 hover:-translate-y-0.5 hover:border-border-strong hover:shadow-md focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background"
      aria-label={product.title}
    >
      <div className={`relative aspect-square overflow-hidden bg-surface-muted ${!inStock ? "opacity-75" : ""}`}>
        {product.cover_url ? (
          <Image
            src={product.cover_url}
            alt=""
            fill
            unoptimized
            sizes="(min-width: 1536px) 16vw, (min-width: 1280px) 20vw, (min-width: 1024px) 25vw, (min-width: 640px) 33vw, 50vw"
            className="object-contain transition-opacity duration-300 ease-out group-hover/card:opacity-95"
          />
        ) : (
          <div className="flex h-full w-full items-center justify-center">
            <Package className="size-8 text-muted-foreground/25" aria-hidden="true" />
          </div>
        )}
        {!inStock && (
          <span className="pointer-events-none absolute right-2 top-2 rounded-full border border-border-subtle bg-surface/90 px-2 py-1 text-[10px] font-medium text-muted-foreground shadow-sm">
            {t("product.soldOut")}
          </span>
        )}
      </div>

      <div className="flex flex-1 flex-col gap-1.5 p-2.5 sm:p-3">
        <h3 className="line-clamp-2 min-h-[2.5rem] text-sm font-semibold leading-5 tracking-tight text-foreground transition-colors group-hover/card:text-primary">
            {product.title}
        </h3>

        <div className="flex min-w-0 items-baseline gap-1">
          <span className={`min-w-0 truncate text-lg font-semibold tracking-tight sm:text-xl ${inStock ? "text-foreground" : "text-muted-foreground"}`}>
            {formatYuan(product.price)}
          </span>
          {product.has_specs && <span className="shrink-0 text-xs text-muted-foreground">{t("home.startFrom")}</span>}
        </div>
      </div>
    </Link>
  )
}
