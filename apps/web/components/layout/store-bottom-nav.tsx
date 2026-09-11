"use client"

import { useCallback, useEffect, useRef, useState } from "react"
import { usePathname, useRouter } from "next/navigation"
import { House, ReceiptText, UserRound, type LucideIcon } from "lucide-react"
import { animate, motion, useMotionValue, useReducedMotion } from "motion/react"
import { useLocale } from "@/lib/context"
import { getStorefrontDockAction } from "@/lib/storefront-dock"
import { getStorefrontActiveTab, type StorefrontNavKey } from "@/lib/storefront-routes"

type DockKey = StorefrontNavKey

const DOCK_KEYS: readonly DockKey[] = ["home", "orders", "me"]
const SNAP_FALLBACK_MS = 480

interface StoreDockItem {
  key: DockKey
  label: string
  icon: LucideIcon
}

interface DockMetrics {
  slotCenters: number[]
  slotLefts: number[]
  indicatorWidth: number
  minX: number
  maxX: number
}

function getNearestDockKey(center: number, slotCenters: readonly number[]): DockKey | null {
  if (slotCenters.length !== DOCK_KEYS.length) return null

  let nearestIndex = 0
  let nearestDistance = Math.abs(center - slotCenters[0])

  for (let index = 1; index < slotCenters.length; index += 1) {
    const distance = Math.abs(center - slotCenters[index])
    if (distance < nearestDistance) {
      nearestIndex = index
      nearestDistance = distance
    }
  }

  return DOCK_KEYS[nearestIndex]
}

function getIndicatorOffset(key: DockKey | null, metrics: DockMetrics): number {
  const index = key ? DOCK_KEYS.indexOf(key) : 0
  if (index < 0) return 0
  return metrics.slotLefts[index] - metrics.minX
}

