"use client"

import Link from "next/link"
import { usePathname, useRouter } from "next/navigation"
import { toast } from "sonner"
import {
  ChevronDown,
  Globe2,
  LogOut,
  Moon,
  Monitor,
  Sun,
} from "lucide-react"

import { Button } from "@/components/ui/button"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { SidebarTrigger } from "@/components/ui/sidebar"
import { useAuth, useLocale, useTheme } from "@/lib/context"
import { useLayoutPreference } from "@/components/layout/layout-mode"
import { useAdminAttention } from "@/lib/admin-attention"
import type { TranslationKey } from "@/lib/i18n"

function getPageLabel(pathname: string): TranslationKey {
  if (pathname === "/admin" || pathname === "/admin/dashboard") return "admin.dashboard"
  if (pathname === "/admin/products/new") return "admin.addProduct"
  if (pathname.startsWith("/admin/products/") && pathname.endsWith("/edit")) return "admin.editProduct"
  if (pathname.startsWith("/admin/products")) return "admin.products"
  if (pathname.startsWith("/admin/media")) return "admin.media"
  if (pathname.startsWith("/admin/categories")) return "admin.categories"
  if (pathname.startsWith("/admin/card-keys")) return "admin.cardKeys"
  if (pathname.startsWith("/admin/orders")) return "admin.orders"
  if (pathname.startsWith("/admin/support")) return "admin.supportMessages"
  if (pathname.startsWith("/admin/notifications")) return "admin.notifications"
  if (pathname.startsWith("/admin/users")) return "admin.users"
  if (pathname.startsWith("/admin/payment-channels")) return "admin.payment"
  if (pathname.startsWith("/admin/site-config")) return "admin.siteConfig"
  if (pathname.startsWith("/admin/administrators")) return "admin.administrators"
  if (pathname.startsWith("/admin/risk")) return "admin.risk"
  if (pathname.startsWith("/admin/operation-logs")) return "admin.logs"
  return "admin.dashboard"
}

export function AdminHeader() {
  const pathname = usePathname()
  const router = useRouter()
  const { locale, setLocale, t } = useLocale()
  const { resolvedTheme, setTheme } = useTheme()
  const { mode: layoutMode, setMode: setLayoutMode } = useLayoutPreference()
  const { user, logout } = useAuth()
  const { total: attentionTotal } = useAdminAttention()
  const attentionLabel = attentionTotal > 99 ? "99+" : String(attentionTotal)
  const toggleLocale = () => setLocale(locale === "zh" ? "en" : "zh")
  const toggleTheme = () => setTheme(resolvedTheme === "dark" ? "light" : "dark")
  const handleLogout = () => { void logout().then(() => router.push("/")).catch(() => toast.error("退出失败，请重试")) }

  return (
    <div className="admin-header-layer">
      <header className="admin-floating-toolbar static-material">
        <div className="admin-toolbar-leading">
          <span className="relative inline-flex shrink-0">
            <SidebarTrigger className="h-11 w-11 rounded-control md:h-7 md:w-7" aria-label={t("admin.toggleSidebar")} />
            {attentionTotal > 0 && <span role="status" aria-label={`${t("admin.totalAttention")}: ${attentionLabel}`} className="absolute -right-1 -top-1 inline-flex min-h-4 min-w-4 items-center justify-center rounded-full bg-destructive px-1 text-[9px] font-bold leading-none text-destructive-foreground md:hidden">{attentionLabel}</span>}
          </span>
          <div className="hidden min-w-0 flex-col gap-0.5 md:flex">
            <span className="text-[11px] font-semibold uppercase tracking-[0.08em] text-muted-foreground">{t("admin.adminHome")}</span>
            <span className="truncate text-[15px] font-semibold">{t(getPageLabel(pathname))}</span>
          </div>
        </div>

        <div className="admin-toolbar-actions">
        <Button asChild variant="ghost" size="sm" className="hidden gap-2 sm:inline-flex">
          <Link href="/">
            <span>{t("admin.backToSite")}</span>
          </Link>
        </Button>

        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button type="button" variant="ghost" className="max-w-36 gap-2 px-2 sm:max-w-48 sm:px-3" title={t("admin.administrator")}>
              <span className="flex size-7 shrink-0 items-center justify-center rounded-full bg-primary/10 text-xs font-semibold text-primary">
                {(user?.username || t("admin.administrator")).slice(0, 1).toUpperCase()}
              </span>
              <span className="hidden truncate text-sm sm:inline">{user?.username || t("admin.administrator")}</span>
              <ChevronDown className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden="true" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="w-56">
            <DropdownMenuLabel className="font-normal">
              <p className="text-sm font-medium text-foreground">{user?.username || t("admin.administrator")}</p>
              <p className="mt-1 truncate text-xs text-muted-foreground">{user?.email || ""}</p>
            </DropdownMenuLabel>
            <DropdownMenuSeparator />
            <DropdownMenuItem onClick={toggleLocale}>
              <Globe2 className="h-4 w-4" aria-hidden="true" />
              {locale === "zh" ? t("admin.switchToEnglish") : t("admin.switchToChinese")}
            </DropdownMenuItem>
            <DropdownMenuItem onClick={toggleTheme}>
              {resolvedTheme === "dark" ? <Sun className="h-4 w-4" aria-hidden="true" /> : <Moon className="h-4 w-4" aria-hidden="true" />}
              {resolvedTheme === "dark" ? t("admin.switchToLight") : t("admin.switchToDark")}
            </DropdownMenuItem>
            <DropdownMenuSeparator />
            <DropdownMenuItem onClick={() => setLayoutMode(layoutMode === "desktop" ? "auto" : "desktop")}>
              <Monitor className="h-4 w-4" aria-hidden="true" />
              {t(layoutMode === "desktop" ? "layout.switchToAuto" : "layout.switchToDesktop")}
            </DropdownMenuItem>
            <DropdownMenuSeparator />
            <DropdownMenuItem onClick={handleLogout} className="text-destructive focus:text-destructive">
              <LogOut className="h-4 w-4" aria-hidden="true" />
              {t("admin.logout")}
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
        </div>

      </header>
    </div>
  )
}
