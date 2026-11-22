"use client"

import React, { useState, useEffect, useCallback } from "react"
import Link from "next/link"
import { usePathname } from "next/navigation"
import { Wrench } from "lucide-react"
import { StoreFooter } from "@/components/layout/store-footer"
import { StoreBottomNav } from "@/components/layout/store-bottom-nav"
import { StoreSearchPanel } from "@/components/layout/store-search-panel"
import { VisitTracker } from "@/components/store/visit-tracker"
import { MessageSummaryProvider } from "@/components/store/message-summary"
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { useSiteConfig, useAuth, useLocale } from "@/lib/context"
import { ContactSupport } from "@/components/shared/contact-methods"
import { MarkdownContent } from "@/components/shared/markdown-content"
import { isAdminRole } from "@/lib/hooks"
import { cn } from "@/lib/utils"
import { isStorefrontDockVisible, isStorefrontHeaderVisible } from "@/lib/storefront-routes"
import { announcementPopupKey } from "@/lib/announcement-popup"

function AnnouncementBar() {
  const { config } = useSiteConfig()

  if (!config?.announcement_enabled || !config.announcement) return null

  return (
    <div className="overflow-hidden border-b border-primary/15 bg-primary/[0.06] text-primary">
      <Link href={config.announcement_id ? `/messages/announcements/${config.announcement_id}` : "/messages/announcements"}
        className="mx-auto block max-w-[1440px] truncate px-4 py-2 text-center text-xs font-medium sm:px-6 sm:text-sm">
        {config.announcement}
      </Link>
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
        <Link href="/messages/support" className="mb-4 inline-flex min-h-11 items-center rounded-control bg-primary px-5 text-sm font-medium text-primary-foreground">联系主站客服</Link>
        <ContactSupport
          title={t("maintenance.needHelp")}
          email={config?.contact_email}
          items={config?.contact_items}
          customerService={config?.customer_service}
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

function PopupAnnouncement() {
  const { t } = useLocale()
  const { config } = useSiteConfig()
  const [open, setOpen] = useState(false)
  const key = announcementPopupKey(config?.announcement_site_id, config?.popup_id, config?.popup_version)

  useEffect(() => {
    let dismissed = false
    try { dismissed = !!key && localStorage.getItem(key) === "1" } catch { /* Storage can be disabled. */ }
    setOpen(!!(key && config?.popup_enabled && config.popup_content && !dismissed))
  }, [config?.popup_enabled, config?.popup_content, key])

  const handleClose = useCallback(() => {
    setOpen(false)
    try { if (key) localStorage.setItem(key, "1") } catch { /* The dialog can still be closed. */ }
  }, [key])

  if (!open) return null

  return (
    <Dialog open={open} onOpenChange={(nextOpen) => { if (!nextOpen) handleClose() }}>
      <DialogContent className="max-w-lg gap-0 p-0">
        <DialogHeader className="border-b border-border px-6 py-4 pr-12">
          <DialogTitle className="text-base">{config?.popup_title || t("common.announcement")}</DialogTitle>
        </DialogHeader>
        <div className="px-6 py-4">
          <MarkdownContent value={config!.popup_content!} />
        </div>
        <DialogFooter className="border-t border-border px-6 py-3">
          <button
            type="button"
            className="rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90"
            onClick={handleClose}
          >
            {t("common.acknowledge")}
          </button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

/** Routes that are always accessible even in maintenance mode */
const MAINTENANCE_EXEMPT_PATHS = ["/login", "/register"]

export function StoreShellClient({ header, children }: { header: React.ReactNode; children: React.ReactNode }) {
  const { config, isLoading } = useSiteConfig()
  const { user, isLoggedIn, authLoaded } = useAuth()
  const pathname = usePathname()
  const hasHeader = isStorefrontHeaderVisible(pathname)
  const hasDock = isStorefrontDockVisible(pathname)
  const isChat = pathname.startsWith("/messages/support/")

  // 维护模式判断需要 config + auth 都就绪才能准确决策
  const configReady = !isLoading && !!config
  const isExempt = MAINTENANCE_EXEMPT_PATHS.includes(pathname) || pathname === "/messages/support" || pathname.startsWith("/messages/support/")
  const isAdmin = authLoaded && isLoggedIn && isAdminRole(user?.role)
  const isMaintenance = configReady && config?.maintenance_enabled

  // Maintenance mode: block non-admin users on non-exempt pages
  // 等待服务端用户资料返回，避免把维护模式下的管理员误判为访客。
  if (isMaintenance && !isExempt) {
    if (!authLoaded) return null
    if (!isAdmin) return <MaintenancePage />
  }

  return (
    <MessageSummaryProvider><div className="store-shell flex min-h-screen flex-col bg-background">
      <VisitTracker />
      {isMaintenance && isAdmin && <MaintenanceAdminBanner />}
      <AnnouncementBar />
      {hasHeader && header}
      <main className={cn(
        "storefront-main mx-auto w-full max-w-[var(--storefront-max-width)] flex-1 px-[var(--storefront-gutter)]",
        hasDock && "storefront-main--with-dock",
        !hasHeader && "storefront-main--without-header",
      )}>{children}</main>
      {!isChat && <StoreFooter reserveForDock={hasDock} />}
      <StoreSearchPanel />
      {hasDock && <StoreBottomNav />}
      <PopupAnnouncement />
    </div></MessageSummaryProvider>
  )
}
