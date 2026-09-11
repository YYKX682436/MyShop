"use client"

import { useCallback, useEffect, useMemo, useRef, useState, useTransition } from "react"
import { usePathname, useRouter } from "next/navigation"
import { House, ReceiptText, UserRound, type LucideIcon } from "lucide-react"
import { animate, motion, useMotionValue, useReducedMotion } from "motion/react"
import { useLocale } from "@/lib/context"
import type { TranslationKey } from "@/lib/i18n"
import {
  getStorefrontDockAction,
  isCurrentStorefrontDockAction,
  resolveStorefrontDockNavigation,
  tryStartStorefrontDockNavigation,
  type StorefrontDockNavigationAttempt,
} from "@/lib/storefront-dock"
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
 * first-level destination only needs another definition plus its route
 * identity/action; the motion code does not assume three slots.
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

type PendingDockAction = StorefrontDockNavigationAttempt & {
  key: DockKey
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
  const [isNavigationPending, startNavigationTransition] = useTransition()
  const { t } = useLocale()
  const isVisible = isStorefrontDockVisible(pathname)
  const activeKey = isVisible ? getStorefrontActiveTab(pathname) : null

  const dockRef = useRef<HTMLDivElement>(null)
  const itemRefs = useRef<Array<HTMLButtonElement | null>>([])
  const draggingRef = useRef(false)
  const dragMovedRef = useRef(false)
  const suppressTapRef = useRef(false)
  const actionGenerationRef = useRef(0)
  const pendingActionRef = useRef<PendingDockAction | null>(null)
  const previousPathnameRef = useRef(pathname)
  const pathnameRef = useRef(pathname)
  const snapAnimationRef = useRef<ReturnType<typeof animate> | null>(null)
  const snapActionTimerRef = useRef<number | null>(null)
  const navigationTransitionRef = useRef<{ actionId: number; observedPending: boolean } | null>(null)
  const indicatorX = useMotionValue(0)
  const [metrics, setMetrics] = useState<DockMetrics | null>(null)
  const [dragPreviewKey, setDragPreviewKey] = useState<DockKey | null>(null)

  useEffect(() => {
    pathnameRef.current = pathname
  }, [pathname])

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

  const restoreIndicatorToActive = useCallback(() => {
    if (!metrics || !activeKey) return
    snapIndicator(getIndicatorOffset(activeKey, navItems, metrics))
  }, [activeKey, metrics, navItems, snapIndicator])

  useEffect(() => {
    if (!metrics) return

    const currentOffset = indicatorX.get()
    const boundedOffset = Math.min(Math.max(currentOffset, 0), metrics.maxX)
    if (currentOffset !== boundedOffset) indicatorX.set(boundedOffset)

    // A pending action owns the capsule until it either reaches the real
    // route or is explicitly invalidated. This prevents a resize/translation
    // render from snapping it back to the old route mid-action.
    if (draggingRef.current || pendingActionRef.current || !activeKey) return

    snapIndicator(getIndicatorOffset(activeKey, navItems, metrics))
  }, [activeKey, indicatorX, metrics, navItems, snapIndicator])

  const invalidateAction = useCallback((expectedActionId?: number): boolean => {
    if (
      expectedActionId !== undefined &&
      !isCurrentStorefrontDockAction(expectedActionId, actionGenerationRef.current)
    ) {
      return false
    }

    actionGenerationRef.current += 1
    pendingActionRef.current = null
    navigationTransitionRef.current = null
    cancelSnap()
    draggingRef.current = false
    dragMovedRef.current = false
    suppressTapRef.current = false
    setDragPreviewKey(null)
    return true
  }, [cancelSnap])

  const finalizePendingAction = useCallback((expectedActionId: number): boolean => {
    const pending = pendingActionRef.current
    if (
      !pending ||
      !isCurrentStorefrontDockAction(expectedActionId, actionGenerationRef.current) ||
      pending.id !== expectedActionId
    ) {
      return false
    }

    actionGenerationRef.current += 1
    pendingActionRef.current = null
    navigationTransitionRef.current = null
    cancelSnap()
    draggingRef.current = false
    dragMovedRef.current = false
    setDragPreviewKey(null)
    return true
  }, [cancelSnap])

  const abortPendingAction = useCallback((expectedActionId: number) => {
    if (!pendingActionRef.current || pendingActionRef.current.id !== expectedActionId) return
    if (!invalidateAction(expectedActionId)) return
    // pathname is authoritative. A failed or interrupted action always
    // returns the capsule to the active slot derived from that pathname.
    restoreIndicatorToActive()
  }, [invalidateAction, restoreIndicatorToActive])

  const completePendingAction = useCallback((expectedActionId: number) => {
    const pending = pendingActionRef.current
    if (
      !pending ||
      pending.id !== expectedActionId ||
      !isCurrentStorefrontDockAction(expectedActionId, actionGenerationRef.current) ||
      pending.phase !== "snapping"
    ) {
      return
    }

    const resolution = resolveStorefrontDockNavigation(pending, pathnameRef.current)
    if (resolution === "succeeded") {
      finalizePendingAction(expectedActionId)
      return
    }
    if (resolution === "interrupted") {
      abortPendingAction(expectedActionId)
      return
    }

    pending.phase = "navigating"
    cancelSnap()
    navigationTransitionRef.current = { actionId: expectedActionId, observedPending: false }

    const didStartNavigation = tryStartStorefrontDockNavigation(
      () => {
        startNavigationTransition(() => {
          router.replace(pending.targetPathname)
        })
      },
      () => {
        navigationTransitionRef.current = null
        abortPendingAction(expectedActionId)
      },
    )
    if (!didStartNavigation) return
  }, [abortPendingAction, cancelSnap, finalizePendingAction, router, startNavigationTransition])

  useEffect(() => {
    const pending = pendingActionRef.current
    const transition = navigationTransitionRef.current
    if (
      !pending ||
      pending.phase !== "navigating" ||
      !transition ||
      transition.actionId !== pending.id
    ) {
      return
    }

    if (isNavigationPending) {
      transition.observedPending = true
      return
    }

    // React can render the action's visual state before the transition has
    // exposed its first pending frame. Do not infer failure from that initial
    // false value. A settled transition is meaningful only after pending was
    // observed at least once.
    if (!transition.observedPending) return

    const actionId = pending.id
    queueMicrotask(() => {
      const current = pendingActionRef.current
      if (
        !current ||
        current.id !== actionId ||
        !isCurrentStorefrontDockAction(actionId, actionGenerationRef.current) ||
        current.phase !== "navigating"
      ) {
        return
      }

      const resolution = resolveStorefrontDockNavigation(current, pathnameRef.current, false)
      if (resolution === "succeeded") {
        finalizePendingAction(actionId)
      } else if (resolution === "interrupted" || resolution === "failed") {
        // A transition that settled without leaving the source route is a
        // genuine failure. Unlike the removed watchdog, this never fires
        // merely because a fixed amount of wall-clock time elapsed.
        abortPendingAction(actionId)
      }
    })
  }, [abortPendingAction, finalizePendingAction, isNavigationPending, pathname])

  const beginDockAction = useCallback((key: DockKey): number | null => {
    cancelSnap()
    navigationTransitionRef.current = null
    actionGenerationRef.current += 1
    const actionId = actionGenerationRef.current
    pendingActionRef.current = null
    draggingRef.current = false
    dragMovedRef.current = false

    const action = getStorefrontDockAction(key, pathnameRef.current)
    if (!action.href) {
      setDragPreviewKey(null)
      return null
    }

    pendingActionRef.current = {
      id: actionId,
      key,
      fromPathname: pathnameRef.current,
      targetPathname: action.href,
      phase: "snapping",
    }
    setDragPreviewKey(key)
    return actionId
  }, [cancelSnap])

  const snapAndComplete = useCallback((key: DockKey, actionId: number) => {
    if (!isCurrentStorefrontDockAction(actionId, actionGenerationRef.current)) return

    if (!metrics) {
      completePendingAction(actionId)
      return
    }

    const targetOffset = getIndicatorOffset(key, navItems, metrics)
    const complete = () => completePendingAction(actionId)
    if (reducedMotion || Math.abs(indicatorX.get() - targetOffset) <= 1) {
      indicatorX.set(targetOffset)
      complete()
      return
    }

    snapIndicator(targetOffset, complete)
    snapActionTimerRef.current = window.setTimeout(() => {
      snapActionTimerRef.current = null
      completePendingAction(actionId)
    }, SNAP_FALLBACK_MS)
  }, [completePendingAction, indicatorX, metrics, navItems, reducedMotion, snapIndicator])

  const requestDockAction = useCallback((key: DockKey) => {
    if (!activeKey) return

    suppressTapRef.current = false
    const actionId = beginDockAction(key)
    if (actionId === null) {
      restoreIndicatorToActive()
      return
    }

    snapAndComplete(key, actionId)
  }, [activeKey, beginDockAction, restoreIndicatorToActive, snapAndComplete])

  const handleDragStart = useCallback(() => {
    if (!activeKey) return
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
    if (!draggingRef.current) return
    if (!metrics) {
      // A drag that never got measurable slots cannot produce a valid drop.
      // Treat it as an interruption and restore the real active item.
      invalidateAction()
      restoreIndicatorToActive()
      return
    }

    if (!dragMovedRef.current) {
      draggingRef.current = false
      dragMovedRef.current = false
      setDragPreviewKey(null)
      restoreIndicatorToActive()
      return
    }

    const center = metrics.slotCenters[0] + indicatorX.get()
    const nearestIndex = getNearestDockIndex(center, metrics.slotCenters)
    const nearestKey = nearestIndex === null ? null : navItems[nearestIndex]?.key
    if (!nearestKey) {
      invalidateAction()
      suppressTapRef.current = true
      restoreIndicatorToActive()
      return
    }

    const actionId = beginDockAction(nearestKey)
    suppressTapRef.current = true
    if (actionId === null) {
      restoreIndicatorToActive()
      return
    }

    snapAndComplete(nearestKey, actionId)
  }, [beginDockAction, indicatorX, invalidateAction, metrics, navItems, restoreIndicatorToActive, snapAndComplete])

  const cancelDrag = useCallback(() => {
    // Once finishDrag has committed a nearest-slot drop, navigation owns the
    // action. A later pointer-cancel from that pointer must not undo it.
    if (!draggingRef.current || pendingActionRef.current?.phase === "navigating") return

    invalidateAction()
    suppressTapRef.current = true
    restoreIndicatorToActive()
  }, [invalidateAction, restoreIndicatorToActive])

  const handleIndicatorPointerDown = useCallback(() => {
    if (!activeKey) return
    invalidateAction()
    draggingRef.current = true
    dragMovedRef.current = false
    suppressTapRef.current = false
    setDragPreviewKey(activeKey)
  }, [activeKey, invalidateAction])

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

  useEffect(() => {
    const previousPathname = previousPathnameRef.current
    previousPathnameRef.current = pathname
    if (previousPathname === pathname) return

    const pending = pendingActionRef.current
    if (pending) {
      const resolution = resolveStorefrontDockNavigation(pending, pathname)
      if (resolution === "succeeded") {
        finalizePendingAction(pending.id)
      } else {
        abortPendingAction(pending.id)
      }
      return
    }

    // A route change without a pending Dock action still invalidates any
    // drag preview/animation left by the previous route.
    invalidateAction()
    restoreIndicatorToActive()
  }, [abortPendingAction, finalizePendingAction, invalidateAction, pathname, restoreIndicatorToActive])

  useEffect(() => () => {
    actionGenerationRef.current += 1
    pendingActionRef.current = null
    navigationTransitionRef.current = null
    cancelSnap()
  }, [cancelSnap])

  const visualActiveKey = dragPreviewKey ?? activeKey

  if (!isVisible || !activeKey) return null

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
