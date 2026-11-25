import { notFound } from "next/navigation"
import { ProductImageViewer } from "@/components/store/product-image-viewer"
import { Package } from "lucide-react"
import { getAnonymousProductDetail, getViewerProductDetail, getPaymentChannels, getSiteConfig } from "@/services/api-server"
import { ProductPurchaseDock } from "./product-purchase-dock"
import { hasPurchasableStock } from "@/lib/product-stock"
import { ProductBreadcrumb } from "./product-breadcrumb"
import { ProductDescription } from "@/components/shared/product-description"
import { ProductSummary } from "./product-summary"
import { ScrollToTop } from "./scroll-to-top"
import type { Metadata } from "next"
import type { PublicPaymentChannel } from "@/types"
import { serializeJsonLd } from "@/lib/json-ld"
import { requestSiteUrl } from "@/lib/request-site"
import { managedProductImagePath } from "@/lib/managed-product-image"

export async function generateMetadata({ params }: { params: Promise<{ id: string }> }): Promise<Metadata> {
  const { id } = await params
  try {
    const [product, config] = await Promise.all([getAnonymousProductDetail(id), getSiteConfig()])
    return {
      title: { absolute: `${product.title} | ${config.site_name?.trim() || "商城"}` },
      description: product.description || product.title,
      alternates: { canonical: `/product/${id}` },
      openGraph: {
        title: `${product.title} | ${config.site_name?.trim() || "商城"}`,
        siteName: config.site_name?.trim() || "商城",
        description: product.description || product.title,
        url: `/product/${id}`,
        type: "website",
        ...(product.cover_url ? { images: [{ url: product.cover_url }] } : {}),
      },
      twitter: {
        card: "summary_large_image",
        title: `${product.title} | ${config.site_name?.trim() || "商城"}`,
        description: product.description || product.title,
        ...(product.cover_url ? { images: [product.cover_url] } : {}),
      },
    }
  } catch {
    return { title: "Not Found" }
  }
}

export default async function ProductDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params

  const [product, channels, siteConfig] = await Promise.all([
    getViewerProductDetail(id).catch(() => null),
    getPaymentChannels().catch(() => [] as PublicPaymentChannel[]),
    getSiteConfig().catch(() => null),
  ])

  if (!product) notFound()

  const inStock = product.unlimited || (product.stock_status
    ? hasPurchasableStock(product.stock_available,false,product.stock_status)
    : product.specs?.some(spec => hasPurchasableStock(spec.stock_available,Boolean(spec.unlimited),spec.stock_status))
      || hasPurchasableStock(product.stock_available,false))
  const stockUnconfirmed=Boolean(product.stock_status && !["AVAILABLE","OUT_OF_STOCK"].includes(product.stock_status))
  const baseUrl = await requestSiteUrl()
  const allowedImageOrigins = [baseUrl]
  try { if (siteConfig?.main_site_url) allowedImageOrigins.push(new URL(siteConfig.main_site_url).origin) } catch { /* Ignore invalid configuration. */ }
  const previewSrc = product.cover_url ? managedProductImagePath(product.cover_url, allowedImageOrigins) ?? product.cover_url : ""
  const categoryPath = [product.primary_category_name, product.secondary_category_name]
    .filter((name): name is string => Boolean(name))

  const breadcrumbLd = {
    "@context": "https://schema.org",
      "@type": "BreadcrumbList",
      itemListElement: [
        { "@type": "ListItem", position: 1, name: "首页", item: baseUrl },
      ...categoryPath.map((name, index) => ({ "@type": "ListItem", position: index + 2, name })),
      { "@type": "ListItem", position: categoryPath.length + 2, name: product.title },
    ],
  }

  const jsonLd = {
    "@context": "https://schema.org",
    "@type": "Product",
    name: product.title,
    description: product.description || product.title,
    ...(product.cover_url ? { image: product.cover_url } : {}),
    offers: {
      "@type": "Offer",
      price: product.retail_price,
      priceCurrency: "CNY",
      availability: inStock
        ? "https://schema.org/InStock"
        : stockUnconfirmed ? undefined : "https://schema.org/OutOfStock",
    },
  }

  return (
    <div className="product-detail-page storefront-content-width flex flex-col gap-4 pb-[calc(6.5rem+env(safe-area-inset-bottom))] lg:gap-8 sm:pb-[calc(7.5rem+env(safe-area-inset-bottom))]">
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{ __html: serializeJsonLd(jsonLd) }}
      />
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{ __html: serializeJsonLd(breadcrumbLd) }}
      />

      {/* Breadcrumb */}
      <ProductBreadcrumb title={product.title} />

      <div className="product-detail-layout lg:grid-cols-[minmax(0,1.12fr)_minmax(20rem,0.88fr)] lg:items-start">
        {/* Left: Image — server rendered */}
        <div className="product-visual-column min-w-0">
          {product.cover_url ? (
            <ProductImageViewer src={product.cover_url} previewSrc={previewSrc} title={product.title} />
          ) : (
            <div className="product-image-stage product-image-stage--detail">
              <div className="flex h-full w-full items-center justify-center">
                <Package className="size-10 text-muted-foreground/20 lg:size-20" aria-hidden="true" />
              </div>
            </div>
          )}
        </div>

        {/* Right: Product-first summary; purchase controls live in the Dock. */}
        <div className="product-info-column">
          <ProductSummary product={product} />
        </div>
      </div>

      {/* Product Description */}
      {(product.detail_md?.trim() || product.description?.trim()) && (
        <div className="product-description-region">
          <ProductDescription product={product} separated={false} />
        </div>
      )}

      <ProductPurchaseDock product={product} channels={channels} />

      {/* Scroll to top */}
      <ScrollToTop />
    </div>
  )
}
