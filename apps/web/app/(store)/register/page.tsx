"use client"

import { useState, useEffect, useCallback } from "react"
import Link from "next/link"
import Image from "next/image"
import { useRouter, useSearchParams } from "next/navigation"
import { Eye, EyeOff, UserPlus, RefreshCw } from "lucide-react"
import { toast } from "sonner"
import { useAuth, useLocale } from "@/lib/context"
import { ApiError, authApi, getApiErrorMessage, setTurnstileHeaders } from "@/services/api"
import { Turnstile, useTurnstile } from "@/components/shared/turnstile"
import {
  buildRegisterRequest,
  mapRegistrationApiError,
  registrationErrorConsumesCaptcha,
  validateRegistrationField,
  validateRegistrationForm,
  type RegistrationErrors,
  type RegistrationField,
} from "@/lib/auth-validation"
import { safeLoginRedirect } from "@/lib/safe-navigation"

export default function RegisterPage() {
  const { t } = useLocale()
  const { setUser } = useAuth()
  const router = useRouter()
  const searchParams = useSearchParams()
  const redirectTo = safeLoginRedirect(searchParams.get("redirect"))

  const [form, setForm] = useState({
    username: "",
    email: "",
    phone: "",
    password: "",
    confirmPassword: "",
    captcha: "",
  })
  const [showPassword, setShowPassword] = useState(false)
  const [showConfirmPassword, setShowConfirmPassword] = useState(false)
  const [isLoading, setIsLoading] = useState(false)
  const [captchaId, setCaptchaId] = useState("")
  const [captchaImage, setCaptchaImage] = useState("")
  const [captchaLoading, setCaptchaLoading] = useState(false)
  const [captchaLoadError, setCaptchaLoadError] = useState(false)
  const [errors, setErrors] = useState<RegistrationErrors>({})
  const { turnstileToken, setTurnstileToken, handleTurnstileReset } = useTurnstile()

  const fetchCaptcha = useCallback(async (clearCaptchaError = false) => {
    setCaptchaLoading(true)
    setCaptchaLoadError(false)
    if (clearCaptchaError) {
      setErrors((current) => {
        const next = { ...current }
        delete next.captcha
        return next
      })
      setForm((current) => ({ ...current, captcha: "" }))
    }
    try {
      const result = await authApi.getCaptcha()
      setCaptchaId(result.captcha_id)
      setCaptchaImage(result.captcha_image)
    } catch {
      setCaptchaId("")
      setCaptchaImage("")
      setCaptchaLoadError(true)
    } finally {
      setCaptchaLoading(false)
    }
  }, [])

  // Fetch captcha on mount
  useEffect(() => {
    fetchCaptcha()
  }, [fetchCaptcha])

  const update = (key: RegistrationField, value: string) => {
    const nextForm = { ...form, [key]: value }
    setForm(nextForm)
    setErrors((current) => {
      const next = { ...current }
      if (current[key]) {
        const message = validateRegistrationField(key, nextForm)
        if (message) next[key] = message
        else delete next[key]
      }
      if (key === "password" && current.confirmPassword) {
        const message = validateRegistrationField("confirmPassword", nextForm)
        if (message) next.confirmPassword = message
        else delete next.confirmPassword
      }
      return next
    })
    if (key === "captcha" && value.trim()) setCaptchaLoadError(false)
  }

  const handleRegister = async (e: React.FormEvent) => {
    e.preventDefault()
    const validationErrors = validateRegistrationForm(form)
    setErrors(validationErrors)
    const firstInvalidField = Object.keys(validationErrors)[0] as RegistrationField | undefined
    if (firstInvalidField) {
      const focusId = firstInvalidField === "confirmPassword" ? "reg-confirm-password" : "reg-" + firstInvalidField
      document.getElementById(focusId)?.focus()
      return
    }
    if (!captchaId) {
      setCaptchaLoadError(true)
      return
    }

    setIsLoading(true)
    setErrors({})
    try {
      setTurnstileHeaders(turnstileToken)
      const result = await authApi.register(buildRegisterRequest(form, captchaId))
      setUser(result.user)
      toast.success(t("auth.registerSuccess"))
      router.replace(redirectTo)
    } catch (err: unknown) {
      const apiError = err instanceof ApiError ? err : null
      const fieldErrors = apiError
        ? mapRegistrationApiError(apiError.code, apiError.params)
        : null
      if (fieldErrors) {
        setErrors(fieldErrors)
        const firstField = Object.keys(fieldErrors)[0] as RegistrationField | undefined
        if (firstField) {
          const focusId = firstField === "confirmPassword" ? "reg-confirm-password" : "reg-" + firstField
          document.getElementById(focusId)?.focus()
        }
      } else {
        toast.error(getApiErrorMessage(err, t))
      }
      const captchaConsumed = apiError
        ? registrationErrorConsumesCaptcha(apiError.code, apiError.params)
        : false
      if (captchaConsumed) {
        setForm((prev) => ({ ...prev, captcha: "" }))
        void fetchCaptcha()
      }
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
          {t("auth.register")}
        </h1>
        <p className="mt-3 hidden text-sm leading-6 text-muted-foreground sm:block">{t("auth.hasAccount")}</p>
      </header>

      <section className="auth-form-panel">

          <form onSubmit={handleRegister} noValidate className="flex flex-col gap-4">
            {/* Username */}
            <div>
              <label htmlFor="reg-username" className="mb-1.5 block text-sm font-medium text-foreground">
                {t("auth.username")}
              </label>
              <input
                id="reg-username"
                type="text"
                value={form.username}
                onChange={(e) => update("username", e.target.value)}
                autoComplete="username"
                spellCheck={false}
                minLength={3}
                maxLength={20}
                aria-invalid={Boolean(errors.username)}
                aria-describedby={errors.username ? "reg-username-error reg-username-rule" : "reg-username-rule"}
                className="h-11 w-full rounded-control border border-input bg-surface-muted px-3 text-base text-foreground focus:outline-none focus:ring-2 focus:ring-ring sm:text-sm"
                required
              />
              <p id="reg-username-rule" className="mt-1 text-xs text-muted-foreground">{t("auth.usernameRule")}</p>
              {errors.username && <p id="reg-username-error" role="alert" className="mt-1 text-xs text-destructive">{t(errors.username)}</p>}
            </div>

            {/* Email */}
            <div>
              <label htmlFor="reg-email" className="mb-1.5 block text-sm font-medium text-foreground">
                {t("auth.email")}
              </label>
              <input
                id="reg-email"
                type="email"
                value={form.email}
                onChange={(e) => update("email", e.target.value)}
                autoComplete="email"
                autoCapitalize="none"
                spellCheck={false}
                maxLength={255}
                aria-invalid={Boolean(errors.email)}
                aria-describedby={errors.email ? "reg-email-error" : undefined}
                className="h-11 w-full rounded-control border border-input bg-surface-muted px-3 text-base text-foreground focus:outline-none focus:ring-2 focus:ring-ring sm:text-sm"
                required
              />
              {errors.email && <p id="reg-email-error" role="alert" className="mt-1 text-xs text-destructive">{t(errors.email)}</p>}
            </div>

            {/* Phone */}
            <div>
              <label htmlFor="reg-phone" className="mb-1.5 block text-sm font-medium text-foreground">
                {t("auth.phone")}
              </label>
              <input
                id="reg-phone"
                type="tel"
                inputMode="tel"
                value={form.phone}
                onChange={(e) => update("phone", e.target.value)}
                autoComplete="tel"
                maxLength={11}
                aria-invalid={Boolean(errors.phone)}
                aria-describedby={errors.phone ? "reg-phone-error reg-phone-rule" : "reg-phone-rule"}
                className="h-11 w-full rounded-control border border-input bg-surface-muted px-3 text-base text-foreground focus:outline-none focus:ring-2 focus:ring-ring sm:text-sm"
                required
              />
              <p id="reg-phone-rule" className="mt-1 text-xs text-muted-foreground">{t("auth.phoneRule")}</p>
              {errors.phone && <p id="reg-phone-error" role="alert" className="mt-1 text-xs text-destructive">{t(errors.phone)}</p>}
            </div>

            {/* Password */}
            <div>
              <label htmlFor="reg-password" className="mb-1.5 block text-sm font-medium text-foreground">
                {t("auth.password")}
              </label>
              <div className="relative">
                <input
                  id="reg-password"
                  type={showPassword ? "text" : "password"}
                  value={form.password}
                  onChange={(e) => update("password", e.target.value)}
                  autoComplete="new-password"
                  minLength={6}
                  maxLength={72}
                  aria-invalid={Boolean(errors.password)}
                  aria-describedby={errors.password ? "reg-password-error reg-password-rule" : "reg-password-rule"}
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
                  {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                </button>
              </div>
              <p id="reg-password-rule" className="mt-1 text-xs text-muted-foreground">{t("auth.passwordRule")}</p>
              {errors.password && <p id="reg-password-error" role="alert" className="mt-1 text-xs text-destructive">{t(errors.password)}</p>}
            </div>

            {/* Confirm password */}
            <div>
              <label htmlFor="reg-confirm-password" className="mb-1.5 block text-sm font-medium text-foreground">
                {t("auth.confirmPassword")}
              </label>
              <div className="relative">
                <input
                  id="reg-confirm-password"
                  type={showConfirmPassword ? "text" : "password"}
                  value={form.confirmPassword}
                  onChange={(e) => update("confirmPassword", e.target.value)}
                  autoComplete="new-password"
                  minLength={6}
                  maxLength={72}
                  aria-invalid={Boolean(errors.confirmPassword)}
                  aria-describedby={errors.confirmPassword ? "reg-confirm-password-error" : undefined}
                  className="h-11 w-full rounded-control border border-input bg-surface-muted px-3 pr-10 text-base text-foreground focus:outline-none focus:ring-2 focus:ring-ring sm:text-sm"
                  required
                />
                <button
                  type="button"
                  onClick={() => setShowConfirmPassword(!showConfirmPassword)}
                  aria-label={t("auth.togglePassword")}
                  aria-pressed={showConfirmPassword}
                  className="absolute right-0 top-1/2 flex size-11 -translate-y-1/2 items-center justify-center rounded-control text-muted-foreground transition-colors hover:bg-surface hover:text-foreground"
                >
                  {showConfirmPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                </button>
              </div>
              {errors.confirmPassword && <p id="reg-confirm-password-error" role="alert" className="mt-1 text-xs text-destructive">{t(errors.confirmPassword)}</p>}
            </div>

            {/* Image Captcha */}
            <div>
              <label htmlFor="reg-captcha" className="mb-1.5 block text-sm font-medium text-foreground">
                {t("auth.imageCode")}
              </label>
              <div className="flex gap-2">
                <input
                  id="reg-captcha"
                  type="text"
                  value={form.captcha}
                  onChange={(e) => update("captcha", e.target.value.toUpperCase())}
                  autoCapitalize="characters"
                  maxLength={6}
                  aria-invalid={Boolean(errors.captcha) || captchaLoadError}
                  aria-describedby={[
                    errors.captcha ? "reg-captcha-error" : null,
                    captchaLoadError ? "reg-captcha-load-error" : null,
                  ].filter(Boolean).join(" ") || undefined}
                  className="h-11 min-w-0 flex-1 rounded-control border border-input bg-surface-muted px-3 text-base uppercase tracking-wider text-foreground focus:outline-none focus:ring-2 focus:ring-ring sm:text-sm"
                  required
                />
                <button
                  type="button"
                  disabled={captchaLoading}
                  onClick={() => void fetchCaptcha(true)}
                  aria-label={t("auth.clickToRefresh")}
                  title={t("auth.clickToRefresh")}
                  className="relative flex h-11 w-24 shrink-0 items-center justify-center overflow-hidden rounded-control border border-border-subtle bg-surface-muted disabled:cursor-not-allowed"
                >
                  {captchaLoading ? (
                    <RefreshCw className="h-4 w-4 animate-spin text-muted-foreground" aria-hidden="true" />
                  ) : captchaImage ? (
                    <Image src={captchaImage} alt={t("auth.imageCode")} fill unoptimized sizes="96px" className="object-contain" />
                  ) : (
                    <span className="select-none text-lg font-bold tracking-wider text-foreground/60" aria-hidden="true">
                      ----
                    </span>
                  )}
                  <RefreshCw className="absolute right-1 top-1 h-3 w-3 text-muted-foreground" aria-hidden="true" />
                </button>
              </div>
              {errors.captcha && <p id="reg-captcha-error" role="alert" className="mt-1 text-xs text-destructive">{t(errors.captcha)}</p>}
              {captchaLoadError && <p id="reg-captcha-load-error" role="alert" className="mt-1 text-xs text-destructive">{t("auth.captchaLoadFailed")}</p>}
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
                <UserPlus className="h-4 w-4" />
              )}
              {t("auth.register")}
            </button>
          </form>

          <p className="mt-4 text-center text-sm text-muted-foreground">
            {t("auth.hasAccount")}{" "}
            <Link
              href={redirectTo !== "/" ? `/login?redirect=${encodeURIComponent(redirectTo)}` : "/login"}
              className="font-medium text-primary hover:underline"
            >
              {t("auth.goLogin")}
            </Link>
          </p>
      </section>
    </div>
  )
}
