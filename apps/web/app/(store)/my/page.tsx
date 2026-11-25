"use client"

import { useEffect, useState } from "react"
import Link from "next/link"
import {
  ChevronRight,
  Crown,
  LayoutDashboard,
  LogIn,
  LogOut,
  Settings,
  ShieldCheck,
  Store,
  UserPlus,
  Wallet,
  type LucideIcon,
} from "lucide-react"
import { toast } from "sonner"
import { AccountAvatar } from "@/components/store/account-avatar"
import { Badge } from "@/components/ui/badge"
import { useAuth, useLocale } from "@/lib/context"
import { isAdminRole } from "@/lib/hooks"
import { copyText } from "@/lib/clipboard"
import { getAvatarFileValidationError } from "@/lib/image-upload"
import { formatYuan } from "@/lib/utils"
import { getApiErrorMessage, requestOrThrow, userAccountApi, userApi } from "@/services/api"
import type { UserAccountOverview } from "@/types"

interface AccountLinkProps {
  href: string
  icon: LucideIcon
  label: string
}

function AccountLink({ href, icon: Icon, label }: AccountLinkProps) {
  return (
    <Link
      href={href}
      className="group flex min-h-12 items-center gap-3 px-4 text-sm text-foreground transition-colors hover:bg-foreground/[0.04] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring"
    >
      <span className="flex size-8 shrink-0 items-center justify-center rounded-control bg-foreground/[0.05] text-muted-foreground transition-colors group-hover:text-foreground">
        <Icon className="size-[18px]" strokeWidth={1.9} aria-hidden="true" />
      </span>
      <span className="min-w-0 flex-1 truncate font-medium">{label}</span>
      <ChevronRight className="size-4 shrink-0 text-muted-foreground/70" aria-hidden="true" />
    </Link>
  )
}

function Divider() {
  return <div className="mx-4 border-t border-border-subtle" />
}

function LoadingAccount() {
  return (
    <div className="account-page space-y-3 sm:space-y-4">
      <header className="hidden space-y-2 sm:block" aria-hidden="true">
        <div className="h-9 w-24 animate-pulse rounded-full bg-foreground/[0.07]" />
        <div className="h-4 w-32 animate-pulse rounded-full bg-foreground/[0.07]" />
      </header>
      <section className="content-surface rounded-card p-5 sm:p-6" data-layout="account-identity" aria-hidden="true">
        <div className="flex items-start gap-4">
          <div className="size-16 shrink-0 animate-pulse rounded-full bg-foreground/[0.07] sm:size-20 md:size-24" />
          <div className="min-w-0 flex-1 space-y-2 pt-1">
            <div className="h-5 w-32 animate-pulse rounded-full bg-foreground/[0.07]" />
            <div className="mt-2 flex min-w-0 max-w-full items-center gap-2">
              <div className="h-6 w-28 min-w-0 animate-pulse rounded-full bg-foreground/[0.07]" />
              <div className="h-3 w-20 shrink-0 animate-pulse rounded-full bg-foreground/[0.07]" />
            </div>
          </div>
        </div>
      </section>
      <section
        className="overflow-hidden rounded-card border border-border-subtle bg-surface p-4"
        data-layout="account-balance"
        aria-hidden="true"
      >
        <div className="flex items-center gap-4">
          <div className="space-y-2">
            <div className="h-4 w-14 animate-pulse rounded-full bg-foreground/[0.07]" />
            <div className="h-9 w-24 animate-pulse rounded-full bg-foreground/[0.07]" />
          </div>
        </div>
      </section>
    </div>
  )
}

