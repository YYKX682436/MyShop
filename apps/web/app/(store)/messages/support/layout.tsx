import type { ResolvingMetadata } from "next"
import type { ReactNode } from "react"
import { internalPageMetadata } from "@/lib/page-metadata"

export function generateMetadata(_: unknown, parent: ResolvingMetadata) {
  return internalPageMetadata("主站客服", parent)
}

export default function PageTitleLayout({ children }: { children: ReactNode }) {
  return children
}
