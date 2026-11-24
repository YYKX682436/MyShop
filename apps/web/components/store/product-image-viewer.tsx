"use client"

import { ImageViewer } from "@/components/shared/image-viewer"
import { ProductImage } from "@/components/store/product-image"
import { managedImageVariant } from "@/lib/managed-product-image"

/** The preview stays optimized; the viewer mounts the original only when opened. */
export function ProductImageViewer({ src, previewSrc = src, title }: { src: string; previewSrc?: string; title: string }) {
  return (
    <ImageViewer src={managedImageVariant(previewSrc, "clear") || src} title={title} triggerClassName="product-image-stage product-image-stage--detail block w-full focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
      <ProductImage src={previewSrc} alt={title} priority sizes="(min-width: 1024px) 55vw, (min-width: 640px) 192px, (min-width: 426px) 145px, (min-width: 324px) 34vw, 110px" className="product-image-stage-image" />
    </ImageViewer>
  )
}
