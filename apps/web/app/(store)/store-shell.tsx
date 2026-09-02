"use client"

import React, { useState, useEffect, useCallback } from "react"
import { usePathname } from "next/navigation"
import { Wrench } from "lucide-react"
import ReactMarkdown from "react-markdown"
import { StoreHeader } from "@/components/layout/store-header"
import { StoreFooter } from "@/components/layout/store-footer"
import { VisitTracker } from "@/components/store/visit-tracker"
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { useSiteConfig, useAuth, useLocale } from "@/lib/context"
import { ContactSupport } from "@/components/shared/contact-methods"
import { isAdminRole } from "@/lib/hooks"

function AnnouncementBar() {
  const { config } = useSiteConfig()

  if (!config?.announcement_enabled || !config.announcement) return null

  return (
    <div className="overflow-hidden border-b border-primary/15 bg-primary/[0.06] text-primary">
      <div className="animate-marquee whitespace-nowrap py-2 text-xs font-medium sm:text-sm">
        <span className="mx-8">{config.announcement}</span>
        <span className="mx-8">{config.announcement}</span>
      </div>
    </div>
  )
}

function MaintenancePage() {
  const { t } = useLocale()
  const { config } = useSiteConfig()

  return (
    <div className="flex min-h-screen flex-col items-center justify-center bg-background px-4">
      <div className="flex flex-col items-center text-center">
        <div className="mb-6 flex h-20 w-20 items-center justify-center rounded-full bg-muted">
          <Wrench className="h-10 w-10 text-muted-foreground" />
        </div>
        <h1 className="mb-3 text-2xl font-bold text-foreground">{t("maintenance.title")}</h1>
        <p className="mb-6 max-w-md text-sm text-muted-foreground">
          {config?.maintenance_message || t("maintenance.description")}
        </p>
        <ContactSupport
          title={t("maintenance.needHelp")}
          email={config?.contact_email}
          items={config?.contact_items}
          emailLabel={t("maintenance.contactSupport")}
        />
      </div>
    </div>
  )
}

function MaintenanceAdminBanner() {
  const { t } = useLocale()

  return (
    <div className="border-b border-amber-500/15 bg-amber-500/[0.07] text-amber-800 dark:text-amber-300">
      <div className="mx-auto max-w-[1440px] px-4 py-2 text-center text-xs font-medium">
        {t("maintenance.adminBanner")}
      </div>
    </div>
  )
}

const POPUP_DISMISSED_KEY = "popup_dismissed"

function PopupAnnouncement() {
  const { config } = useSiteConfig()
  const [open, setOpen] = useState(false)

  useEffect(() => {
    if (
      config?.popup_enabled &&
      config.popup_content &&
      !sessionStorage.getItem(POPUP_DISMISSED_KEY)
    ) {
      setOpen(true)
    }
  }, [config?.popup_enabled, config?.popup_content])

  const handleClose = useCallback(() => {
    setOpen(false)
    sessionStorage.setItem(POPUP_DISMISSED_KEY, "1")
  }, [])

  if (!open) return null

  return (
    <Dialog open={open} onOpenChange={(nextOpen) => { if (!nextOpen) handleClose() }}>
      <DialogContent className="max-w-lg gap-0 p-0">
        <DialogHeader className="border-b border-border px-6 py-4 pr-12">
          <DialogTitle className="text-base">公告</DialogTitle>
        </DialogHeader>
        <div className="px-6 py-4">
          <div className="prose prose-sm max-w-none text-muted-foreground dark:prose-invert">
            <ReactMarkdown>{config!.popup_content!}</ReactMarkdown>
          </div>
        </div>
        <DialogFooter className="border-t border-border px-6 py-3">
          <button
            type="button"
            className="rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors"
            onClick={handleClose}
          >
            我知道了
          </button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

/** Routes that are always accessible even in maintenance mode */
const MAINTENANCE_EXEMPT_PATHS = ["/login", "/register"]

interface StoreShellProps {
  siteName: string
  children: React.ReactNode
}

export function StoreShell({ siteName, children }: StoreShellProps) {
  const { config, isLoading } = useSiteConfig()
  const { user, isLoggedIn, authLoaded } = useAuth()
  const pathname = usePathname()

  // 维护模式判断需要 config + auth 都就绪才能准确决策
  const configReady = !isLoading && !!config
  const isExempt = MAINTENANCE_EXEMPT_PATHS.includes(pathname)
  const isAdmin = authLoaded && isLoggedIn && isAdminRole(user?.role)
  const isMaintenance = configReady && config?.maintenance_enabled

  // Maintenance mode: block non-admin users on non-exempt pages
  // auth 未就绪时暂不渲染内容，避免管理员被误拦（auth 读 localStorage，通常 <1 帧）
  if (isMaintenance && !isExempt) {
    if (!authLoaded) return null
    if (!isAdmin) return <MaintenancePage />
  }

  return (
    <div className="flex min-h-screen flex-col bg-background">
      <VisitTracker />
      {isMaintenance && isAdmin && <MaintenanceAdminBanner />}
      <AnnouncementBar />
      <StoreHeader siteName={siteName} />
      <main className="mx-auto w-full max-w-[1440px] flex-1 px-4 py-8 sm:px-6 lg:px-8">{children}</main>
      <StoreFooter />
      <PopupAnnouncement />
    </div>
  )
}