export default function MyPage() {
  const { t } = useLocale()
  const { user, isLoggedIn, authLoaded, setUser, logout } = useAuth()
  const [isAvatarBusy, setIsAvatarBusy] = useState(false)
  const [accountOverview, setAccountOverview] = useState<UserAccountOverview | null>(null)

  useEffect(() => {
    if (!authLoaded || !isLoggedIn || !user?.id) {
      setAccountOverview(null)
      return
    }
    let cancelled = false
    requestOrThrow(() => userAccountApi.getOverview())
      .then((overview) => { if (!cancelled) setAccountOverview(overview) })
      .catch(() => { if (!cancelled) setAccountOverview(null) })
    return () => { cancelled = true }
  }, [authLoaded, isLoggedIn, user?.id])

  if (!authLoaded) return <LoadingAccount />

  if (!isLoggedIn || !user) {
    return (
      <div className="account-page max-w-2xl space-y-3 sm:space-y-4">
        <header className="sr-only sm:not-sr-only">
          <h1 className="text-3xl font-semibold tracking-[-0.04em] text-foreground">{t("my.title")}</h1>
        </header>

        <section className="account-identity-section" aria-label={t("my.title")}>
          <div className="account-identity">
            <AccountAvatar avatarUrl={null} username="" editable={false} />
            <div className="min-w-0 flex-1">
              <p className="text-lg font-semibold text-foreground text-balance">{t("my.visitor")}</p>
              <p className="mt-1 text-sm leading-6 text-muted-foreground text-pretty">{t("my.visitorDescription")}</p>
            </div>
          </div>
          <div className="mt-4 flex flex-col gap-2.5 sm:flex-row">
            <Link
              href="/login"
              className="inline-flex min-h-11 h-11 w-full sm:flex-1 shrink-0 items-center justify-center gap-2 rounded-control bg-primary px-4 text-sm font-semibold text-primary-foreground shadow-sm transition-[background-color,transform] hover:bg-primary/90 active:scale-[.99]"
            >
              <LogIn className="size-4" aria-hidden="true" />
              {t("nav.login")}
            </Link>
            <Link
              href="/register"
              className="inline-flex min-h-11 h-11 w-full sm:flex-1 shrink-0 items-center justify-center gap-2 rounded-control border border-input bg-surface px-4 text-sm font-semibold text-foreground transition-colors hover:bg-surface-muted"
            >
              <UserPlus className="size-4" aria-hidden="true" />
              {t("nav.register")}
            </Link>
          </div>
        </section>

        <section className="settings-group" aria-label={t("settings.title")}>
          <AccountLink href="/my/settings" icon={Settings} label={t("my.settings")} />
        </section>
      </div>
    )
  }

  const handleAvatarUpload = async (file: File) => {
    const validationError = getAvatarFileValidationError(file)
    if (validationError === "format") {
      toast.error(t("my.avatarFormatError"))
      return
    }
    if (validationError === "size") {
      toast.error(t("my.avatarSizeError"))
      return
    }

    setIsAvatarBusy(true)
    try {
      const updatedProfile = await requestOrThrow(() => userApi.uploadAvatar(file))
      setUser(updatedProfile)
      toast.success(t("my.avatarUpdated"))
      if (updatedProfile.image_processing_reason === "color_unspecified") toast.warning(t("my.imageColorAssumed"))
    } catch (error: unknown) {
      toast.error(getApiErrorMessage(error, t))
    } finally {
      setIsAvatarBusy(false)
    }
  }

  const copyAccountValue = async (value: string, successMessage: string) => {
    const copied = await copyText(value)
    toast[copied ? "success" : "error"](copied ? successMessage : t("my.copyFailed"))
  }

  const handleCopyUsername = () => copyAccountValue(user.username, t("my.usernameCopied"))

  const handleCopyUserId = () => {
    if (user.user_no === null) return
    return copyAccountValue(String(user.user_no), t("my.userIdCopied"))
  }

  const roleLabel = user.role === "SUPER_ADMIN" ? t("my.superAdminRole") : t("my.adminRole")
  const identityBadge = isAdminRole(user.role) ? (
    <Badge
      variant="secondary"
      className="min-w-0 max-w-full gap-1.5 overflow-hidden border-primary/20 bg-gradient-to-r from-primary/[0.08] via-primary/[0.12] to-primary/[0.06] text-primary dark:border-primary/30"
    >
      <ShieldCheck className="size-3.5 shrink-0" aria-hidden="true" />
      <span className="min-w-0 truncate">{roleLabel}</span>
    </Badge>
  ) : user.user_level_name ? (
    <Badge
      variant="secondary"
      className="min-w-0 max-w-full gap-1.5 overflow-hidden border-border-subtle bg-surface-muted text-foreground"
    >
      <Crown className="size-3.5 shrink-0" aria-hidden="true" />
      <span className="min-w-0 truncate">{user.user_level_name}</span>
    </Badge>
  ) : null

  return (
    <div className="account-page space-y-3 sm:space-y-4">
      <header className="sr-only sm:not-sr-only">
        <h1 className="text-3xl font-semibold tracking-[-0.04em] text-foreground">{t("my.title")}</h1>
        <p className="mt-2 text-sm text-muted-foreground">{t("my.accountSummary")}</p>
      </header>

      <section className="account-identity-section" aria-label={t("my.accountSummary")}>
        <div className="account-identity">
          <AccountAvatar
            avatarUrl={user.avatar_url}
            username={user.username}
            isBusy={isAvatarBusy}
            onUpload={handleAvatarUpload}
          />
          <div className="min-w-0 flex-1 pt-1">
            <h2 className="max-w-full text-xl font-semibold tracking-tight text-foreground">
              <button
                type="button"
                onClick={handleCopyUsername}
                aria-label={t("my.copyUsername")}
                className="-mx-1 inline-flex min-h-11 max-w-full items-center rounded px-1 py-0.5 text-left text-xl font-semibold tracking-tight text-foreground transition-[color,transform] hover:text-foreground/80 active:scale-[.995] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background"
              >
                <span className="truncate">{user.username}</span>
              </button>
            </h2>
            {(identityBadge || (!isAdminRole(user.role) && user.user_no !== null)) && (
              <div className="mt-1 flex min-w-0 max-w-full flex-wrap items-center gap-2">
                {identityBadge}
                {!isAdminRole(user.role) && user.user_no !== null && (
                  <button
                    type="button"
                    onClick={handleCopyUserId}
                    aria-label={t("my.copyUserId")}
                    className="inline-flex shrink-0 items-center rounded px-1 py-0.5 text-left text-sm text-muted-foreground transition-colors hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background"
                  >
                    <span className="whitespace-nowrap">{t("my.userId").replace("{id}", String(user.user_no))}</span>
                  </button>
                )}
              </div>
            )}
          </div>
        </div>
      </section>

      <section
        className="account-balance bg-surface"
        aria-label={t("my.balance")}
      >
        <div className="account-balance-row">
          <div className="flex min-w-0 flex-1 items-center justify-between gap-3">
            <div className="min-w-0">
              <p className="text-xs font-medium text-muted-foreground">{t("my.balance")}</p>
              <p className="mt-0.5 break-all text-2xl font-semibold tracking-tight text-foreground tabular-nums">
                {user.unlimited ? "∞" : formatYuan(user.balance)}
              </p>
              {!user.unlimited && <Link href="/my/balance#history" className="mt-1 inline-flex min-h-8 items-center text-xs font-medium text-muted-foreground transition-colors hover:text-foreground">{t("my.balanceDetails")}</Link>}
            </div>
            {!user.unlimited && accountOverview?.recharge_enabled && (
              <Link href="/my/balance" className="inline-flex min-h-11 shrink-0 items-center justify-center gap-2 rounded-control bg-primary px-4 text-sm font-semibold text-primary-foreground transition-colors hover:bg-primary/90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background">
                <Wallet className="size-4" aria-hidden="true" />{t("my.recharge")}
              </Link>
            )}
          </div>
        </div>
      </section>

      {!user.unlimited && accountOverview?.upgrade_available && (
        <section className="settings-group overflow-hidden rounded-card" aria-label={t("my.levelUpgrade")}>
          <AccountLink href="/my/levels" icon={Crown} label={`${user.user_level_name ? `${user.user_level_name} · ` : ""}${t("my.levelUpgrade")}`} />
        </section>
      )}

      <section className="settings-group overflow-hidden rounded-card" aria-label={t("my.title")}>
        <AccountLink href="/my/subsite" icon={Store} label="我的分站" />
        <Divider />
        <AccountLink href="/my/security" icon={ShieldCheck} label={t("my.security")} />
        <Divider />
        <AccountLink href="/my/settings" icon={Settings} label={t("my.settings")} />
        {isAdminRole(user.role) && (
          <>
            <Divider />
            <AccountLink href="/admin/dashboard" icon={LayoutDashboard} label={t("nav.admin")} />
          </>
        )}
      </section>

      <section className="settings-group overflow-hidden rounded-card">
        <button
          type="button"
          onClick={() => void logout().catch(() => toast.error("退出失败，请重试"))}
          className="flex min-h-12 w-full items-center gap-3 px-4 text-sm font-medium text-destructive transition-colors hover:bg-destructive/[0.07] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-destructive"
        >
          <span className="flex size-8 shrink-0 items-center justify-center rounded-control bg-destructive/[0.10]">
            <LogOut className="size-[18px]" strokeWidth={1.9} aria-hidden="true" />
          </span>
          <span>{t("nav.logout")}</span>
        </button>
      </section>
    </div>
  )
}
