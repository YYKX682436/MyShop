"use client"

import Link from "next/link"
import { usePathname, useRouter } from "next/navigation"
import { useEffect, useState } from "react"
import {
  ArrowUpRight,
  ChevronDown,
  Globe2,
  LayoutDashboard,
  LogOut,
  Moon,
  Package,
  Search,
  Sun,
  Users,
  ClipboardList,
  ShieldAlert,
  ScrollText,
  CreditCard,
  FolderTree,
  KeyRound,
  Settings,
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
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { SidebarTrigger } from "@/components/ui/sidebar"
import {
  CommandDialog,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
  CommandShortcut,
} from "@/components/ui/command"
import { useAuth, useLocale, useTheme } from "@/lib/context"
import type { TranslationKey } from "@/lib/i18n"

const commandItems: Array<{ labelKey: TranslationKey; href: string; icon: typeof LayoutDashboard; superAdminOnly?: boolean }> = [
  { labelKey: "admin.dashboard", href: "/admin/dashboard", icon: LayoutDashboard },
  { labelKey: "admin.products", href: "/admin/products", icon: Package },
  { labelKey: "admin.categories", href: "/admin/categories", icon: FolderTree },
  { labelKey: "admin.cardKeys", href: "/admin/card-keys", icon: KeyRound },
  { labelKey: "admin.orders", href: "/admin/orders", icon: ClipboardList },
  { labelKey: "admin.payment", href: "/admin/payment-channels", icon: CreditCard },
  { labelKey: "admin.users", href: "/admin/users", icon: Users },
  { labelKey: "admin.userLevels", href: "/admin/user-levels", icon: ShieldAlert },
  { labelKey: "admin.risk", href: "/admin/risk", icon: ShieldAlert },
  { labelKey: "admin.logs", href: "/admin/operation-logs", icon: ScrollText },
  { labelKey: "admin.siteConfig", href: "/admin/site-config", icon: Settings },
  { labelKey: "admin.administrators", href: "/admin/administrators", icon: Users, superAdminOnly: true },
]

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
  const { user, logout } = useAuth()
  const [commandOpen, setCommandOpen] = useState(false)
  const pageLabel = t(getPageLabel(pathname))

  useEffect(() => {
    const handleShortcut = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
        event.preventDefault()
        setCommandOpen(true)
      }
    }
    window.addEventListener("keydown", handleShortcut)
    return () => window.removeEventListener("keydown", handleShortcut)
  }, [])

  const toggleLocale = () => setLocale(locale === "zh" ? "en" : "zh")
  const toggleTheme = () => setTheme(resolvedTheme === "dark" ? "light" : "dark")
  const handleLogout = () => {
    logout()
    router.push("/")
  }

  return (
    <header className="control-header control-material sticky top-0 z-30 flex min-h-16 items-center px-4 sm:px-6">
      <div className="flex min-w-0 flex-1 items-center gap-3">
        <SidebarTrigger className="shrink-0 rounded-control" aria-label={t("admin.toggleSidebar")} />
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

      <div className="flex min-w-0 shrink-0 items-center gap-1 sm:gap-2">
        <Button
          type="button"
          variant="outline"
          className="hidden h-9 w-48 justify-between gap-3 border-border-subtle bg-surface-muted/65 px-3 text-muted-foreground shadow-none hover:bg-surface md:inline-flex lg:w-60"
          onClick={() => setCommandOpen(true)}
          aria-label={t("admin.commandBar")}
        >
          <span className="flex min-w-0 items-center gap-2">
            <Search className="size-3.5 shrink-0" aria-hidden="true" />
            <span className="truncate text-xs font-normal">{t("admin.commandBar")}</span>
          </span>
          <kbd className="hidden rounded border border-border-subtle bg-surface px-1.5 py-0.5 text-[10px] font-medium text-muted-foreground lg:inline-block">⌘ K</kbd>
        </Button>
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
            <DropdownMenuItem onClick={handleLogout} className="text-destructive focus:text-destructive">
              <LogOut className="h-4 w-4" aria-hidden="true" />
              {t("admin.logout")}
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>

      <CommandDialog open={commandOpen} onOpenChange={setCommandOpen}>
        <CommandInput placeholder={t("admin.commandBar")} />
        <CommandList>
          <CommandEmpty>{t("admin.noCommandResults")}</CommandEmpty>
          <CommandGroup heading={t("admin.quickNavigation")}>
            {commandItems
              .filter((item) => !item.superAdminOnly || user?.role === "SUPER_ADMIN")
              .map((item) => (
                <CommandItem
                  key={item.href}
                  value={t(item.labelKey)}
                  onSelect={() => {
                    setCommandOpen(false)
                    router.push(item.href)
                  }}
                >
                  <item.icon className="text-muted-foreground" aria-hidden="true" />
                  <span>{t(item.labelKey)}</span>
                  <ArrowUpRight className="ml-auto size-3.5 text-muted-foreground" aria-hidden="true" />
                  <CommandShortcut>↵</CommandShortcut>
                </CommandItem>
              ))}
          </CommandGroup>
        </CommandList>
      </CommandDialog>
    </header>
  )
}
