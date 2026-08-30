"use client"

import { useEffect } from "react"
import { useRouter, usePathname } from "next/navigation"
import { useAuth } from "./context"

export function isAdminRole(role: string | null | undefined): role is "ADMIN" | "SUPER_ADMIN" {
  return role === "ADMIN" || role === "SUPER_ADMIN"
}

/**
 * Redirects to login if user is not authenticated.
 * Passes current path as redirect param so login page can redirect back.
 */
export function useRequireAuth() {
  const { user, isLoggedIn, authLoaded } = useAuth()
  const router = useRouter()
  const pathname = usePathname()

  useEffect(() => {
    if (!authLoaded) return
    if (!isLoggedIn) {
      router.replace(`/login?redirect=${encodeURIComponent(pathname)}`)
    }
  }, [authLoaded, isLoggedIn, router, pathname])

  return authLoaded ? user : null
}

/**
 * Requires admin role.
 * - Not logged in → redirect to /login?redirect=currentPath
 * - Logged in but not ADMIN → redirect to /
 */
export function useRequireAdmin() {
  const { user, isLoggedIn, authLoaded } = useAuth()
  const router = useRouter()
  const pathname = usePathname()

  useEffect(() => {
    if (!authLoaded) return
    if (!isLoggedIn) {
      router.replace(`/login?redirect=${encodeURIComponent(pathname)}`)
    } else if (!isAdminRole(user?.role)) {
      router.replace("/")
    }
  }, [authLoaded, isLoggedIn, user, router, pathname])

  // 同步判断：未加载完或非管理员均返回 null，阻止子组件渲染
  if (!authLoaded || !isLoggedIn || !isAdminRole(user?.role)) return null
  return user
}

/** Requires the fixed super-administrator role for system-management pages. */
export function useRequireSuperAdmin() {
  const { user, isLoggedIn, authLoaded } = useAuth()
  const router = useRouter()
  const pathname = usePathname()

  useEffect(() => {
    if (!authLoaded) return
    if (!isLoggedIn) {
      router.replace(`/login?redirect=${encodeURIComponent(pathname)}`)
    } else if (user?.role !== "SUPER_ADMIN") {
      router.replace("/")
    }
  }, [authLoaded, isLoggedIn, user, router, pathname])

  if (!authLoaded || !isLoggedIn || user?.role !== "SUPER_ADMIN") return null
  return user
}
