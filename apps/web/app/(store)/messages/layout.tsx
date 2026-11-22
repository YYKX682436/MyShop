import type { Metadata } from "next"
import type { ReactNode } from "react"

export const metadata: Metadata = { title: "消息中心" }

export default function PageTitleLayout({ children }: { children: ReactNode }) {
  return children
}
