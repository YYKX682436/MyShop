"use client"

import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { usePathname, useRouter } from "next/navigation"
import { House, ReceiptText, UserRound, type LucideIcon } from "lucide-react"
import { animate, motion, useMotionValue, useReducedMotion } from "motion/react"
import { useLocale } from "@/lib/context"
import type { TranslationKey } from "@/lib/i18n"
import { getStorefrontDockAction } from "@/lib/storefront-dock"
import { getStorefrontActiveTab, isStorefrontDockVisible, type StorefrontNavKey } from "@/lib/storefront-routes"
import { cn } from "@/lib/utils"

type DockKey = StorefrontNavKey

interface StoreDockItemDefinition {
  key: DockKey
  labelKey: TranslationKey
  icon: LucideIcon
}

/**
 * The order and contents of the Dock live in one data list. Adding another
 * first-level destination only needs another definition plus its route action.
 */
const DOCK_ITEM_DEFINITIONS: readonly StoreDockItemDefinition[] = [
  { key: "home", labelKey: "nav.home", icon: House },
  { key: "orders", labelKey: "nav.orders", icon: ReceiptText },
  { key: "me", labelKey: "nav.me", icon: UserRound },
]

const SNAP_FALLBACK_MS = 480

interface StoreDockItem {
  key: DockKey
  label: string
  icon: LucideIcon
}

interface DockMetrics {
  slotCenters: number[]
  slotLefts: number[]
  capsuleWidth: number
  minX: number
  maxX: number
}

function getNearestDockIndex(center: number, slotCenters: readonly number[]): number | null {
  if (slotCenters.length === 0) return null

  let nearestIndex = 0
  let nearestDistance = Math.abs(center - slotCenters[0])

  for (let index = 1; index < slotCenters.length; index += 1) {
    const distance = Math.abs(center - slotCenters[index])
    if (distance < nearestDistance) {
      nearestIndex = index
      nearestDistance = distance
    }
  }

  return nearestIndex
}

function getIndicatorOffset(key: DockKey | null, items: readonly StoreDockItem[], metrics: DockMetrics): number {
  const index = key ? items.findIndex((item) => item.key === key) : 0
  if (index < 0 || index >= metrics.slotLefts.length) return 0
  return metrics.slotLefts[index] - metrics.minX
}

