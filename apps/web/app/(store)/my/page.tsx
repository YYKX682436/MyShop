"use client"

import { useState } from "react"
import Link from "next/link"
import {
  ChevronRight,
  Copy,
  LayoutDashboard,
  LogIn,
  LogOut,
  ReceiptText,
  Settings,
  ShieldCheck,
  UserPlus,
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
import { getApiErrorMessage, requestOrThrow, userApi } from "@/services/api"

interface AccountLinkProps {
  href: string
  icon: LucideIcon
  label: string
}

function AccountLink({ href, icon: Icon, label }: AccountLinkProps) {
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

function Divider() {
  return <div className="mx-4 border-t border-border-subtle" />
}

function LoadingAccount() {
  return (
    <div className="mx-auto w-full max-w-3xl space-y-6">
      <div>
        <div className="h-9 w-24 animate-pulse rounded-full bg-foreground/[0.07]" />
      </div>
      <div className="store-glass space-y-5 rounded-card p-5 sm:p-6" aria-hidden="true">
        <div className="flex items-center gap-4">
          <div className="size-20 animate-pulse rounded-full bg-foreground/[0.07] sm:size-24" />
          <div className="space-y-2">
            <div className="h-4 w-32 animate-pulse rounded-full bg-foreground/[0.07]" />
            <div className="h-3 w-48 animate-pulse rounded-full bg-foreground/[0.07]" />
          </div>
        </div>
        <div className="grid gap-3 sm:grid-cols-2">
          <div className="h-20 animate-pulse rounded-card bg-foreground/[0.07]" />
          <div className="h-20 animate-pulse rounded-card bg-foreground/[0.07]" />
        </div>
      </div>
    </div>
  )
}

export default function MyPage() {
  const { t } = useLocale()
  const { user, isLoggedIn, authLoaded, setUser, logout } = useAuth()
  const [isAvatarBusy, setIsAvatarBusy] = useState(false)

  if (!authLoaded) return <LoadingAccount />

  if (!isLoggedIn || !user) {
    return (
      <div className="mx-auto w-full max-w-2xl space-y-6">
        <header>
          <h1 className="text-3xl font-semibold tracking-[-0.04em] text-foreground">{t("my.title")}</h1>
        </header>

        <section className="store-glass rounded-card p-5 sm:p-6" aria-label={t("my.title")}>
          <div className="flex items-center gap-4">
            <AccountAvatar avatarUrl={null} username="" editable={false} />
            <div className="min-w-0">
              <p className="text-lg font-semibold text-foreground">{t("my.visitor")}</p>
              <p className="mt-1 text-sm leading-6 text-muted-foreground">{t("my.visitorDescription")}</p>
            </div>
          </div>
          <div className="mt-6 flex flex-col gap-2 sm:flex-row">
            <Link
              href="/login"
              className="inline-flex h-11 flex-1 items-center justify-center gap-2 rounded-control bg-primary px-4 text-sm font-semibold text-primary-foreground shadow-sm transition-[background-color,transform] hover:bg-primary/90 active:scale-[.99]"
            >
              <LogIn className="size-4" aria-hidden="true" />
              {t("nav.login")}
            </Link>
            <Link
              href="/register"
              className="inline-flex h-11 flex-1 items-center justify-center gap-2 rounded-control border border-input bg-surface px-4 text-sm font-semibold text-foreground transition-colors hover:bg-surface-muted"
            >
              <UserPlus className="size-4" aria-hidden="true" />
              {t("nav.register")}
            </Link>
          </div>
        </section>

        <section className="store-glass overflow-hidden rounded-card" aria-label={t("settings.title")}>
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
    } catch (error: unknown) {
      toast.error(getApiErrorMessage(error, t))
    } finally {
      setIsAvatarBusy(false)
    }
  }

  const handleCopyUserId = async () => {
    if (user.user_no === null) return
    const copied = await copyText(String(user.user_no))
    toast[copied ? "success" : "error"](copied ? t("my.userIdCopied") : t("my.copyFailed"))
  }

  const roleLabel = user.role === "SUPER_ADMIN" ? t("my.superAdminRole") : t("my.adminRole")

  return (
    <div className="mx-auto w-full max-w-3xl space-y-6">
      <header>
        <h1 className="text-3xl font-semibold tracking-[-0.04em] text-foreground">{t("my.title")}</h1>
        <p className="mt-2 text-sm text-muted-foreground">{t("my.accountSummary")}</p>
      </header>

      <section className="store-glass rounded-card p-5 sm:p-6" aria-label={t("my.accountSummary")}>
        <div className="flex items-start gap-4 sm:gap-5">
          <AccountAvatar
            avatarUrl={user.avatar_url}
            username={user.username}
            isBusy={isAvatarBusy}
            onUpload={handleAvatarUpload}
          />
          <div className="min-w-0 flex-1 pt-1">
            <div className="flex flex-wrap items-center gap-2">
              <h2 className="truncate text-xl font-semibold tracking-tight text-foreground">{user.username}</h2>
              {isAdminRole(user.role) && (
                <Badge variant="secondary" className="gap-1 border-primary/15 bg-primary/10 text-primary">
                  <ShieldCheck className="size-3.5" aria-hidden="true" />
                  {roleLabel}
                </Badge>
              )}
            </div>
            <p className="mt-1 truncate text-sm text-muted-foreground">{user.email || "—"}</p>
          </div>
        </div>

        <div className="mt-6 grid gap-3 sm:grid-cols-2">
          <AccountStat label={t("my.balance")} value={user.unlimited ? "∞" : formatYuan(user.balance)} valueClassName="text-2xl" />
          <div className="rounded-card border border-border-subtle bg-surface-muted p-3.5">
            <p className="text-xs text-muted-foreground">{isAdminRole(user.role) ? t("my.role") : t("my.userId")}</p>
            {isAdminRole(user.role) ? (
              <p className="mt-1 text-sm font-semibold text-foreground">{roleLabel}</p>
            ) : (
              <button
                type="button"
                onClick={handleCopyUserId}
                disabled={user.user_no === null}
                aria-label={t("my.copyUserId")}
                className="mt-1 inline-flex max-w-full items-center gap-2 rounded-control text-left font-mono text-lg font-semibold text-foreground transition-colors hover:text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:pointer-events-none disabled:opacity-60"
              >
                <span className="truncate">{user.user_no ?? "—"}</span>
                <Copy className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
              </button>
            )}
          </div>
          {user.user_level_name && (
            <AccountStat label={t("my.userLevel")} value={user.user_level_name} />
          )}
        </div>
      </section>

      <section className="store-glass overflow-hidden rounded-card" aria-label={t("my.title")}>
        <AccountLink href="/my/orders" icon={ReceiptText} label={t("my.orders")} />
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
    </div>
  )
}

function AccountStat({ label, value, valueClassName = "text-lg" }: { label: string; value: string; valueClassName?: string }) {
  return (
    <div className="rounded-card border border-border-subtle bg-surface-muted p-3.5">
      <p className="text-xs text-muted-foreground">{label}</p>
      <p className={`mt-1 font-semibold text-foreground ${valueClassName}`}>{value}</p>
    </div>
  )
}
