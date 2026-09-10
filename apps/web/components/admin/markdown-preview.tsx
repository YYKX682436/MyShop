import { useLocale } from "@/lib/context"
import { cn } from "@/lib/utils"
import { MarkdownContent } from "@/components/shared/markdown-content"

interface MarkdownPreviewProps {
  value: string
  emptyMessage?: string
  className?: string
}

export function MarkdownPreview({ value, emptyMessage, className }: MarkdownPreviewProps) {
  const { t } = useLocale()
  const resolvedEmptyMessage = emptyMessage || t("admin.markdownEmpty")
  if (!value.trim()) {
    return (
      <div className={cn("flex min-h-32 items-center justify-center rounded-card border border-dashed border-border-subtle bg-surface-muted/45 px-4 text-center text-sm text-muted-foreground", className)}>
        {resolvedEmptyMessage}
      </div>
    )
  }

  return <MarkdownContent value={value} className={className} />
}
