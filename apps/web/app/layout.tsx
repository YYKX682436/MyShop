import React from "react"
import type { Metadata } from "next"
import { Toaster } from "sonner"
import { AppProviders } from "@/lib/context"
import { MotionProvider } from "@/components/ui/motion"
import { ThemeScript } from "./theme-script"
import { DynamicFavicon } from "@/components/dynamic-favicon"
import { requestSiteUrl } from "@/lib/request-site"
import "./globals.css"

export const dynamic = "force-dynamic"

export async function generateMetadata(): Promise<Metadata> {
  return {
    metadataBase: new URL(await requestSiteUrl()),
    title: {
      template: "%s | MyShop",
      default: "MyShop - Auto Card Delivery",
    },
    description: "Automated virtual goods card delivery platform",
    robots: { index: true, follow: true },
    openGraph: {
      type: "website",
      locale: "zh_CN",
      siteName: "MyShop",
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
      </head>
      <body className="font-sans antialiased" suppressHydrationWarning>
        <MotionProvider>
          <AppProviders>
            <DynamicFavicon />
            {children}
            <Toaster position="top-center" richColors closeButton />
          </AppProviders>
        </MotionProvider>
      </body>
    </html>
  )
}
