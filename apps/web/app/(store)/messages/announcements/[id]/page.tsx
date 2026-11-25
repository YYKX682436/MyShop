"use client"

import { useEffect } from "react"
import { useParams, useRouter } from "next/navigation"

/** Historical single-notice links now land in the same scrollable feed. */
export default function LegacyNotificationLink() {
  const id = useParams<{ id: string }>().id
  const router = useRouter()
  useEffect(() => {
    router.replace(`/messages/announcements?focus=${encodeURIComponent(id)}`)
  }, [id, router])
  return <div role="status" aria-label="正在打开通知" className="mx-auto max-w-3xl py-8"><div className="h-40 animate-pulse rounded-card border border-border-subtle bg-muted" /></div>
}
