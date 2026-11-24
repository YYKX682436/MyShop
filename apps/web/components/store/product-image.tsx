"use client"

import Image from "next/image"
import { useState } from "react"
import { ImageOff } from "lucide-react"
import { managedImageVariant } from "@/lib/managed-product-image"

// Keep supplier URLs, GIFs, avatars, and noncanonical paths on their original
// URLs. The server optimizer must never fetch arbitrary external resources.
const managedImage = /^\/api\/uploads\/(?:[0-9a-f]{64}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\.(?:jpe?g|png|webp|bmp)$/i

export function ProductImage({ src, alt, sizes, className, priority = false, variant = "display" }: {
  src: string
  alt: string
  sizes: string
  className?: string
  priority?: boolean
  variant?: "display" | "thumb"
}) {
  const [failedSrc, setFailedSrc] = useState<string | null>(null)
  const [missingSrc, setMissingSrc] = useState<string | null>(null)
  const selectedSrc = managedImageVariant(src, variant) || src
  const unoptimized = !managedImage.test(selectedSrc) || failedSrc === src
  const imageSrc = unoptimized ? selectedSrc : selectedSrc.replace("/api/uploads/", "/image-source/")
  if (missingSrc === src) return <span className="absolute inset-0 flex items-center justify-center text-muted-foreground" role={alt ? "img" : undefined} aria-label={alt || undefined}><ImageOff aria-hidden="true" /></span>
  return <Image src={imageSrc} alt={alt} fill sizes={sizes} className={className}
    unoptimized={unoptimized} loading={priority ? "eager" : "lazy"}
    fetchPriority={priority ? "high" : undefined}
    onError={() => { if (!unoptimized) setFailedSrc(src); else setMissingSrc(src) }} />
}
