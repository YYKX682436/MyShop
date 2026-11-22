import type { Metadata } from "next"
import type { ReactNode } from "react"

export const metadata: Metadata = { title: "支付渠道" }

export default function PageTitleLayout({ children }: { children: ReactNode }) {
  return children
}
