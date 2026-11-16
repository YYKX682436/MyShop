import type { MetadataRoute } from "next"
import { getPublicSiteUrl } from "@/lib/site-url"

export const dynamic = "force-dynamic"

export default function robots(): MetadataRoute.Robots {
  const baseUrl = getPublicSiteUrl()
  return {
    rules: [
      {
        userAgent: "*",
        allow: "/",
        disallow: ["/admin/"],
      },
    ],
    sitemap: `${baseUrl}/sitemap.xml`,
  }
}
