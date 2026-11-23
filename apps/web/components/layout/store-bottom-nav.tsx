"use client"

import { useCallback, useEffect, useMemo, useRef, useState, useTransition } from "react"
import { usePathname, useRouter } from "next/navigation"
import { House, ReceiptText, MessageCircle, UserRound, type LucideIcon } from "lucide-react"
import { useReducedMotion } from "motion/react"
import { useLocale } from "@/lib/context"
import type { TranslationKey } from "@/lib/i18n"
import { getStorefrontDockAction } from "@/lib/storefront-dock"
import { getStorefrontActiveTab, isStorefrontDockVisible, normalizeStorefrontPath, type StorefrontNavKey } from "@/lib/storefront-routes"
import { cn } from "@/lib/utils"
import { useMessageSummary } from "@/components/store/message-summary"
import { StoreBottomNavWebGL, type StoreBottomNavWebGLHandle } from "./store-bottom-nav-webgl"

type DockKey = StorefrontNavKey

interface StoreDockItemDefinition {
  key: DockKey
  labelKey: TranslationKey
  icon: LucideIcon
}

const DOCK_ITEM_DEFINITIONS: readonly StoreDockItemDefinition[] = [
  { key: "home", labelKey: "nav.home", icon: House },
  { key: "orders", labelKey: "nav.orders", icon: ReceiptText },
  { key: "messages", labelKey: "nav.messages", icon: MessageCircle },
  { key: "me", labelKey: "nav.me", icon: UserRound },
]

const DOCK_HEIGHT = 64

export function StoreBottomNav() {
  const pathname = usePathname()
  const router = useRouter()
  const reducedMotion = useReducedMotion() ?? false
  const [, startNavigationTransition] = useTransition()
  const { t } = useLocale()
  const summary = useMessageSummary()
  const unread = summary.announcements.unread + summary.support.unread

  const isVisible = isStorefrontDockVisible(pathname)
  const activeKey = isVisible ? getStorefrontActiveTab(pathname) : null

  const dockRef = useRef<HTMLDivElement>(null)
  const pendingTargetRef = useRef<string | null>(null)
  const webglRef = useRef<StoreBottomNavWebGLHandle | null>(null)

  const [dockDimensions, setDockDimensions] = useState<{ width: number; height: number }>({ width: 0, height: 64 })
  const [webglAvailable, setWebglAvailable] = useState(false)
  const [isDark, setIsDark] = useState(false)

  useEffect(() => {
    const updateDark = () => setIsDark(document.documentElement.classList.contains("dark"))
    updateDark()
    const observer = new MutationObserver(updateDark)
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ["class"] })
    return () => observer.disconnect()
  }, [])

  const navItems = useMemo(
    () => DOCK_ITEM_DEFINITIONS.map((item) => ({ ...item, label: t(item.labelKey) })),
    [t],
  )

  const labels = useMemo<readonly [string, string, string, string]>(
    () => [
      navItems[0]?.label || "首页",
      navItems[1]?.label || "订单",
      navItems[2]?.label || "消息",
      navItems[3]?.label || "我的",
    ],
    [navItems],
  )

  const activeIndex = activeKey ? navItems.findIndex((item) => item.key === activeKey) : 0

  // Measure dock layout
  const measureDock = useCallback(() => {
    const dock = dockRef.current
    if (!dock) return
    const dockRect = dock.getBoundingClientRect()
    const width = Math.round(dockRect.width)
    if (width > 0) {
      setDockDimensions((prev) => (prev.width === width && prev.height === DOCK_HEIGHT ? prev : { width, height: DOCK_HEIGHT }))
    }
  }, [])

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

  // Clear pending target when destination is reached
  useEffect(() => {
    if (pendingTargetRef.current) {
      if (normalizeStorefrontPath(pathname) === normalizeStorefrontPath(pendingTargetRef.current)) {
        pendingTargetRef.current = null
      }
    }
  }, [pathname])

  // Navigate immediately to a dock item, accounting for any in-flight navigation intent
  const navigateToKey = useCallback(
    (key: DockKey): boolean => {
      const currentDestination = pendingTargetRef.current ?? pathname
      const action = getStorefrontDockAction(key, currentDestination)
      if (!action.href) return false
      const targetPathname = action.href
      pendingTargetRef.current = targetPathname

      const targetIndex = navItems.findIndex((item) => item.key === key)
      if (targetIndex >= 0) {
        webglRef.current?.selectTab(targetIndex)
      }

      try {
        startNavigationTransition(() => {
          router.replace(targetPathname)
        })
      } catch {
        pendingTargetRef.current = null
      }
      return true
    },
    [navItems, pathname, router, startNavigationTransition],
  )

  const requestDockAction = useCallback(
    (key: DockKey) => {
      if (!activeKey) return
      navigateToKey(key)
    },
    [activeKey, navigateToKey],
  )

  const finishDrag = useCallback(
    (targetIndex: number) => {
      const targetItem = navItems[targetIndex]
      if (targetItem) {
        navigateToKey(targetItem.key)
      }
    },
    [navItems, navigateToKey],
  )

  if (!isVisible || !activeKey) return null

  const isWebGLActive = webglAvailable && !reducedMotion

  return (
    <div className="store-dock-viewport">
      <nav
        ref={dockRef}
        className="store-dock static-material"
        data-webgl-active={isWebGLActive ? "true" : "false"}
        aria-label={t("nav.main")}
      >
        {!reducedMotion && dockDimensions.width > 0 && (
          <StoreBottomNavWebGL
            ref={webglRef}
            width={dockDimensions.width}
            height={dockDimensions.height}
            activeIndex={activeIndex}
            isDark={isDark}
            labels={labels}
            unread={unread}
            onSelectTab={finishDrag}
            onAvailabilityChange={setWebglAvailable}
          />
        )}

        {/* Accessible navigation layer */}
        <div className={cn("store-dock-track", isWebGLActive && "sr-only")}>
          {navItems.map((item) => {
            const Icon = item.icon
            const isActive = activeKey === item.key

            return (
              <button
                key={item.key}
                type="button"
                data-testid={`store-dock-${item.key}`}
                onClick={() => requestDockAction(item.key)}
                aria-label={item.key === "messages" && unread > 0 ? `${item.label}，${unread} 条未读` : item.label}
                aria-current={isActive ? "page" : undefined}
                aria-pressed={isActive}
                className={cn(
                  "store-dock-item",
                  isActive ? "text-primary font-semibold" : "text-muted-foreground",
                )}
              >
                <span className="store-dock-item-content">
                  <span className="relative">
                    <Icon className="size-[18px]" strokeWidth={isActive ? 2.1 : 1.75} aria-hidden="true" />
                    {item.key === "messages" && unread > 0 && (
                      <span
                        aria-hidden="true"
                        className="absolute -right-2 -top-1 min-w-3 rounded-full bg-destructive px-1 text-center text-[9px] leading-3 text-white"
                      >
                        {unread > 99 ? "99+" : unread}
                      </span>
                    )}
                  </span>
                  <span className="store-dock-label">{item.label}</span>
                </span>
              </button>
            )
          })}
        </div>
      </nav>
    </div>
  )
}
