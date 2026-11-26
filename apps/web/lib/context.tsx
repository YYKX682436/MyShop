"use client"

import { createContext, useContext, useState, useEffect, useCallback, useMemo, useRef, type ReactNode } from "react"
import { type Locale, getDictionary, type TranslationKey } from "./i18n"
import type { UserProfile, SiteConfig } from "@/types"
import { siteApi, userApi, authApi, requestOrThrow, setMessageViewer, isUnauthorizedApiError } from "@/services/api"

// ============================================================
// Theme
// ============================================================

export type Theme = "light" | "dark" | "system"

interface ThemeContextValue {
  theme: Theme
  setTheme: (t: Theme) => void
  resolvedTheme: "light" | "dark"
}

const ThemeContext = createContext<ThemeContextValue>({
  theme: "system",
  setTheme: () => {},
  resolvedTheme: "light",
})

export function useTheme() {
  return useContext(ThemeContext)
}

// ============================================================
// Locale
// ============================================================

interface LocaleContextValue {
  locale: Locale
  setLocale: (l: Locale) => void
  t: (key: TranslationKey) => string
}

const LocaleContext = createContext<LocaleContextValue>({
  locale: "zh",
  setLocale: () => {},
  t: (key) => key,
})

export function useLocale() {
  return useContext(LocaleContext)
}

// ============================================================
// Auth
// ============================================================

interface AuthContextValue {
  user: UserProfile | null
  setUser: (u: UserProfile | null) => void
  isLoggedIn: boolean
  authLoaded: boolean
  logout: () => Promise<void>
}

const AuthContext = createContext<AuthContextValue>({
  user: null,
  setUser: () => {},
  isLoggedIn: false,
  authLoaded: false,
  logout: async () => {},
})

export function useAuth() {
  return useContext(AuthContext)
}

// ============================================================
// Site Config
// ============================================================

interface SiteConfigContextValue {
  config: SiteConfig | null
  isLoading: boolean
  refresh: () => Promise<void>
}

const SiteConfigContext = createContext<SiteConfigContextValue>({
  config: null,
  isLoading: true,
  refresh: async () => {},
})

export function useSiteConfig() {
  return useContext(SiteConfigContext)
}

// ============================================================
// Combined Provider
// ============================================================

