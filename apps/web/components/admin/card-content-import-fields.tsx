"use client"

import { Plus, Trash2 } from "lucide-react"
import { recognizedCardContentCount, validateCardContentColumns } from "@/lib/card-content"

interface CardContentImportFieldsProps {
  columns: string[]
  onChange: (columns: string[]) => void
  disabled?: boolean
}

export function CardContentImportFields({ columns, onChange, disabled = false }: CardContentImportFieldsProps) {
  const validationError = validateCardContentColumns(columns)
  const update = (index: number, value: string) => {
    onChange(columns.map((column, current) => current === index ? value : column))
  }

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <p className="text-sm font-medium text-foreground">卡券内容</p>
          <p className="text-xs text-muted-foreground">每列一条一行，系统按行配对；内部空行会拒绝导入。</p>
        </div>
        <button
          type="button"
          disabled={disabled}
          onClick={() => onChange([...columns, ""])}
          className="inline-flex items-center gap-1 rounded-md border border-input px-2.5 py-1.5 text-xs font-medium text-foreground hover:bg-accent disabled:opacity-50"
        >
          <Plus className="h-3.5 w-3.5" />新增一列
        </button>
      </div>
      <div className="grid gap-3 md:grid-cols-2">
        {columns.map((column, index) => (
          <div key={index} className="flex min-w-0 flex-col gap-1.5 rounded-lg border border-border bg-muted/20 p-3">
            <div className="flex items-center justify-between gap-2">
              <label htmlFor={`card-content-column-${index}`} className="text-sm font-medium text-foreground">
                卡券内容 {index + 1} <span className="text-destructive">*</span>
              </label>
              <button
                type="button"
                disabled={disabled || columns.length <= 1}
                onClick={() => onChange(columns.filter((_, current) => current !== index))}
                aria-label={`删除卡券内容 ${index + 1}`}
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
              className="min-h-32 w-full resize-y rounded-md border border-input bg-background px-3 py-2 font-mono text-xs text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring disabled:opacity-60"
              placeholder={`第 ${index + 1} 列，每行一条卡券内容`}
            />
            <p className="text-right text-xs text-muted-foreground">已识别 {recognizedCardContentCount(column)} 条</p>
          </div>
        ))}
      </div>
      {validationError && (
        <p role="alert" className="rounded-md bg-destructive/10 px-3 py-2 text-xs text-destructive">
          {validationError}
        </p>
      )}
    </div>
  )
}
