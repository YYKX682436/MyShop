"use client"

import { useCallback, useEffect, useMemo, useRef, useState, useTransition } from "react"
import { usePathname, useRouter } from "next/navigation"
import { House, ReceiptText, UserRound, type LucideIcon } from "lucide-react"
import { animate, motion, useMotionValue, useReducedMotion } from "motion/react"
import { useLocale } from "@/lib/context"
import type { TranslationKey } from "@/lib/i18n"
import { getStorefrontDockAction } from "@/lib/storefront-dock"
import { getStorefrontActiveTab, isStorefrontDockVisible, normalizeStorefrontPath, type StorefrontNavKey } from "@/lib/storefront-routes"
import { cn } from "@/lib/utils"

type DockKey = StorefrontNavKey

interface StoreDockItemDefinition {
  key: DockKey
  labelKey: TranslationKey
  icon: LucideIcon
}

const DOCK_ITEM_DEFINITIONS: readonly StoreDockItemDefinition[] = [
  { key: "home", labelKey: "nav.home", icon: House },
  { key: "orders", labelKey: "nav.orders", icon: ReceiptText },
  { key: "me", labelKey: "nav.me", icon: UserRound },
]

interface DockMetrics {
  slotWidth: number
  slotLefts: number[]
  slotCenters: number[]
  minX: number
  maxX: number
}

function getNearestDockIndex(center: number, slotCenters: readonly number[]): number {
  if (slotCenters.length === 0) return 0
  let nearestIndex = 0
  let minDistance = Math.abs(center - slotCenters[0])
  for (let i = 1; i < slotCenters.length; i++) {
    const dist = Math.abs(center - slotCenters[i])
    if (dist < minDistance) {
      nearestIndex = i
      minDistance = dist
    }
  }
  return nearestIndex
}