export function AppProviders({ children }: { children: ReactNode }) {
  // ThemeScript sets the first painted theme; effects then synchronize React state.

  // ---------- Theme ----------
  // 服务端和客户端都使用默认值，避免 hydration mismatch
  const [theme, setThemeState] = useState<Theme>("system")
  const [resolvedTheme, setResolvedTheme] = useState<"light" | "dark">("light")

  // 客户端 mount 后立即同步 localStorage 和 DOM 状态
  useEffect(() => {
    const savedTheme = (localStorage.getItem("theme") as Theme) || "system"
    const isDark = document.documentElement.classList.contains("dark")
    setThemeState(savedTheme)
    setResolvedTheme(isDark ? "dark" : "light")

    const updateColorSchemeMeta = (t: Theme, darkResolved: boolean) => {
      let meta = document.querySelector('meta[name="color-scheme"]')
      if (!meta) {
        meta = document.createElement("meta")
        meta.setAttribute("name", "color-scheme")
        document.head.appendChild(meta)
      }
      const content = t === "system" ? "light dark" : t
      meta.setAttribute("content", content)
      document.documentElement.style.colorScheme = darkResolved ? "dark" : "light"
    }

    updateColorSchemeMeta(savedTheme, isDark)

    // 监听系统主题变化
    const mq = window.matchMedia("(prefers-color-scheme: dark)")
    const handler = () => {
      const currentTheme = localStorage.getItem("theme") || "system"
      if (currentTheme === "system") {
        const systemIsDark = mq.matches
        setResolvedTheme(systemIsDark ? "dark" : "light")
        document.documentElement.classList.toggle("dark", systemIsDark)
        updateColorSchemeMeta("system", systemIsDark)
      }
    }
    mq.addEventListener("change", handler)
    return () => mq.removeEventListener("change", handler)
  }, [])

  const setTheme = useCallback((t: Theme) => {
    setThemeState(t)
    localStorage.setItem("theme", t)

    let meta = document.querySelector('meta[name="color-scheme"]')
    if (!meta) {
      meta = document.createElement("meta")
      meta.setAttribute("name", "color-scheme")
      document.head.appendChild(meta)
    }
    const content = t === "system" ? "light dark" : t
    meta.setAttribute("content", content)

    // 立即同步更新 DOM 和 resolvedTheme
    if (t === "system") {
      const isDark = window.matchMedia("(prefers-color-scheme: dark)").matches
      document.documentElement.classList.toggle("dark", isDark)
      document.documentElement.style.colorScheme = isDark ? "dark" : "light"
      setResolvedTheme(isDark ? "dark" : "light")
    } else {
      const isDark = t === "dark"
      document.documentElement.classList.toggle("dark", isDark)
      document.documentElement.style.colorScheme = isDark ? "dark" : "light"
      setResolvedTheme(isDark ? "dark" : "light")
    }
  }, [])

  // ---------- Locale ----------
  const [locale, setLocaleState] = useState<Locale>("zh")

  useEffect(() => {
    const saved = (localStorage.getItem("locale") as Locale) || "zh"
    setLocaleState(saved)
  }, [])

  const setLocale = useCallback((l: Locale) => {
    setLocaleState(l)
    localStorage.setItem("locale", l)
    document.documentElement.setAttribute("lang", l === "en" ? "en" : "zh")
  }, [])

  const t = useCallback(
    (key: TranslationKey) => {
      const dict = getDictionary(locale)
      return dict[key] || key
    },
    [locale]
  )

  // ---------- Auth ----------
  const [user, setUserState] = useState<UserProfile | null>(null)
  const [authLoaded, setAuthLoaded] = useState(false)
  const authVersionRef = useRef(0)
  const userRef = useRef<UserProfile | null>(null)
  const profileRefreshInFlight = useRef(false)
  const lastProfileRefreshAt = useRef(0)
  const initialAuthRetry = useRef<number | null>(null)
  const profileRetryTimer = useRef<number | null>(null)
  const refreshProfileRef = useRef<(force?: boolean) => Promise<void>>(async () => {})

  useEffect(() => { userRef.current = user }, [user])

  useEffect(() => {
    let cancelled = false
    let retries = 0
    const loadProfile = async () => {
      const requestVersion = authVersionRef.current
      const controller = new AbortController()
      const timeout = window.setTimeout(() => controller.abort(), 15_000)
      try {
        const profile = await userApi.getProfile(controller.signal)
        if (!cancelled && requestVersion === authVersionRef.current) {
          userRef.current = profile
          setMessageViewer(profile.id, profile.user_level_id)
          setUserState(profile)
          setAuthLoaded(true)
          initialAuthRetry.current = null
        }
      } catch (error) {
        if (cancelled || requestVersion !== authVersionRef.current) return
        if (isUnauthorizedApiError(error)) {
          userRef.current = null
          setMessageViewer(null)
          setUserState(null)
          setAuthLoaded(true)
          initialAuthRetry.current = null
          return
        }
        // A timeout, offline period, 403, or server error cannot establish
        // that the browser has no valid session. Keep guards in the loading
        // state and retry with bounded backoff instead of redirecting to login.
        setAuthLoaded(false)
        retries += 1
        const delay = Math.min(30_000, 1000 * 2 ** Math.min(retries - 1, 5))
        initialAuthRetry.current = window.setTimeout(() => { void loadProfile() }, delay)
      } finally {
        window.clearTimeout(timeout)
      }
    }
    void loadProfile()
    return () => {
      cancelled = true
      if (initialAuthRetry.current) window.clearTimeout(initialAuthRetry.current)
      if (profileRetryTimer.current) window.clearTimeout(profileRetryTimer.current)
    }
  }, [])

  const setUser = useCallback((u: UserProfile | null) => {
    authVersionRef.current += 1
    userRef.current = u
    if (profileRetryTimer.current) window.clearTimeout(profileRetryTimer.current)
    setMessageViewer(u?.id ?? null, u?.user_level_id)
    setUserState(u)
    setAuthLoaded(true)
  }, [])

  const refreshProfile = useCallback(async (force = false) => {
    const now = Date.now()
    if (profileRefreshInFlight.current || (!force && now - lastProfileRefreshAt.current < 3000)) return
    profileRefreshInFlight.current = true
    lastProfileRefreshAt.current = now
    const requestVersion = ++authVersionRef.current
    const previousUser = userRef.current
    if (!previousUser) {
      setMessageViewer(undefined)
      setAuthLoaded(false)
    }
    const controller = new AbortController()
    const timeout = window.setTimeout(() => controller.abort(), 15_000)
    try {
      const profile = await userApi.getProfile(controller.signal)
      if (requestVersion === authVersionRef.current) {
        userRef.current = profile
        setMessageViewer(profile.id, profile.user_level_id)
        setUserState(profile)
        setAuthLoaded(true)
      }
    } catch (error) {
      if (requestVersion === authVersionRef.current) {
        if (isUnauthorizedApiError(error)) {
          userRef.current = null
          setMessageViewer(null)
          setUserState(null)
          setAuthLoaded(true)
          if (profileRetryTimer.current) window.clearTimeout(profileRetryTimer.current)
        } else if (previousUser) {
          // Keep the last known identity during transient network or server
          // failures. The next focus/visibility event will retry the profile.
          setMessageViewer(previousUser.id, previousUser.user_level_id)
          setUserState(previousUser)
          setAuthLoaded(true)
          if (profileRetryTimer.current) window.clearTimeout(profileRetryTimer.current)
          profileRetryTimer.current = window.setTimeout(() => { void refreshProfileRef.current(true) }, 30_000)
        }
      }
    } finally {
      window.clearTimeout(timeout)
      profileRefreshInFlight.current = false
    }
  }, [])

  useEffect(() => { refreshProfileRef.current = refreshProfile }, [refreshProfile])

  useEffect(() => {
    const refreshWhenVisible = () => {
      if (document.visibilityState === "visible" && (authLoaded || userRef.current)) void refreshProfile()
    }
    const refreshOnIdentityMismatch = () => { void refreshProfile(true) }
    window.addEventListener("focus", refreshWhenVisible)
    document.addEventListener("visibilitychange", refreshWhenVisible)
    window.addEventListener("myshop:refresh-message-identity", refreshOnIdentityMismatch)
    return () => {
      window.removeEventListener("focus", refreshWhenVisible)
      document.removeEventListener("visibilitychange", refreshWhenVisible)
      window.removeEventListener("myshop:refresh-message-identity", refreshOnIdentityMismatch)
    }
  }, [authLoaded, refreshProfile])

  const isLoggedIn = useMemo(() => !!user, [user])

  const logout = useCallback(async () => {
    authVersionRef.current += 1
    const requestVersion = authVersionRef.current
    setMessageViewer(undefined)
    setAuthLoaded(false)
    try {
      await authApi.logout()
      if (requestVersion !== authVersionRef.current) return
      userRef.current = null
      setMessageViewer(null); setUserState(null); setAuthLoaded(true)
    } catch (error) {
      if (requestVersion !== authVersionRef.current) throw error
      setMessageViewer(user?.id ?? null, user?.user_level_id); setAuthLoaded(true)
      throw error
    }
  }, [user?.id, user?.user_level_id])

  // ---------- Site Config ----------
  const [siteConfig, setSiteConfig] = useState<SiteConfig | null>(null)
  const [siteConfigLoading, setSiteConfigLoading] = useState(true)
  const siteConfigVersion = useRef(0)
  const refreshSiteConfig = useCallback(async () => {
    const version = ++siteConfigVersion.current
    try {
      const config = await requestOrThrow(() => siteApi.getConfig())
      if (version === siteConfigVersion.current) setSiteConfig(config)
    } finally {
      if (version === siteConfigVersion.current) setSiteConfigLoading(false)
    }
  }, [])

  useEffect(() => {
    let cancelled = false
    const version = ++siteConfigVersion.current
    async function fetchConfig() {
      try {
        const config = await requestOrThrow(() => siteApi.getConfig())
        if (!cancelled && version === siteConfigVersion.current) setSiteConfig(config)
      } catch {
        if (!cancelled && version === siteConfigVersion.current) setSiteConfig(null)
      } finally {
        if (!cancelled && version === siteConfigVersion.current) setSiteConfigLoading(false)
      }
    }
    fetchConfig()
    return () => { cancelled = true }
  }, [])

  // ---------- Memoized Context Values ----------
  const themeValue = useMemo<ThemeContextValue>(() => ({ theme, setTheme, resolvedTheme }), [theme, setTheme, resolvedTheme])
  const localeValue = useMemo<LocaleContextValue>(() => ({ locale, setLocale, t }), [locale, setLocale, t])
  const authValue = useMemo<AuthContextValue>(() => ({ user, setUser, isLoggedIn, authLoaded, logout }), [user, setUser, isLoggedIn, authLoaded, logout])
  const siteConfigValue = useMemo<SiteConfigContextValue>(() => ({ config: siteConfig, isLoading: siteConfigLoading, refresh: refreshSiteConfig }), [siteConfig, siteConfigLoading, refreshSiteConfig])

  return (
    <ThemeContext.Provider value={themeValue}>
      <LocaleContext.Provider value={localeValue}>
        <AuthContext.Provider value={authValue}>
          <SiteConfigContext.Provider value={siteConfigValue}>
            {children}
          </SiteConfigContext.Provider>
        </AuthContext.Provider>
      </LocaleContext.Provider>
    </ThemeContext.Provider>
  )
}
