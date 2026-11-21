import type { MetadataRoute } from "next"
import { getAnonymousProducts } from "@/services/api-server"
import { getSitemapProducts } from "@/lib/product-pages"
import { requestSiteUrl } from "@/lib/request-site"

export const dynamic = "force-dynamic"

export default async function sitemap(): Promise<MetadataRoute.Sitemap> {
  const baseUrl = await requestSiteUrl()

  const staticRoutes: MetadataRoute.Sitemap = [
    { url: baseUrl, lastModified: new Date(), changeFrequency: "daily", priority: 1.0 },
    { url: `${baseUrl}/login`, changeFrequency: "monthly", priority: 0.3 },
    { url: `${baseUrl}/register`, changeFrequency: "monthly", priority: 0.3 },
  ]

  const products = await getSitemapProducts(getAnonymousProducts)
  const productRoutes: MetadataRoute.Sitemap = products.map((p) => ({
    url: `${baseUrl}/product/${p.id}`,
    lastModified: p.created_at ? new Date(p.created_at) : new Date(),
    changeFrequency: "weekly" as const,
    priority: 0.8,
  }))
  return [...staticRoutes, ...productRoutes]
}
