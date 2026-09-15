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
    <div className="space-y-5 lg:pt-3">
      <h1 className="max-w-3xl text-3xl font-semibold tracking-[-0.04em] text-foreground sm:text-5xl sm:leading-[1.08]">
        {product.title}
      </h1>
      <div className="flex items-baseline gap-1.5">
        <span className="text-3xl font-semibold tracking-tight text-foreground sm:text-4xl">
          {formatYuan(product.price)}
        </span>
        {product.has_specs && <span className="text-sm text-muted-foreground">{t("home.startFrom")}</span>}
      </div>
      <p className={cn(
        "text-footnote text-muted-foreground",
        !inStock && "font-medium text-destructive/80",
      )}>
        {inStock
          ? `${stockLabel} · ${t("product.sold")} ${sales} · ${deliveryLabel}`
          : `${t("product.soldOut")} · ${t("product.sold")} ${sales} · ${deliveryLabel}`}
      </p>
    </div>
  )
}
