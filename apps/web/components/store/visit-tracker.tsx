"use client"

import { useEffect } from "react"
import { usePathname } from "next/navigation"
import { getCsrfToken } from "@/services/api"

export function VisitTracker() {
  const pathname = usePathname()

  useEffect(() => {
    const csrf = getCsrfToken()
    fetch("/api/visit/track", {
      method: "POST",
      headers: csrf ? { "X-CSRF-Token": csrf } : undefined,
      keepalive: true,
    }).catch(() => {
      // fire-and-forget：采集失败不影响用户体验
    })
  }, [pathname])

  return null
}
