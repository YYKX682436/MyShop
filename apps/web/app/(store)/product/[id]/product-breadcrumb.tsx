"use client"

import Link from "next/link"
import { ArrowLeft } from "lucide-react"
import { useLocale } from "@/lib/context"

export function ProductBreadcrumb({ title }: { title: string }) {
  const { t } = useLocale()

  return (
    <div className="product-breadcrumb hidden items-center gap-2 text-sm text-muted-foreground lg:flex">
      <Link
        href="/"
        className="inline-flex min-h-11 items-center gap-1 rounded-control px-2 transition-colors hover:bg-surface-muted hover:text-foreground"
      >
        <ArrowLeft className="h-3.5 w-3.5" />
        {t("common.back")}
      </Link>
      <span>/</span>
      <span className="truncate">{title}</span>
    </div>
  )
}
