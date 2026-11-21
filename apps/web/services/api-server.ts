/**
 * 服务端数据获取工具（仅用于 Server Component）
 *
 * 与客户端 api.ts 的区别：
 * - 直接使用 BACKEND_URL 调用后端，不走 Next.js rewrites 代理
 * - 不依赖 localStorage（服务端无浏览器环境）
 * - 商品价格按访问者 Cookie 获取；SEO 和站点配置使用匿名公开请求
 */

import type {
  ApiResponse,
  PaginatedData,
  ProductCard,
  ProductListQuery,
  ProductDetail,
  Category,
  PublicPaymentChannel,
  SiteConfig,
} from "@/types"
import { cookies } from "next/headers"
import { requestSiteUrl } from "@/lib/request-site"
import { getServerHttpResponse, ServerApiError } from "@/lib/server-http"

// 后端直连地址（Docker 内部网络或本地开发）
const BACKEND_URL = process.env.BACKEND_URL || "http://localhost:8083"

// ============================================================
// Core request
// ============================================================

async function serverRequest<T>(path: string, accessToken?: string): Promise<T> {
  const headers: Record<string, string> = { "Content-Type": "application/json" }
  // Preserve the validated storefront host for the API's site resolution.
  headers.Host = new URL(await requestSiteUrl()).host
  // Forward only the authentication cookie; unrelated browser cookies stay private.
  if (accessToken) headers.Cookie = `myshop_access_token=${accessToken}`
  // Node's fetch rewrites Host to the backend URL, even when a Host header is
  // supplied. The native HTTP client preserves the validated storefront host.
  const res = await getServerHttpResponse(`${BACKEND_URL}/api${path}`, headers)

  if (res.status < 200 || res.status >= 300) {
    throw new ServerApiError(res.status, res.statusText, path)
  }

  const body: ApiResponse<T> = JSON.parse(res.body)

  if (body.code !== 0) {
    throw new Error(body.message || `API error code: ${body.code}`)
  }

  return body.data
}

async function viewerAccessToken(): Promise<string | undefined> {
  const cookieStore = await cookies()
  return cookieStore.get("myshop_access_token")?.value || undefined
}

async function viewerRequest<T>(path: string): Promise<T> {
  return serverRequest<T>(path, await viewerAccessToken())
}

// ============================================================
// Public API — 商品
// ============================================================

function productQuery(params: ProductListQuery = {}): string {
  const sp = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== "") sp.set(key, String(value))
  }
  const qs = sp.toString()
  return `/products${qs ? `?${qs}` : ""}`
}

export async function getAnonymousProducts(params?: ProductListQuery): Promise<PaginatedData<ProductCard>> {
  return serverRequest<PaginatedData<ProductCard>>(productQuery(params))
}

export async function getViewerProducts(params?: ProductListQuery): Promise<PaginatedData<ProductCard>> {
  return viewerRequest<PaginatedData<ProductCard>>(productQuery(params))
}

/** Storefront lists are viewer-aware so the first server-rendered price is correct. */
export async function getProducts(params?: ProductListQuery): Promise<PaginatedData<ProductCard>> {
  return getViewerProducts(params)
}

export async function getAnonymousProductDetail(id: string): Promise<ProductDetail> {
  return serverRequest<ProductDetail>(`/products/${id}`)
}

export async function getViewerProductDetail(id: string): Promise<ProductDetail> {
  return viewerRequest<ProductDetail>(`/products/${id}`)
}

export async function getProductDetail(id: string): Promise<ProductDetail> {
  return getViewerProductDetail(id)
}

// ============================================================
// Public API — 分类
// ============================================================

export async function getCategories(): Promise<Category[]> {
  return serverRequest<Category[]>("/categories")
}

// ============================================================
// Public API — 支付渠道
// ============================================================

export async function getPaymentChannels(): Promise<PublicPaymentChannel[]> {
  return serverRequest<PublicPaymentChannel[]>("/payment-channels")
}

// ============================================================
// Public API — 站点配置
// ============================================================

export async function getSiteConfig(): Promise<SiteConfig> {
  return serverRequest<SiteConfig>("/site/config")
}
