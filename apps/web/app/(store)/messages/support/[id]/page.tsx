"use client"

import Link from "next/link"
import { useParams } from "next/navigation"
import { SupportThread } from "@/components/shared/support-thread"
import { useSupportIdentity } from "@/hooks/use-support-identity"

export default function SupportConversationPage() {
  const id = useParams<{ id: string }>().id
  const identity = useSupportIdentity()
  return <div className="mx-auto max-w-3xl space-y-2 py-2"><Link href="/messages/support" className="inline-block text-sm text-muted-foreground">← 客服</Link>
    {identity && <SupportThread key={`${identity}:${id}`} id={id} identity={identity} />}
  </div>
}
