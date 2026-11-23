"use client"

import {
  forwardRef,
  useCallback,
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
import type { ElementInteraction, GestureState } from "@/lib/liquid-glass/context/types"
import { useGestureHandlers } from "@/lib/liquid-glass/context/pointer-handlers"

export interface StoreBottomNavWebGLHandle {
  selectTab: (index: number) => void
  isReady: () => boolean
}

interface StoreBottomNavWebGLProps {
  width: number
  height: number
  activeIndex: number
  isDark: boolean
  labels: readonly [string, string, string, string]
  unread: number
  onSelectTab: (index: number) => void
  onAvailabilityChange?: (available: boolean) => void
}

export const StoreBottomNavWebGL = forwardRef<
  StoreBottomNavWebGLHandle,
  StoreBottomNavWebGLProps
>(function StoreBottomNavWebGL(
  { width, height, activeIndex, isDark, labels, unread, onSelectTab, onAvailabilityChange },
  ref
) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null)
  const rendererRef = useRef<LiquidGlassRenderer | null>(null)
  const elementsRef = useRef<StoreBottomTabsResult["elements"]>([])
  const interactionsRef = useRef<Record<string, ElementInteraction>>({})
  const gesturesRef = useRef<Map<number, GestureState>>(new Map())
  const prevPinchRef = useRef<{ dist: number; angle: number; cx: number; cy: number } | null>(null)
  const [isSupported, setIsSupported] = useState(true)

  const palette: ThemePalette = isDark ? DARK_PALETTE : LIGHT_PALETTE

  // Build tabs configuration and update refs
  const updateTabs = useCallback(() => {
    if (width <= 0) return
    const res = buildStoreBottomTabs(
      width,
      height,
      activeIndex,
      onSelectTab,
      rendererRef,
      palette,
      labels,
      unread
    )
    elementsRef.current = res.elements
    interactionsRef.current = res.interactions
    rendererRef.current?.setElements(res.elements)
  }, [width, height, activeIndex, onSelectTab, palette, labels, unread])

  // Expose imperative API to synchronize external active tab changes
  useImperativeHandle(
    ref,
    () => ({
      selectTab: (index: number) => {
        const r = rendererRef.current
        if (!r || !isSupported) return
        r.setTabSelected("store-dock", index, 4)
      },
      isReady: () => Boolean(rendererRef.current && isSupported),
    }),
    [isSupported]
  )

  // Initialize WebGL renderer and assets
  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return

    let renderer: LiquidGlassRenderer | null = null
    try {
      renderer = new LiquidGlassRenderer(canvas)
      rendererRef.current = renderer
      renderer.dpr = Math.min(window.devicePixelRatio || 1, 3)
      renderer.noContinuousSdf = false
      renderer.directBackdropSample = true

      // Load authentic upstream wallpaper and SDF texture
      renderer.loadWallpaper("/wallpaper/wallpaper_light.webp").catch(() => {})
      renderer.loadSdfTexture("/clock_sdf.webp").catch(() => {})

      onAvailabilityChange?.(true)
      setIsSupported(true)
    } catch (err) {
      console.warn("[LiquidGlassWebGL] WebGL initialization failed, falling back to static CSS:", err)
      setIsSupported(false)
      onAvailabilityChange?.(false)
      return
    }

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
      renderer?.dispose()
      rendererRef.current = null
    }
  }, [onAvailabilityChange])

  // Resize renderer when container dimensions change
  useEffect(() => {
    const renderer = rendererRef.current
    if (!renderer || width <= 0 || height <= 0) return
    renderer.resize(width, height)
    updateTabs()
  }, [width, height, updateTabs])

  // Synchronize tab target on activeIndex change
  useEffect(() => {
    const r = rendererRef.current
    if (!r || !isSupported) return
    r.setTabSelected("store-dock", activeIndex, 4)
  }, [activeIndex, isSupported])

  // Re-build tabs when labels, unread, or theme palette change
  useEffect(() => {
    updateTabs()
  }, [updateTabs])

  // Gesture handling directly via upstream useGestureHandlers
  const { handlePointerDown, handlePointerMove, handlePointerUp } = useGestureHandlers({
    canvasRef,
    rendererRefInternal: rendererRef,
    elementsRef,
    interactionsRef,
    gesturesRef,
    prevPinchRef,
  })

  if (!isSupported) return null

  return (
    <canvas
      ref={canvasRef}
      onPointerDown={handlePointerDown}
      onPointerMove={handlePointerMove}
      onPointerUp={handlePointerUp}
      onPointerLeave={handlePointerUp}
      onPointerCancel={handlePointerUp}
      style={{
        display: "block",
        width: `${width}px`,
        height: `${height}px`,
        cursor: "pointer",
        touchAction: "none",
        position: "relative",
        zIndex: 1,
      }}
    />
  )
})
