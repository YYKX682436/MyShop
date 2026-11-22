"use client"

import { useState } from "react"
import Link from "next/link"
import { useRouter, useSearchParams } from "next/navigation"
import { Eye, EyeOff, LogIn } from "lucide-react"
import { toast } from "sonner"
import { useLocale, useAuth } from "@/lib/context"
import { authApi, getApiErrorMessage, getHomeSiteFromError, setTurnstileHeaders } from "@/services/api"
import { Turnstile, useTurnstile } from "@/components/shared/turnstile"
import { safeHomeSiteUrl, safeLoginRedirect } from "@/lib/safe-navigation"

export default function LoginPage() {
  const { t } = useLocale()
  const { setUser } = useAuth()
  const router = useRouter()
  const searchParams = useSearchParams()
  const redirectTo = safeLoginRedirect(searchParams.get("redirect"))

  const [account, setAccount] = useState("")
  const [password, setPassword] = useState("")
  const [accountError, setAccountError] = useState(false)
  const [passwordError, setPasswordError] = useState(false)
  const [showPassword, setShowPassword] = useState(false)
  const [isLoading, setIsLoading] = useState(false)
  const [homeSite, setHomeSite] = useState<string | null>(null)
  const { turnstileToken, setTurnstileToken, handleTurnstileReset } = useTurnstile()

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault()
    const missingAccount = !account.trim()
    const missingPassword = !password
    setAccountError(missingAccount)
    setPasswordError(missingPassword)
    if (missingAccount || missingPassword) {
      if (missingAccount) document.getElementById("account")?.focus()
      else document.getElementById("password")?.focus()
      return
    }

    setIsLoading(true)
    setHomeSite(null)
    try {
      setTurnstileHeaders(turnstileToken)
      const result = await authApi.login({ account: account.trim(), password })

      setUser(result.user)

      toast.success(t("auth.loginSuccess"))
      router.push(redirectTo)
    } catch (err: unknown) {
      setHomeSite(safeHomeSiteUrl(getHomeSiteFromError(err), window.location.origin))
      toast.error(getApiErrorMessage(err, t))
      handleTurnstileReset()
    } finally {
      setIsLoading(false)
    }
  }

  return (
    <div className="auth-page mx-auto min-h-[calc(100vh-13rem)] w-full max-w-3xl py-8 sm:grid-cols-[minmax(0,0.8fr)_minmax(18rem,1fr)] sm:gap-10 lg:gap-16">
      <header className="auth-intro">
        <Link href="/" className="mb-1 inline-flex min-h-11 items-center text-sm text-muted-foreground sm:hidden">← {t("common.back")}</Link>
        <p className="mb-2 hidden text-xs font-semibold uppercase tracking-[0.16em] text-primary sm:block">MyShop</p>
        <h1 className="text-2xl font-semibold tracking-[-0.04em] text-foreground sm:text-4xl">
          {t("auth.login")}
        </h1>
        <p className="mt-3 hidden text-sm leading-6 text-muted-foreground sm:block">{t("auth.noAccount")}</p>
      </header>

      <section className="auth-form-panel">
          {homeSite && <p role="status" className="mb-4 text-sm">请前往账号归属站点登录： <a className="break-all text-primary underline" href={`${homeSite}/login`}>{homeSite}</a></p>}

          <form onSubmit={handleLogin} noValidate className="flex flex-col gap-4">
            {/* Username or Email */}
            <div>
              <label
                htmlFor="account"
                className="mb-1.5 block text-sm font-medium text-foreground"
              >
                {t("auth.usernameOrEmail")}
              </label>
              <input
                id="account"
                type="text"
                value={account}
                onChange={(e) => {
                  setAccount(e.target.value)
                  if (e.target.value.trim()) setAccountError(false)
                }}
                autoComplete="username"
                spellCheck={false}
                enterKeyHint="next"
                aria-invalid={accountError}
                aria-describedby={accountError ? "account-error" : undefined}
                className="h-11 w-full rounded-control border border-input bg-surface-muted px-3 text-base text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring sm:text-sm"
                required
              />
              {accountError && <p id="account-error" role="alert" className="mt-1 text-xs text-destructive">{t("auth.loginAccountRequired")}</p>}
            </div>

            {/* Password */}
            <div>
              <label
                htmlFor="password"
                className="mb-1.5 block text-sm font-medium text-foreground"
              >
                {t("auth.password")}
              </label>
              <div className="relative">
                <input
                  id="password"
                  type={showPassword ? "text" : "password"}
                  value={password}
                  onChange={(e) => {
                    setPassword(e.target.value)
                    if (e.target.value) setPasswordError(false)
                  }}
                  autoComplete="current-password"
                  enterKeyHint="done"
                  aria-invalid={passwordError}
                  aria-describedby={passwordError ? "password-error" : undefined}
                  className="h-11 w-full rounded-control border border-input bg-surface-muted px-3 pr-10 text-base text-foreground focus:outline-none focus:ring-2 focus:ring-ring sm:text-sm"
                  required
                />
                <button
                  type="button"
                  onClick={() => setShowPassword(!showPassword)}
                  aria-label={t("auth.togglePassword")}
                  aria-pressed={showPassword}
                  className="absolute right-0 top-1/2 flex size-11 -translate-y-1/2 items-center justify-center rounded-control text-muted-foreground transition-colors hover:bg-surface hover:text-foreground"
                >
                  {showPassword ? (
                    <EyeOff className="h-4 w-4" />
                  ) : (
                    <Eye className="h-4 w-4" />
                  )}
                </button>
              </div>
              {passwordError && <p id="password-error" role="alert" className="mt-1 text-xs text-destructive">{t("auth.loginPasswordRequired")}</p>}
            </div>

            <Turnstile onSuccess={setTurnstileToken} onError={handleTurnstileReset} className="mb-1" />

            {/* Submit */}
            <button
              type="submit"
              disabled={isLoading}
              className="inline-flex h-11 items-center justify-center gap-2 rounded-control bg-primary text-sm font-semibold text-primary-foreground shadow-sm transition-[background-color,transform] duration-180 hover:bg-primary/90 active:scale-[.99] disabled:pointer-events-none disabled:opacity-50"
            >
              {isLoading ? (
                <div className="h-4 w-4 animate-spin rounded-full border-2 border-primary-foreground border-t-transparent" />
              ) : (
                <LogIn className="h-4 w-4" />
              )}
              {t("auth.login")}
            </button>
          </form>

          <p className="mt-4 text-center text-sm text-muted-foreground">
            {t("auth.noAccount")}{" "}
            <Link
              href={redirectTo !== "/" ? `/register?redirect=${encodeURIComponent(redirectTo)}` : "/register"}
              className="font-medium text-primary hover:underline"
            >
              {t("auth.goRegister")}
            </Link>
          </p>
      </section>
    </div>
  )
}
