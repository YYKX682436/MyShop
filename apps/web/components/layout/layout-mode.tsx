"use client"

import { useEffect, useState } from "react"
import { Monitor } from "lucide-react"

import { Button } from "@/components/ui/button"
import { useLocale } from "@/lib/context"
import { cn } from "@/lib/utils"

export type LayoutPreference = "auto" | "desktop"

const STORAGE_KEY = "myshop-layout-preference"
const DEVICE_VIEWPORT = "width=device-width, initial-scale=1, viewport-fit=cover"
const DESKTOP_VIEWPORT = "width=1024, initial-scale=1, viewport-fit=cover"
const LAYOUT_CHANGE_EVENT = "myshop:layout-preference-change"

function isSmallTouchScreen(): boolean {
  return typeof window !== "undefined"
    && navigator.maxTouchPoints > 0
    && (window.screen?.width ?? window.innerWidth) <= 640
}

function isExplicitDesktopRequest(): boolean {
  if (typeof window === "undefined" || !isSmallTouchScreen() || window.innerWidth >= 768) return false
  const ua = navigator.userAgent || ""
  const clientHints = (navigator as Navigator & { userAgentData?: { mobile?: boolean } }).userAgentData
  const mobileUa = /\biPhone\b|\biPod\b|\bAndroid\b.*\bMobile\b/i.test(ua)
  const androidUa = /\bAndroid\b/i.test(ua)
  const desktopUa = /\bMacintosh\b|\bWindows NT\b|\bX11\b|\bCrOS x86_64\b/i.test(ua)
  const desktopHint = clientHints?.mobile === false && !androidUa
  return !mobileUa && (desktopUa || desktopHint)
}

function setViewport(mode: LayoutPreference): void {
  const viewport = document.querySelector<HTMLMetaElement>('meta[name="viewport"]')
  if (!viewport) return

  if (mode === "desktop" && isSmallTouchScreen() && window.innerWidth < 768) {
    viewport.content = DESKTOP_VIEWPORT
    viewport.dataset.myshopDesktopWidth = "true"
    return
  }

  if (mode === "auto" && viewport.dataset.myshopDesktopWidth === "true") {
    viewport.content = DEVICE_VIEWPORT
    delete viewport.dataset.myshopDesktopWidth
  }
}

export function applyLayoutPreference(mode: LayoutPreference, persist = true): void {
  document.documentElement.dataset.layoutMode = mode
  setViewport(mode)
  if (persist) {
    try { localStorage.setItem(STORAGE_KEY, mode) } catch { /* Keep the current page preference for this session. */ }
  }
  window.dispatchEvent(new Event(LAYOUT_CHANGE_EVENT))
}

function readPreference(): LayoutPreference | null {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    return saved === "desktop" || saved === "auto" ? saved : null
  } catch {
    return null
  }
}

export function LayoutModeController() {
  useEffect(() => {
    const saved = readPreference()
    const bootDetectedDesktop = document.documentElement.dataset.layoutMode === "desktop"
    const mode = saved ?? (bootDetectedDesktop || isExplicitDesktopRequest() ? "desktop" : "auto")
    document.documentElement.dataset.layoutMode = mode
  }, [])
  return null
}

export function useLayoutPreference() {
  const [mode, setMode] = useState<LayoutPreference>("auto")

  useEffect(() => {
    const sync = () => setMode(document.documentElement.dataset.layoutMode === "desktop" ? "desktop" : "auto")
    sync()
    window.addEventListener(LAYOUT_CHANGE_EVENT, sync)
    return () => window.removeEventListener(LAYOUT_CHANGE_EVENT, sync)
  }, [])

  return { mode, setMode: applyLayoutPreference }
}

export function LayoutModePreferenceButton({ className }: { className?: string }) {
  const { t } = useLocale()
  const { mode, setMode } = useLayoutPreference()
  const nextMode = mode === "desktop" ? "auto" : "desktop"

  return (
    <Button
      type="button"
      variant="ghost"
      className={cn("gap-2", className)}
      aria-pressed={mode === "desktop"}
      onClick={() => setMode(nextMode)}
    >
      <Monitor className="size-4 shrink-0" aria-hidden="true" />
      <span>{t(mode === "desktop" ? "layout.switchToAuto" : "layout.switchToDesktop")}</span>
    </Button>
  )
}
