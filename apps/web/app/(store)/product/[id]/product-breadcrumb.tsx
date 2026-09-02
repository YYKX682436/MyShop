"use client"

import Link from "next/link"
import { ArrowLeft } from "lucide-react"
import { useLocale } from "@/lib/context"

export function ProductBreadcrumb({ title }: { title: string }) {
  const { t } = useLocale()

  return (
    <div className="flex items-center gap-2 text-sm text-muted-foreground">
      <Link
        href="/"
        className="inline-flex items-center gap-1 rounded-control px-2 py-1 transition-colors hover:bg-surface-muted hover:text-foreground"
      >
        <ArrowLeft className="h-3.5 w-3.5" />
        {t("common.back")}
      </Link>
      <span>/</span>
      <span className="truncate max-w-[200px] sm:max-w-none">{title}</span>
    </div>
  )
}
