"use client"

import Link from "next/link"
import { usePathname, useRouter } from "next/navigation"
import {
  ChevronDown,
  Globe2,
  LogOut,
  Moon,
  Palette,
  Sun,
} from "lucide-react"

import {
  Breadcrumb,
  BreadcrumbItem,
  BreadcrumbLink,
  BreadcrumbList,
  BreadcrumbPage,
  BreadcrumbSeparator,
} from "@/components/ui/breadcrumb"
import { Button } from "@/components/ui/button"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { SidebarTrigger } from "@/components/ui/sidebar"
import { useAuth, useColorScheme, useLocale, useTheme, COLOR_SCHEMES, type ColorScheme } from "@/lib/context"
import type { TranslationKey } from "@/lib/i18n"

const colorSchemeKeys: Record<ColorScheme, TranslationKey> = {
  orange: "admin.colorOrange",
  coral: "admin.colorCoral",
  emerald: "admin.colorEmerald",
  blue: "admin.colorBlue",
  pink: "admin.colorPink",
  purple: "admin.colorPurple",
}

function getPageLabel(pathname: string): TranslationKey {
  if (pathname === "/admin" || pathname === "/admin/dashboard") return "admin.dashboard"
  if (pathname === "/admin/products/new") return "admin.addProduct"
  if (pathname.startsWith("/admin/products/") && pathname.endsWith("/edit")) return "admin.editProduct"
  if (pathname.startsWith("/admin/products")) return "admin.products"
  if (pathname.startsWith("/admin/categories")) return "admin.categories"
  if (pathname.startsWith("/admin/card-keys")) return "admin.cardKeys"
  if (pathname.startsWith("/admin/orders")) return "admin.orders"
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
  const { colorScheme, setColorScheme } = useColorScheme()
  const { user, logout } = useAuth()
  const pageLabel = t(getPageLabel(pathname))

  const toggleLocale = () => setLocale(locale === "zh" ? "en" : "zh")
  const toggleTheme = () => setTheme(resolvedTheme === "dark" ? "light" : "dark")
  const handleLogout = () => {
    logout()
    router.push("/")
  }

  return (
    <header className="sticky top-0 z-30 flex min-h-16 items-center border-b border-border bg-background/95 px-4 backdrop-blur supports-[backdrop-filter]:bg-background/80 sm:px-6">
      <div className="flex min-w-0 flex-1 items-center gap-3">
        <SidebarTrigger className="shrink-0" aria-label={t("admin.toggleSidebar")} />
        <Breadcrumb className="min-w-0" aria-label={t("admin.breadcrumb")}>
          <BreadcrumbList className="flex-nowrap overflow-hidden">
            <BreadcrumbItem className="hidden sm:inline-flex">
              <BreadcrumbLink asChild>
                <Link href="/admin/dashboard">{t("admin.adminHome")}</Link>
              </BreadcrumbLink>
            </BreadcrumbItem>
            <BreadcrumbSeparator className="hidden sm:inline-flex" />
            <BreadcrumbItem className="min-w-0">
              <BreadcrumbPage className="truncate">{pageLabel}</BreadcrumbPage>
            </BreadcrumbItem>
          </BreadcrumbList>
        </Breadcrumb>
      </div>

      <div className="flex shrink-0 items-center gap-1 sm:gap-2">
        <Button asChild variant="ghost" size="sm" className="hidden gap-2 sm:inline-flex">
          <Link href="/">
            <span>{t("admin.backToSite")}</span>
          </Link>
        </Button>

        <Button
          type="button"
          variant="ghost"
          size="icon"
          onClick={toggleLocale}
          title={locale === "zh" ? t("admin.switchToEnglish") : t("admin.switchToChinese")}
          aria-label={locale === "zh" ? t("admin.switchToEnglish") : t("admin.switchToChinese")}
        >
          <Globe2 className="h-4 w-4" aria-hidden="true" />
          <span className="sr-only">{locale === "zh" ? t("admin.switchToEnglish") : t("admin.switchToChinese")}</span>
        </Button>

        <Button
          type="button"
          variant="ghost"
          size="icon"
          onClick={toggleTheme}
          title={resolvedTheme === "dark" ? t("admin.switchToLight") : t("admin.switchToDark")}
          aria-label={resolvedTheme === "dark" ? t("admin.switchToLight") : t("admin.switchToDark")}
        >
          {resolvedTheme === "dark" ? <Sun className="h-4 w-4" aria-hidden="true" /> : <Moon className="h-4 w-4" aria-hidden="true" />}
        </Button>

        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button type="button" variant="ghost" size="icon" title={t("admin.colorTheme")} aria-label={t("admin.colorTheme")}>
              <Palette className="h-4 w-4" aria-hidden="true" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="w-48">
            <DropdownMenuLabel>{t("admin.colorTheme")}</DropdownMenuLabel>
            <DropdownMenuRadioGroup value={colorScheme} onValueChange={(value) => setColorScheme(value as ColorScheme)}>
              {COLOR_SCHEMES.map((scheme) => (
                <DropdownMenuRadioItem key={scheme.key} value={scheme.key}>
                  <span className="mr-2 size-3 rounded-full" style={{ backgroundColor: scheme.color }} aria-hidden="true" />
                  {t(colorSchemeKeys[scheme.key])}
                </DropdownMenuRadioItem>
              ))}
            </DropdownMenuRadioGroup>
          </DropdownMenuContent>
        </DropdownMenu>

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
            <DropdownMenuItem onClick={handleLogout} className="text-destructive focus:text-destructive">
              <LogOut className="h-4 w-4" aria-hidden="true" />
              {t("admin.logout")}
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    </header>
  )
}
