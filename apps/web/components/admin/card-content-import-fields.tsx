"use client"

import { Plus, Trash2 } from "lucide-react"
import { recognizedCardContentCount, validateCardContentColumns } from "@/lib/card-content"
import { useLocale } from "@/lib/context"

interface CardContentImportFieldsProps {
  columns: string[]
  onChange: (columns: string[]) => void
  disabled?: boolean
}

export function CardContentImportFields({ columns, onChange, disabled = false }: CardContentImportFieldsProps) {
  const { t } = useLocale()
  const validationError = validateCardContentColumns(columns)
  const update = (index: number, value: string) => {
    onChange(columns.map((column, current) => current === index ? value : column))
  }

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <p className="text-sm font-medium text-foreground">{t("admin.cardContent")}</p>
          <p className="text-xs text-muted-foreground">{t("admin.cardContentHint")}</p>
        </div>
        <button
          type="button"
          disabled={disabled}
          onClick={() => onChange([...columns, ""])}
          className="inline-flex items-center gap-1 rounded-control border border-input bg-surface px-2.5 py-1.5 text-xs font-medium text-foreground hover:bg-surface-muted disabled:opacity-50"
        >
          <Plus className="h-3.5 w-3.5" />{t("admin.addColumn")}
        </button>
      </div>
      <div className="grid gap-3 md:grid-cols-2">
        {columns.map((column, index) => (
          <div key={index} className="content-surface-muted flex min-w-0 flex-col gap-1.5 rounded-card p-3">
            <div className="flex items-center justify-between gap-2">
              <label htmlFor={`card-content-column-${index}`} className="text-sm font-medium text-foreground">
                {t("admin.cardContentColumn").replace("{index}", String(index + 1))} <span className="text-destructive">*</span>
              </label>
              <button
                type="button"
                disabled={disabled || columns.length <= 1}
                onClick={() => onChange(columns.filter((_, current) => current !== index))}
                aria-label={t("admin.removeColumn").replace("{index}", String(index + 1))}
                className="rounded p-1 text-muted-foreground hover:bg-destructive/10 hover:text-destructive disabled:opacity-30"
              >
                <Trash2 className="h-3.5 w-3.5" />
              </button>
            </div>
            <textarea
              id={`card-content-column-${index}`}
              value={column}
              disabled={disabled}
              onChange={(event) => update(index, event.target.value)}
              className="min-h-32 w-full resize-y rounded-control border border-input bg-surface px-3 py-2 font-mono text-xs text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring disabled:opacity-60"
              placeholder={t("admin.cardContentColumnPlaceholder").replace("{index}", String(index + 1))}
            />
            <p className="text-right text-xs text-muted-foreground">{t("admin.recognizedCount").replace("{count}", String(recognizedCardContentCount(column)))}</p>
          </div>
        ))}
      </div>
      {validationError && (
        <p role="alert" className="rounded-control border border-destructive/20 bg-destructive/10 px-3 py-2 text-xs text-destructive">
          {validationError}
        </p>
      )}
    </div>
  )
}
