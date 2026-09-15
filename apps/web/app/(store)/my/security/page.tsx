"use client"

import Link from "next/link"
import { ArrowLeft, Mail } from "lucide-react"
import { ChangePasswordForm } from "@/components/store/change-password-form"
import { useLocale } from "@/lib/context"
import { useRequireAuth } from "@/lib/hooks"

export default function MySecurityPage() {
  const { t } = useLocale()
  const user = useRequireAuth()

  if (!user) return null

  return (
    <div className="account-page max-w-2xl space-y-6">
      <header>
        <Link href="/my" className="mb-4 inline-flex items-center gap-1.5 text-sm font-medium text-muted-foreground transition-colors hover:text-foreground">
          <ArrowLeft className="size-4" aria-hidden="true" />
          {t("my.backToAccount")}
        </Link>
        <h1 className="text-3xl font-semibold tracking-[-0.04em] text-foreground">{t("security.title")}</h1>
        <p className="mt-2 text-sm text-muted-foreground">{t("security.description")}</p>
      </header>

      <section className="settings-section" aria-labelledby="account-email-title">
        <header className="settings-section-header flex items-start gap-3">
          <span className="flex size-10 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary">
            <Mail className="size-5" aria-hidden="true" />
          </span>
          <div className="min-w-0">
            <h2 id="account-email-title" className="text-base font-semibold text-foreground">{t("security.email")}</h2>
            <p className="mt-2 break-all text-sm font-medium text-foreground">{user.email || t("security.emailNotBound")}</p>
          </div>
        </header>
      </section>

      <ChangePasswordForm />
    </div>
  )
}
