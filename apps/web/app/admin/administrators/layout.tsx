import type { ResolvingMetadata } from "next"
import type { ReactNode } from "react"
import { internalPageMetadata } from "@/lib/page-metadata"

export function generateMetadata(_: unknown, parent: ResolvingMetadata) {
  return internalPageMetadata("管理员", parent)
}

export default function PageTitleLayout({ children }: { children: ReactNode }) {
  return children
}
