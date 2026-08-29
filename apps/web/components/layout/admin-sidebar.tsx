"use client"

import Link from "next/link"
import { usePathname } from "next/navigation"
import {
  CreditCard,
  FolderTree,
  KeyRound,
  LayoutDashboard,
  Package,
  ScrollText,
  Settings,
  ShieldAlert,
  ShoppingCart,
  Users,
  type LucideIcon,
} from "lucide-react"

import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarGroup,
  SidebarGroupContent,
  SidebarGroupLabel,
  SidebarHeader,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarRail,
  useSidebar,
} from "@/components/ui/sidebar"
import { useLocale, useSiteConfig } from "@/lib/context"
import type { TranslationKey } from "@/lib/i18n"

interface AdminNavItem {
  labelKey: TranslationKey
  href: string
  icon: LucideIcon
}

interface AdminNavGroup {
  labelKey: TranslationKey
  items: AdminNavItem[]
}

const navGroups: AdminNavGroup[] = [
  {
    labelKey: "admin.navOverview",
    items: [{ labelKey: "admin.dashboard", href: "/admin/dashboard", icon: LayoutDashboard }],
  },
  {
    labelKey: "admin.navProductsInventory",
    items: [
      { labelKey: "admin.products", href: "/admin/products", icon: Package },
      { labelKey: "admin.categories", href: "/admin/categories", icon: FolderTree },
      { labelKey: "admin.cardKeys", href: "/admin/card-keys", icon: KeyRound },
    ],
  },
  {
    labelKey: "admin.navTransactions",
    items: [
      { labelKey: "admin.orders", href: "/admin/orders", icon: ShoppingCart },
      { labelKey: "admin.payment", href: "/admin/payment-channels", icon: CreditCard },
    ],
  },
  {
    labelKey: "admin.navCustomers",
    items: [{ labelKey: "admin.users", href: "/admin/users", icon: Users }],
  },
  {
    labelKey: "admin.navSecurityAudit",
    items: [
      { labelKey: "admin.risk", href: "/admin/risk", icon: ShieldAlert },
      { labelKey: "admin.logs", href: "/admin/operation-logs", icon: ScrollText },
    ],
  },
  {
    labelKey: "admin.navSystem",
    items: [{ labelKey: "admin.siteConfig", href: "/admin/site-config", icon: Settings }],
  },
]

function AdminNavLink({ item }: { item: AdminNavItem }) {
  const pathname = usePathname()
  const { isMobile, setOpenMobile } = useSidebar()
  const { t } = useLocale()
  const isActive = pathname === item.href || pathname.startsWith(`${item.href}/`)

  return (
    <SidebarMenuButton asChild isActive={isActive} tooltip={t(item.labelKey)}>
      <Link
        href={item.href}
        onClick={() => {
          if (isMobile) setOpenMobile(false)
        }}
      >
        <item.icon aria-hidden="true" />
        <span>{t(item.labelKey)}</span>
      </Link>
    </SidebarMenuButton>
  )
}

export function AdminSidebar() {
  const { t } = useLocale()
  const { config } = useSiteConfig()

  return (
    <Sidebar collapsible="icon" variant="sidebar">
      <SidebarHeader className="border-b border-sidebar-border">
        <SidebarMenu>
          <SidebarMenuItem>
            <SidebarMenuButton asChild size="lg" tooltip={config?.site_name || t("admin.brand")}>
              <Link href="/admin/dashboard">
                {config?.logo_url ? (
                  <img src={config.logo_url} alt={config.site_name || t("admin.brand")} className="size-8 rounded-md object-contain" />
                ) : (
                  <span className="flex size-8 items-center justify-center rounded-md bg-sidebar-primary text-sidebar-primary-foreground">
                    <Package className="size-4" aria-hidden="true" />
                  </span>
                )}
                <span className="truncate font-semibold">{config?.site_name || t("admin.brand")}</span>
              </Link>
            </SidebarMenuButton>
          </SidebarMenuItem>
        </SidebarMenu>
      </SidebarHeader>

      <SidebarContent>
        {navGroups.map((group) => (
          <SidebarGroup key={group.labelKey}>
            <SidebarGroupLabel>{t(group.labelKey)}</SidebarGroupLabel>
            <SidebarGroupContent>
              <SidebarMenu>
                {group.items.map((item) => (
                  <SidebarMenuItem key={item.href}>
                    <AdminNavLink item={item} />
                  </SidebarMenuItem>
                ))}
              </SidebarMenu>
            </SidebarGroupContent>
          </SidebarGroup>
        ))}
      </SidebarContent>

      <SidebarFooter className="border-t border-sidebar-border">
        <SidebarMenu>
          <SidebarMenuItem>
            <SidebarMenuButton asChild tooltip={t("admin.backToSite")}>
              <Link href="/">
                <Package aria-hidden="true" />
                <span>{t("admin.backToSite")}</span>
              </Link>
            </SidebarMenuButton>
          </SidebarMenuItem>
        </SidebarMenu>
      </SidebarFooter>
      <SidebarRail aria-label={t("admin.toggleSidebar")} />
    </Sidebar>
  )
}
