import type { ResolvingMetadata } from "next"
import type { ReactNode } from "react"
import { internalPageMetadata } from "@/lib/page-metadata"

export function generateMetadata(_: unknown, parent: ResolvingMetadata) {
  return internalPageMetadata("通知管理", parent)
}

export default function NotificationsLayout({ children }: { children: ReactNode }) {
  return children
}
