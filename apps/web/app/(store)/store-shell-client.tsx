"use client"

import React, { useRef } from "react"
import Link from "next/link"
import { usePathname } from "next/navigation"
import { Wrench } from "lucide-react"
import { VisitTracker } from "@/components/store/visit-tracker"
import { MessageSummaryProvider } from "@/components/store/message-summary"
import { StoreBottomNavigation } from "@/components/store/liquid-tabs/store-bottom-navigation"
import { useSiteConfig, useAuth, useLocale } from "@/lib/context"
import { isAdminRole } from "@/lib/hooks"

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


/** Routes that are always accessible even in maintenance mode */
const MAINTENANCE_EXEMPT_PATHS = ["/login", "/register"]

export function StoreShellClient({ children }: { children: React.ReactNode }) {
  const { config, isLoading } = useSiteConfig()
  const { user, isLoggedIn, authLoaded } = useAuth()
  const pathname = usePathname()
  const shellRef = useRef<HTMLDivElement>(null)
  const hasBottomNavigation = ["/", "/my/orders", "/messages", "/my"].includes(pathname)

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
    <MessageSummaryProvider><div ref={shellRef} className="store-shell flex min-h-screen flex-col bg-background">
      <VisitTracker />
      {isMaintenance && isAdmin && <MaintenanceAdminBanner />}
      <main
        className={`storefront-main storefront-main--without-header mx-auto w-full max-w-[var(--storefront-max-width)] flex-1 px-[var(--storefront-gutter)]${hasBottomNavigation ? " storefront-main--bottom-navigation" : ""}`}
      >
        {children}
      </main>
    </div><StoreBottomNavigation sourceRef={shellRef} /></MessageSummaryProvider>
  )
}
