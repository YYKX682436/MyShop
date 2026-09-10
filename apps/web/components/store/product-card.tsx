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
      <div className="relative aspect-square overflow-hidden bg-surface-muted">
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
        <div className="pointer-events-none absolute inset-x-0 top-0 flex items-center justify-between gap-2 p-2">
          {product.delivery_type !== "MANUAL" ? (
            <span className="inline-flex min-w-0 items-center gap-1 rounded-full border border-border-subtle bg-surface px-1.5 py-0.5 text-[10px] font-semibold text-foreground shadow-sm">
              <Zap className="size-2.5 shrink-0 text-primary" aria-hidden="true" />
              {t("home.instantDelivery")}
            </span>
          ) : <span />}
          {!inStock && (
            <span className="shrink-0 rounded-full border border-destructive/20 bg-surface px-1.5 py-0.5 text-[10px] font-semibold text-destructive shadow-sm">
              {t("home.stock")} 0
            </span>
          )}
        </div>
      </div>

      <div className="flex flex-1 flex-col gap-2.5 p-3 sm:p-3.5">
        <div className="min-h-[2.75rem]">
          <h3 className="line-clamp-2 text-sm font-semibold leading-[1.375rem] tracking-tight text-foreground transition-colors group-hover/card:text-primary">
            {product.title}
          </h3>
        </div>

        <div className="flex min-h-5 min-w-0 items-center justify-between gap-2 text-[11px] text-muted-foreground">
          <span className={cn("inline-flex min-w-0 flex-1 items-center gap-1 truncate", lowStock && "text-amber-700 dark:text-amber-300")}>
            {lowStock && <AlertTriangle className="size-3" aria-hidden="true" />}
            <span className="truncate">
              {lowStock ? `${t("home.stock")} ${product.stock_available}` : inStock ? t("home.inStock") : t("home.outOfStock")}
            </span>
          </span>
          {sales > 0 && <span className="max-w-[45%] shrink-0 truncate text-right">{sales}+ {t("home.sales")}</span>}
        </div>

        <div className="mt-auto flex flex-col gap-2 border-t border-border-subtle pt-2.5 min-[400px]:flex-row min-[400px]:items-end min-[400px]:justify-between min-[400px]:gap-2">
          <div className="flex min-w-0 items-baseline gap-1">
            <span className="min-w-0 truncate text-lg font-semibold tracking-tight text-foreground sm:text-xl">{formatYuan(product.price)}</span>
            {product.has_specs && <span className="shrink-0 text-xs text-muted-foreground">{t("home.startFrom")}</span>}
          </div>
          <span className="inline-flex min-h-8 w-full shrink-0 items-center justify-center rounded-control bg-primary px-2.5 py-1.5 text-[11px] font-semibold text-primary-foreground shadow-sm transition-[background-color,transform] duration-180 group-hover/card:bg-primary/90 group-active/card:scale-[.98] min-[400px]:w-auto">
            {t("product.buyNow")}
          </span>
        </div>
      </div>
    </Link>
  )
}
