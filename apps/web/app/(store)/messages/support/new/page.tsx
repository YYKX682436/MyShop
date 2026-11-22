"use client"

import { Suspense, useEffect, useState } from "react"
import Link from "next/link"
import { useRouter, useSearchParams } from "next/navigation"
import { SupportThread } from "@/components/shared/support-thread"
import { useSupportIdentity } from "@/hooks/use-support-identity"
import { supportApi } from "@/services/api"

function NewSupport() {
  const identity = useSupportIdentity()
  const orderId = useSearchParams().get("order") || undefined
  const router = useRouter()
  const [ready, setReady] = useState("")
  const [orderNo, setOrderNo] = useState<number | undefined>()
  const [error, setError] = useState("")
  const scope = `${identity}:${orderId ?? "general"}`
  useEffect(() => {
    if (!identity) return
    const controller = new AbortController()
    setError("")
    const call = orderId ? supportApi.order(orderId, controller.signal) : supportApi.general(controller.signal)
    call.then(result => {
      if (controller.signal.aborted) return
      if (result.conversation_id) router.replace(`/messages/support/${result.conversation_id}`)
      else { setOrderNo("order_no" in result && typeof result.order_no === "number" ? result.order_no : undefined); setReady(scope) }
    }).catch(e => { if (!controller.signal.aborted) setError(e instanceof Error ? e.message : "无法打开会话") })
    return () => controller.abort()
  }, [identity, orderId, router, scope])
  return <div className="mx-auto max-w-3xl space-y-2 py-2"><Link href="/messages/support" className="inline-block text-sm text-muted-foreground">← 客服</Link>
    {error && <p role="alert" className="text-destructive">{error}</p>}
    {ready === scope && <SupportThread key={scope} orderId={orderId} orderNo={orderNo} identity={identity} onCreated={id => router.replace(`/messages/support/${id}`)} />}
  </div>
}
export default function NewSupportPage() { return <Suspense><NewSupport /></Suspense> }
