"use client"

import { useState } from "react"
import Link from "next/link"
import { useRouter, useSearchParams } from "next/navigation"
import { Eye, EyeOff, LogIn } from "lucide-react"
import { toast } from "sonner"
import { useLocale, useAuth } from "@/lib/context"
import { authApi, getApiErrorMessage, setTurnstileHeaders } from "@/services/api"
import { Turnstile, useTurnstile } from "@/components/shared/turnstile"

export default function LoginPage() {
  const { t } = useLocale()
  const { setUser } = useAuth()
  const router = useRouter()
  const searchParams = useSearchParams()
  const redirectTo = searchParams.get("redirect")

  const [account, setAccount] = useState("")
  const [password, setPassword] = useState("")
  const [showPassword, setShowPassword] = useState(false)
  const [rememberMe, setRememberMe] = useState(false)
  const [isLoading, setIsLoading] = useState(false)
  const { turnstileToken, setTurnstileToken, handleTurnstileReset } = useTurnstile()

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!account.trim() || !password.trim()) return

    setIsLoading(true)
    try {
      setTurnstileHeaders(turnstileToken)
      const result = await authApi.login({ account: account.trim(), password })

      setUser(result.user)

      if (rememberMe) {
        localStorage.setItem("rememberMe", "true")
      } else {
        localStorage.removeItem("rememberMe")
      }

      toast.success(t("auth.loginSuccess"))
      router.push(redirectTo || "/")
    } catch (err: unknown) {
      toast.error(getApiErrorMessage(err, t))
      handleTurnstileReset()
    } finally {
      setIsLoading(false)
    }
  }

  return (
    <div className="flex min-h-[calc(100vh-13rem)] items-center justify-center py-8">
      <div className="w-full max-w-sm">
        <div className="surface p-6 sm:p-8">
          <div className="mb-7 text-center">
            <p className="mb-2 text-xs font-semibold uppercase tracking-[0.16em] text-primary">MyShop</p>
            <h1 className="text-2xl font-semibold tracking-tight text-foreground">
            {t("auth.login")}
            </h1>
          </div>

          <form onSubmit={handleLogin} className="flex flex-col gap-4">
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
                onChange={(e) => setAccount(e.target.value)}
                autoComplete="username email"
                className="h-11 w-full rounded-control border border-input bg-surface-muted px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                required
              />
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
                  onChange={(e) => setPassword(e.target.value)}
                  autoComplete="current-password"
                  className="h-11 w-full rounded-control border border-input bg-surface-muted px-3 pr-10 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                  required
                />
                <button
                  type="button"
                  onClick={() => setShowPassword(!showPassword)}
                  className="absolute right-2 top-1/2 flex size-8 -translate-y-1/2 items-center justify-center rounded-control text-muted-foreground transition-colors hover:bg-surface hover:text-foreground"
                >
                  {showPassword ? (
                    <EyeOff className="h-4 w-4" />
                  ) : (
                    <Eye className="h-4 w-4" />
                  )}
                </button>
              </div>
            </div>

            {/* Remember Me */}
            <div className="flex items-center">
              <input
                id="rememberMe"
                type="checkbox"
                checked={rememberMe}
                onChange={(e) => setRememberMe(e.target.checked)}
                className="h-4 w-4 rounded border-input text-primary focus:ring-2 focus:ring-ring focus:ring-offset-0"
              />
              <label
                htmlFor="rememberMe"
                className="ml-2 text-sm text-muted-foreground cursor-pointer select-none"
              >
                {t("auth.rememberMe")}
              </label>
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
            <Link href="/register" className="font-medium text-primary hover:underline">
              {t("auth.goRegister")}
            </Link>
          </p>
        </div>
      </div>
    </div>
  )
}
