import type {
  ApiResponse,
  PaginatedData,
  UserProfile,
  RegisterRequest,
  OrderBrief,
  OrderStatus,
  ProductCard,
  ProductDetail,
  AdminProductDetail,
  AdminProductSpec,
  AdminPricingPreview,
  Category,
  CreateOrderRequest,
  CreateOrderResult,
  DeliverResult,
  SiteConfig,
  SiteConfigKV,
  DashboardStats,
  SalesTrend,
  SystemStatus,
  CardKeyStockSummary,
  CardKeyListItem,
  AdminCardKeyPickupResult,
  CardImportBatch,
  OrderCardKey,
  AdminUserItem,
  AdminUserDetail,
  AdminUserOrder,
  BalanceTransaction,
  AdminOperationLogDetail,
  UserNumberConfig,
  AdminOrderItem,
  PaymentChannelItem,
  PaymentProviderMetadata,
  OperationLog,
  RiskConfig,
  UserLevel,
  CaptchaResult,
  AuthResult,
  AdministratorItem,
  AdministratorOperationLog,
} from "@/types"
import type { AdminProductPricesUpdatePayload } from "@/lib/product-quick-edit"
import { buildAdminOrderSearchBody } from "@/lib/admin-order-search"
import type { AdminOrderSearchParams } from "@/lib/admin-order-search"

// ============================================================
// Config
// ============================================================

const API_BASE = process.env.NEXT_PUBLIC_API_BASE || "/api"

// ============================================================
// Error
// ============================================================

class ApiError extends Error {
  code: number
  params?: Record<string, string | number>
  constructor(code: number, message: string, params?: Record<string, string | number>) {
    super(message)
    this.code = code
    this.params = params
    this.name = "ApiError"
  }
}

/**
 * JWT 过期/无效时：跳转登录页；HttpOnly 登录 Cookie 由服务端控制
 * 使用防抖避免多个并发请求同时触发多次跳转
 */
let redirecting = false
function handleUnauthorized() {
  if (typeof window === "undefined" || redirecting) return
  redirecting = true
  const currentPath = window.location.pathname
  // 已经在登录页则不再跳转
  if (currentPath === "/login") {
    redirecting = false
    return
  }
  // This shared API module needs a hard redirect when an HttpOnly session has expired.
  // eslint-disable-next-line @next/next/no-location-assign-relative-destination
  window.location.assign(`/login?redirect=${encodeURIComponent(currentPath)}`)
}

// ============================================================
// Session Token (guest order)
// ============================================================

function getSessionToken(): string | null {
  if (typeof window === "undefined") return null
  return localStorage.getItem("session_token")
}

function setSessionToken(token: string) {
  localStorage.setItem("session_token", token)
}

