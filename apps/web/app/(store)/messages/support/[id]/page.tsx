"use client"

import { Suspense, useEffect, useState } from "react"
import { useParams, useRouter, useSearchParams } from "next/navigation"
import { useSupportIdentity } from "@/hooks/use-support-identity"
import { supportApi } from "@/services/api"

/** Legacy order-thread URLs resolve to the unified customer conversation. */
export default function LegacySupportThreadPage() {
  return <Suspense fallback={<div role="status" aria-label="正在打开客服" className="mx-auto max-w-3xl py-8"><div className="h-48 animate-pulse rounded-card bg-muted" /></div>}><LegacySupportThreadContent /></Suspense>
}

function LegacySupportThreadContent() {
  const id = useParams<{ id: string }>().id
  const identity = useSupportIdentity()
  const router = useRouter()
  const search = useSearchParams()
  const requestId = search.get("request_id")
  const round = search.get("round")
  const notice = search.get("notice")
  const [error, setError] = useState("")
  useEffect(() => {
    if (!identity) return
    const controller = new AbortController()
    supportApi.detail(id, false, controller.signal).then(item => {
      if (controller.signal.aborted) return
      const params = new URLSearchParams()
      if (item.order_id) params.set("order", item.order_id)
      const saleRequest = requestId || item.request_id
      const saleRound = round || (item.round == null ? "" : String(item.round))
      if (saleRequest) params.set("request_id", saleRequest)
      if (saleRound) params.set("round", saleRound)
      if (notice) params.set("notice", notice)
      router.replace("/messages" + (params.size ? `?${params}` : ""))
    }).catch(reason => { if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : "无法打开客服") })
    return () => controller.abort()
  }, [id, identity, router, requestId, round, notice])
  return error
    ? <p role="alert" className="mx-auto max-w-3xl py-8 text-sm text-destructive">{error}</p>
    : <div role="status" aria-label="正在打开客服" className="mx-auto max-w-3xl py-8"><div className="h-48 animate-pulse rounded-card bg-muted" /></div>
}
