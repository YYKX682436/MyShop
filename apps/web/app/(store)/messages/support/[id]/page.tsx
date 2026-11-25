"use client"

import { useEffect, useState } from "react"
import { useParams, useRouter } from "next/navigation"
import { useSupportIdentity } from "@/hooks/use-support-identity"
import { supportApi } from "@/services/api"

/** Legacy order-thread URLs resolve to the unified customer conversation. */
export default function LegacySupportThreadPage() {
  const id = useParams<{ id: string }>().id
  const identity = useSupportIdentity()
  const router = useRouter()
  const [error, setError] = useState("")
  useEffect(() => {
    if (!identity) return
    const controller = new AbortController()
    supportApi.detail(id, false, controller.signal).then(item => {
      if (controller.signal.aborted) return
      router.replace(item.order_id ? `/messages/support?order=${encodeURIComponent(item.order_id)}` : "/messages/support")
    }).catch(reason => { if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : "无法打开客服") })
    return () => controller.abort()
  }, [id, identity, router])
  return error
    ? <p role="alert" className="mx-auto max-w-3xl py-8 text-sm text-destructive">{error}</p>
    : <div role="status" aria-label="正在打开客服" className="mx-auto max-w-3xl py-8"><div className="h-48 animate-pulse rounded-card bg-muted" /></div>
}
