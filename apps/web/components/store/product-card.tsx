"use client"

import Link from "next/link"
import Image from "next/image"
import { AlertTriangle, Package, Zap } from "lucide-react"
import type { ProductCard as ProductCardType } from "@/types"
import { useLocale } from "@/lib/context"
import { cn, formatYuan } from "@/lib/utils"

interface ProductCardProps {
  product: ProductCardType
}

export function ProductCard({ product }: ProductCardProps) {
  const { t } = useLocale()
  const inStock = product.unlimited || (product.stock_available ?? 0) > 0
  const lowStock = !product.unlimited && (product.stock_available ?? 0) > 0 && (product.stock_available ?? 0) <= 10
  const sales = (product.sales_count ?? 0) + (product.initial_sales ?? 0)
  const productUrl = `/product/${product.id}`

  return (
    <Link
      href={productUrl}
      className="group/card flex min-w-0 flex-col overflow-hidden rounded-card border border-border-subtle bg-surface shadow-sm transition-[transform,border-color,box-shadow] duration-200 hover:-translate-y-0.5 hover:border-border-strong hover:shadow-md focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background"
      aria-label={product.title}
    >
      <div className="relative aspect-[4/3] overflow-hidden bg-surface-muted">
        {product.cover_url ? (
          <Image
            src={product.cover_url}
            alt=""
            fill
            unoptimized
            sizes="(min-width: 1280px) 25vw, (min-width: 640px) 50vw, 100vw"
            className="object-cover transition-transform duration-300 ease-out group-hover/card:scale-[1.02]"
          />
        ) : (
          <div className="flex h-full w-full items-center justify-center">
            <Package className="size-9 text-muted-foreground/25" aria-hidden="true" />
          </div>
        )}
        <div className="pointer-events-none absolute inset-x-0 top-0 flex items-center justify-between p-3">
          {product.delivery_type !== "MANUAL" ? (
            <span className="inline-flex items-center gap-1 rounded-full border border-border-subtle bg-surface px-2 py-1 text-[11px] font-semibold text-foreground shadow-sm">
              <Zap className="size-3 text-primary" aria-hidden="true" />
              {t("home.instantDelivery")}
            </span>
          ) : <span />}
          {!inStock && (
            <span className="rounded-full border border-destructive/20 bg-surface px-2 py-1 text-[11px] font-semibold text-destructive shadow-sm">
              {t("home.stock")} 0
            </span>
          )}
        </div>
      </div>

      <div className="flex flex-1 flex-col gap-3 p-4">
        <div className="min-h-[3.25rem]">
          <h3 className="line-clamp-2 text-[15px] font-semibold leading-6 tracking-tight text-foreground transition-colors group-hover/card:text-primary">
            {product.title}
          </h3>
        </div>

        <div className="flex min-h-5 items-center justify-between gap-3 text-xs text-muted-foreground">
          <span className={cn("inline-flex items-center gap-1", lowStock && "text-amber-700 dark:text-amber-300")}>
            {lowStock && <AlertTriangle className="size-3" aria-hidden="true" />}
            {lowStock ? `${t("home.stock")} ${product.stock_available}` : inStock ? t("home.inStock") : t("home.outOfStock")}
          </span>
          {sales > 0 && <span className="shrink-0">{sales}+ {t("home.sales")}</span>}
        </div>

        <div className="mt-auto flex items-end justify-between gap-3 border-t border-border-subtle pt-3">
          <div className="flex min-w-0 items-baseline gap-1">
            <span className="truncate text-xl font-semibold tracking-tight text-foreground">{formatYuan(product.price)}</span>
            {product.has_specs && <span className="shrink-0 text-xs text-muted-foreground">{t("home.startFrom")}</span>}
          </div>
          <span className="inline-flex shrink-0 items-center rounded-control bg-primary px-3 py-2 text-xs font-semibold text-primary-foreground shadow-sm transition-[background-color,transform] duration-180 group-hover/card:bg-primary/90 group-active/card:scale-[.98]">
            {t("product.buyNow")}
          </span>
        </div>
      </div>
    </Link>
  )
}
