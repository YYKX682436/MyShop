"use client"

import Image from "next/image"
import { useState } from "react"

// Keep supplier URLs, GIFs, avatars, and noncanonical paths on their original
// URLs. The server optimizer must never fetch arbitrary external resources.
const managedImage = /^\/api\/uploads\/(?:[0-9a-f]{64}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\.(?:jpe?g|png|webp|bmp)$/i

export function ProductImage({ src, alt, sizes, className, priority = false }: {
  src: string
  alt: string
  sizes: string
  className?: string
  priority?: boolean
}) {
  const [failedSrc, setFailedSrc] = useState<string | null>(null)
  const unoptimized = !managedImage.test(src) || failedSrc === src
  const imageSrc = unoptimized ? src : src.replace("/api/uploads/", "/image-source/")
  return <Image src={imageSrc} alt={alt} fill sizes={sizes} className={className}
    unoptimized={unoptimized} loading={priority ? "eager" : "lazy"}
    fetchPriority={priority ? "high" : undefined}
    onError={() => { if (!unoptimized) setFailedSrc(src) }} />
}
