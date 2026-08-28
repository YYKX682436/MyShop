import ReactMarkdown from "react-markdown"
import { cn } from "@/lib/utils"

interface MarkdownPreviewProps {
  value: string
  emptyMessage?: string
  className?: string
}

export function MarkdownPreview({ value, emptyMessage = "暂无内容，Markdown 预览会显示在这里。", className }: MarkdownPreviewProps) {
  if (!value.trim()) {
    return (
      <div className={cn("flex min-h-32 items-center justify-center rounded-lg border border-dashed border-border bg-muted/20 px-4 text-center text-sm text-muted-foreground", className)}>
        {emptyMessage}
      </div>
    )
  }

  return (
    <div
      className={cn(
        "min-w-0 max-w-full overflow-hidden break-words prose prose-sm text-muted-foreground dark:prose-invert",
        "[&_img]:my-3 [&_img]:block [&_img]:h-auto [&_img]:max-w-full [&_img]:rounded-lg",
        className,
      )}
    >
      <ReactMarkdown>{value}</ReactMarkdown>
    </div>
  )
}
