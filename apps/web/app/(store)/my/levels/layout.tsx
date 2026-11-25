import type { ResolvingMetadata } from "next"
import type { ReactNode } from "react"
import { internalPageMetadata } from "@/lib/page-metadata"

export function generateMetadata(_: unknown, parent: ResolvingMetadata) {
  return internalPageMetadata("等级升级", parent)
}

export default function LevelsLayout({ children }: { children: ReactNode }) {
  return children
}
