"use client"

import {
  forwardRef,
  useEffect,
  useImperativeHandle,
  useRef,
  useState,
} from "react"
import { LiquidGlassRenderer } from "@/lib/liquid-glass/renderer"
import {
  buildStoreBottomTabs,
  type StoreBottomTabsResult,
} from "@/lib/liquid-glass/build-store-bottom-tabs"
import {
  DARK_PALETTE,
  LIGHT_PALETTE,
  type ThemePalette,
} from "@/lib/liquid-glass/catalog/palettes"

export interface StoreBottomNavWebGLHandle {
  selectTab: (index: number) => void
  beginDrag: (index: number) => void
  drag: (
    index: number,
    currentX: number,
    startX: number,
    tabWidth: number
  ) => void
  endDrag: () => number
  isReady: () => boolean
}

interface StoreBottomNavWebGLProps {
  width: number
  height: number
  activeIndex: number
  isDark: boolean
  onSelectTab: (index: number) => void
  onAvailabilityChange?: (available: boolean) => void
}

/**
 * Creates a localized background texture representing the page background
 * behind the dock pill for WebGL refraction.
 */
function createLocalizedBackdrop(isDark: boolean): HTMLCanvasElement {
  const canvas = document.createElement("canvas")
  canvas.width = 256
  canvas.height = 64
  const ctx = canvas.getContext("2d")
  if (!ctx) return canvas

  // Soft vertical gradient matching MyShop storefront background
  const grad = ctx.createLinearGradient(0, 0, 0, 64)
  if (isDark) {
    grad.addColorStop(0, "rgba(9, 9, 11, 1)")
    grad.addColorStop(0.5, "rgba(18, 18, 22, 1)")
    grad.addColorStop(1, "rgba(24, 24, 28, 1)")
  } else {
    grad.addColorStop(0, "rgba(255, 255, 255, 1)")
    grad.addColorStop(0.5, "rgba(248, 250, 252, 1)")
    grad.addColorStop(1, "rgba(241, 245, 249, 1)")
  }
  ctx.fillStyle = grad
  ctx.fillRect(0, 0, 256, 64)

  // Subtle ambient highlight
  ctx.fillStyle = isDark
    ? "rgba(255, 255, 255, 0.04)"
    : "rgba(255, 255, 255, 0.6)"
  ctx.fillRect(0, 0, 256, 1)

  return canvas
}

export const StoreBottomNavWebGL = forwardRef<
  StoreBottomNavWebGLHandle,
  StoreBottomNavWebGLProps
>(function StoreBottomNavWebGL(
  { width, height, activeIndex, isDark, onSelectTab, onAvailabilityChange },
  ref
) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null)
  const rendererRef = useRef<LiquidGlassRenderer | null>(null)
  const tabsResultRef = useRef<StoreBottomTabsResult | null>(null)
  const [isSupported, setIsSupported] = useState(true)
  const isDraggingRef = useRef(false)
  const lastActiveIndexRef = useRef(activeIndex)

  const palette: ThemePalette = isDark ? DARK_PALETTE : LIGHT_PALETTE

  // Expose imperative API to synchronize gestures and active tab changes
  useImperativeHandle(
    ref,
    () => ({
      selectTab: (index: number) => {
        const r = rendererRef.current
        if (!r || !isSupported) return
        r.setTabSelected("store-dock", index, 4)
      },
      beginDrag: (index: number) => {
        const r = rendererRef.current
        if (!r || !isSupported) return
        isDraggingRef.current = true
        r.beginTabDrag("store-dock", index, 4)
      },
      drag: (
        index: number,
        currentX: number,
        startX: number,
        tabWidth: number
      ) => {
        const r = rendererRef.current
        if (!r || !isSupported) return
        r.dragTab("store-dock", index, currentX, startX, tabWidth, 4)
      },
      endDrag: () => {
        const r = rendererRef.current
        isDraggingRef.current = false
        if (!r || !isSupported) return activeIndex
        return r.endTabDrag("store-dock", 4)
      },
      isReady: () => Boolean(rendererRef.current && isSupported),
    }),
    [activeIndex, isSupported]
  )

  // Initialize WebGL renderer
  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return

    let renderer: LiquidGlassRenderer | null = null
    try {
      renderer = new LiquidGlassRenderer(canvas)
      rendererRef.current = renderer
      renderer.dpr = Math.min(window.devicePixelRatio || 1, 3)

      const backdropCanvas = createLocalizedBackdrop(isDark)
      renderer.loadWallpaperFromCanvas(backdropCanvas)
      renderer.noContinuousSdf = false
      renderer.directBackdropSample = true

      onAvailabilityChange?.(true)
      setIsSupported(true)
    } catch (err) {
      console.warn("[LiquidGlassWebGL] WebGL initialization failed, falling back to CSS:", err)
      setIsSupported(false)
      onAvailabilityChange?.(false)
      return
    }

    // Visibility change handler to stop unnecessary animations when page is backgrounded
    const handleVisibilityChange = () => {
      if (document.hidden) {
        renderer?.stopAnimation()
      } else {
        renderer?.requestRender()
      }
    }
    document.addEventListener("visibilitychange", handleVisibilityChange)

    return () => {
      document.removeEventListener("visibilitychange", handleVisibilityChange)
      if (renderer) {
        try {
          renderer.dispose()
        } catch {
          // ignore cleanup errors
        }
      }
      rendererRef.current = null
      tabsResultRef.current = null
    }
  }, [isDark, onAvailabilityChange])

  // Update localized backdrop on theme change
  useEffect(() => {
    const renderer = rendererRef.current
    if (!renderer || !isSupported) return
    const backdropCanvas = createLocalizedBackdrop(isDark)
    renderer.loadWallpaperFromCanvas(backdropCanvas)
  }, [isDark, isSupported])

  // Build and configure elements on resize or dimensions change
  useEffect(() => {
    const renderer = rendererRef.current
    if (!renderer || !isSupported || width <= 0 || height <= 0) return

    renderer.resize(width, height)

    const result = buildStoreBottomTabs(
      width,
      height,
      activeIndex,
      onSelectTab,
      rendererRef,
      palette
    )
    tabsResultRef.current = result
    renderer.setElements(result.elements)

    // Ensure tab index is set without fighting an ongoing drag
    if (!isDraggingRef.current) {
      renderer.setTabSelected("store-dock", activeIndex, 4)
    }
    renderer.requestRender()
  }, [activeIndex, height, isDark, isSupported, onSelectTab, palette, width])

  // Sync activeIndex changes when not dragging
  useEffect(() => {
    const renderer = rendererRef.current
    if (!renderer || !isSupported || isDraggingRef.current) return
    if (lastActiveIndexRef.current !== activeIndex) {
      lastActiveIndexRef.current = activeIndex
      renderer.setTabSelected("store-dock", activeIndex, 4)
    }
  }, [activeIndex, isSupported])

  if (!isSupported) return null

  return (
    <canvas
      ref={canvasRef}
      className="pointer-events-none absolute inset-0 size-full"
      style={{
        borderRadius: "inherit",
        zIndex: 0,
      }}
      aria-hidden="true"
    />
  )
})
