"use client"

import { useRouter } from "next/navigation"
import { useCallback, useEffect, useRef } from "react"
import type { ReactNode } from "react"
import { toast } from "sonner"
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
export function useRouteOverlayClose(dirty = false, enabled = true, blocked = false) {
  const router = useRouter()
  const { t } = useLocale()
  const dirtyRef = useRef(dirty)
  const promptRef = useRef(t("admin.unsavedChangesMessage"))
  const closingRef = useRef(false)
  const restoringRef = useRef(false)
  const blockedRef = useRef(blocked)
  const ignoredPopRef = useRef(false)
  const guardUrlRef = useRef("")
  const guardIdRef = useRef("")

  useEffect(() => {
    dirtyRef.current = dirty
  }, [dirty])

  useEffect(() => {
    blockedRef.current = blocked
  }, [blocked])

  useEffect(() => {
    promptRef.current = t("admin.unsavedChangesMessage")
  }, [t])

  useEffect(() => {
    if (!enabled || typeof window === "undefined") return

    const blockedNotice = () => toast.error("请等待图片上传完成")

    const handlePopState = () => {
      if (ignoredPopRef.current) { ignoredPopRef.current = false; return }
      if (blockedRef.current) {
        if (window.location.href === guardUrlRef.current) {
          window.history.pushState({ ...window.history.state, myshopUploadGuard: guardIdRef.current }, "", guardUrlRef.current)
        } else {
          window.history.forward()
        }
        blockedNotice()
        return
      }
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

    const handleLink = (event: MouseEvent) => {
      if (!blockedRef.current || event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
      const anchor = event.target instanceof Element ? event.target.closest("a[href]") : null
      if (!(anchor instanceof HTMLAnchorElement) || anchor.target && anchor.target !== "_self" || anchor.hasAttribute("download")) return
      const destination = new URL(anchor.href, window.location.href)
      if (destination.origin === window.location.origin && destination.pathname === window.location.pathname && destination.search === window.location.search) return
      event.preventDefault(); event.stopPropagation(); blockedNotice()
    }
    const handleBeforeUnload = (event: BeforeUnloadEvent) => {
      if (!blockedRef.current) return
      event.preventDefault()
      event.returnValue = ""
    }
    window.addEventListener("popstate", handlePopState)
    document.addEventListener("click", handleLink, true)
    window.addEventListener("beforeunload", handleBeforeUnload)
    return () => {
      window.removeEventListener("popstate", handlePopState)
      document.removeEventListener("click", handleLink, true)
      window.removeEventListener("beforeunload", handleBeforeUnload)
    }
  }, [enabled])

  useEffect(() => {
    if (!enabled || !blocked) return
    const guardId = globalThis.crypto.randomUUID()
    guardIdRef.current = guardId
    guardUrlRef.current = window.location.href
    // Back lands on the same route first, allowing the pending upload to keep
    // its mounted editor and selection while we restore the guard entry.
    window.history.pushState({ ...window.history.state, myshopUploadGuard: guardId }, "", guardUrlRef.current)
    return () => {
      if (window.history.state?.myshopUploadGuard === guardId) {
        ignoredPopRef.current = true
        window.history.back()
      }
    }
  }, [blocked, enabled])

  return useCallback((options: RouteOverlayCloseOptions = {}) => {
    if (!enabled) return
    if (blockedRef.current) { toast.error("请等待图片上传完成"); return }
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
  headerActions?: ReactNode
  dirty?: boolean
  blocked?: boolean
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
        <SheetHeader className="border-b border-border-subtle pl-5 pr-16 py-5 sm:pl-6 sm:pr-16">
          <SheetTitle className="tracking-tight">{title}</SheetTitle>
          {description && <SheetDescription className="break-all">{description}</SheetDescription>}
        </SheetHeader>
        <div className={cn("min-h-0 flex-1 overflow-y-auto px-5 py-5 sm:px-6", contentClassName)}>{children}</div>
        {footer && <div className="border-t border-border-subtle bg-surface-muted/45 px-5 py-4 sm:px-6">{footer}</div>}
      </SheetContent>
    </Sheet>
  )
}

export function WorkspaceSheet({ children, title, description, headerActions, dirty = false, blocked = false, onClose, open = true, className, contentClassName }: RouteSheetProps) {
  const { t } = useLocale()

  return (
    <Sheet open={open} onOpenChange={(nextOpen) => { if (!nextOpen) onClose() }}>
      <SheetContent
        side="right"
        data-testid="product-workspace-sheet"
        className={cn("flex h-full w-full max-w-none flex-col gap-0 p-0 sm:w-[88vw] lg:w-[64vw] lg:max-w-[1100px] xl:w-[60vw]", className)}
        onInteractOutside={(event) => {
          if (dirty || blocked) {
            event.preventDefault()
            onClose()
          }
        }}
      >
        <SheetHeader className="flex-col items-stretch gap-3 space-y-0 border-b border-border-subtle pl-5 pr-16 py-5 sm:flex-row sm:items-start sm:justify-between sm:gap-4 sm:pl-6 sm:pr-16">
          <div className="min-w-0 sm:flex-1">
            <SheetTitle className="tracking-tight">{title}</SheetTitle>
            {description && <SheetDescription className="mt-2 max-w-2xl">{description}</SheetDescription>}
          </div>
          <div data-testid="workspace-header-actions" className="flex min-w-0 max-w-full flex-wrap items-center gap-2 sm:flex-1 sm:justify-end">
            {headerActions}
            {dirty && <span className="admin-warning-badge hidden rounded-full px-2.5 py-0.5 text-xs font-medium sm:inline-flex">{t("admin.unsavedChanges")}</span>}
          </div>
        </SheetHeader>
        <div className={cn("min-h-0 flex-1 overflow-y-auto px-5 py-5 sm:px-6 sm:py-6", contentClassName)}>{children}</div>
      </SheetContent>
    </Sheet>
  )
}
