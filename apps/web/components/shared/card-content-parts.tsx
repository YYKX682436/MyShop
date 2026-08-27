"use client"

import { useState } from "react"
import { Copy } from "lucide-react"

interface CardContentPartsProps {
  parts?: string[] | null
  fallback?: string | null
  onCopy: (value: string) => void
  className?: string
}

/** Renders one CardKey's ordered parts without assigning card-number semantics. */
export function CardContentParts({ parts, fallback, onCopy, className = "" }: CardContentPartsProps) {
  const resolvedParts = parts && parts.length > 0
    ? parts
    : fallback == null
      ? []
      : [fallback]
  const [expanded, setExpanded] = useState(false)
  const visibleParts = expanded ? resolvedParts : resolvedParts.slice(0, 2)
  const remainingCount = Math.max(0, resolvedParts.length - 2)

  if (resolvedParts.length === 0) return null

  return (
    <div className={`flex min-w-0 flex-col gap-1.5 ${className}`}>
      {visibleParts.map((part, index) => (
        <div key={`${index}-${part}`} className="flex min-w-0 items-center gap-2 rounded-md bg-background/70 px-2.5 py-1.5">
          <code className="min-w-0 flex-1 break-all text-xs text-foreground">{part}</code>
          <button
            type="button"
            onClick={() => onCopy(part)}
            aria-label="复制内容"
            className="shrink-0 rounded p-1 text-muted-foreground hover:bg-accent hover:text-foreground"
          >
            <Copy className="h-3.5 w-3.5" />
          </button>
        </div>
      ))}
      <div className="flex flex-wrap items-center justify-end gap-2">
        {remainingCount > 0 && (
          <button
            type="button"
            onClick={() => setExpanded(value => !value)}
            className="text-xs text-primary hover:underline"
          >
            {expanded ? "收起" : `更多（${remainingCount}）`}
          </button>
        )}
        <button
          type="button"
          onClick={() => onCopy(resolvedParts.join("\n"))}
          className="inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"
        >
          <Copy className="h-3.5 w-3.5" />复制全部
        </button>
      </div>
    </div>
  )
}
