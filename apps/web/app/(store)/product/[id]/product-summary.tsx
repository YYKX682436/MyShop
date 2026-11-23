"use client"

import { useLocale } from "@/lib/context"
import { cn, formatYuan } from "@/lib/utils"
import type { ProductDetail } from "@/types"

export function ProductSummary({ product }: { product: ProductDetail }) {
  const { t } = useLocale()
  const inStock = product.unlimited
    || product.specs?.some((spec) => spec.unlimited || (spec.stock_available ?? 0) > 0)
    || (product.stock_available ?? 0) > 0
  const sales = (product.sales_count ?? 0) + (product.initial_sales ?? 0)
  const hasUnlimitedSpec = product.specs?.some((spec) => spec.unlimited) ?? false
  const finiteSpecStock = product.specs?.reduce(
    (total, spec) => total + (spec.unlimited ? 0 : Math.max(0, spec.stock_available ?? 0)),
    0,
  ) ?? 0
  const stockLabel = product.unlimited || hasUnlimitedSpec
    ? t("product.unlimitedStock")
    : `${t("product.stock")} ${product.specs?.length ? finiteSpecStock : Math.max(0, product.stock_available ?? 0)}`
  const deliveryLabel = product.delivery_type === "MANUAL"
    ? t("product.deliveryManual")
    : t("product.deliveryAuto")

  return (
    <div className="product-summary">
      <div className="product-summary-main">
        <h1 className="max-w-3xl text-xl font-semibold leading-snug tracking-tight text-foreground lg:text-5xl lg:leading-[1.08]">
          {product.title}
        </h1>
        <div className="flex min-w-0 flex-wrap items-baseline gap-x-1.5">
          <span className="min-w-0 break-all text-2xl font-semibold tracking-tight text-foreground lg:text-4xl">
            {formatYuan(product.price)}
          </span>
          {product.has_specs && <span className="shrink-0 text-sm text-muted-foreground">{t("home.startFrom")}</span>}
        </div>
        <p className="product-summary-delivery text-footnote text-muted-foreground lg:hidden">{deliveryLabel}</p>
      </div>
      <p className={cn(
        "product-summary-meta text-footnote text-muted-foreground",
        !inStock && "font-medium text-destructive/80",
      )}>
        {inStock
          ? `${stockLabel} · ${t("product.sold")} ${sales}`
          : `${t("product.soldOut")} · ${t("product.sold")} ${sales}`}
        <span className="hidden lg:inline"> · {deliveryLabel}</span>
      </p>
    </div>
  )
}
