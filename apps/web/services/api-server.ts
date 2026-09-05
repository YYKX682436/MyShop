/**
 * 服务端数据获取工具（仅用于 Server Component）
 *
 * 与客户端 api.ts 的区别：
 * - 直接使用 BACKEND_URL 调用后端，不走 Next.js rewrites 代理
 * - 不依赖 localStorage（服务端无浏览器环境）
 * - 仅封装公开接口（不需要 auth token）
 */

import type {
  ApiResponse,
  PaginatedData,
  ProductCard,
  ProductDetail,
  Category,
  PublicPaymentChannel,
  SiteConfig,
} from "@/types"
import { cookies } from "next/headers"

// 后端直连地址（Docker 内部网络或本地开发）
const BACKEND_URL = process.env.BACKEND_URL || "http://localhost:8083"

// ============================================================
// Core request
// ============================================================

async function serverRequest<T>(path: string, accessToken?: string): Promise<T> {
  const headers: Record<string, string> = { "Content-Type": "application/json" }
  // Forward only the authentication cookie; unrelated browser cookies stay private.
  if (accessToken) headers.Cookie = `myshop_access_token=${accessToken}`
  const res = await fetch(`${BACKEND_URL}/api${path}`, {
    headers,
    cache: "no-store",
  })

  if (!res.ok) {
    throw new Error(`Server API error: ${res.status} ${res.statusText} [${path}]`)
  }

  const body: ApiResponse<T> = await res.json()

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

function productQuery(params?: {
  page?: number
  page_size?: number
  category_id?: string
  keyword?: string
}): string {
  const sp = new URLSearchParams()
  if (params?.page) sp.set("page", String(params.page))
  if (params?.page_size) sp.set("page_size", String(params.page_size))
  if (params?.category_id) sp.set("category_id", params.category_id)
  if (params?.keyword) sp.set("keyword", params.keyword)
  const qs = sp.toString()
  return `/products${qs ? `?${qs}` : ""}`
}

export async function getAnonymousProducts(params?: {
  page?: number
  page_size?: number
  category_id?: string
  keyword?: string
}): Promise<PaginatedData<ProductCard>> {
  return serverRequest<PaginatedData<ProductCard>>(productQuery(params))
}

export async function getViewerProducts(params?: {
  page?: number
  page_size?: number
  category_id?: string
  keyword?: string
}): Promise<PaginatedData<ProductCard>> {
  return viewerRequest<PaginatedData<ProductCard>>(productQuery(params))
}

/** Storefront lists are viewer-aware so the first server-rendered price is correct. */
export async function getProducts(params?: {
  page?: number
  page_size?: number
  category_id?: string
  keyword?: string
}): Promise<PaginatedData<ProductCard>> {
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
