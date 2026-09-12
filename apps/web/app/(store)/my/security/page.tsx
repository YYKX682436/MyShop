"use client"

import Link from "next/link"
import { ArrowLeft } from "lucide-react"
import { ChangePasswordForm } from "@/components/store/change-password-form"
import { useLocale } from "@/lib/context"
import { useRequireAuth } from "@/lib/hooks"

export default function MySecurityPage() {
  const { t } = useLocale()
  const user = useRequireAuth()

  if (!user) return null

  return (
    <div className="mx-auto w-full max-w-2xl space-y-6">
      <header>
        <Link href="/my" className="mb-4 inline-flex items-center gap-1.5 text-sm font-medium text-muted-foreground transition-colors hover:text-foreground">
          <ArrowLeft className="size-4" aria-hidden="true" />
          {t("my.backToAccount")}
        </Link>
        <h1 className="text-3xl font-semibold tracking-[-0.04em] text-foreground">{t("security.title")}</h1>
        <p className="mt-2 text-sm text-muted-foreground">{t("security.description")}</p>
      </header>
      <ChangePasswordForm />
    </div>
  )
}