export function StoreBottomNav() {
  const pathname = usePathname()
  const router = useRouter()
  const reducedMotion = useReducedMotion() ?? false
  const { t } = useLocale()
  const isVisible = isStorefrontDockVisible(pathname)
  const activeKey = isVisible ? getStorefrontActiveTab(pathname) : null

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

  const navItems = useMemo<StoreDockItem[]>(
    () => DOCK_ITEM_DEFINITIONS.map((item) => ({ ...item, label: t(item.labelKey) })),
    [t],
  )
  const itemCount = navItems.length

  const cancelSnap = useCallback(() => {
    snapAnimationRef.current?.stop()
    snapAnimationRef.current = null
    if (snapActionTimerRef.current !== null) {
      window.clearTimeout(snapActionTimerRef.current)
      snapActionTimerRef.current = null
    }
  }, [])

  const measureDock = useCallback(() => {
    if (!isVisible) return

    const dock = dockRef.current
    const elements = itemRefs.current.slice(0, itemCount)
    const rects = elements.filter((element): element is HTMLButtonElement => element !== null)
    if (!dock || itemCount === 0 || rects.length !== itemCount) return

    const dockRect = dock.getBoundingClientRect()
    const slotRects = rects.map((element) => element.getBoundingClientRect())
    const slotLefts = slotRects.map((rect) => rect.left - dockRect.left)
    const slotCenters = slotRects.map((rect) => rect.left + rect.width / 2)
    const minX = slotLefts[0]
    const maxX = slotLefts[slotLefts.length - 1] - minX
    const capsuleWidth = slotRects[0].width

    if (!Number.isFinite(minX) || !Number.isFinite(maxX) || !Number.isFinite(capsuleWidth)) return

    setMetrics((previous) => {
      const next = { slotCenters, slotLefts, capsuleWidth, minX, maxX }
      if (
        previous &&
        previous.capsuleWidth === next.capsuleWidth &&
        previous.minX === next.minX &&
        previous.maxX === next.maxX &&
        previous.slotLefts.every((value, index) => value === next.slotLefts[index]) &&
        previous.slotCenters.every((value, index) => value === next.slotCenters[index])
      ) {
        return previous
      }
      return next
    })
  }, [isVisible, itemCount])

  useEffect(() => {
    if (!isVisible) return

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
  }, [isVisible, measureDock])

  useEffect(() => {
    if (!metrics) return

    const currentOffset = indicatorX.get()
    const boundedOffset = Math.min(Math.max(currentOffset, 0), metrics.maxX)
    if (currentOffset !== boundedOffset) indicatorX.set(boundedOffset)

    if (draggingRef.current || !activeKey) return

    const target = getIndicatorOffset(activeKey, navItems, metrics)
    cancelSnap()
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
  }, [activeKey, cancelSnap, indicatorX, metrics, navItems, reducedMotion])

  useEffect(() => () => cancelSnap(), [cancelSnap])

  const snapIndicator = useCallback((target: number, onComplete?: () => void) => {
    cancelSnap()

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
  }, [cancelSnap, indicatorX, reducedMotion])

  const handleDockAction = useCallback((key: DockKey) => {
    cancelSnap()
    draggingRef.current = false
    dragMovedRef.current = false
    pendingActionRef.current = null
    setDragPreviewKey(null)

    const action = getStorefrontDockAction(key, pathname)
    if (action.href && action.href !== pathname) router.push(action.href)
  }, [cancelSnap, pathname, router])

  const completePendingAction = useCallback(() => {
    const key = pendingActionRef.current
    if (!key) return

    pendingActionRef.current = null
    setDragPreviewKey(null)
    handleDockAction(key)
  }, [handleDockAction])

  const requestDockAction = useCallback((key: DockKey) => {
    if (!activeKey) return

    cancelSnap()
    draggingRef.current = false
    dragMovedRef.current = false
    suppressTapRef.current = false

    if (!metrics) {
      handleDockAction(key)
      return
    }

    const targetOffset = getIndicatorOffset(key, navItems, metrics)
    pendingActionRef.current = key
    setDragPreviewKey(key)

    if (reducedMotion || Math.abs(indicatorX.get() - targetOffset) <= 1) {
      indicatorX.set(targetOffset)
      completePendingAction()
      return
    }

    snapIndicator(targetOffset, completePendingAction)
    snapActionTimerRef.current = window.setTimeout(() => {
      snapActionTimerRef.current = null
      completePendingAction()
    }, SNAP_FALLBACK_MS)
  }, [activeKey, cancelSnap, completePendingAction, handleDockAction, indicatorX, metrics, navItems, reducedMotion, snapIndicator])

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
    const nearestIndex = getNearestDockIndex(center, metrics.slotCenters)
    const key = nearestIndex === null ? null : navItems[nearestIndex]?.key
    if (key) setDragPreviewKey(key)
  }, [indicatorX, metrics, navItems])

  const finishDrag = useCallback(() => {
    if (!draggingRef.current || !metrics) return
    if (!dragMovedRef.current) {
      draggingRef.current = false
      return
    }

    draggingRef.current = false
    suppressTapRef.current = true

    const center = metrics.slotCenters[0] + indicatorX.get()
    const nearestIndex = getNearestDockIndex(center, metrics.slotCenters)
    const nearestKey = nearestIndex === null ? null : navItems[nearestIndex]?.key
    if (!nearestKey) {
      setDragPreviewKey(null)
      return
    }

    const targetOffset = getIndicatorOffset(nearestKey, navItems, metrics)
    pendingActionRef.current = nearestKey
    setDragPreviewKey(nearestKey)

    if (reducedMotion || Math.abs(indicatorX.get() - targetOffset) <= 1) {
      indicatorX.set(targetOffset)
      completePendingAction()
      return
    }

    snapIndicator(targetOffset, completePendingAction)
    snapActionTimerRef.current = window.setTimeout(() => {
      snapActionTimerRef.current = null
      completePendingAction()
    }, SNAP_FALLBACK_MS)
  }, [completePendingAction, indicatorX, metrics, navItems, reducedMotion, snapIndicator])

  const handleIndicatorPointerDown = useCallback(() => {
    if (!activeKey) return
    cancelSnap()
    pendingActionRef.current = null
    draggingRef.current = true
    dragMovedRef.current = false
    suppressTapRef.current = false
    setDragPreviewKey(activeKey)
  }, [activeKey, cancelSnap])

  const handleIndicatorTap = useCallback(() => {
    if (suppressTapRef.current || dragMovedRef.current) {
      suppressTapRef.current = false
      dragMovedRef.current = false
      return
    }

    draggingRef.current = false
    dragMovedRef.current = false
    if (activeKey) requestDockAction(activeKey)
  }, [activeKey, requestDockAction])

  const visualActiveKey = dragPreviewKey ?? activeKey

  if (!isVisible || activeKey !== "home") return null

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
                onClick={() => requestDockAction(item.key)}
                aria-label={item.label}
                aria-current={isActive ? "page" : undefined}
                aria-pressed={isActive}
                data-preview-active={isPreviewed ? "true" : "false"}
                className={cn(
                  "store-dock-item",
                  isPreviewed ? "text-foreground" : "text-muted-foreground",
                )}
              >
                <span className="store-dock-item-content">
                  <Icon
                    className="size-[18px]"
                    strokeWidth={isPreviewed ? 2.1 : 1.75}
                    aria-hidden="true"
                  />
                  <span className="store-dock-label">{item.label}</span>
                </span>
              </button>
            )
          })}

          {metrics && activeKey && (
            <>
              <motion.div
                aria-hidden="true"
                role="presentation"
                className="store-dock-active"
                style={{ left: metrics.minX, width: metrics.capsuleWidth, x: indicatorX }}
              />
              <motion.div
                aria-hidden="true"
                role="presentation"
                className="store-dock-drag-hit-area"
                style={{ left: metrics.minX, width: metrics.capsuleWidth, x: indicatorX }}
                drag="x"
                dragConstraints={{ left: 0, right: metrics.maxX }}
                dragElastic={0}
                dragMomentum={false}
                onTap={handleIndicatorTap}
                onPointerDown={handleIndicatorPointerDown}
                onPointerCancel={finishDrag}
                onDragStart={handleDragStart}
                onDrag={updateDragPreview}
                onDragEnd={finishDrag}
              />
            </>
          )}
        </div>
      </nav>
    </div>
  )
}
