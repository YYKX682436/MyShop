"use client"

import { useState, useEffect, useCallback } from "react"
import Link from "next/link"
import { useRouter } from "next/navigation"
import { Eye, EyeOff, UserPlus, RefreshCw } from "lucide-react"
import { toast } from "sonner"
import { useAuth, useLocale } from "@/lib/context"
import { authApi, getApiErrorMessage, setTurnstileHeaders } from "@/services/api"
import { Turnstile, useTurnstile } from "@/components/shared/turnstile"
import { buildRegisterRequest, validateRegistrationForm } from "@/lib/auth-validation"

export default function RegisterPage() {
  const { t } = useLocale()
  const { setUser } = useAuth()
  const router = useRouter()

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
  const { turnstileToken, setTurnstileToken, handleTurnstileReset } = useTurnstile()

  const fetchCaptcha = useCallback(async () => {
    setCaptchaLoading(true)
    try {
      const result = await authApi.getCaptcha()
      setCaptchaId(result.captcha_id)
      setCaptchaImage(result.captcha_image)
    } catch {
      setCaptchaId("")
      setCaptchaImage("")
    } finally {
      setCaptchaLoading(false)
    }
  }, [])

  // Fetch captcha on mount
  useEffect(() => {
    fetchCaptcha()
  }, [fetchCaptcha])

  const update = (key: string, value: string) => {
    setForm((prev) => ({ ...prev, [key]: value }))
  }

  const handleRegister = async (e: React.FormEvent) => {
    e.preventDefault()
    const validationMessage = validateRegistrationForm(form)
    if (validationMessage) {
      toast.error(t(validationMessage))
      return
    }

    setIsLoading(true)
    try {
      setTurnstileHeaders(turnstileToken)
      const result = await authApi.register(buildRegisterRequest(form, captchaId))
      setUser(result.user)
      toast.success(t("auth.registerSuccess"))
      router.replace("/")
    } catch (err: unknown) {
      toast.error(getApiErrorMessage(err, t))
      // Refresh captcha after failed attempt
      fetchCaptcha()
      setForm((prev) => ({ ...prev, captcha: "" }))
      handleTurnstileReset()
    } finally {
      setIsLoading(false)
    }
  }

  return (
    <div className="flex min-h-[60vh] items-center justify-center">
      <div className="w-full max-w-sm">
        <div className="rounded-lg border border-border bg-card p-6 shadow-sm">
          <h1 className="mb-6 text-center text-xl font-bold text-card-foreground">
            {t("auth.register")}
          </h1>

          <form onSubmit={handleRegister} className="flex flex-col gap-4">
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
                minLength={3}
                maxLength={20}
                className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                required
              />
              <p className="mt-1 text-xs text-muted-foreground">{t("auth.usernameRule")}</p>
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
                maxLength={255}
                className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                required
              />
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
                className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                required
              />
              <p className="mt-1 text-xs text-muted-foreground">{t("auth.phoneRule")}</p>
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
                  className="h-10 w-full rounded-lg border border-input bg-background px-3 pr-10 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                  required
                />
                <button
                  type="button"
                  onClick={() => setShowPassword(!showPassword)}
                  aria-label={t("auth.togglePassword")}
                  className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
                >
                  {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                </button>
              </div>
              <p className="mt-1 text-xs text-muted-foreground">{t("auth.passwordRule")}</p>
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
                  className="h-10 w-full rounded-lg border border-input bg-background px-3 pr-10 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                  required
                />
                <button
                  type="button"
                  onClick={() => setShowConfirmPassword(!showConfirmPassword)}
                  aria-label={t("auth.togglePassword")}
                  className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
                >
                  {showConfirmPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                </button>
              </div>
              {form.confirmPassword && form.password !== form.confirmPassword && (
                <p className="mt-1 text-xs text-destructive" role="alert">{t("auth.passwordMismatch")}</p>
              )}
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
                  maxLength={6}
                  placeholder="请输入验证码"
                  className="h-10 flex-1 rounded-lg border border-input bg-background px-3 text-sm uppercase text-foreground tracking-wider focus:outline-none focus:ring-2 focus:ring-ring"
                  required
                />
                <div
                  className="relative flex h-10 w-24 shrink-0 cursor-pointer items-center justify-center rounded-lg border border-border bg-muted overflow-hidden"
                  onClick={fetchCaptcha}
                  title={t("auth.clickToRefresh")}
                >
                  {captchaLoading ? (
                    <RefreshCw className="h-4 w-4 animate-spin text-muted-foreground" />
                  ) : captchaImage ? (
                    <img src={captchaImage} alt="captcha" className="h-full w-full object-contain" />
                  ) : (
                    <span className="select-none text-lg font-bold tracking-wider text-foreground/60">
                      ----
                    </span>
                  )}
                  <RefreshCw className="absolute right-1 top-1 h-3 w-3 text-muted-foreground" />
                </div>
              </div>
              <p className="mt-1 text-xs text-muted-foreground">
                {t("auth.clickToRefresh")}
              </p>
            </div>

            <Turnstile onSuccess={setTurnstileToken} onError={handleTurnstileReset} className="mb-1" />

            {/* Submit */}
            <button
              type="submit"
              disabled={isLoading}
              className="inline-flex h-10 items-center justify-center gap-2 rounded-lg bg-primary text-sm font-semibold text-primary-foreground transition-colors hover:bg-primary/90 disabled:pointer-events-none disabled:opacity-50"
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
            <Link href="/login" className="font-medium text-primary hover:underline">
              {t("auth.goLogin")}
            </Link>
          </p>
        </div>
      </div>
    </div>
  )
}