/** Direct-buy guests also need a stable owner for idempotency replays. */
export function getOrCreateGuestSessionToken(): string {
  if (typeof window === "undefined") return ""
  const existing = getSessionToken()
  if (existing) return existing
  const token = typeof crypto !== "undefined" && crypto.randomUUID
    ? crypto.randomUUID()
    : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`
  setSessionToken(token)
  return token
}

export function clearSessionToken() {
  localStorage.removeItem("session_token")
}

const ORDER_TOKEN_PREFIX = "myshop_order_lookup_by_no:"

export function saveOrderLookupToken(orderNo: number | string, token: string | undefined) {
  if (typeof window !== "undefined" && token) {
    localStorage.setItem(`${ORDER_TOKEN_PREFIX}${orderNo}`, token)
  }
}

export function getOrderLookupToken(orderNo: number | string): string | undefined {
  if (typeof window === "undefined") return undefined
  return localStorage.getItem(`${ORDER_TOKEN_PREFIX}${orderNo}`) || undefined
}

// ============================================================
// Query builder
// ============================================================

function buildQuery(params: Record<string, string | number | boolean | undefined | null>): string {
  const sp = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== "") {
      sp.set(key, String(value))
    }
  }
  return sp.toString()
}

function getCsrfToken(): string | null {
  if (typeof document === "undefined") return null
  const entry = document.cookie.split(";").map(value => value.trim()).find(value => value.startsWith("myshop_csrf="))
  return entry ? decodeURIComponent(entry.slice("myshop_csrf=".length)) : null
}

// ============================================================
// Device Fingerprint
// ============================================================

let cachedDeviceId: string | null = null

async function ensureDeviceId(): Promise<string> {
  if (cachedDeviceId) return cachedDeviceId
  if (typeof window === "undefined") return ""
  try {
    const { getDeviceId } = await import("@/lib/fingerprint")
    cachedDeviceId = await getDeviceId()
    return cachedDeviceId
  } catch {
    return ""
  }
}

// 启动时异步预热，不阻塞首屏
if (typeof window !== "undefined") {
  ensureDeviceId()
}

// ============================================================
// Turnstile token（模块级别，由页面组件设置）
// ============================================================

let pendingTurnstileToken: string | null = null

/** 设置 Turnstile token（在调用受保护 API 前由页面组件调用） */
export function setTurnstileHeaders(token: string) {
  pendingTurnstileToken = token
}

/** 消费并清除 Turnstile token（request 内部使用） */
function consumeTurnstileToken(): string | null {
  const t = pendingTurnstileToken
  pendingTurnstileToken = null
  return t
}

// ============================================================
// Core request
// ============================================================

async function request<T>(
  path: string,
  options: RequestInit = {}
): Promise<T> {
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    ...(options.headers as Record<string, string>),
  }

  const method = (options.method || "GET").toUpperCase()
  if (["POST", "PUT", "PATCH", "DELETE"].includes(method)) {
    const csrf = getCsrfToken()
    if (csrf) headers["X-CSRF-Token"] = csrf
  }
  // Session token only owns the guest order flow; authentication remains
  // exclusively in the HttpOnly access cookie.
  const sessionToken = getSessionToken()
  if (sessionToken) {
    headers["X-Session-Token"] = sessionToken
  }

  // 设备指纹 — 始终发送
  const deviceId = cachedDeviceId || (await ensureDeviceId())
  if (deviceId) {
    headers["X-Device-Id"] = deviceId
  }

  // Turnstile token — 如果有 pending token 则发送（单次消费）
  const turnstileToken = consumeTurnstileToken()
  if (turnstileToken) {
    headers["X-Turnstile-Token"] = turnstileToken
  }

  const res = await fetch(`${API_BASE}${path}`, {
    ...options,
    credentials: "include",
    headers,
  })

  // Capture session token from response
  const newSessionToken = res.headers.get("X-Session-Token")
  if (newSessionToken) {
    setSessionToken(newSessionToken)
  }

  if (!res.ok) {
    if (res.status === 401 && path !== "/user/profile") {
      handleUnauthorized()
    }
    const body = await res.json().catch(() => ({ code: res.status, message: res.statusText }))
    throw new ApiError(body.code || res.status, body.message || res.statusText, body.params)
  }

  const body: ApiResponse<T> = await res.json()

  if (body.code !== 0) {
    throw new ApiError(body.code, body.message, body.params)
  }

  return body.data
}

async function uploadRequest<T>(path: string, formData: FormData): Promise<T> {
  const headers: Record<string, string> = {}
  const csrf = getCsrfToken()
  if (csrf) headers["X-CSRF-Token"] = csrf
  const sessionToken = getSessionToken()
  if (sessionToken) headers["X-Session-Token"] = sessionToken

  const res = await fetch(`${API_BASE}${path}`, {
    method: "POST",
    headers,
    credentials: "include",
    body: formData,
  })

  if (!res.ok) {
    if (res.status === 401) {
      handleUnauthorized()
    }
    const body = await res.json().catch(() => ({ code: res.status, message: res.statusText }))
    throw new ApiError(body.code || res.status, body.message || res.statusText, body.params)
  }

  const body: ApiResponse<T> = await res.json()

  if (body.code !== 0) {
    throw new ApiError(body.code, body.message, body.params)
  }

  return body.data
}

async function requestText(path: string): Promise<string> {
  const headers: Record<string, string> = {}
  const sessionToken = getSessionToken()
  if (sessionToken) headers["X-Session-Token"] = sessionToken
  const csrf = getCsrfToken()
  if (csrf) headers["X-CSRF-Token"] = csrf
  const res = await fetch(`${API_BASE}${path}`, { credentials: "include", headers })
  if (!res.ok) {
    if (res.status === 401) handleUnauthorized()
    const body = await res.json().catch(() => ({ code: res.status, message: res.statusText }))
    throw new ApiError(body.code || res.status, body.message || res.statusText, body.params)
  }
  return res.text()
}

// ============================================================
// Strict API requests
// ============================================================

/** Execute a required API call; errors are never replaced with local data. */
export async function requestOrThrow<T>(apiCall: () => Promise<T>): Promise<T> {
  return apiCall()
}


// ============================================================
// Auth
// ============================================================

export const authApi = {
  getCaptcha: () =>
    request<CaptchaResult>("/auth/captcha"),
  register: (data: RegisterRequest) =>
    request<AuthResult>("/auth/register", { method: "POST", body: JSON.stringify(data) }),
  login: (data: { account: string; password: string }) =>
    request<AuthResult>("/auth/login", { method: "POST", body: JSON.stringify(data) }),
  logout: () =>
    request<null>("/auth/logout", { method: "POST" }),
}

// ============================================================
// User
// ============================================================

export const userApi = {
  getProfile: () =>
    request<UserProfile>("/user/profile"),
  updatePassword: (data: { old_password: string; new_password: string }) =>
    request<null>("/user/password", { method: "PUT", body: JSON.stringify(data) }),
  getOrders: (params: { page?: number; page_size?: number; status?: string }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<OrderBrief>>(`/user/orders?${qs}`)
  },
}

// ============================================================
// Product
// ============================================================

export const productApi = {
  getList: (params: { page?: number; page_size?: number; category_id?: string; keyword?: string }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<ProductCard>>(`/products?${qs}`)
  },
  getDetail: (id: string) =>
    request<ProductDetail>(`/products/${id}`),
  getCategories: () =>
    request<Category[]>("/categories"),
}

// ============================================================
// Order
// ============================================================

export const orderApi = {
  create: (data: CreateOrderRequest) => {
    getOrCreateGuestSessionToken()
    return request<CreateOrderResult>("/orders", { method: "POST", body: JSON.stringify(data) })
  },
  getStatus: (orderId: string, lookupToken?: string, orderNo?: number | string) =>
    request<{ order_id: string; order_no: number; status: OrderStatus; expires_at: string | null; remaining_seconds: number; payment_url?: string }>(`/orders/${orderId}/status?${buildQuery({ lookup_token: lookupToken || (orderNo === undefined ? undefined : getOrderLookupToken(orderNo)) })}`),
  refreshStatus: (orderId: string, lookupToken?: string, orderNo?: number | string) =>
    request<{ status: OrderStatus; order_no: number }>(`/orders/${orderId}/refresh?${buildQuery({ lookup_token: lookupToken || (orderNo === undefined ? undefined : getOrderLookupToken(orderNo)) })}`, { method: "POST" }),
  query: (data: { orders: { order_no: number | string; lookup_token?: string }[] }) =>
    request<OrderBrief[]>("/orders/query", { method: "POST", body: JSON.stringify(data) }),
  deliver: (data: { orders: { order_no: number | string; lookup_token?: string }[] }) =>
    request<DeliverResult[]>("/orders/deliver", { method: "POST", body: JSON.stringify(data) }),
  exportKeys: (orderId: string, orderNo: number | string, lookupToken?: string) =>
    requestText(`/orders/${orderId}/export?${buildQuery({ order_no: orderNo, lookup_token: lookupToken || getOrderLookupToken(orderNo) })}`),
  repay: (orderId: string, orderNo: number | string, device?: string, lookupToken?: string) =>
    request<import("@/types").PaymentCreateResult>(`/orders/${orderId}/repay`, {
      method: "POST",
      body: JSON.stringify({ device, lookup_token: lookupToken || getOrderLookupToken(orderNo) }),
    }),
}

// ============================================================
// Site Config (public)
// ============================================================

export const siteApi = {
  getConfig: () =>
    request<SiteConfig>("/site/config"),
}

// ============================================================
// Payment Channels (public, for store display)
// ============================================================

export const paymentApi = {
  getChannels: () =>
    request<PaymentChannelItem[]>("/payment-channels"),
  getProviders: () =>
    request<PaymentProviderMetadata[]>("/payment-providers"),
}

// ============================================================
// Admin Dashboard
// ============================================================

export const adminDashboardApi = {
  getStats: (options?: RequestInit) =>
    request<DashboardStats>("/admin/dashboard", options),
  getSalesTrend: (params: { period?: string; start_date?: string; end_date?: string }, options?: RequestInit) => {
    const qs = buildQuery(params)
    return request<SalesTrend[]>(`/admin/dashboard/sales-trend?${qs}`, options)
  },
  getSystemStatus: (options?: RequestInit) =>
    request<SystemStatus>("/admin/dashboard/system-status", options),
}

// ============================================================
// Admin Product
// ============================================================

export const adminProductApi = {
  getList: (params: {
    page?: number; page_size?: number; category_id?: string; is_enabled?: number; keyword?: string;
    sort_by?: "sort_weight" | "created_at" | "product_no" | "retail_price" | "cost_price";
    sort_direction?: "asc" | "desc";
  }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<AdminProductDetail>>(`/admin/products?${qs}`)
  },
  getDetail: (id: string) =>
    request<AdminProductDetail>(`/admin/products/${id}`),
  create: (data: AdminProductUpsertPayload) =>
    request<AdminProductDetail>("/admin/products", { method: "POST", body: JSON.stringify(data) }),
  update: (id: string, data: AdminProductUpsertPayload) =>
    request<AdminProductDetail>(`/admin/products/${id}`, { method: "PUT", body: JSON.stringify(data) }),
  setStatus: (id: string, enabled: boolean) =>
    request<AdminProductDetail>(`/admin/products/${id}/status`, {
      method: "POST",
      body: JSON.stringify({ enabled }),
    }),
  updateTitle: (id: string, data: { title: string }) =>
    request<AdminProductDetail>(`/admin/products/${id}/title`, {
      method: "PUT",
      body: JSON.stringify(data),
    }),
  updateCategory: (id: string, data: { category_id: string }) =>
    request<AdminProductDetail>(`/admin/products/${id}/category`, {
      method: "PUT",
      body: JSON.stringify(data),
    }),
  updatePrices: (id: string, data: AdminProductPricesUpdatePayload) =>
    request<AdminProductDetail>(`/admin/products/${id}/prices`, {
      method: "PUT",
      body: JSON.stringify(data),
    }),
  updateSortWeight: (id: string, sortWeight: number) =>
    request<null>(`/admin/products/${id}/sort-weight`, {
      method: "PATCH",
      body: JSON.stringify({ sort_weight: sortWeight }),
    }),
  delete: (id: string) =>
    request<null>(`/admin/products/${id}`, { method: "DELETE" }),
  // Specs
  getSpecs: (productId: string) =>
    request<AdminProductSpec[]>(`/admin/products/${productId}/specs`),
  previewPricing: (data: AdminPricingPreviewPayload, signal?: AbortSignal) =>
    request<AdminPricingPreview>("/admin/products/pricing-preview", {
      method: "POST",
      body: JSON.stringify(data),
      signal,
    }),
  // Image upload
  uploadImage: (file: File) => {
    const formData = new FormData()
    formData.append("file", file)
    return uploadRequest<{ url: string }>("/upload/image", formData)
  },
}

// ============================================================
// Admin Category
// ============================================================

export const adminCategoryApi = {
  getList: () =>
    request<Category[]>("/admin/categories"),
  create: (data: { name: string; sort_order?: number }) =>
    request<null>("/admin/categories", { method: "POST", body: JSON.stringify(data) }),
  update: (id: string, data: { name?: string; sort_order?: number }) =>
    request<null>(`/admin/categories/${id}`, { method: "PUT", body: JSON.stringify(data) }),
  delete: (id: string) =>
    request<null>(`/admin/categories/${id}`, { method: "DELETE" }),
}

// ============================================================
// Admin Card Keys
// ============================================================

export const adminCardKeyApi = {
  getList: (params: { product_id: string; spec_id?: string | null; page?: number; page_size?: number }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<CardKeyListItem>>(`/admin/card-keys/list?${qs}`)
  },
  getStock: (params?: { product_id?: string; spec_id?: string }) => {
    const qs = buildQuery(params ?? {})
    return request<CardKeyStockSummary[]>(`/admin/card-keys/stock?${qs}`)
  },
  import: (data: { product_id: string; spec_id?: string | null; content_columns?: string[]; check_duplicate?: boolean }) =>
    request<CardImportBatch>("/admin/card-keys/import", { method: "POST", body: JSON.stringify(data) }),
  getImportBatches: (params: { product_id?: string; page?: number; page_size?: number }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<CardImportBatch>>(`/admin/card-keys/import-batches?${qs}`)
  },
  invalidate: (id: string) =>
    request<null>(`/admin/card-keys/${id}/invalidate`, { method: "POST" }),
  batchInvalidate: (params: { product_id: string; spec_id?: string | null }) => {
    const qs = buildQuery(params)
    return request<{ invalidated_count: number }>(`/admin/card-keys/batch-invalidate?${qs}`, { method: "POST" })
  },
  batchPickup: (data: { product_id: string; spec_id: string | null; quantity: number }) =>
    request<AdminCardKeyPickupResult>("/admin/card-keys/batch-pickup", { method: "POST", body: JSON.stringify(data) }),
  pickup: (id: string) =>
    request<AdminCardKeyPickupResult>(`/admin/card-keys/${id}/pickup`, { method: "POST" }),
  getByOrder: (orderId: string) =>
    request<OrderCardKey[]>(`/admin/card-keys/by-order/${orderId}`),
}

// ============================================================
// Admin Order
// ============================================================

export const adminOrderApi = {
  getList: (params: AdminOrderSearchParams = {}, options?: RequestInit) => {
    return request<PaginatedData<AdminOrderItem>>("/admin/orders/search", {
      ...options,
      method: "POST",
      body: JSON.stringify(buildAdminOrderSearchBody(params)),
    })
  },
  getDetail: (id: string) =>
    request<AdminOrderItem>(`/admin/orders/${id}`),
  markPaid: (id: string, reason: string) =>
    request<null>(`/admin/orders/${id}/mark-paid`, {
      method: "POST",
      body: JSON.stringify({ reason, confirmed: true }),
    }),
  completeManualItem: (orderId: string, orderItemId: string) =>
    request<{ order_id: string; status: string }>(`/admin/orders/${orderId}/items/${orderItemId}/complete-manual`, {
      method: "POST",
    }),
}

export interface AdminProductUpsertPayload {
  title: string
  description?: string
  detail_md?: string
  cover_url?: string
  cost_price: number | null
  retail_price?: number | null
  category_id: string
  low_stock_threshold?: number | null
  spec_enabled: boolean
  is_enabled: boolean
  initial_sales?: number | null
  sort_weight?: number | null
  delivery_type: "AUTO" | "MANUAL"
  customer_input_fields?: { id?: string; label: string }[]
  stock_mode: "CARD_KEY" | "MANUAL" | "UNLIMITED"
  manual_stock?: number
  specs: {
    id?: string
    name: string
    cost_price: number | null
    retail_price: number | null
    visible: boolean
    sort_order: number
    manual_stock?: number | null
  }[]
  overrides: {
    user_level_id: string
    spec_id: string | null
    price: number | null
  }[]
}

/** Only the values needed by the live pricing calculation. */
export interface AdminPricingPreviewPayload {
  cost_price: number | null
  retail_price: number | null
  spec_enabled: boolean
  specs: {
    id: string
    cost_price: number | null
    retail_price: number | null
  }[]
  overrides: {
    user_level_id: string
    spec_id: string | null
    price: number | null
  }[]
}

// ============================================================
// Admin User
// ============================================================

export const adminUserApi = {
  getList: (params: { page?: number; page_size?: number; keyword?: string; is_deleted?: 0 | 1; user_level_id?: string }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<AdminUserItem>>(`/admin/users?${qs}`)
  },
  getDetail: (id: string) =>
    request<AdminUserDetail>(`/admin/users/${id}`),
  getOrders: (id: string, params: { page?: number; page_size?: number } = {}) => {
    const qs = buildQuery(params)
    return request<PaginatedData<AdminUserOrder>>(`/admin/users/${id}/orders?${qs}`)
  },
  getBalanceTransactions: (id: string, params: { page?: number; page_size?: number } = {}) => {
    const qs = buildQuery(params)
    return request<PaginatedData<BalanceTransaction>>(`/admin/users/${id}/balance-transactions?${qs}`)
  },
  getOperationLogs: (id: string, params: { page?: number; page_size?: number } = {}) => {
    const qs = buildQuery(params)
    return request<PaginatedData<AdminOperationLogDetail>>(`/admin/users/${id}/operation-logs?${qs}`)
  },
  updateProfile: (id: string, data: { email: string }) =>
    request<AdminUserDetail>(`/admin/users/${id}/profile`, { method: "PUT", body: JSON.stringify(data) }),
  adjustBalance: (id: string, data: { type: "INCREASE" | "DECREASE"; amount: string; reason: string }) =>
    request<BalanceTransaction>(`/admin/users/${id}/balance`, { method: "POST", body: JSON.stringify(data) }),
  toggleStatus: (id: string, isDeleted: 0 | 1) =>
    request<null>(`/admin/users/${id}/toggle`, { method: "POST", body: JSON.stringify({ is_deleted: isDeleted }) }),
  changeLevel: (id: string, userLevelId: string) =>
    request<UserLevel>(`/admin/users/${id}/level`, {
      method: "POST",
      body: JSON.stringify({ user_level_id: userLevelId }),
    }),
  resetPassword: (id: string, newPassword: string) =>
    request<null>(`/admin/users/${id}/reset-password`, {
      method: "POST",
      body: JSON.stringify({ new_password: newPassword }),
    }),
}

// ============================================================
// Admin Payment
// ============================================================

export const adminPaymentApi = {
  getList: () =>
    request<PaymentChannelItem[]>("/admin/payment-channels"),
  create: (data: { channel_code: string; channel_name: string; provider_type: string; payment_type: string; config_data?: Record<string, unknown>; is_enabled?: boolean; sort_order?: number }) =>
    request<null>("/admin/payment-channels", { method: "POST", body: JSON.stringify(data) }),
  update: (id: string, data: Partial<{ channel_code: string; channel_name: string; provider_type: string; payment_type: string; config_data: Record<string, unknown>; is_enabled: boolean; sort_order: number }>) =>
    request<null>(`/admin/payment-channels/${id}`, { method: "PUT", body: JSON.stringify(data) }),
  delete: (id: string) =>
    request<null>(`/admin/payment-channels/${id}`, { method: "DELETE" }),
}

// ============================================================
// Admin Config
// ============================================================

export const adminConfigApi = {
  get: () =>
    request<SiteConfigKV[]>("/admin/site-config"),
  update: (data: { configs: { config_key: string; config_value: string }[] }) =>
    request<null>("/admin/site-config", { method: "PUT", body: JSON.stringify(data) }),
  toggleMaintenance: (enabled: boolean) =>
    request<null>("/admin/site-config/maintenance", { method: "POST", body: JSON.stringify({ enabled }) }),
  getUserNumbering: () =>
    request<UserNumberConfig>("/admin/site-config/user-numbering"),
  updateUserNumbering: (data: Pick<UserNumberConfig, "start_user_no" | "next_user_no">) =>
    request<null>("/admin/site-config/user-numbering", { method: "PUT", body: JSON.stringify(data) }),
}

// ============================================================
// Admin User Levels
// ============================================================

export interface UserLevelUpsertPayload {
  name: string
  pricing_type: "RETAIL" | "COST_MARKUP"
  markup_rate?: number | null
  is_default?: boolean
  enabled?: boolean
  sort_order?: number
  remark?: string | null
}

export const adminUserLevelApi = {
  getList: () => request<UserLevel[]>("/admin/user-levels"),
  create: (data: UserLevelUpsertPayload) =>
    request<UserLevel>("/admin/user-levels", { method: "POST", body: JSON.stringify(data) }),
  update: (id: string, data: UserLevelUpsertPayload) =>
    request<UserLevel>(`/admin/user-levels/${id}`, { method: "PUT", body: JSON.stringify(data) }),
  setStatus: (id: string, enabled: boolean) =>
    request<UserLevel>(`/admin/user-levels/${id}/status`, {
      method: "POST",
      body: JSON.stringify({ enabled }),
    }),
  setDefault: (id: string) =>
    request<UserLevel>(`/admin/user-levels/${id}/default`, { method: "POST" }),
  delete: (id: string) => request<null>(`/admin/user-levels/${id}`, { method: "DELETE" }),
  migrate: (id: string, targetLevelId: string) =>
    request<number>(`/admin/user-levels/${id}/migrate`, {
      method: "POST",
      body: JSON.stringify({ target_level_id: targetLevelId }),
    }),
}

// ============================================================
// Super administrator management
// ============================================================

export const adminAdministratorApi = {
  getList: (params: { page?: number; page_size?: number; keyword?: string; is_deleted?: 0 | 1 }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<AdministratorItem>>(`/admin/administrators?${qs}`)
  },
  get: (id: string) =>
    request<AdministratorItem>(`/admin/administrators/${id}`),
  create: (data: { username: string; password: string; email?: string; phone?: string }) =>
    request<AdministratorItem>("/admin/administrators", { method: "POST", body: JSON.stringify(data) }),
  update: (id: string, data: { email?: string; phone?: string }) =>
    request<AdministratorItem>(`/admin/administrators/${id}`, { method: "PUT", body: JSON.stringify(data) }),
  toggleStatus: (id: string, isDeleted: 0 | 1) =>
    request<null>(`/admin/administrators/${id}/toggle`, {
      method: "POST", body: JSON.stringify({ is_deleted: isDeleted }),
    }),
  resetPassword: (id: string, newPassword: string) =>
    request<null>(`/admin/administrators/${id}/reset-password`, {
      method: "POST", body: JSON.stringify({ new_password: newPassword }),
    }),
  getOperationLogs: (id: string, params: { page?: number; page_size?: number } = {}) => {
    const qs = buildQuery(params)
    return request<PaginatedData<AdministratorOperationLog>>(`/admin/administrators/${id}/operation-logs?${qs}`)
  },
}

// ============================================================
// Admin Log
// ============================================================

export const adminLogApi = {
  getList: (params: { page?: number; page_size?: number; user_id?: string; action?: string; target_type?: string; start_date?: string; end_date?: string }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<OperationLog>>(`/admin/operation-logs?${qs}`)
  },
}

// ============================================================
// Admin Risk
// ============================================================

export const adminRiskApi = {
  getConfig: () =>
    request<RiskConfig>("/admin/risk-config"),
  updateConfig: (data: Partial<RiskConfig>) =>
    request<null>("/admin/risk-config", { method: "PUT", body: JSON.stringify(data) }),
  getFlaggedOrders: (params: { page?: number; page_size?: number }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<AdminOrderItem>>(`/admin/risk/flagged-orders?${qs}`)
  },
}

export { ApiError }

// ============================================================
// Error code → i18n key mapping
// ============================================================

// 只映射用户可见的前台错误码，后台管理页面不映射（后台统一中文界面）
const ERROR_CODE_I18N: Record<number, string> = {
  // 通用
  10002: "error.unauthorized",
  10003: "error.forbidden",
  10005: "error.tooManyRequests",
  10006: "error.serverError",
  10007: "error.maintenance",
  // Auth
  20001: "error.usernameExists",
  20002: "error.emailExists",
  20003: "error.captchaInvalid",
  20004: "error.invalidCredentials",
  20005: "error.oldPasswordWrong",
  20006: "error.accountDisabled",
  20008: "error.phoneExists",
  20009: "error.identifierConflict",
  // Product
  30001: "error.productNotFound",
  30002: "error.insufficientStock",
  30003: "error.specNotFound",
  30004: "error.purchaseLimitExceeded",
  30005: "error.specHasCardKeys",
  30006: "error.specNameDuplicate",
  // Order
  40001: "error.orderNotFound",
  40002: "error.orderExpired",
  40003: "error.orderNotPaid",
  40004: "error.orderOutOfStock",
  40005: "error.orderProcessing",
  40006: "error.purchaseLimitPerUser",
  40007: "error.priceChanged",
  // Payment
  50001: "error.channelUnavailable",
  // Admin card-key pickup conflicts are safe to translate in both locales.
  70011: "error.cardKeyUnavailable",
}

/**
 * 从 API 错误中提取用户可见的提示文案。
 * 已映射 → i18n 文案 + 参数插值
 * 未映射 → 后端原始 message（兜底，不丢信息）
 * 非 ApiError → 原始 error message
 */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export function getApiErrorMessage(err: unknown, t: (key: any) => string): string {
  if (err instanceof ApiError) {
    const i18nKey = ERROR_CODE_I18N[err.code]
    if (i18nKey) {
      let msg = t(i18nKey)
      // 参数插值: {available} → 实际值
      if (err.params) {
        for (const [k, v] of Object.entries(err.params)) {
          msg = msg.replace(`{${k}}`, String(v))
        }
      }
      return msg
    }
    // 未映射的 code → 直接返回后端原始 message（兜底，不丢信息）
    return err.message
  }
  return err instanceof Error ? err.message : t("common.error")
}
