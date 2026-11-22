import type { Metadata } from "next"
import type { ReactNode } from "react"

export const metadata: Metadata = { title: "商品分类" }

export default function PageTitleLayout({ children }: { children: ReactNode }) {
  return children
}
