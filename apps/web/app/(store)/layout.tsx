import React from "react"
import { notFound } from "next/navigation"
import { ServerApiError } from "@/lib/server-http"
import { getSiteConfig } from "@/services/api-server"
import { StoreShell } from "./store-shell"

export default async function StoreLayout({ children }: { children: React.ReactNode }) {
  const config = await getSiteConfig().catch(error => {
    if (error instanceof ServerApiError && error.status === 404) notFound()
    throw error
  })

  return (
    <StoreShell siteName={config.site_name || "MyShop"} logoUrl={config.logo_url}>
      {children}
    </StoreShell>
  )
}
