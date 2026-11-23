"use client"

import { useCallback, useEffect, useLayoutEffect, useRef, useState, type MouseEvent, type PointerEvent, type RefObject } from "react"
import Link from "next/link"
import { usePathname, useRouter } from "next/navigation"
import { House, ClipboardList, MessageCircle, UserRound } from "lucide-react"
import { useMessageSummary } from "@/components/store/message-summary"
import { useTheme } from "@/lib/context"
import { LiquidGlassRenderer } from "@/lib/vendor/liquid-glass/renderer"
import { StoreBackdrop } from "./backdrop"
import { BAR_TOP, buildElements, DRAWING_HEIGHT, GROUP, LABELS, PATHS } from "./elements"

const Icons = [House, ClipboardList, MessageCircle, UserRound]
const DRAG_THRESHOLD = 8

function tabContentColor(nav: HTMLElement, dark: boolean): [number, number, number, number] {
  // Read the same resolved theme token used by the catalog's category text.
  const rgb = getComputedStyle(nav).color.match(/[\d.]+/g)?.map(Number)
  if (rgb && rgb.length >= 3) return [rgb[0] / 255, rgb[1] / 255, rgb[2] / 255, 1]
  return dark ? [168 / 255, 177 / 255, 189 / 255, 1] : [101 / 255, 117 / 255, 139 / 255, 1]
}

type ActivePointer = { id: number; type: string; startX: number; startY: number; lastX: number; lastY: number; startTarget: number; clicked: number; dragged: boolean }
type SuppressedClick = { id: number; x: number; y: number; until: number }

/** Stop an interrupted hold without committing its target. */
function restoreRoute(renderer: LiquidGlassRenderer, index: number, immediate = false) {
  const state = renderer.ensureToggleState(GROUP, index, LiquidGlassRenderer.TAB_PRESSED_SCALE, 3)
  state.isDragging = false
  state.targetFraction = index
  state.targetPress = 0
  state.targetScaleX = state.targetScaleY = 1
  state.targetVelocity = 0
  state.targetPanelOffset = 0
  state.trackVelocityAfterRelease = false
  state.velocityTracker.resetTracking()
  if (immediate) {
    state.fraction = index
    state.fractionVelocity = state.pressProgress = state.pressVelocity = 0
    state.scaleX = state.scaleY = 1
    state.scaleXVelocity = state.scaleYVelocity = 0
    state.velocity = state.velocityVelocity = state.panelOffset = state.panelOffsetVelocity = 0
  }
  renderer.markGroupDirty(GROUP)
  renderer.startAnimation()
}

