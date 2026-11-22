"use client"

import { createContext, useContext, useState, type ReactNode } from "react"
import { useVisiblePoll } from "@/hooks/use-visible-poll"
import { useSupportIdentity } from "@/hooks/use-support-identity"
import { announcementApi, supportApi, type SiteAnnouncement, type SupportConversation } from "@/services/api"

type Summary = {
  announcements: { unread: number; latest: Pick<SiteAnnouncement, "id" | "title" | "body" | "published_at"> | null }
  support: { unread: number; latest: SupportConversation | null }
}
const empty: Summary = { announcements: { unread: 0, latest: null }, support: { unread: 0, latest: null } }
const MessageSummaryContext = createContext<Summary>(empty)
export function useMessageSummary() { return useContext(MessageSummaryContext) }
export function notifyMessagesChanged() { window.dispatchEvent(new Event("myshop:messages-changed")) }

export function MessageSummaryProvider({ children }: { children: ReactNode }) {
  const identity = useSupportIdentity()
  const [snapshot, setSnapshot] = useState<{ identity: string; value: Summary }>({ identity: "", value: empty })
  useVisiblePoll(async signal => {
    const [announcements, support] = await Promise.allSettled([announcementApi.summary(signal), supportApi.summary(signal)])
    if (signal.aborted) return
    if (announcements.status === "rejected" && support.status === "rejected") throw new Error("消息摘要读取失败")
    setSnapshot(previous => ({ identity, value: {
      announcements: announcements.status === "fulfilled" ? announcements.value : previous.identity === identity ? previous.value.announcements : empty.announcements,
      support: support.status === "fulfilled" ? support.value : previous.identity === identity ? previous.value.support : empty.support,
    } }))
    if (announcements.status === "rejected" || support.status === "rejected") throw new Error("部分消息摘要读取失败")
  }, !!identity, 30_000, identity)
  return <MessageSummaryContext.Provider value={snapshot.identity === identity ? snapshot.value : empty}>{children}</MessageSummaryContext.Provider>
}
