"use client"

import { useEffect } from "react"
import { useRouter, useSearchParams } from "next/navigation"

/** Keep old order consultation URLs, routing them into the single customer thread. */
export default function LegacyNewSupportPage() {
  const router = useRouter()
  const search = useSearchParams()
  const orderId = search.get("order")
  const requestId = search.get("request_id")
  const round = search.get("round")
  useEffect(() => {
    const params = new URLSearchParams()
    if (orderId) params.set("order", orderId)
    if (requestId) params.set("request_id", requestId)
    if (round) params.set("round", round)
    router.replace(`/messages/support${params.size ? `?${params}` : ""}`)
  }, [orderId, requestId, round, router])
  return <div role="status" aria-label="正在打开客服" className="mx-auto max-w-3xl py-8"><div className="h-48 animate-pulse rounded-card bg-muted" /></div>
}
