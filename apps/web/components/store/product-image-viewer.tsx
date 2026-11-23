"use client"

import * as DialogPrimitive from "@radix-ui/react-dialog"
import { ZoomIn } from "lucide-react"
import { useCallback, useEffect, useRef, useState } from "react"
import { Dialog, DialogOverlay, DialogPortal, DialogTrigger } from "@/components/ui/dialog"
import { ProductImage } from "@/components/store/product-image"
import { useLocale } from "@/lib/context"

type CloseWatcher = EventTarget & { destroy: () => void }
type BackdropPointer = { target: EventTarget; x: number; y: number; scrollTop: number }
const viewerHistoryKey = "__myshopProductImageViewer"

/** The preview stays optimized; the viewer mounts the original only when opened. */
export function ProductImageViewer({ src, previewSrc = src, title }: { src: string; previewSrc?: string; title: string }) {
  const { t } = useLocale()
  const [open, setOpen] = useState(false)
  const [failed, setFailed] = useState(false)
  const [shape, setShape] = useState<"portrait" | "wide">("portrait")
  const triggerRef = useRef<HTMLButtonElement>(null)
  const scrollRef = useRef<HTMLDivElement>(null)
  const openRef = useRef(false)
  const restoreFrame = useRef<number | null>(null)
  const scrollPosition = useRef({ x: 0, y: 0 })
  const backdropPointer = useRef<BackdropPointer | null>(null)
  const historyToken = useRef<string | null>(null)
  const historyCleanupPending = useRef(false)
  const reopenAfterCleanup = useRef(false)

  const beginOpen = useCallback(() => {
    if (restoreFrame.current !== null) cancelAnimationFrame(restoreFrame.current)
    scrollPosition.current = { x: window.scrollX, y: window.scrollY }
    setFailed(false)
    setShape("portrait")
    const token = `${Date.now()}-${Math.random().toString(36).slice(2)}`
    try {
      const current = window.history.state
      window.history.pushState({ ...(current && typeof current === "object" ? current : {}), [viewerHistoryKey]: token }, "")
      historyToken.current = token
    } catch {
      // An unavailable history API leaves the other close methods intact.
      historyToken.current = null
    }
    openRef.current = true
    setOpen(true)
  }, [])

  const changeOpen = useCallback((next: boolean) => {
    if (next) {
      if (historyCleanupPending.current) { reopenAfterCleanup.current = true; return }
      beginOpen()
      return
    }
    reopenAfterCleanup.current = false
    openRef.current = false
    setOpen(false)
    const token = historyToken.current
    historyToken.current = null
    if (token && window.history.state?.[viewerHistoryKey] === token) {
      historyCleanupPending.current = true
      window.history.back()
    }
  }, [beginOpen])

  useEffect(() => {
    const onPopState = (event: PopStateEvent) => {
      if (historyCleanupPending.current) {
        historyCleanupPending.current = false
        if (reopenAfterCleanup.current && triggerRef.current?.isConnected) {
          reopenAfterCleanup.current = false
          beginOpen()
        }
        return
      }
      if (openRef.current && historyToken.current && event.state?.[viewerHistoryKey] !== historyToken.current) {
        historyToken.current = null
        openRef.current = false
        setOpen(false)
      }
    }
    window.addEventListener("popstate", onPopState)
    return () => window.removeEventListener("popstate", onPopState)
  }, [beginOpen])

  useEffect(() => {
    if (!open) return
    // CloseWatcher handles Android's back action where supported; Radix still
    // handles Escape and the accessible close control on other browsers.
    const Watcher = (window as Window & { CloseWatcher?: new () => CloseWatcher }).CloseWatcher
    if (!Watcher) return
    try {
      const watcher = new Watcher()
      watcher.addEventListener("close", () => changeOpen(false))
      return () => watcher.destroy()
    } catch {
      // Browsers that cannot create a watcher still support the other exits.
    }
  }, [open, changeOpen])

  useEffect(() => () => {
    if (restoreFrame.current !== null) cancelAnimationFrame(restoreFrame.current)
  }, [])

  const isBackground = (target: EventTarget | null, scrollArea: HTMLElement) => (
    target === scrollArea || (target instanceof HTMLElement && target.dataset.viewerBackground === "true")
  )

  return (
    <Dialog open={open} onOpenChange={changeOpen}>
      <DialogTrigger asChild>
        <button ref={triggerRef} type="button" className="product-image-stage product-image-stage--detail block w-full focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" aria-label={`${t("product.viewImage")}: ${title}`}>
          <ProductImage src={previewSrc} alt={title} priority sizes="(min-width: 1024px) 55vw, (min-width: 640px) 192px, (min-width: 426px) 145px, (min-width: 324px) 34vw, 110px" className="product-image-stage-image" />
          <span aria-hidden="true" className="pointer-events-none absolute bottom-1.5 right-1.5 rounded-full bg-black/65 p-1.5 text-white">
            <ZoomIn className="size-3.5" />
          </span>
        </button>
      </DialogTrigger>
      <DialogPortal>
        <DialogOverlay className="z-[60] bg-black" style={{ animation: "none" }} />
        <DialogPrimitive.Content
          className="product-image-viewer fixed inset-0 z-[60] flex flex-col bg-black text-white outline-none"
          aria-describedby={undefined}
          onOpenAutoFocus={(event) => {
            event.preventDefault()
            scrollRef.current?.focus({ preventScroll: true })
          }}
          onCloseAutoFocus={(event) => {
            event.preventDefault()
            if (openRef.current) return
            triggerRef.current?.focus({ preventScroll: true })
            const { x, y } = scrollPosition.current
            restoreFrame.current = requestAnimationFrame(() => {
              restoreFrame.current = null
              if (triggerRef.current?.isConnected && !openRef.current) window.scrollTo({ left: x, top: y, behavior: "instant" })
            })
          }}
        >
          <DialogPrimitive.Title className="sr-only">{title}</DialogPrimitive.Title>
          <div
            ref={scrollRef}
            className="product-image-viewer-scroll outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-white"
            tabIndex={0}
            onPointerDown={(event) => {
              backdropPointer.current = event.isPrimary && event.button === 0 && isBackground(event.target, event.currentTarget)
                ? { target: event.target, x: event.clientX, y: event.clientY, scrollTop: event.currentTarget.scrollTop }
                : null
            }}
            onPointerCancel={() => { backdropPointer.current = null }}
            onClick={(event) => {
              const start = backdropPointer.current
              backdropPointer.current = null
              if (start && event.target === start.target && isBackground(event.target, event.currentTarget)
                && Math.hypot(event.clientX - start.x, event.clientY - start.y) < 8
                && Math.abs(event.currentTarget.scrollTop - start.scrollTop) < 2) changeOpen(false)
            }}
          >
            <div className="product-image-viewer-canvas" data-viewer-background="true">
              {failed ? (
                <p role="alert" className="pointer-events-none text-sm text-white/80">{t("product.imageLoadFailed")}</p>
              ) : (
                // The original URL is intentionally not sent through the thumbnail optimizer.
                // eslint-disable-next-line @next/next/no-img-element
                <img src={src} alt={title} className="product-image-viewer-original" data-shape={shape} referrerPolicy="no-referrer"
                  onLoad={(event) => setShape(event.currentTarget.naturalHeight > event.currentTarget.naturalWidth ? "portrait" : "wide")}
                  onError={() => setFailed(true)} />
              )}
            </div>
          </div>
          <DialogPrimitive.Close className="sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-4 focus:z-10 focus:rounded-control focus:bg-white focus:px-4 focus:py-3 focus:text-black">
            {t("common.close")}
          </DialogPrimitive.Close>
        </DialogPrimitive.Content>
      </DialogPortal>
    </Dialog>
  )
}