export function StoreBottomNavigation({ sourceRef }: { sourceRef: RefObject<HTMLDivElement | null> }) {
  const pathname = usePathname()
  const router = useRouter()
  const selected = PATHS.findIndex(path => path === pathname)
  const active = selected >= 0
  const { resolvedTheme } = useTheme()
  const { announcements, support } = useMessageSummary()
  const unread = announcements.unread + support.unread
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const navRef = useRef<HTMLElement>(null)
  const rendererRef = useRef<LiquidGlassRenderer | null>(null)
  const backdropRef = useRef<StoreBackdrop | null>(null)
  const pointerRef = useRef<ActivePointer | null>(null)
  const ignoredPointers = useRef(new Set<number>())
  const suppressed = useRef<SuppressedClick | null>(null)
  const pathRef = useRef(pathname)
  const themeRef = useRef(resolvedTheme)
  const selectedRef = useRef(selected)
  const intentRef = useRef<{ path: string; settledUntil: number } | null>(null)
  const [ready, setReady] = useState(false)
  const [generation, setGeneration] = useState(0)
  useLayoutEffect(() => {
    pathRef.current = pathname
    themeRef.current = resolvedTheme
    selectedRef.current = selected
  }, [pathname, resolvedTheme, selected])

  const navigate = useCallback((index: number) => {
    const path = PATHS[index]
    if (!path || path === pathRef.current && (!intentRef.current || intentRef.current.path === path)) return
    intentRef.current = { path, settledUntil: 0 }
    router.replace(path, { scroll: false })
  }, [router])

  useEffect(() => {
    const clearIntent = () => { intentRef.current = null }
    window.addEventListener("popstate", clearIntent)
    return () => window.removeEventListener("popstate", clearIntent)
  }, [])

  useEffect(() => {
    if (!active) { intentRef.current = null; setReady(false); return }
    backdropRef.current?.invalidate("页面切换", "route")
    const pointer = pointerRef.current
    if (pointer) {
      pointerRef.current = null
      suppressed.current = { id: pointer.id, x: pointer.lastX, y: pointer.lastY, until: Date.now() + 700 }
      const nav = navRef.current
      if (nav) delete nav.dataset.touchActive
      if (nav?.hasPointerCapture(pointer.id)) nav.releasePointerCapture(pointer.id)
      if (rendererRef.current) restoreRoute(rendererRef.current, selected)
    }
    const intent = intentRef.current
    if (intent && intent.path !== pathname && (intent.settledUntil === 0 || intent.settledUntil > Date.now())) {
      router.replace(intent.path, { scroll: false })
      return
    }
    if (intent?.path === pathname) {
      intent.settledUntil = Date.now() + 1200
      window.setTimeout(() => { if (intentRef.current === intent && intent.settledUntil <= Date.now()) intentRef.current = null }, 1250)
    } else intentRef.current = null
    const renderer = rendererRef.current
    if (renderer && !pointerRef.current) restoreRoute(renderer, selected)
  }, [active, pathname, router, selected])

  useEffect(() => {
    if (!active || !canvasRef.current || !navRef.current || !sourceRef.current) return
    const canvas = canvasRef.current
    const nav = navRef.current
    let renderer: LiquidGlassRenderer
    let backdrop: StoreBackdrop | null = null
    let disposed = false
    let rendererDisposed = false
    try {
      renderer = new LiquidGlassRenderer(canvas)
      rendererRef.current = renderer
      renderer.dpr = Math.min(window.devicePixelRatio || 1, 1.5)
      renderer.reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches
      renderer.ensureToggleState(GROUP, Math.max(0, selectedRef.current), LiquidGlassRenderer.TAB_PRESSED_SCALE, 3)
      const configure = () => {
        const rect = canvas.getBoundingClientRect()
        if (!rect.width || !rect.height) return
        const width = nav.getBoundingClientRect().width
        renderer.dpr = Math.min(window.devicePixelRatio || 1, 1.5)
        renderer.resize(rect.width, rect.height)
        const dark = themeRef.current === "dark"
        renderer.setElements(buildElements(width, dark, (rect.width - width) / 2, tabContentColor(nav, dark)))
        backdrop?.invalidate("导航尺寸变化", "layout")
      }
      configure()
      backdrop = new StoreBackdrop(sourceRef.current, canvas, renderer, info => {
        if (disposed) return
        nav.dataset.mode = info.ready ? "webgl" : "fallback"
        nav.dataset.backdropReason = info.reason ?? ""
        nav.dataset.backdropPending = String(info.pending ?? false)
        nav.dataset.captureCount = String(info.captures)
        nav.dataset.captureMs = info.lastMs.toFixed(1)
        nav.dataset.captureWarnings = info.warnings.join("; ")
        setReady(current => current === info.ready ? current : info.ready)
      })
      backdropRef.current = backdrop
      const observer = new ResizeObserver(configure)
      observer.observe(canvas)
      const motion = window.matchMedia("(prefers-reduced-motion: reduce)")
      const motionChanged = () => {
        renderer.reducedMotion = motion.matches
        if (motion.matches && renderer.animRafId !== null) {
          cancelAnimationFrame(renderer.animRafId)
          renderer.animRafId = null
        }
        renderer.startAnimation()
      }
      motion.addEventListener("change", motionChanged)
      const visibility = () => {
        renderer.suspended = document.hidden
        if (document.hidden) {
          if (renderer.rafId !== null) cancelAnimationFrame(renderer.rafId)
          if (renderer.animRafId !== null) cancelAnimationFrame(renderer.animRafId)
          renderer.rafId = renderer.animRafId = null
          const pointer = pointerRef.current
          pointerRef.current = null
          delete nav.dataset.touchActive
          ignoredPointers.current.clear()
          if (pointer) {
            suppressed.current = { id: pointer.id, x: pointer.lastX, y: pointer.lastY, until: Date.now() + 700 }
            if (nav.hasPointerCapture(pointer.id)) nav.releasePointerCapture(pointer.id)
          }
          restoreRoute(renderer, Math.max(0, selectedRef.current), true)
        } else {
          renderer.requestRender()
          renderer.startAnimation()
        }
        backdrop?.setSuspended(document.hidden)
      }
      document.addEventListener("visibilitychange", visibility)
      const lost = (event: Event) => {
        event.preventDefault()
        const pointer = pointerRef.current
        pointerRef.current = null
        delete nav.dataset.touchActive
        ignoredPointers.current.clear()
        if (pointer) {
          suppressed.current = { id: pointer.id, x: pointer.lastX, y: pointer.lastY, until: Date.now() + 700 }
          if (nav.hasPointerCapture(pointer.id)) nav.releasePointerCapture(pointer.id)
        }
        backdrop?.dispose()
        backdrop = null
        backdropRef.current = null
        renderer.suspended = true
        renderer.dispose()
        rendererDisposed = true
        rendererRef.current = null
        setReady(false)
        nav.dataset.backdropReason = "WebGL context lost"
      }
      const restored = () => setGeneration(value => value + 1)
      canvas.addEventListener("webglcontextlost", lost)
      canvas.addEventListener("webglcontextrestored", restored)
      if (document.hidden) visibility()
      return () => {
        disposed = true
        observer.disconnect()
        motion.removeEventListener("change", motionChanged)
        document.removeEventListener("visibilitychange", visibility)
        canvas.removeEventListener("webglcontextlost", lost)
        canvas.removeEventListener("webglcontextrestored", restored)
        backdrop?.dispose()
        if (!rendererDisposed) renderer.dispose()
        if (rendererRef.current === renderer) rendererRef.current = null
        if (backdropRef.current === backdrop) backdropRef.current = null
        pointerRef.current = null
      }
    } catch (error) {
      console.warn("[store-tabs] WebGL unavailable", error)
      nav.dataset.mode = "fallback"
      nav.dataset.backdropReason = error instanceof Error ? error.message : String(error)
      setReady(false)
      backdrop?.dispose()
      rendererRef.current?.dispose()
      rendererRef.current = null
    }
  }, [active, generation, sourceRef])

  useEffect(() => {
    const renderer = rendererRef.current
    const canvas = canvasRef.current
    const nav = navRef.current
    if (!renderer || !canvas || !nav || !active) return
    const canvasWidth = canvas.getBoundingClientRect().width
    const width = nav.getBoundingClientRect().width
    const dark = resolvedTheme === "dark"
    renderer.setElements(buildElements(width, dark, (canvasWidth - width) / 2, tabContentColor(nav, dark)))
    backdropRef.current?.invalidate("主题变化", "theme")
  }, [active, resolvedTheme])

  const cancel = useCallback((id?: number, position?: { x: number; y: number }) => {
    const pointer = pointerRef.current
    if (!pointer || id !== undefined && pointer.id !== id) return
    pointerRef.current = null
    suppressed.current = { id: pointer.id, x: position?.x ?? pointer.lastX, y: position?.y ?? pointer.lastY, until: Date.now() + 700 }
    const renderer = rendererRef.current
    if (renderer) restoreRoute(renderer, Math.max(0, selectedRef.current))
    const nav = navRef.current
    if (nav) delete nav.dataset.touchActive
    if (nav?.hasPointerCapture(pointer.id)) nav.releasePointerCapture(pointer.id)
  }, [])
  useEffect(() => {
    const blur = () => cancel()
    window.addEventListener("blur", blur)
    return () => window.removeEventListener("blur", blur)
  }, [cancel])

  function down(event: PointerEvent<HTMLElement>) {
    if (event.button !== 0 || event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return
    if (pointerRef.current) { ignoredPointers.current.add(event.pointerId); return }
    const renderer = rendererRef.current
    const target = (event.target as Element).closest<HTMLAnchorElement>("a[data-tab]")
    if (!renderer || target && !event.currentTarget.contains(target) || !target && event.target !== event.currentTarget) return
    const rect = event.currentTarget.getBoundingClientRect()
    const clicked = target ? Number(target.dataset.tab) : Math.max(0, Math.min(3, Math.floor((event.clientX - rect.left) / (rect.width / 4))))
    pointerRef.current = { id: event.pointerId, type: event.pointerType, startX: event.clientX, startY: event.clientY, lastX: event.clientX, lastY: event.clientY,
      startTarget: renderer.getTabTarget(GROUP), clicked, dragged: false }
    if (event.pointerType === "touch") event.currentTarget.dataset.touchActive = "true"
    event.currentTarget.setPointerCapture(event.pointerId)
    renderer.beginTabDrag(GROUP, pointerRef.current.startTarget, 4)
  }
  function move(event: PointerEvent<HTMLElement>) {
    const pointer = pointerRef.current
    const renderer = rendererRef.current
    if (!pointer || pointer.id !== event.pointerId || !renderer) return
    pointer.lastX = event.clientX
    pointer.lastY = event.clientY
    const dx = event.clientX - pointer.startX
    const dy = event.clientY - pointer.startY
    if (!pointer.dragged && Math.abs(dx) >= DRAG_THRESHOLD && Math.abs(dx) > Math.abs(dy)) pointer.dragged = true
    if (pointer.dragged) renderer.dragTab(GROUP, pointer.startTarget, event.clientX, pointer.startX,
      (event.currentTarget.getBoundingClientRect().width - 8) / 4, 4)
  }
  function up(event: PointerEvent<HTMLElement>) {
    if (ignoredPointers.current.delete(event.pointerId)) {
      suppressed.current = { id: event.pointerId, x: event.clientX, y: event.clientY, until: Date.now() + 700 }
      return
    }
    const pointer = pointerRef.current
    const renderer = rendererRef.current
    if (!pointer || pointer.id !== event.pointerId || !renderer) return
    if (!pointer.dragged) {
      const link = event.currentTarget.querySelector<HTMLAnchorElement>(`a[data-tab="${pointer.clicked}"]`)
      const rect = link?.getBoundingClientRect()
      if (!rect || event.clientX < rect.left || event.clientX >= rect.right || event.clientY < rect.top || event.clientY >= rect.bottom) {
        cancel(event.pointerId, { x: event.clientX, y: event.clientY })
        return
      }
    }
    if (pointer.dragged) renderer.dragTab(GROUP, pointer.startTarget, event.clientX, pointer.startX,
      (event.currentTarget.getBoundingClientRect().width - 8) / 4, 4)
    const target = renderer.endTabDrag(GROUP, 4)
    const destination = pointer.dragged ? target : pointer.clicked
    if (!pointer.dragged && destination !== target) renderer.setTabSelected(GROUP, destination, 4)
    pointerRef.current = null
    delete event.currentTarget.dataset.touchActive
    suppressed.current = { id: event.pointerId, x: event.clientX, y: event.clientY, until: Date.now() + 700 }
    if (event.currentTarget.hasPointerCapture(pointer.id)) event.currentTarget.releasePointerCapture(pointer.id)
    navigate(destination)
  }
  function suppressClick(event: MouseEvent<HTMLElement>) {
    if (event.button !== 0 || event.altKey || event.ctrlKey || event.metaKey || event.shiftKey || event.detail === 0) return
    const last = suppressed.current
    if (!last || Date.now() > last.until) return
    const pointerId = (event.nativeEvent as globalThis.MouseEvent & { pointerId?: number }).pointerId
    if (pointerId === last.id || pointerId === undefined && Math.abs(event.clientX - last.x) < 2 && Math.abs(event.clientY - last.y) < 2) {
      suppressed.current = null
      event.preventDefault()
      event.stopPropagation()
    }
  }
  function click(event: MouseEvent<HTMLAnchorElement>, index: number) {
    if (event.defaultPrevented || event.button !== 0 || event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return
    event.preventDefault()
    const renderer = rendererRef.current
    if (renderer && index !== selectedRef.current) renderer.setTabSelected(GROUP, index, 4)
    navigate(index)
  }

  if (!active) return null
  return (
    <div className="store-liquid-surface" style={{ height: DRAWING_HEIGHT }}>
      <canvas ref={canvasRef} className="store-liquid-canvas" style={{ visibility: ready ? "visible" : "hidden" }} aria-hidden="true" />
      <nav ref={navRef} aria-label="店铺导航" data-mode={ready ? "webgl" : "fallback"}
        className="store-liquid-nav" style={{ top: BAR_TOP }} onDragStart={event => event.preventDefault()} onPointerDown={down} onPointerMove={move} onPointerUp={up}
        onContextMenu={event => {
          const pointerType = (event.nativeEvent as globalThis.MouseEvent & { pointerType?: string }).pointerType
          if (pointerRef.current?.type === "touch" || pointerType === "touch") event.preventDefault()
        }}
        onPointerCancel={event => { ignoredPointers.current.delete(event.pointerId); cancel(event.pointerId) }}
        onLostPointerCapture={event => cancel(event.pointerId)} onClickCapture={suppressClick}>
        {PATHS.map((path, index) => {
          const Icon = Icons[index]
          return <Link key={path} href={path} replace scroll={false} draggable={false} data-tab={index} aria-current={index === selected ? "page" : undefined}
            onClick={event => click(event, index)} className="store-liquid-link">
            <span className="store-liquid-fallback-content" aria-hidden="true"><Icon size={24} strokeWidth={1.8} /><span>{LABELS[index]}</span></span>
            <span className="sr-only">{LABELS[index]}</span>
            {index === 2 && unread > 0 && <span className="store-liquid-badge" aria-label={`${unread} 条未读消息`}>{unread > 99 ? "99+" : unread}</span>}
          </Link>
        })}
      </nav>
    </div>
  )
}
