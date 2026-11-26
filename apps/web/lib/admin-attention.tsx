"use client"

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react"

import type { AdminAttentionSummary } from "@/types"
import { useAuth } from "@/lib/context"
import { isAdminRole } from "@/lib/hooks"
import { useVisiblePoll } from "@/hooks/use-visible-poll"
import { adminAttentionApi } from "@/services/api"

type AttentionListener = () => void

const EMPTY_SUMMARY: AdminAttentionSummary = {
  orders_total: 0,
  after_sale_needs_attention: 0,
  manual_fulfillment_orders: 0,
  supplier_exception_orders: 0,
  auto_delivery_exception_orders: 0,
  payment_exception_orders: 0,
  support_total: 0,
  support_unread: 0,
  support_pending: 0,
  supplier_needs_attention: 0,
  low_stock_products: 0,
  total: 0,
}

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
  summary: AdminAttentionSummary
  afterSaleNeedsAttention: number
  total: number
  loading: boolean
  refresh: () => Promise<boolean>
}

const AdminAttentionContext = createContext<AdminAttentionContextValue>({
  summary: EMPTY_SUMMARY,
  afterSaleNeedsAttention: 0,
  total: 0,
  loading: false,
  refresh: async () => false,
})

export function useAdminAttention() {
  return useContext(AdminAttentionContext)
}

export function AdminAttentionProvider({ children }: { children: ReactNode }) {
  const { user, authLoaded } = useAuth()
  const [storedSummary, setStoredSummary] = useState<{ identity: string; data: AdminAttentionSummary }>({ identity: "", data: EMPTY_SUMMARY })
  const [loading, setLoading] = useState(false)
  const [invalidationRevision, setInvalidationRevision] = useState(revision)
  const isAdmin = authLoaded && isAdminRole(user?.role)
  const identity = isAdmin && user?.id ? `${user.id}:${user.role}` : ""
  const summary = storedSummary.identity === identity ? storedSummary.data : EMPTY_SUMMARY
  const identityRef = useRef(identity)
  const sequence = useRef(0)
  const inFlight = useRef<{ identity: string; promise: Promise<boolean> } | null>(null)
  identityRef.current = identity

  useEffect(() => {
    const unsubscribe = subscribe(() => setInvalidationRevision(current => current + 1))
    return () => { unsubscribe() }
  }, [])

  useEffect(() => {
    sequence.current += 1
    inFlight.current = null
    setStoredSummary({ identity, data: EMPTY_SUMMARY })
    setLoading(false)
  }, [identity])

  const refresh = useCallback((signal?: AbortSignal) => {
    if (!isAdmin || !identity || document.visibilityState !== "visible") return Promise.resolve(false)
    if (inFlight.current?.identity === identity) return inFlight.current.promise
    const requestSequence = ++sequence.current
    setLoading(true)
    const promise = adminAttentionApi.getSummary({ signal })
      .then(data => {
        if (identityRef.current === identity && requestSequence === sequence.current) {
          const normalized = { ...EMPTY_SUMMARY }
          for (const key of Object.keys(normalized) as (keyof AdminAttentionSummary)[]) {
            const value = data[key]
            normalized[key] = typeof value === "number" && Number.isFinite(value) ? Math.max(0, value) : 0
          }
          setStoredSummary({ identity, data: normalized })
        }
        return true
      })
      .catch(() => false)
      .finally(() => {
        if (identityRef.current === identity && requestSequence === sequence.current) setLoading(false)
        if (inFlight.current?.promise === promise) inFlight.current = null
      })
    inFlight.current = { identity, promise }
    return promise
  }, [identity, isAdmin])

  useVisiblePoll(async signal => {
    const updated = await refresh(signal)
    if (!updated && !signal.aborted) throw new Error("Attention refresh failed")
  }, Boolean(identity), 15_000, identity)

  useEffect(() => {
    if (invalidationRevision === 0 || !identity || document.visibilityState !== "visible") return
    void refresh()
  }, [invalidationRevision, identity, refresh])

  const value = useMemo(() => ({
    summary,
    afterSaleNeedsAttention: summary.after_sale_needs_attention,
    total: summary.total,
    loading,
    refresh: () => refresh(),
  }), [summary, loading, refresh])
  return <AdminAttentionContext.Provider value={value}>{children}</AdminAttentionContext.Provider>
}
