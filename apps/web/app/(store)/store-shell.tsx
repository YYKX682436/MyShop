import React from "react"
import { StoreHeader } from "@/components/layout/store-header"
import { StoreShellClient } from "./store-shell-client"

interface StoreShellProps {
  siteName: string
  children: React.ReactNode
}

export function StoreShell({ siteName, children }: StoreShellProps) {
  return (
    <StoreShellClient header={<StoreHeader siteName={siteName} />}>
      {children}
    </StoreShellClient>
  )
}
