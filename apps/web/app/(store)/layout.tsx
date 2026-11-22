import React from "react"
import { notFound } from "next/navigation"
import { ServerApiError } from "@/lib/server-http"
import { getSiteConfig } from "@/services/api-server"
import { StoreShellClient } from "./store-shell-client"

export default async function StoreLayout({ children }: { children: React.ReactNode }) {
  await getSiteConfig().catch(error => {
    if (error instanceof ServerApiError && error.status === 404) notFound()
    throw error
  })

  return (
    <StoreShellClient>
      {children}
    </StoreShellClient>
  )
}
