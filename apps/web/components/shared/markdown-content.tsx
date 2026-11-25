"use client"

import Image from "next/image"
import { Children, isValidElement, useState } from "react"
import { ImageOff } from "lucide-react"
import ReactMarkdown from "react-markdown"
import remarkBreaks from "remark-breaks"
import remarkGfm from "remark-gfm"
import { ImageViewer } from "@/components/shared/image-viewer"
import { sanitizeMarkdownUrl } from "@/lib/markdown-security"
import { managedImageVariant, managedProductImagePath } from "@/lib/managed-product-image"
import { useSiteConfig } from "@/lib/context"
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
        "min-w-0 max-w-full break-words prose prose-sm text-muted-foreground dark:prose-invert",
        "[&_a]:[overflow-wrap:anywhere] [&_code]:[overflow-wrap:anywhere]",
        "[&_img]:my-3 [&_img]:block [&_img]:h-auto [&_img]:max-w-full [&_img]:rounded-card",
        className,
      )}
    >
      <ReactMarkdown
        skipHtml
        remarkPlugins={[remarkGfm, remarkBreaks]}
        urlTransform={(url, key) => sanitizeMarkdownUrl(url, key === "src" ? "image" : "link") || ""}
        components={{
          a: ({ href, children, node, ...props }) => {
            const safeHref = sanitizeMarkdownUrl(href, "link")
            if (!safeHref) return <span>{children}</span>
            // A Markdown link may wrap an image. Render the image as its own
            // viewer trigger, while preserving any adjacent linked text.
            if (node?.children.some(child => child.type === "element" && child.tagName === "img")) {
              return <>{Children.map(children, child => {
                if (isValidElement<{ node?: { tagName?: string } }>(child) && child.props.node?.tagName === "img") return child
                return <a {...props} href={safeHref} target="_blank" rel="noopener noreferrer">{child}</a>
              })}</>
            }
            return <a {...props} href={safeHref} target="_blank" rel="noopener noreferrer">{children}</a>
          },
          img: ({ src, alt, title }) => {
            const safeSrc = typeof src === "string" ? sanitizeMarkdownUrl(src, "image") : null
            if (!safeSrc) return null
            return <MarkdownImage src={safeSrc} alt={alt || ""} title={title} />
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
  const [failedOptimizedSrc, setFailedOptimizedSrc] = useState<string | null>(null)
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
  const managedPath = managedProductImagePath(src, origins)
  const displayPath = managedPath ? managedImageVariant(managedPath, "display") : null
  const clearPath = managedPath ? managedImageVariant(managedPath, "clear") : null
  if (failedSrc === src) return <span role="img" aria-label={alt || "Image unavailable"} className="inline-flex min-h-16 min-w-16 items-center justify-center text-muted-foreground"><ImageOff aria-hidden="true" /></span>
  const preview = managedPath && !displayPath && failedOptimizedSrc !== src ? (
    <Image src={managedPath.replace("/api/uploads/", "/image-source/")} alt={alt || ""} title={props.title}
      width={1200} height={1200} sizes="(min-width: 1024px) 900px, 100vw"
      className="h-auto max-w-full" style={{ width: "auto", height: "auto" }} loading="lazy"
      onError={() => setFailedOptimizedSrc(src)} />
  ) : (
    // External images are loaded directly; they are never fetched by our optimizer.
    // eslint-disable-next-line @next/next/no-img-element
    <img {...props} src={displayPath || src} alt={alt || ""} loading="lazy" referrerPolicy="no-referrer" onError={() => setFailedSrc(src)} />
  )
  return <ImageViewer src={clearPath || src} title={alt || "Image"} triggerClassName="block max-w-full rounded-card text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">{preview}</ImageViewer>
}
