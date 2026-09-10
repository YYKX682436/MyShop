import ReactMarkdown from "react-markdown"
import { sanitizeMarkdownUrl } from "@/lib/markdown-security"
import { cn } from "@/lib/utils"

interface MarkdownContentProps {
  value: string
  className?: string
}

export function MarkdownContent({ value, className }: MarkdownContentProps) {
  if (!value.trim()) return null

  return (
    <div
      className={cn(
        "min-w-0 max-w-full overflow-hidden break-words prose prose-sm text-muted-foreground dark:prose-invert",
        "[&_img]:my-3 [&_img]:block [&_img]:h-auto [&_img]:max-w-full [&_img]:rounded-card",
        className,
      )}
    >
      <ReactMarkdown
        skipHtml
        urlTransform={(url, key) => sanitizeMarkdownUrl(url, key === "src" ? "image" : "link") || ""}
        components={{
          a: ({ href, children, ...props }) => {
            const safeHref = sanitizeMarkdownUrl(href, "link")
            if (!safeHref) return <span>{children}</span>
            return <a {...props} href={safeHref} target="_blank" rel="noopener noreferrer">{children}</a>
          },
          img: ({ src, alt, ...props }) => {
            const safeSrc = typeof src === "string" ? sanitizeMarkdownUrl(src, "image") : null
            if (!safeSrc) return null
            // Markdown may contain approved external image hosts, so next/image
            // would require a deployment-specific remotePatterns allowlist.
            // The URL is already constrained by sanitizeMarkdownUrl.
            // eslint-disable-next-line @next/next/no-img-element
            return <img {...props} src={safeSrc} alt={alt || ""} loading="lazy" referrerPolicy="no-referrer" />
          },
        }}
      >
        {value}
      </ReactMarkdown>
    </div>
  )
}
