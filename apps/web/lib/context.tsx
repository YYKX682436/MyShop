"use client"

import { createContext, useContext, useState, useEffect, useCallback, useMemo, useRef, type ReactNode } from "react"
import { type Locale, getDictionary, type TranslationKey } from "./i18n"
import type { UserProfile, SiteConfig } from "@/types"
import { siteApi, userApi, authApi, requestOrThrow } from "@/services/api"

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
// Search
// ============================================================

export type SortKey = "default" | "hot" | "price_low" | "price_high" | "new"

interface SearchContextValue {
  searchQuery: string
  setSearchQuery: (q: string) => void
  searchOpen: boolean
  setSearchOpen: (open: boolean) => void
  sortBy: SortKey
  setSortBy: (s: SortKey) => void
  filterOpen: boolean
  setFilterOpen: (open: boolean) => void
  inStockOnly: boolean
  setInStockOnly: (v: boolean) => void
  priceMin: string
  setPriceMin: (v: string) => void
  priceMax: string
  setPriceMax: (v: string) => void
}

const SearchContext = createContext<SearchContextValue>({
  searchQuery: "",
  setSearchQuery: () => {},
  searchOpen: false,
  setSearchOpen: () => {},
  sortBy: "default",
  setSortBy: () => {},
  filterOpen: false,
  setFilterOpen: () => {},
  inStockOnly: false,
  setInStockOnly: () => {},
  priceMin: "",
  setPriceMin: () => {},
  priceMax: "",
  setPriceMax: () => {},
})

export function useSearch() {
  return useContext(SearchContext)
}

// ============================================================
// Auth
// ============================================================

interface AuthContextValue {
  user: UserProfile | null
  setUser: (u: UserProfile | null) => void
  isLoggedIn: boolean
  authLoaded: boolean
  logout: () => void
}

const AuthContext = createContext<AuthContextValue>({
  user: null,
  setUser: () => {},
  isLoggedIn: false,
  authLoaded: false,
  logout: () => {},
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
}

const SiteConfigContext = createContext<SiteConfigContextValue>({
  config: null,
  isLoading: true,
})

export function useSiteConfig() {
  return useContext(SiteConfigContext)
}

// ============================================================
// Combined Provider
// ============================================================

export function AppProviders({ children }: { children: ReactNode }) {
  // ==============================================================
  // 架构说明（重构版 - 零闪屏方案）：
  //
  // 1. ThemeScript（在 <head> 中同步执行）：
  //    - 在 React hydration 之前读取 localStorage 并直接设置 DOM
  //    - 设置 dark class、lang 属性
  //    - 确保首帧渲染就是正确的视觉状态
  //
  // 2. React State 初始化（本 Provider）：
  //    - 使用 lazy initializer 在首次渲染时同步读取 localStorage
  //    - 直接从 DOM 读取 ThemeScript 已设置好的状态
  //    - 零 useEffect，零二次渲染，零闪屏
  //
  // 3. 用户切换时：
  //    - 同时更新：React state + localStorage + DOM
  //    - 三者保持同步，无延迟
  //
  // 4. 登录状态（测试用）：
  //    - 采用相同的 lazy initializer 模式
  //    - 后续接入服务端时，只需替换数据源
  // ==============================================================

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

    // 监听系统主题变化
    const mq = window.matchMedia("(prefers-color-scheme: dark)")
    const handler = () => {
      const currentTheme = localStorage.getItem("theme") || "system"
      if (currentTheme === "system") {
        const systemIsDark = mq.matches
        setResolvedTheme(systemIsDark ? "dark" : "light")
        document.documentElement.classList.toggle("dark", systemIsDark)
      }
    }
    mq.addEventListener("change", handler)
    return () => mq.removeEventListener("change", handler)
  }, [])

  const setTheme = useCallback((t: Theme) => {
    setThemeState(t)
    localStorage.setItem("theme", t)

    // 立即同步更新 DOM 和 resolvedTheme
    if (t === "system") {
      const isDark = window.matchMedia("(prefers-color-scheme: dark)").matches
      document.documentElement.classList.toggle("dark", isDark)
      setResolvedTheme(isDark ? "dark" : "light")
    } else {
      const isDark = t === "dark"
      document.documentElement.classList.toggle("dark", isDark)
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

  useEffect(() => {
    let cancelled = false
    const requestVersion = authVersionRef.current
    userApi.getProfile()
      .then(profile => {
        if (!cancelled && requestVersion === authVersionRef.current) setUserState(profile)
      })
      .catch(() => {
        if (!cancelled && requestVersion === authVersionRef.current) setUserState(null)
      })
      .finally(() => {
        if (!cancelled && requestVersion === authVersionRef.current) setAuthLoaded(true)
      })
    return () => { cancelled = true }
  }, [])

  const setUser = useCallback((u: UserProfile | null) => {
    authVersionRef.current += 1
    setUserState(u)
    setAuthLoaded(true)
  }, [])

  const isLoggedIn = useMemo(() => !!user, [user])

  const logout = useCallback(() => {
    void authApi.logout().catch(() => undefined)
    setUser(null)
  }, [setUser])

  // ---------- Site Config ----------
  const [siteConfig, setSiteConfig] = useState<SiteConfig | null>(null)
  const [siteConfigLoading, setSiteConfigLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    async function fetchConfig() {
      try {
        const config = await requestOrThrow(() => siteApi.getConfig())
        if (!cancelled) setSiteConfig(config)
      } catch {
        if (!cancelled) setSiteConfig(null)
      } finally {
        if (!cancelled) setSiteConfigLoading(false)
      }
    }
    fetchConfig()
    return () => { cancelled = true }
  }, [])

  // ---------- Search + Sort/Filter（会话状态，无需持久化）----------
  const [searchQuery, setSearchQuery] = useState("")
  const [searchOpen, setSearchOpen] = useState(false)
  const [sortBy, setSortBy] = useState<SortKey>("default")
  const [filterOpen, setFilterOpen] = useState(false)
  const [inStockOnly, setInStockOnly] = useState(false)
  const [priceMin, setPriceMin] = useState("")
  const [priceMax, setPriceMax] = useState("")

  return (
    <ThemeContext.Provider value={{ theme, setTheme, resolvedTheme }}>
      <LocaleContext.Provider value={{ locale, setLocale, t }}>
        <AuthContext.Provider value={{ user, setUser, isLoggedIn, authLoaded, logout }}>
          <SiteConfigContext.Provider value={{ config: siteConfig, isLoading: siteConfigLoading }}>
            <SearchContext.Provider
              value={{
                searchQuery,
                setSearchQuery,
                searchOpen,
                setSearchOpen,
                sortBy,
                setSortBy,
                filterOpen,
                setFilterOpen,
                inStockOnly,
                setInStockOnly,
                priceMin,
                setPriceMin,
                priceMax,
                setPriceMax,
              }}
            >
              {children}
            </SearchContext.Provider>
          </SiteConfigContext.Provider>
        </AuthContext.Provider>
      </LocaleContext.Provider>
    </ThemeContext.Provider>
  )
}