export function StoreBottomNav() {
  const pathname = usePathname()
  const router = useRouter()
  const reducedMotion = useReducedMotion() ?? false
  const { t } = useLocale()

  const dockRef = useRef<HTMLDivElement>(null)
  const itemRefs = useRef<Array<HTMLButtonElement | null>>([])
  const draggingRef = useRef(false)
  const dragMovedRef = useRef(false)
  const suppressTapRef = useRef(false)
  const pendingActionRef = useRef<DockKey | null>(null)
  const snapAnimationRef = useRef<ReturnType<typeof animate> | null>(null)
  const snapActionTimerRef = useRef<number | null>(null)
  const indicatorX = useMotionValue(0)
  const [metrics, setMetrics] = useState<DockMetrics | null>(null)
  const [dragPreviewKey, setDragPreviewKey] = useState<DockKey | null>(null)

  const activeKey = getStorefrontActiveTab(pathname)
  const navItems: StoreDockItem[] = [
    { key: "home", label: t("nav.home"), icon: House },
    { key: "orders", label: t("nav.orders"), icon: ReceiptText },
    { key: "me", label: t("nav.me"), icon: UserRound },
  ]

  const measureDock = useCallback(() => {
    const dock = dockRef.current
    const elements = itemRefs.current
    const rects = elements.filter((element): element is HTMLButtonElement => element !== null)
    if (!dock || rects.length !== DOCK_KEYS.length) return

    const dockRect = dock.getBoundingClientRect()
    const slotLefts = rects.map((element) => element.getBoundingClientRect().left - dockRect.left)
    const slotCenters = rects.map((element) => {
      const rect = element.getBoundingClientRect()
      return rect.left + rect.width / 2
    })
    const minX = slotLefts[0]
    const maxX = slotLefts[slotLefts.length - 1] - minX
    const indicatorWidth = rects[0].getBoundingClientRect().width

    if (!Number.isFinite(minX) || !Number.isFinite(maxX) || !Number.isFinite(indicatorWidth)) return

    setMetrics((previous) => {
      const next = { slotCenters, slotLefts, indicatorWidth, minX, maxX }
      if (
        previous &&
        previous.indicatorWidth === next.indicatorWidth &&
        previous.minX === next.minX &&
        previous.maxX === next.maxX &&
        previous.slotLefts.every((value, index) => value === next.slotLefts[index]) &&
        previous.slotCenters.every((value, index) => value === next.slotCenters[index])
      ) {
        return previous
      }
      return next
    })
  }, [])

  useEffect(() => {
    let frame = 0
    const scheduleMeasure = () => {
      window.cancelAnimationFrame(frame)
      frame = window.requestAnimationFrame(measureDock)
    }

    scheduleMeasure()
    window.addEventListener("resize", scheduleMeasure)
    window.addEventListener("orientationchange", scheduleMeasure)

    const dock = dockRef.current
    const observer = dock && typeof ResizeObserver !== "undefined"
      ? new ResizeObserver(scheduleMeasure)
      : null
    if (observer && dock) observer.observe(dock)

    return () => {
      window.cancelAnimationFrame(frame)
      window.removeEventListener("resize", scheduleMeasure)
      window.removeEventListener("orientationchange", scheduleMeasure)
      observer?.disconnect()
    }
  }, [measureDock])

  useEffect(() => {
    if (!metrics || draggingRef.current || !activeKey) return
    const target = getIndicatorOffset(activeKey, metrics)
    snapAnimationRef.current?.stop()
    snapAnimationRef.current = null
    if (reducedMotion) {
      indicatorX.set(target)
    } else {
      snapAnimationRef.current = animate(indicatorX, target, {
        type: "spring",
        stiffness: 520,
        damping: 42,
        mass: 0.45,
      })
    }
  }, [activeKey, indicatorX, metrics, reducedMotion])

  useEffect(() => () => {
    snapAnimationRef.current?.stop()
    if (snapActionTimerRef.current !== null) window.clearTimeout(snapActionTimerRef.current)
  }, [])

  const snapIndicator = useCallback((target: number, onComplete?: () => void) => {
    snapAnimationRef.current?.stop()
    snapAnimationRef.current = null

    if (reducedMotion) {
      indicatorX.set(target)
      onComplete?.()
      return
    }

    const animation = animate(indicatorX, target, {
      type: "spring",
      stiffness: 520,
      damping: 42,
      mass: 0.45,
    })
    snapAnimationRef.current = animation
    animation.then(() => {
      if (snapAnimationRef.current !== animation) return
      snapAnimationRef.current = null
      onComplete?.()
    })
  }, [indicatorX, reducedMotion])

  const handleDockAction = useCallback((key: DockKey) => {
    draggingRef.current = false
    dragMovedRef.current = false
    snapAnimationRef.current?.stop()
    snapAnimationRef.current = null
    if (snapActionTimerRef.current !== null) {
      window.clearTimeout(snapActionTimerRef.current)
      snapActionTimerRef.current = null
    }
    pendingActionRef.current = null
    setDragPreviewKey(null)

    const action = getStorefrontDockAction(key, pathname)
    if (action.href && action.href !== pathname) router.push(action.href)
  }, [pathname, router])

  const completePendingAction = useCallback(() => {
    const key = pendingActionRef.current
    if (!key) return

    pendingActionRef.current = null
    setDragPreviewKey(null)
    handleDockAction(key)
  }, [handleDockAction])

  const handleDragStart = useCallback(() => {
    if (!activeKey) return
    pendingActionRef.current = null
    draggingRef.current = true
    dragMovedRef.current = true
    setDragPreviewKey(activeKey)
  }, [activeKey])

  const updateDragPreview = useCallback(() => {
    if (!metrics || !draggingRef.current) return
    dragMovedRef.current = true

    const center = metrics.slotCenters[0] + indicatorX.get()
    const key = getNearestDockKey(center, metrics.slotCenters)
    if (key) setDragPreviewKey(key)
  }, [indicatorX, metrics])

  const finishDrag = useCallback(() => {
    if (!draggingRef.current || !metrics) return
    if (!dragMovedRef.current) {
      draggingRef.current = false
      return
    }

    draggingRef.current = false
    suppressTapRef.current = true

    const center = metrics.slotCenters[0] + indicatorX.get()
    const nearestKey = getNearestDockKey(center, metrics.slotCenters)
    if (!nearestKey) return

    const targetOffset = getIndicatorOffset(nearestKey, metrics)
    const shouldWaitForSnap = !reducedMotion && Math.abs(indicatorX.get() - targetOffset) > 1

    if (shouldWaitForSnap) {
      pendingActionRef.current = nearestKey
      snapIndicator(targetOffset, completePendingAction)
      snapActionTimerRef.current = window.setTimeout(() => {
        snapActionTimerRef.current = null
        completePendingAction()
      }, SNAP_FALLBACK_MS)
      return
    }

    pendingActionRef.current = null
    setDragPreviewKey(null)
    snapIndicator(targetOffset, () => handleDockAction(nearestKey))
  }, [completePendingAction, handleDockAction, indicatorX, metrics, reducedMotion, snapIndicator])

  const handleIndicatorPointerDown = useCallback(() => {
    if (!activeKey) return
    snapAnimationRef.current?.stop()
    snapAnimationRef.current = null
    if (snapActionTimerRef.current !== null) {
      window.clearTimeout(snapActionTimerRef.current)
      snapActionTimerRef.current = null
    }
    pendingActionRef.current = null
    draggingRef.current = true
    dragMovedRef.current = false
    suppressTapRef.current = false
    setDragPreviewKey(activeKey)
  }, [activeKey])

  const handleIndicatorTap = useCallback(() => {
    if (suppressTapRef.current) {
      suppressTapRef.current = false
      dragMovedRef.current = false
      return
    }

    draggingRef.current = false
    dragMovedRef.current = false
    if (activeKey) handleDockAction(activeKey)
  }, [activeKey, handleDockAction])

  const visualActiveKey = dragPreviewKey ?? activeKey

  return (
    <div className="store-dock-viewport">
      <nav className="store-dock store-glass" aria-label={t("nav.main")}>
        <div ref={dockRef} className="store-dock-track">
          {navItems.map((item, index) => {
            const Icon = item.icon
            const isActive = activeKey === item.key
            const isPreviewed = visualActiveKey === item.key

            return (
              <button
                key={item.key}
                ref={(element) => { itemRefs.current[index] = element }}
                type="button"
                onClick={() => handleDockAction(item.key)}
                aria-label={item.label}
                aria-current={isActive ? "page" : undefined}
                aria-pressed={isActive}
                className={`store-dock-item text-muted-foreground${isPreviewed ? " text-foreground" : ""}`}
              >
                <span className="store-dock-item-content">
                  <Icon className="size-[18px]" strokeWidth={1.9} aria-hidden="true" />
                  <span className="store-dock-label">{item.label}</span>
                </span>
              </button>
            )
          })}

          {metrics && activeKey && (
            <motion.div
              aria-hidden="true"
              role="presentation"
              tabIndex={-1}
              className="store-dock-active"
              style={{ left: metrics.minX, width: metrics.indicatorWidth, x: indicatorX }}
              drag="x"
              dragConstraints={{ left: 0, right: metrics.maxX }}
              dragElastic={0}
              dragMomentum={false}
              onTap={handleIndicatorTap}
              onPointerDown={handleIndicatorPointerDown}
              onPointerUp={finishDrag}
              onPointerCancel={finishDrag}
              onDragStart={handleDragStart}
              onDrag={updateDragPreview}
              onDragEnd={finishDrag}
            />
          )}
        </div>
      </nav>
    </div>
  )
}
