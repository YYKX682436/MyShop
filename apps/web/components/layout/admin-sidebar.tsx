"use client"

import Link from "next/link"
import Image from "next/image"
import { usePathname } from "next/navigation"
import {
  CreditCard,
  FolderTree,
  KeyRound,
  LayoutDashboard,
  Package,
  ScrollText,
  Store,
  Settings,
  ShieldAlert,
  ShieldCheck,
  ClipboardList,
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
import { useAuth, useLocale, useSiteConfig } from "@/lib/context"
import { useAdminAttention } from "@/lib/admin-attention"
import type { TranslationKey } from "@/lib/i18n"

interface AdminNavItem {
  labelKey: TranslationKey
  href: string
  icon: LucideIcon
  superAdminOnly?: boolean
  attention?: boolean
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
    labelKey: "admin.navXianyu",
    items: [
      { labelKey: "admin.xianyuAccounts", href: "/admin/xianyu/accounts", icon: Store },
      { labelKey: "admin.xianyuItems", href: "/admin/xianyu/items", icon: Package },
    ],
  },
  {
    labelKey: "admin.navTransactions",
    items: [
      { labelKey: "admin.orders", href: "/admin/orders", icon: ClipboardList, attention: true },
      { labelKey: "admin.salesChannelOrders", href: "/admin/sales-channel-orders", icon: Store },
      { labelKey: "admin.payment", href: "/admin/payment-channels", icon: CreditCard },
    ],
  },
  {
    labelKey: "admin.navCustomers",
    items: [
      { labelKey: "admin.users", href: "/admin/users", icon: Users },
      { labelKey: "admin.userLevels", href: "/admin/user-levels", icon: ShieldCheck },
    ],
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
    items: [
      { labelKey: "admin.siteConfig", href: "/admin/site-config", icon: Settings },
      { labelKey: "admin.administrators", href: "/admin/administrators", icon: ShieldCheck, superAdminOnly: true },
    ],
  },
]

function AdminNavLink({ item }: { item: AdminNavItem }) {
  const pathname = usePathname()
  const { isMobile, setOpenMobile } = useSidebar()
  const { t } = useLocale()
  const { afterSaleNeedsAttention } = useAdminAttention()
  const isActive = pathname === item.href || pathname.startsWith(`${item.href}/`)
  const attentionCount = item.attention ? afterSaleNeedsAttention : 0
  const attentionLabel = attentionCount > 99 ? "99+" : String(attentionCount)

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
        {attentionCount > 0 && (
          <span
            role="status"
            data-testid="admin-orders-attention-badge"
            title={`${t("admin.afterSaleNeedsAttention")}: ${attentionLabel}`}
            aria-label={`${t("admin.afterSaleNeedsAttention")}: ${attentionLabel}`}
            className="ml-auto inline-flex min-w-5 items-center justify-center rounded-full bg-destructive px-1.5 py-0.5 text-[10px] font-bold leading-none text-destructive-foreground group-data-[collapsible=icon]:absolute group-data-[collapsible=icon]:right-0 group-data-[collapsible=icon]:top-0 group-data-[collapsible=icon]:min-w-3 group-data-[collapsible=icon]:px-0.5 group-data-[collapsible=icon]:py-0.5"
          >
            <span className="group-data-[collapsible=icon]:hidden">{attentionLabel}</span>
            <span className="hidden size-1.5 rounded-full bg-destructive-foreground group-data-[collapsible=icon]:block" aria-hidden="true" />
          </span>
        )}
      </Link>
    </SidebarMenuButton>
  )
}

export function AdminSidebar() {
  const { t } = useLocale()
  const { config } = useSiteConfig()
  const { user } = useAuth()
  const superAdmin = user?.role === "SUPER_ADMIN"

  return (
    <Sidebar collapsible="icon" variant="floating">
      <SidebarHeader className="border-b border-sidebar-border/70 px-2 py-3">
        <SidebarMenu>
          <SidebarMenuItem>
            <SidebarMenuButton asChild size="lg" tooltip={config?.site_name || t("admin.brand")}>
              <Link href="/admin/dashboard" className="group/brand">
                {config?.logo_url ? (
                  <Image src={config.logo_url} alt={config.site_name || t("admin.brand")} width={32} height={32} unoptimized className="size-8 rounded-control object-contain" />
                ) : (
                  <span className="flex size-8 items-center justify-center rounded-control bg-sidebar-primary text-sidebar-primary-foreground shadow-sm transition-transform duration-180 group-hover/brand:scale-[1.03]">
                    <Package className="size-4" aria-hidden="true" />
                  </span>
                )}
                <span className="truncate text-sm font-semibold tracking-tight">{config?.site_name || t("admin.brand")}</span>
              </Link>
            </SidebarMenuButton>
          </SidebarMenuItem>
        </SidebarMenu>
      </SidebarHeader>

      <SidebarContent className="px-2 py-3">
        {navGroups.map((group) => (
          <SidebarGroup key={group.labelKey} className="px-0 py-1">
            <SidebarGroupLabel>{t(group.labelKey)}</SidebarGroupLabel>
            <SidebarGroupContent>
              <SidebarMenu>
                {group.items.filter((item) => !item.superAdminOnly || superAdmin).map((item) => (
                  <SidebarMenuItem key={item.href}>
                    <AdminNavLink item={item} />
                  </SidebarMenuItem>
                ))}
              </SidebarMenu>
            </SidebarGroupContent>
          </SidebarGroup>
        ))}
      </SidebarContent>

      <SidebarFooter className="border-t border-sidebar-border/70 px-2 py-3">
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
