"use client"

import { useRouter } from "next/navigation"
import { useCallback, useEffect, useRef } from "react"
import type { ReactNode } from "react"
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from "@/components/ui/sheet"
import { useLocale } from "@/lib/context"
import { shouldConfirmRouteOverlayClose, type RouteOverlayCloseOptions } from "@/lib/route-overlay"
import { cn } from "@/lib/utils"

export type { RouteOverlayCloseOptions } from "@/lib/route-overlay"

/**
 * Intercepted admin routes already have the list route immediately behind
 * them. Closing therefore uses one normal router.back(). The popstate guard is
 * mounted only while the form is actually dirty, so clean inspectors never
 * add sentinel entries or delay navigation.
 */
export function useRouteOverlayClose(dirty = false, enabled = true) {
  const router = useRouter()
  const { t } = useLocale()
  const dirtyRef = useRef(dirty)
  const promptRef = useRef(t("admin.unsavedChangesMessage"))
  const closingRef = useRef(false)
  const restoringRef = useRef(false)

  useEffect(() => {
    dirtyRef.current = dirty
  }, [dirty])

  useEffect(() => {
    promptRef.current = t("admin.unsavedChangesMessage")
  }, [t])

  useEffect(() => {
    if (!enabled || !dirty || typeof window === "undefined") return

    const handlePopState = () => {
      if (closingRef.current) {
        closingRef.current = false
        return
      }

      if (restoringRef.current) {
        restoringRef.current = false
        return
      }

      if (dirtyRef.current && !window.confirm(promptRef.current)) {
        restoringRef.current = true
        window.history.forward()
        return
      }
    }

    window.addEventListener("popstate", handlePopState)
    return () => window.removeEventListener("popstate", handlePopState)
  }, [dirty, enabled])

  return useCallback((options: RouteOverlayCloseOptions = {}) => {
    if (!enabled) return
    if (shouldConfirmRouteOverlayClose(dirtyRef.current, options) && !window.confirm(promptRef.current)) return
    closingRef.current = true
    router.back()
  }, [enabled, router])
}

interface RouteSheetProps {
  children: ReactNode
  title: ReactNode
  description?: ReactNode
  footer?: ReactNode
  dirty?: boolean
  onClose: () => void
  open?: boolean
  className?: string
  contentClassName?: string
}

export function InspectorSheet({ children, title, description, footer, dirty = false, onClose, open = true, className, contentClassName }: RouteSheetProps) {
  return (
    <Sheet open={open} onOpenChange={(nextOpen) => { if (!nextOpen) onClose() }}>
      <SheetContent
        side="right"
        className={cn("flex h-full w-full max-w-none flex-col gap-0 p-0 sm:w-[80vw] sm:max-w-[640px] lg:w-[480px]", className)}
        onInteractOutside={(event) => {
          if (dirty) {
            event.preventDefault()
            onClose()
          }
        }}
      >
        <SheetHeader className="border-b border-border-subtle px-5 py-5 pr-14 sm:px-6">
          <SheetTitle className="tracking-tight">{title}</SheetTitle>
          {description && <SheetDescription className="break-all">{description}</SheetDescription>}
        </SheetHeader>
        <div className={cn("min-h-0 flex-1 overflow-y-auto px-5 py-5 sm:px-6", contentClassName)}>{children}</div>
        {footer && <div className="border-t border-border-subtle bg-surface-muted/45 px-5 py-4 sm:px-6">{footer}</div>}
      </SheetContent>
    </Sheet>
  )
}

export function WorkspaceSheet({ children, title, description, dirty = false, onClose, open = true, className, contentClassName }: RouteSheetProps) {
  const { t } = useLocale()

  return (
    <Sheet open={open} onOpenChange={(nextOpen) => { if (!nextOpen) onClose() }}>
      <SheetContent
        side="right"
        className={cn("flex h-full w-full max-w-none flex-col gap-0 p-0 sm:w-[88vw] lg:w-[64vw] lg:max-w-[1100px] xl:w-[60vw]", className)}
        onInteractOutside={(event) => {
          if (dirty) {
            event.preventDefault()
            onClose()
          }
        }}
      >
        <SheetHeader className="flex-row items-start justify-between border-b border-border-subtle px-5 py-5 pr-14 sm:px-7">
          <div className="min-w-0">
            <SheetTitle className="tracking-tight">{title}</SheetTitle>
            {description && <SheetDescription className="mt-2 max-w-2xl">{description}</SheetDescription>}
          </div>
          {dirty && <span className="hidden shrink-0 rounded-full bg-amber-500/10 px-2.5 py-1 text-xs font-medium text-amber-700 dark:text-amber-300 sm:inline-flex">{t("admin.unsavedChanges")}</span>}
        </SheetHeader>
        <div className={cn("min-h-0 flex-1 overflow-y-auto px-5 py-5 sm:px-7 sm:py-7", contentClassName)}>{children}</div>
      </SheetContent>
    </Sheet>
  )
}
