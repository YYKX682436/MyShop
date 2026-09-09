"use client"

import Link from "next/link"
import {
  ChevronRight,
  Languages,
  LogIn,
  LogOut,
  Moon,
  ReceiptText,
  Settings,
  UserRound,
  UserPlus,
} from "lucide-react"
import type { LucideIcon } from "lucide-react"
import { useAuth, useLocale, useTheme } from "@/lib/context"
import { isAdminRole } from "@/lib/hooks"
import { Switch } from "@/components/ui/switch"

interface SettingsLinkProps {
  href: string
  icon: LucideIcon
  label: string
}

function SettingsLink({ href, icon: Icon, label }: SettingsLinkProps) {
  return (
    <Link
      href={href}
      className="group flex min-h-14 items-center gap-3 px-4 text-sm text-foreground transition-colors hover:bg-foreground/[0.04] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring"
    >
      <span className="flex size-9 shrink-0 items-center justify-center rounded-full bg-foreground/[0.06] text-muted-foreground transition-colors group-hover:text-foreground">
        <Icon className="size-[18px]" strokeWidth={1.9} aria-hidden="true" />
      </span>
      <span className="min-w-0 flex-1 truncate font-medium">{label}</span>
      <ChevronRight className="size-4 shrink-0 text-muted-foreground/70" aria-hidden="true" />
    </Link>
  )
}

interface SettingsSwitchProps {
  checked: boolean
  icon: LucideIcon
  label: string
  onCheckedChange: (checked: boolean) => void
}

function SettingsSwitch({ checked, icon: Icon, label, onCheckedChange }: SettingsSwitchProps) {
  return (
    <div className="flex min-h-14 items-center gap-3 px-4 text-sm text-foreground">
      <span className="flex size-9 shrink-0 items-center justify-center rounded-full bg-foreground/[0.06] text-muted-foreground">
        <Icon className="size-[18px]" strokeWidth={1.9} aria-hidden="true" />
      </span>
      <span className="min-w-0 flex-1 truncate font-medium">{label}</span>
      <Switch checked={checked} onCheckedChange={onCheckedChange} aria-label={label} />
    </div>
  )
}

function LoadingMenu() {
  return (
    <div className="store-glass overflow-hidden rounded-card p-2" aria-hidden="true">
      {Array.from({ length: 4 }, (_, index) => (
        <div key={index} className="flex min-h-14 items-center gap-3 px-2">
          <div className="size-9 animate-pulse rounded-full bg-foreground/[0.07]" />
          <div className="h-3 w-32 animate-pulse rounded-full bg-foreground/[0.07]" />
        </div>
      ))}
    </div>
  )
}

export default function MyPage() {
  const { t, locale, setLocale } = useLocale()
  const { user, isLoggedIn, authLoaded, logout } = useAuth()
  const { resolvedTheme, setTheme } = useTheme()

  if (!authLoaded) {
    return (
      <div className="mx-auto w-full max-w-2xl space-y-6">
        <header>
          <h1 className="text-3xl font-semibold tracking-[-0.04em] text-foreground">{t("my.title")}</h1>
        </header>
        <div role="status" aria-label={t("common.loading")}>
          <LoadingMenu />
        </div>
      </div>
    )
  }

  return (
    <div className="mx-auto w-full max-w-2xl space-y-6">
      <header>
        <h1 className="text-3xl font-semibold tracking-[-0.04em] text-foreground">{t("my.title")}</h1>
        {isLoggedIn && user?.username && (
          <p className="mt-2 text-sm text-muted-foreground">{user.username}</p>
        )}
      </header>

      <section className="store-glass overflow-hidden rounded-card" aria-label={t("my.title")}>
        {isLoggedIn ? (
          <>
            <SettingsLink href="/profile" icon={UserRound} label={t("nav.profile")} />
            <div className="mx-4 border-t border-border-subtle" />
            <SettingsLink href="/my/orders" icon={ReceiptText} label={t("nav.myOrders")} />
            {isAdminRole(user?.role) && (
              <>
                <div className="mx-4 border-t border-border-subtle" />
                <SettingsLink href="/admin/dashboard" icon={Settings} label={t("nav.admin")} />
              </>
            )}
          </>
        ) : (
          <>
            <SettingsLink href="/login" icon={LogIn} label={t("nav.login")} />
            <div className="mx-4 border-t border-border-subtle" />
            <SettingsLink href="/register" icon={UserPlus} label={t("nav.register")} />
            <div className="mx-4 border-t border-border-subtle" />
            <SettingsLink href="/profile" icon={UserRound} label={t("nav.profile")} />
            <div className="mx-4 border-t border-border-subtle" />
            <SettingsLink href="/my/orders" icon={ReceiptText} label={t("nav.myOrders")} />
          </>
        )}
      </section>

      <section className="store-glass overflow-hidden rounded-card" aria-label={t("my.darkMode")}>
        <SettingsSwitch
          icon={Moon}
          label={t("my.darkMode")}
          checked={resolvedTheme === "dark"}
          onCheckedChange={(checked) => setTheme(checked ? "dark" : "light")}
        />
        <div className="mx-4 border-t border-border-subtle" />
        <SettingsSwitch
          icon={Languages}
          label={t("my.english")}
          checked={locale === "en"}
          onCheckedChange={(checked) => setLocale(checked ? "en" : "zh")}
        />
      </section>

      {isLoggedIn && (
        <section className="store-glass overflow-hidden rounded-card">
          <button
            type="button"
            onClick={logout}
            className="flex min-h-14 w-full items-center gap-3 px-4 text-sm font-medium text-destructive transition-colors hover:bg-destructive/[0.07] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-destructive"
          >
            <span className="flex size-9 shrink-0 items-center justify-center rounded-full bg-destructive/[0.10]">
              <LogOut className="size-[18px]" strokeWidth={1.9} aria-hidden="true" />
            </span>
            <span>{t("nav.logout")}</span>
          </button>
        </section>
      )}
    </div>
  )
}
