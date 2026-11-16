"use client"

import { ChevronLeft, ChevronRight } from "lucide-react"
import { useLocale } from "@/lib/context"

interface PaginationProps {
  page: number
  pageSize: number
  total: number
  onChange: (page: number) => void
  disabled?: boolean
}

export function Pagination({ page, pageSize, total, onChange, disabled = false }: PaginationProps) {
  const { t } = useLocale()
  const totalPages = Math.ceil(total / pageSize)

  const pages: (number | "...")[] = []
  if (totalPages <= 7) {
    for (let i = 1; i <= totalPages; i++) pages.push(i)
  } else {
    pages.push(1)
    if (page > 3) pages.push("...")
    for (let i = Math.max(2, page - 1); i <= Math.min(totalPages - 1, page + 1); i++) {
      pages.push(i)
    }
    if (page < totalPages - 2) pages.push("...")
    pages.push(totalPages)
  }

  return (
    <div className="flex flex-wrap items-center justify-between gap-3 px-2 py-4">
      <p className="text-sm text-muted-foreground">
        {t("common.total")} {total} {t("common.records")}
      </p>
      {totalPages > 1 && <nav className="flex flex-wrap items-center gap-1" aria-label={t("admin.pagination")}>
        <button
          type="button"
          onClick={() => onChange(page - 1)}
          disabled={disabled || page <= 1}
          className="inline-flex h-8 w-8 items-center justify-center rounded-md border border-border text-sm transition-colors hover:bg-accent disabled:pointer-events-none disabled:opacity-50"
          aria-label={t("common.prev")}
        >
          <ChevronLeft className="h-4 w-4" />
        </button>
        {pages.map((p, idx) =>
          p === "..." ? (
            <span key={`dots-${idx}`} className="flex h-8 w-8 items-center justify-center text-sm text-muted-foreground">
              ...
            </span>
          ) : (
            <button
              type="button"
              key={p}
              onClick={() => onChange(p)}
              disabled={disabled}
              aria-current={p === page ? "page" : undefined}
              className={`inline-flex h-8 w-8 items-center justify-center rounded-md text-sm font-medium transition-colors ${
                p === page
                  ? "bg-primary text-primary-foreground"
                  : "border border-border hover:bg-accent"
              }`}
            >
              {p}
            </button>
          )
        )}
        <button
          type="button"
          onClick={() => onChange(page + 1)}
          disabled={disabled || page >= totalPages}
          className="inline-flex h-8 w-8 items-center justify-center rounded-md border border-border text-sm transition-colors hover:bg-accent disabled:pointer-events-none disabled:opacity-50"
          aria-label={t("common.next")}
        >
          <ChevronRight className="h-4 w-4" />
        </button>
      </nav>}
    </div>
  )
}
