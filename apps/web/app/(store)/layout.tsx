import React from "react"
import { getSiteConfig } from "@/services/api-server"
import { StoreShell } from "./store-shell"

export default async function StoreLayout({ children }: { children: React.ReactNode }) {
  const config = await getSiteConfig()

  return (
    <StoreShell siteName={config.site_name || "MyShop"} logoUrl={config.logo_url}>
      {children}
    </StoreShell>
  )
}
