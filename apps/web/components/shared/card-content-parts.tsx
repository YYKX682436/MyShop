"use client"

import { Copy } from "lucide-react"
import { useLocale } from "@/lib/context"
import { formatCardContent } from "@/lib/card-display"

interface CardContentPartsProps {
  parts: string[]
  onCopy: (value: string) => void
  className?: string
  showPartCopy?: boolean
  showCopyAll?: boolean
}

/** Renders one CardKey's ordered parts without assigning card-number semantics. */
export function CardContentParts({ parts, onCopy, className = "", showPartCopy = true, showCopyAll = true }: CardContentPartsProps) {
  const { t } = useLocale()
  const resolvedParts = parts

  if (resolvedParts.length === 0) return null

  return (
    <div className={`flex min-w-0 flex-col gap-1.5 ${className}`}>
      {resolvedParts.map((part, index) => (
        <div key={`${index}-${part}`} className="flex min-w-0 items-start gap-2 rounded-md bg-background/70 px-2.5 py-1.5">
          <code className="min-w-0 flex-1 break-all whitespace-pre-wrap text-xs leading-5 text-foreground [overflow-wrap:anywhere]">{part}</code>
          {showPartCopy && (
            <button
              type="button"
              onClick={() => onCopy(part)}
              aria-label={t("admin.copyContent")}
              className="min-h-8 min-w-8 shrink-0 rounded p-1 text-muted-foreground hover:bg-accent hover:text-foreground"
            >
              <Copy className="h-3.5 w-3.5" aria-hidden="true" />
            </button>
          )}
        </div>
      ))}
      {showCopyAll && (
        <div className="flex items-center justify-end">
          <button
            type="button"
            onClick={() => onCopy(formatCardContent(resolvedParts))}
            className="inline-flex min-h-8 items-center gap-1 rounded-control px-2 text-xs text-muted-foreground hover:bg-accent hover:text-foreground"
          >
            <Copy className="h-3.5 w-3.5" aria-hidden="true" />{t("admin.copyAllContent")}
          </button>
        </div>
      )}
    </div>
  )
}
