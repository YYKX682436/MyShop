"use client"

import { useCallback, useLayoutEffect, useRef, useState } from "react"
import { usePathname, useRouter } from "next/navigation"

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { useAuth, useLocale } from "@/lib/context"
import { productApi } from "@/services/api"
import type { ProductDetail } from "@/types"

type AgentNotice = {
  text: string
  showInDetail: boolean
  version: number
  viewerId: string | null
}

function readNotice(product: ProductDetail): AgentNotice | null {
  const text = product.agent_notice_text
  if (!text?.trim()) return null
  return {
    text,
    showInDetail: product.agent_notice_show_in_detail === true,
    version: product.agent_notice_version ?? 0,
    viewerId: product.agent_notice_viewer_id ?? null,
  }
}

function storageKey(site: string, userId: string, productId: string, version: number) {
  return `myshop:agent-notice:${[site, userId, productId, String(version)]
    .map((part) => encodeURIComponent(part))
    .join(":")}`
}

export function AgentProductNotice({ product }: { product: ProductDetail }) {
  const { user, authLoaded } = useAuth()
  const { t } = useLocale()
  const router = useRouter()
  const pathname = usePathname()
  const [noticeState, setNoticeState] = useState(() => ({ productId: product.id, notice: readNotice(product) }))
  const notice = noticeState.productId === product.id ? noticeState.notice : readNotice(product)
  const [open, setOpen] = useState(false)
  const observedViewer = useRef<string | null>(null)
  const observedViewerId = useRef<string | null>(null)
  const activeRouteRef = useRef<boolean | null>(null)
  const currentProductRef = useRef(product.id)
  const currentViewer = `${user?.id ?? "guest"}:${user?.role ?? ""}:${user?.user_level_id ?? ""}`
  const productPath = `/product/${product.id}`
  const activeProductRoute = pathname === productPath
  const serverNoticeSignature = JSON.stringify([
    product.id,
    product.agent_notice_text ?? null,
    product.agent_notice_show_in_detail ?? false,
    product.agent_notice_version ?? null,
    product.agent_notice_viewer_id ?? null,
  ])
  const serverNoticeSignatureRef = useRef(serverNoticeSignature)

  useLayoutEffect(() => {
    currentProductRef.current = product.id
  }, [product.id])

  const loadCurrentNotice = useCallback((viewer: string, productId: string) => {
    void productApi.getDetail(productId).then((freshProduct) => {
      if (observedViewer.current === viewer && currentProductRef.current === productId) {
        setNoticeState({ productId, notice: readNotice(freshProduct) })
      }
    }).catch(() => {
      // Keep a cleared notice when identity refresh fails; never reuse another viewer's data.
    })
  }, [])

  useLayoutEffect(() => {
    if (!authLoaded) return
    if (observedViewer.current === null) {
      observedViewer.current = currentViewer
      if (notice?.viewerId && notice.viewerId !== user?.id) {
        setNoticeState({ productId: product.id, notice: null })
        setOpen(false)
        if (activeProductRoute) router.refresh()
      }
      observedViewerId.current = user?.id ?? null
      return
    }
    if (observedViewer.current === currentViewer) return

    const sameUser = Boolean(user?.id && observedViewerId.current === user.id)
    observedViewer.current = currentViewer
    observedViewerId.current = user?.id ?? null
    setNoticeState({ productId: product.id, notice: null })
    setOpen(false)
    if (sameUser) loadCurrentNotice(currentViewer, product.id)
    if (activeProductRoute) router.refresh()
  }, [activeProductRoute, authLoaded, currentViewer, loadCurrentNotice, notice?.viewerId, product.id, router, user?.id])

  useLayoutEffect(() => {
    if (serverNoticeSignatureRef.current === serverNoticeSignature) return
    serverNoticeSignatureRef.current = serverNoticeSignature
    setNoticeState({ productId: product.id, notice: readNotice(product) })
  }, [product, product.id, serverNoticeSignature])

  useLayoutEffect(() => {
    const wasActive = activeRouteRef.current
    activeRouteRef.current = activeProductRoute
    if (wasActive === null || !activeProductRoute || wasActive) return
    if (authLoaded && user?.id) router.refresh()
  }, [activeProductRoute, authLoaded, product.id, router, user?.id])

  const viewerMatchesNotice = !authLoaded || Boolean(user?.id && user.id === notice?.viewerId)
  const noticeKey = typeof window !== "undefined" && authLoaded && user?.id && notice && viewerMatchesNotice
    ? storageKey(window.location.origin, user.id, product.id, notice.version)
    : null

  useLayoutEffect(() => {
    if (!activeProductRoute || !authLoaded || !user?.id || !noticeKey) {
      setOpen(false)
      return
    }
    try {
      setOpen(localStorage.getItem(noticeKey) !== "1")
    } catch {
      setOpen(true)
    }
  }, [activeProductRoute, authLoaded, noticeKey, user?.id])

  const showFixedNotice = Boolean(notice?.showInDetail && notice.text && viewerMatchesNotice && (!authLoaded || user?.id))

  return (
    <>
      {showFixedNotice && notice && (
        <aside className="rounded-surface border border-primary/15 bg-primary/5 p-4 sm:p-5" role="note">
          <h2 className="text-sm font-semibold text-foreground">{t("product.agentNoticeTitle")}</h2>
          <p className="mt-2 whitespace-pre-wrap break-words text-sm leading-6 text-muted-foreground [overflow-wrap:anywhere]">
            {notice.text}
          </p>
        </aside>
      )}

      <AlertDialog open={open} onOpenChange={setOpen}>
        <AlertDialogContent className="gap-5">
          <AlertDialogHeader>
            <AlertDialogTitle>{t("product.agentNoticeTitle")}</AlertDialogTitle>
          </AlertDialogHeader>
          <AlertDialogDescription className="max-h-[55vh] overflow-y-auto whitespace-pre-wrap break-words leading-6 [overflow-wrap:anywhere]">
            {notice?.text || ""}
          </AlertDialogDescription>
          <AlertDialogFooter className="!flex-row !justify-between gap-2 sm:space-x-0">
            <AlertDialogCancel
              className="mt-0 min-h-11 flex-1 whitespace-normal px-2 text-center"
              onClick={() => {
                if (!noticeKey) return
                try { localStorage.setItem(noticeKey, "1") } catch { /* The current dialog still closes. */ }
              }}
            >
              {t("product.agentNoticeDontShowAgain")}
            </AlertDialogCancel>
            <AlertDialogAction className="min-h-11 flex-1 whitespace-normal px-2 text-center">
              {t("product.agentNoticeAcknowledge")}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  )
}
