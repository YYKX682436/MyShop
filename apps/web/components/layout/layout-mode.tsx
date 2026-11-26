"use client"

import { useEffect, useState } from "react"
import { Monitor } from "lucide-react"

import { Button } from "@/components/ui/button"
import { useLocale } from "@/lib/context"
import { cn } from "@/lib/utils"

export type LayoutPreference = "auto" | "desktop"
export type LayoutSource = "auto" | "native" | "browser" | "user"

interface LayoutState {
  preference: LayoutPreference
  source: LayoutSource
  canToggle: boolean
}

const REQUEST_EVENT = "myshop:layout-preference-request"
const CHANGE_EVENT = "myshop:layout-preference-change"

function readLayoutState(): LayoutState {
  if (typeof document === "undefined") return { preference: "auto", source: "auto", canToggle: false }
  const root = document.documentElement
  const preference = root.dataset.layoutPreference === "desktop" ? "desktop" : "auto"
  const rawSource = root.dataset.layoutSource
  const source: LayoutSource = rawSource === "native" || rawSource === "browser" || rawSource === "user" ? rawSource : "auto"
  return { preference, source, canToggle: root.dataset.layoutCanToggle === "true" }
}

function requestLayoutPreference(preference: LayoutPreference): void {
  if (typeof window === "undefined" || !document.documentElement.dataset.layoutPreference) return
  window.dispatchEvent(new CustomEvent(REQUEST_EVENT, { detail: { preference } }))
}

export function useLayoutPreference() {
  const [state, setState] = useState<LayoutState>({ preference: "auto", source: "auto", canToggle: false })

  useEffect(() => {
    const sync = () => setState(readLayoutState())
    sync()
    window.addEventListener(CHANGE_EVENT, sync)
    return () => window.removeEventListener(CHANGE_EVENT, sync)
  }, [])

  return { ...state, setMode: requestLayoutPreference }
}

export function LayoutModePreferenceButton({ className }: { className?: string }) {
  const { t } = useLocale()
  const { preference, canToggle, setMode } = useLayoutPreference()
  if (!canToggle) return null

  const restoringAuto = preference === "desktop"
  const nextMode = restoringAuto ? "auto" : "desktop"

  return (
    <Button
      type="button"
      variant="ghost"
      className={cn("gap-2", className)}
      aria-pressed={restoringAuto}
      onClick={() => setMode(nextMode)}
    >
      <Monitor className="size-4 shrink-0" aria-hidden="true" />
      <span>{t(restoringAuto ? "layout.switchToAuto" : "layout.switchToDesktop")}</span>
    </Button>
  )
}
