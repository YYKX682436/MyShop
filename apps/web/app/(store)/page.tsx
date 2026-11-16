import { getCategories, getSiteConfig, getProducts } from "@/services/api-server"
import { HomeContent } from "./home-content"
import type { Metadata } from "next"
import { serializeJsonLd } from "@/lib/json-ld"

export async function generateMetadata(): Promise<Metadata> {
  try {
    const config = await getSiteConfig()
    return {
      title: config.site_name || "MyShop",
      description: config.site_description || config.site_slogan || "",
      alternates: { canonical: "/" },
      openGraph: {
        title: config.site_name || "MyShop",
        description: config.site_description || config.site_slogan || "",
        url: "/",
        ...(config.logo_url ? { images: [{ url: config.logo_url }] } : {}),
      },
    }
  } catch {
    return { title: "MyShop" }
  }
}

export default async function HomePage() {
  const [productsResult, categories, config] = await Promise.all([
    getProducts({ page: 1, page_size: 24 })
      .then(data => ({ data, error: false }))
      .catch(() => ({
        data: { list: [] as never[], pagination: { page: 1, page_size: 24, total: 0 } },
        error: true,
      })),
    getCategories().catch(() => []),
    getSiteConfig().catch(() => null),
  ])

  const jsonLd = {
    "@context": "https://schema.org",
    "@type": "WebSite",
    name: config?.site_name || "MyShop",
    description: config?.site_description || config?.site_slogan || "",
  }

  return (
    <>
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{ __html: serializeJsonLd(jsonLd) }}
      />
      <HomeContent
        initialProducts={productsResult.data}
        categories={categories}
        siteSlogan={config?.site_slogan || ""}
        siteDescription={config?.site_description || ""}
        productsError={productsResult.error}
      />
    </>
  )
}
