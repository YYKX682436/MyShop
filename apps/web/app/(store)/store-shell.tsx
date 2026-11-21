import React from "react"
import { StoreHeader } from "@/components/layout/store-header"
import { StoreShellClient } from "./store-shell-client"

interface StoreShellProps {
  siteName: string
  logoUrl?: string
  children: React.ReactNode
}

export function StoreShell({ siteName, logoUrl, children }: StoreShellProps) {
  return (
    <StoreShellClient header={<StoreHeader siteName={siteName} logoUrl={logoUrl} />}>
      {children}
    </StoreShellClient>
  )
}
