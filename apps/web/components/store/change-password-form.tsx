"use client"

import { useState, type FormEvent } from "react"
import { Eye, EyeOff, KeyRound, LoaderCircle } from "lucide-react"
import { useRouter } from "next/navigation"
import { toast } from "sonner"
import { useAuth, useLocale } from "@/lib/context"
import { getApiErrorMessage, requestOrThrow, userApi } from "@/services/api"
import { Button } from "@/components/ui/button"

export function ChangePasswordForm() {
  const { t } = useLocale()
  const { setUser } = useAuth()
  const router = useRouter()
  const [form, setForm] = useState({ currentPassword: "", newPassword: "", confirmNewPassword: "" })
  const [showCurrent, setShowCurrent] = useState(false)
  const [showNew, setShowNew] = useState(false)
  const [isLoading, setIsLoading] = useState(false)

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (form.newPassword !== form.confirmNewPassword) {
      toast.error(t("security.passwordMismatch"))
      return
    }

    setIsLoading(true)
    try {
      await requestOrThrow(() => userApi.updatePassword({
        old_password: form.currentPassword,
        new_password: form.newPassword,
      }))

      // The API increments authVersion and clears both browser cookies. Clear
      // the in-memory profile before navigating so no stale session can render.
      setUser(null)
      toast.success(t("security.passwordChanged"))
      router.replace("/login")
    } catch (error: unknown) {
      toast.error(getApiErrorMessage(error, t))
    } finally {
      setIsLoading(false)
    }
  }

  return (
    <form onSubmit={handleSubmit} className="settings-section">
      <header className="settings-section-header flex items-start gap-3">
        <span className="flex size-10 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary">
          <KeyRound className="size-5" aria-hidden="true" />
        </span>
        <div>
          <h2 className="text-base font-semibold text-foreground">{t("security.changePassword")}</h2>
          <p className="mt-1 text-sm leading-6 text-muted-foreground">{t("security.changePasswordDescription")}</p>
        </div>
      </header>

      <div className="settings-section-content space-y-4">
        <PasswordField
          id="current-password"
          label={t("security.currentPassword")}
          value={form.currentPassword}
          type={showCurrent ? "text" : "password"}
          autoComplete="current-password"
          onChange={(value) => setForm((previous) => ({ ...previous, currentPassword: value }))}
          onToggle={() => setShowCurrent((visible) => !visible)}
          isVisible={showCurrent}
          toggleLabel={t("security.showPassword")}
        />
        <PasswordField
          id="new-password"
          label={t("security.newPassword")}
          value={form.newPassword}
          type={showNew ? "text" : "password"}
          autoComplete="new-password"
          onChange={(value) => setForm((previous) => ({ ...previous, newPassword: value }))}
          onToggle={() => setShowNew((visible) => !visible)}
          isVisible={showNew}
          toggleLabel={t("security.showPassword")}
        />
        <PasswordField
          id="confirm-new-password"
          label={t("security.confirmNewPassword")}
          value={form.confirmNewPassword}
          type="password"
          autoComplete="new-password"
          onChange={(value) => setForm((previous) => ({ ...previous, confirmNewPassword: value }))}
        />
        <Button type="submit" className="mt-5 w-full sm:w-auto" disabled={isLoading}>
          {isLoading ? <LoaderCircle className="animate-spin" aria-hidden="true" /> : <KeyRound aria-hidden="true" />}
          {t("security.savePassword")}
        </Button>
      </div>
    </form>
  )
}

interface PasswordFieldProps {
  id: string
  label: string
  value: string
  type: "text" | "password"
  autoComplete: string
  onChange: (value: string) => void
  onToggle?: () => void
  isVisible?: boolean
  toggleLabel?: string
}

function PasswordField({
  id,
  label,
  value,
  type,
  autoComplete,
  onChange,
  onToggle,
  isVisible = false,
  toggleLabel,
}: PasswordFieldProps) {
  return (
    <div>
      <label htmlFor={id} className="mb-1.5 block text-sm font-medium text-foreground">
        {label}
      </label>
      <div className="relative">
        <input
          id={id}
          type={type}
          value={value}
          onChange={(event) => onChange(event.target.value)}
          autoComplete={autoComplete}
          required
          minLength={6}
          maxLength={72}
          className="h-11 w-full rounded-control border border-input bg-surface-muted px-3 pr-11 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
        />
        {onToggle && (
          <button
            type="button"
            onClick={onToggle}
            aria-label={toggleLabel}
            className="absolute right-1.5 top-1/2 flex size-8 -translate-y-1/2 items-center justify-center rounded-control text-muted-foreground transition-colors hover:bg-surface hover:text-foreground"
          >
            {isVisible ? <EyeOff className="size-4" aria-hidden="true" /> : <Eye className="size-4" aria-hidden="true" />}
          </button>
        )}
      </div>
    </div>
  )
}
