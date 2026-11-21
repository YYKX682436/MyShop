"use client"

import { useState } from "react"
import { ImageOff } from "lucide-react"
import ReactMarkdown from "react-markdown"
import { sanitizeMarkdownUrl } from "@/lib/markdown-security"
import { useSiteConfig } from "@/lib/context"
import { liveMediaUrl } from "@/lib/live-media"
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
            return <MarkdownImage {...props} src={safeSrc} alt={alt || ""} />
          },
        }}
      >
        {value}
      </ReactMarkdown>
    </div>
  )
}
function MarkdownImage({ src, alt, ...props }: React.ComponentPropsWithoutRef<"img"> & { src: string }) {
  const [failedSrc, setFailedSrc] = useState<string | null>(null)
  const { config, isLoading } = useSiteConfig()
  const origins: string[] = []
  try { if (config?.main_site_url) origins.push(new URL(config.main_site_url).origin) } catch { /* Ignore invalid configuration. */ }
  if (typeof window !== "undefined") origins.push(window.location.origin)
  let waitForSite = false
  if (/^https?:\/\//i.test(src)) {
    try {
      const url = new URL(src)
      waitForSite = url.pathname.startsWith("/api/uploads/") && isLoading
    } catch { /* Keep the already sanitized external URL. */ }
  }
  if (waitForSite) return <span className="inline-block min-h-16 min-w-16" aria-hidden="true" />
  const liveSrc = liveMediaUrl(src, origins) || src
  if (failedSrc === src) return <span role="img" aria-label={alt || "Image unavailable"} className="inline-flex min-h-16 min-w-16 items-center justify-center text-muted-foreground"><ImageOff aria-hidden="true" /></span>
  // eslint-disable-next-line @next/next/no-img-element
  return <img {...props} src={liveSrc} alt={alt || ""} loading="lazy" referrerPolicy="no-referrer" onError={() => setFailedSrc(src)} />
}
