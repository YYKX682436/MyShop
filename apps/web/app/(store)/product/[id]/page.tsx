import { notFound } from "next/navigation"
import Image from "next/image"
import { Package } from "lucide-react"
import { getAnonymousProductDetail, getViewerProductDetail, getPaymentChannels } from "@/services/api-server"
import { ProductPurchaseDock } from "./product-purchase-dock"
import { ProductBreadcrumb } from "./product-breadcrumb"
import { ProductDescription } from "@/components/shared/product-description"
import { ProductSummary } from "./product-summary"
import { ScrollToTop } from "./scroll-to-top"
import type { Metadata } from "next"
import type { PublicPaymentChannel } from "@/types"

export async function generateMetadata({ params }: { params: Promise<{ id: string }> }): Promise<Metadata> {
  const { id } = await params
  try {
    const product = await getAnonymousProductDetail(id)
    return {
      title: product.title,
      description: product.description || product.title,
      alternates: { canonical: `/product/${id}` },
      openGraph: {
        title: product.title,
        description: product.description || product.title,
        url: `/product/${id}`,
        type: "website",
        ...(product.cover_url ? { images: [{ url: product.cover_url }] } : {}),
      },
      twitter: {
        card: "summary_large_image",
        title: product.title,
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

  const [product, channels] = await Promise.all([
    getViewerProductDetail(id).catch(() => null),
    getPaymentChannels().catch(() => [] as PublicPaymentChannel[]),
  ])

  if (!product) notFound()

  const inStock = product.unlimited || product.specs?.some(spec => spec.unlimited || (spec.stock_available ?? 0) > 0) || (product.stock_available ?? 0) > 0
  const baseUrl = process.env.NEXT_PUBLIC_BASE_URL || "http://localhost:3000"
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
        : "https://schema.org/OutOfStock",
    },
  }

  return (
    <div className="product-detail-page flex flex-col gap-6 pb-[calc(7rem+env(safe-area-inset-bottom))] sm:gap-8 sm:pb-[calc(7.5rem+env(safe-area-inset-bottom))]">
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{ __html: JSON.stringify(jsonLd) }}
      />
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{ __html: JSON.stringify(breadcrumbLd) }}
      />

      {/* Breadcrumb */}
      <ProductBreadcrumb title={product.title} />

      <div className="product-detail-layout lg:grid-cols-[minmax(0,1.12fr)_minmax(20rem,0.88fr)] lg:items-start">
        {/* Left: Image — server rendered */}
        <div className="product-visual-column min-w-0">
          <div className="product-visual-stage p-3 sm:p-6">
            {product.cover_url ? (
              <Image
                src={product.cover_url}
                alt={product.title}
                fill
                unoptimized
                sizes="(min-width: 1024px) 60vw, 100vw"
                className="object-contain"
              />
            ) : (
              <div className="flex h-full w-full items-center justify-center">
                <Package className="size-20 text-muted-foreground/20" aria-hidden="true" />
              </div>
            )}
          </div>
        </div>

        {/* Right: Product-first summary; purchase controls live in the Dock. */}
        <div className="product-info-column">
          <ProductSummary product={product} />
        </div>
      </div>

      {/* Product Description */}
      {(product.detail_md?.trim() || product.description?.trim()) && (
        <div className="product-description-region">
          <ProductDescription product={product} />
        </div>
      )}

      <ProductPurchaseDock product={product} channels={channels} />

      {/* Scroll to top */}
      <ScrollToTop />
    </div>
  )
}
