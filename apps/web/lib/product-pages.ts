import type { PaginatedData, ProductCard } from "@/types"

/** Fetch every public product for sitemap generation, respecting the API's page limit. */
export async function getSitemapProducts(
  getPage: (params: { page: number; page_size: number }) => Promise<PaginatedData<ProductCard>>,
): Promise<ProductCard[]> {
  const products: ProductCard[] = []
  for (let page = 1; ; page += 1) {
    const result = await getPage({ page, page_size: 100 })
    products.push(...result.list)
    if (page * result.pagination.page_size >= result.pagination.total || result.list.length === 0) {
      return products
    }
  }
}
