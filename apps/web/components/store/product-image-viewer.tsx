"use client"

import * as DialogPrimitive from "@radix-ui/react-dialog"
import { X } from "lucide-react"
import { useRef } from "react"
import { Dialog, DialogOverlay, DialogPortal, DialogTrigger } from "@/components/ui/dialog"
import { ProductImage } from "@/components/store/product-image"
import { useLocale } from "@/lib/context"

/** The list and detail preview stay optimized; the viewer loads the original on demand. */
export function ProductImageViewer({ src, title }: { src: string; title: string }) {
  const { t } = useLocale()
  const triggerRef = useRef<HTMLButtonElement>(null)
  const scrollPosition = useRef({ x: 0, y: 0 })

  return (
    <Dialog onOpenChange={(open) => {
      if (open) scrollPosition.current = { x: window.scrollX, y: window.scrollY }
    }}>
      <DialogTrigger asChild>
        <button ref={triggerRef} type="button" className="product-image-stage product-image-stage--detail block w-full focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" aria-label={`${t("product.viewImage")}: ${title}`}>
          <ProductImage src={src} alt={title} priority sizes="(min-width: 1024px) 60vw, 100vw" className="product-image-stage-image" />
        </button>
      </DialogTrigger>
      <DialogPortal>
        <DialogOverlay className="z-[60] bg-black/95" />
        <DialogPrimitive.Content
          className="product-image-viewer fixed inset-0 z-[60] flex flex-col bg-black text-white outline-none"
          aria-describedby={undefined}
          onCloseAutoFocus={(event) => {
            event.preventDefault()
            triggerRef.current?.focus({ preventScroll: true })
            const { x, y } = scrollPosition.current
            requestAnimationFrame(() => {
              if (triggerRef.current?.isConnected) window.scrollTo({ left: x, top: y, behavior: "instant" })
            })
          }}
        >
          <DialogPrimitive.Title className="sr-only">{title}</DialogPrimitive.Title>
          <div className="product-image-viewer-inset flex shrink-0 justify-end pb-1 pt-[max(0.5rem,env(safe-area-inset-top))]">
            <DialogPrimitive.Close className="flex size-11 items-center justify-center rounded-full bg-white/15 text-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-white" aria-label={t("common.close")}>
              <X className="size-6" aria-hidden="true" />
            </DialogPrimitive.Close>
          </div>
          <div className="product-image-viewer-inset min-h-0 flex-1 overflow-y-auto overscroll-contain pb-[max(0.75rem,env(safe-area-inset-bottom))]" tabIndex={0}>
            <div className="flex min-h-full items-center justify-center">
              {/* The original URL is intentionally not sent through the thumbnail optimizer. */}
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={src} alt={title} className="block h-auto max-w-full" referrerPolicy="no-referrer" />
            </div>
          </div>
        </DialogPrimitive.Content>
      </DialogPortal>
    </Dialog>
  )
}