export function StoreBottomNav() {
  const pathname = usePathname()
  const router = useRouter()
  const reducedMotion = useReducedMotion() ?? false
  const [isNavigationPending, startNavigationTransition] = useTransition()
  const { t } = useLocale()

  const isVisible = isStorefrontDockVisible(pathname)
  const activeKey = isVisible ? getStorefrontActiveTab(pathname) : null

  const dockRef = useRef<HTMLDivElement>(null)
  const itemRefs = useRef<Array<HTMLButtonElement | null>>([])
  const isDraggingRef = useRef(false)
  const dragMovedRef = useRef(false)
  const suppressTapRef = useRef(false)
  const animationRef = useRef<ReturnType<typeof animate> | null>(null)
  const pendingTargetRef = useRef<string | null>(null)

  const indicatorX = useMotionValue(0)
  const [metrics, setMetrics] = useState<DockMetrics | null>(null)
  const [dragPreviewKey, setDragPreviewKey] = useState<DockKey | null>(null)

  const navItems = useMemo(
    () => DOCK_ITEM_DEFINITIONS.map((item) => ({ ...item, label: t(item.labelKey) })),
    [t],
  )

  // Measure dock layout
  const measureDock = useCallback(() => {
    const dock = dockRef.current
    if (!dock) return
    const elements = itemRefs.current.slice(0, navItems.length)
    if (elements.some((el) => !el)) return

    const dockRect = dock.getBoundingClientRect()
    const slotRects = elements.map((el) => el!.getBoundingClientRect())
    const slotLefts = slotRects.map((r) => r.left - dockRect.left)
    const slotCenters = slotRects.map((r) => r.left + r.width / 2)
    const minX = slotLefts[0]
    const maxX = slotLefts[slotLefts.length - 1] - minX
    const slotWidth = slotRects[0].width

    if (!Number.isFinite(minX) || !Number.isFinite(maxX) || slotWidth <= 0) return

    setMetrics((prev) => {
      if (
        prev &&
        Math.abs(prev.slotWidth - slotWidth) < 0.5 &&
        Math.abs(prev.minX - minX) < 0.5 &&
        Math.abs(prev.maxX - maxX) < 0.5
      ) {
        return prev
      }
      return { slotWidth, slotLefts, slotCenters, minX, maxX }
    })
  }, [navItems.length])

  useEffect(() => {
    if (!isVisible) return
    measureDock()
    const dock = dockRef.current
    if (!dock) return
    const observer = new ResizeObserver(() => measureDock())
    observer.observe(dock)
    window.addEventListener("resize", measureDock)
    return () => {
      observer.disconnect()
      window.removeEventListener("resize", measureDock)
    }
  }, [isVisible, measureDock])

  // Move indicator to a target offset
  const snapIndicator = useCallback(
    (target: number, onComplete?: () => void) => {
      animationRef.current?.stop()
      animationRef.current = null

      if (reducedMotion) {
        indicatorX.set(target)
        onComplete?.()
        return
      }

      const anim = animate(indicatorX, target, {
        type: "spring",
        stiffness: 480,
        damping: 38,
        mass: 0.5,
      })
      animationRef.current = anim
      anim.then(() => {
        if (animationRef.current === anim) {
          animationRef.current = null
          onComplete?.()
        }
      })
    },
    [indicatorX, reducedMotion],
  )

  const activeIndex = activeKey ? navItems.findIndex((item) => item.key === activeKey) : 0

  const getOffsetForIndex = useCallback(
    (idx: number, m: DockMetrics) => {
      if (idx < 0 || idx >= m.slotLefts.length) return 0
      return m.slotLefts[idx] - m.minX
    },
    [],
  )

  // Clear pending target when destination is reached
  useEffect(() => {
    if (pendingTargetRef.current) {
      if (normalizeStorefrontPath(pathname) === normalizeStorefrontPath(pendingTargetRef.current)) {
        pendingTargetRef.current = null
      }
    }
  }, [pathname])

  // Settle navigation intent and recover indicator when transition completes
  useEffect(() => {
    if (!isNavigationPending && pendingTargetRef.current) {
      const target = pendingTargetRef.current
      pendingTargetRef.current = null
      if (metrics && activeKey && normalizeStorefrontPath(pathname) !== normalizeStorefrontPath(target)) {
        snapIndicator(getOffsetForIndex(activeIndex, metrics))
      }
    }
  }, [isNavigationPending, pathname, activeKey, activeIndex, metrics, getOffsetForIndex, snapIndicator])

  // Keep indicator in sync with authoritative pathname when idle
  useEffect(() => {
    if (!metrics || isDraggingRef.current || pendingTargetRef.current) return
    const targetOffset = getOffsetForIndex(activeIndex, metrics)
    snapIndicator(targetOffset)
    setDragPreviewKey(null)
  }, [activeIndex, getOffsetForIndex, metrics, snapIndicator])

  // Navigate immediately to a dock item, accounting for any in-flight navigation intent
  const navigateToKey = useCallback(
    (key: DockKey): boolean => {
      const currentDestination = pendingTargetRef.current ?? pathname
      const action = getStorefrontDockAction(key, currentDestination)
      if (!action.href) return false
      const targetPathname = action.href
      pendingTargetRef.current = targetPathname

      const targetIndex = navItems.findIndex((item) => item.key === key)
      if (metrics && targetIndex >= 0) {
        snapIndicator(getOffsetForIndex(targetIndex, metrics))
      }

      try {
        startNavigationTransition(() => {
          router.replace(targetPathname)
        })
      } catch {
        pendingTargetRef.current = null
        if (metrics && activeKey) {
          snapIndicator(getOffsetForIndex(activeIndex, metrics))
        }
      }
      return true
    },
    [activeKey, activeIndex, getOffsetForIndex, metrics, navItems, pathname, router, snapIndicator, startNavigationTransition],
  )

  const requestDockAction = useCallback(
    (key: DockKey) => {
      if (!activeKey) return
      navigateToKey(key)
    },
    [activeKey, navigateToKey],
  )

  const handleDragStart = useCallback(() => {
    isDraggingRef.current = true
    dragMovedRef.current = false
    suppressTapRef.current = false
    animationRef.current?.stop()
  }, [])

  const updateDragPreview = useCallback(() => {
    if (!metrics || !isDraggingRef.current) return
    dragMovedRef.current = true
    const currentCenter = metrics.slotCenters[0] + indicatorX.get()
    const nearestIndex = getNearestDockIndex(currentCenter, metrics.slotCenters)
    const key = navItems[nearestIndex]?.key
    if (key) setDragPreviewKey(key)
  }, [indicatorX, metrics, navItems])

  const finishDrag = useCallback(() => {
    if (!isDraggingRef.current) return
    isDraggingRef.current = false

    if (!metrics || !dragMovedRef.current) {
      dragMovedRef.current = false
      setDragPreviewKey(null)
      if (metrics) snapIndicator(getOffsetForIndex(activeIndex, metrics))
      return
    }

    suppressTapRef.current = true
    const currentCenter = metrics.slotCenters[0] + indicatorX.get()
    const nearestIndex = getNearestDockIndex(currentCenter, metrics.slotCenters)
    const targetItem = navItems[nearestIndex]

    if (targetItem) {
      const navigated = navigateToKey(targetItem.key)
      if (!navigated) {
        snapIndicator(getOffsetForIndex(nearestIndex, metrics))
      }
    } else {
      snapIndicator(getOffsetForIndex(activeIndex, metrics))
    }

    setDragPreviewKey(null)
  }, [activeIndex, getOffsetForIndex, indicatorX, metrics, navItems, navigateToKey, snapIndicator])

  const cancelDrag = useCallback(() => {
    if (!isDraggingRef.current) return
    isDraggingRef.current = false
    dragMovedRef.current = false
    suppressTapRef.current = true
    setDragPreviewKey(null)
    if (metrics) snapIndicator(getOffsetForIndex(activeIndex, metrics))
  }, [activeIndex, getOffsetForIndex, metrics, snapIndicator])

  const handleIndicatorTap = useCallback(() => {
    if (suppressTapRef.current || dragMovedRef.current) {
      suppressTapRef.current = false
      dragMovedRef.current = false
      return
    }
    if (activeKey) requestDockAction(activeKey)
  }, [activeKey, requestDockAction])

  const visualActiveKey = dragPreviewKey ?? activeKey

  if (!isVisible || !activeKey) return null

  return (
    <div className="store-dock-viewport">
      <nav className="store-dock static-material" aria-label={t("nav.main")}>
        <div ref={dockRef} className="store-dock-track">
          {navItems.map((item, index) => {
            const Icon = item.icon
            const isActive = activeKey === item.key
            const isPreviewed = visualActiveKey === item.key

            return (
              <button
                key={item.key}
                ref={(element) => {
                  itemRefs.current[index] = element
                }}
                type="button"
                data-testid={`store-dock-${item.key}`}
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
                style={{ left: metrics.minX, width: metrics.slotWidth, x: indicatorX }}
              />
              <motion.div
                aria-hidden="true"
                role="presentation"
                className="store-dock-drag-hit-area"
                style={{ left: metrics.minX, width: metrics.slotWidth, x: indicatorX }}
                drag="x"
                dragConstraints={{ left: 0, right: metrics.maxX }}
                dragElastic={0}
                dragMomentum={false}
                onTap={handleIndicatorTap}
                onPointerCancel={cancelDrag}
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
