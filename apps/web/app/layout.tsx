import React from "react"
import type { Metadata, Viewport } from "next"
import { Toaster } from "sonner"
import { AppProviders } from "@/lib/context"
import { MotionProvider } from "@/components/ui/motion"
import { ThemeScript } from "./theme-script"
import { ViewportCompatibilityScript } from "./viewport-compatibility-script"
import { requestSiteUrl } from "@/lib/request-site"
import { getSiteConfig } from "@/services/api-server"
import "./globals.css"

export const dynamic = "force-dynamic"

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  viewportFit: "cover",
}

export async function generateMetadata(): Promise<Metadata> {
  const [siteUrl, config] = await Promise.all([requestSiteUrl(), getSiteConfig().catch(() => null)])
  const siteName = config?.site_name?.trim() || "商城"
  const favicon = config?.favicon_url?.trim() || "/default-icon.svg"
  return {
    metadataBase: new URL(siteUrl),
    title: {
      template: `%s | ${siteName}`,
      default: siteName,
    },
    icons: { icon: favicon, shortcut: favicon },
    robots: { index: true, follow: true },
    openGraph: {
      type: "website",
      locale: "zh_CN",
      siteName,
    },
    twitter: {
      card: "summary_large_image",
    },
  }
}

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode
}>) {
  return (
    <html lang="zh" suppressHydrationWarning>
      <head>
        <ThemeScript />
        <ViewportCompatibilityScript />
      </head>
      <body className="font-sans antialiased" suppressHydrationWarning>
        <MotionProvider>
          <AppProviders>
            {children}
            <Toaster position="top-center" richColors closeButton />
          </AppProviders>
        </MotionProvider>
      </body>
    </html>
  )
}
