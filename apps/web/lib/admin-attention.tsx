"use client"

import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react"

import { useAuth } from "@/lib/context"
import { isAdminRole } from "@/lib/hooks"
import { adminAttentionApi, requestOrThrow } from "@/services/api"

type AttentionListener = () => void

let revision = 0
const listeners = new Set<AttentionListener>()

export function invalidateAdminAttention(): number {
  revision += 1
  for (const listener of [...listeners]) listener()
  return revision
}

function subscribe(listener: AttentionListener) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

interface AdminAttentionContextValue {
  afterSaleNeedsAttention: number
  loading: boolean
  refresh: () => Promise<void>
}

const AdminAttentionContext = createContext<AdminAttentionContextValue>({
  afterSaleNeedsAttention: 0,
  loading: false,
  refresh: async () => undefined,
})

export function useAdminAttention() {
  return useContext(AdminAttentionContext)
}

export function AdminAttentionProvider({ children }: { children: ReactNode }) {
  const { user, authLoaded } = useAuth()
  const [afterSaleNeedsAttention, setAfterSaleNeedsAttention] = useState(0)
  const [loading, setLoading] = useState(false)
  const [invalidationRevision, setInvalidationRevision] = useState(revision)
  const isAdmin = authLoaded && isAdminRole(user?.role)

  useEffect(() => {
    const unsubscribe = subscribe(() => setInvalidationRevision(current => current + 1))
    return () => { unsubscribe() }
  }, [])

  const refresh = useCallback(async () => {
    if (!isAdmin) return
    setLoading(true)
    try {
      const summary = await requestOrThrow(() => adminAttentionApi.getSummary())
      setAfterSaleNeedsAttention(Math.max(0, summary.after_sale_needs_attention || 0))
    } catch {
      // Attention is advisory; retain the last successful count while an
      // authenticated background refresh is temporarily unavailable.
    } finally {
      setLoading(false)
    }
  }, [isAdmin])

  useEffect(() => {
    if (!isAdmin) {
      setAfterSaleNeedsAttention(0)
      return
    }
    void refresh()
    const interval = window.setInterval(() => void refresh(), 30_000)
    const refreshWhenVisible = () => {
      if (document.visibilityState === "visible") void refresh()
    }
    document.addEventListener("visibilitychange", refreshWhenVisible)
    window.addEventListener("focus", refreshWhenVisible)
    return () => {
      window.clearInterval(interval)
      document.removeEventListener("visibilitychange", refreshWhenVisible)
      window.removeEventListener("focus", refreshWhenVisible)
    }
  }, [isAdmin, refresh])

  useEffect(() => {
    if (invalidationRevision === 0 || !isAdmin) return
    void refresh()
  }, [invalidationRevision, isAdmin, refresh])

  const value = useMemo(() => ({ afterSaleNeedsAttention, loading, refresh }), [afterSaleNeedsAttention, loading, refresh])
  return <AdminAttentionContext.Provider value={value}>{children}</AdminAttentionContext.Provider>
}
