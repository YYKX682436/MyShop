  import type {
  ApiResponse,
  PaginatedData,
  UserProfile,
  RegisterRequest,
  OrderBrief,
  OrderView,
  ProductCard,
  ProductListQuery,
  ProductDetail,
  AdminProductDetail,
  AdminProductSpec,
  AdminPricingPreview,
  Category,
  CreateOrderRequest,
  CreateOrderResult,
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
  UserSecretPrice,
  AdminUserOrder,
  AdminResetPasswordResponse,
  BalanceTransaction,
  AdminOperationLogDetail,
  UserNumberConfig,
  AdminOrderItem,
  PublicPaymentChannel,
  AdminPaymentChannel,
  PaymentProviderMetadata,
  OperationLog,
  RiskConfig,
  UserLevel,
  CaptchaResult,
  AuthResult,
  AdministratorItem,
  AdministratorOperationLog,
  AdminFulfillmentResult,
  AdminManualFulfillmentRequest,
  AdminOrderRefundResponse,
  AfterSaleApplyResult,
  AfterSaleView,
  AdminAttentionSummary,
} from "@/types"
import { buildAdminRefundPayload } from "@/lib/admin-refund-ui"
import type { AdminProductPricesUpdatePayload } from "@/lib/product-quick-edit"
import { buildAdminOrderSearchBody } from "@/lib/admin-order-search"
import type { AdminOrderSearchParams } from "@/lib/admin-order-search"

// ============================================================
// Config
// ============================================================

const API_BASE = process.env.NEXT_PUBLIC_API_BASE || "/api"
let messageViewer = "pending"
let expectedMessageGuest: string | null = null
/** An assertion checked against the authenticated cookie, never an authority claim. */
export function setMessageViewer(userId: string | null | undefined) {
  messageViewer = userId === undefined ? "pending" : userId === null ? "guest" : `user:${userId}`
}
export function setMessageGuestToken(token: string) { expectedMessageGuest = token }
function isMessagePath(path: string) {
  return path.startsWith("/support/") || path.startsWith("/admin/support/") || path === "/announcements" || path.startsWith("/announcements/") || path.startsWith("/announcements?")
}
function assertMessageIdentity(path: string, viewer: string, token: string | null) {
  if (isMessagePath(path) && (viewer !== messageViewer || (viewer === "guest" && (token !== expectedMessageGuest || token !== getSessionToken()))))
    throw new DOMException("身份已变化", "AbortError")
}

// ============================================================
// Error
// ============================================================

class ApiError extends Error {
  code: number
  params?: Record<string, string | number | boolean>
  constructor(code: number, message: string, params?: Record<string, string | number | boolean>) {
    super(message)
    this.code = code
    this.params = params
    this.name = "ApiError"
  }
}

export function getHomeSiteFromError(error: unknown): unknown {
  return error instanceof ApiError ? error.params?.home_url : null
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
  const webCrypto = window.crypto
  let token: string
  if (typeof webCrypto.randomUUID === "function") {
    token = webCrypto.randomUUID()
  } else {
    if (typeof webCrypto.getRandomValues !== "function") {
      throw new Error("当前浏览器不支持安全的游客会话")
    }
    const bytes = new Uint8Array(32)
    webCrypto.getRandomValues(bytes)
    token = Array.from(bytes, value => value.toString(16).padStart(2, "0")).join("")
  }
  setSessionToken(token)
  return token
}

export function clearSessionToken() {
  localStorage.removeItem("session_token")
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

export function getCsrfToken(): string | null {
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
  options: RequestInit = {},
  redirectOnUnauthorized = true,
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
  const viewer = messageViewer
  if (isMessagePath(path)) headers["X-Message-Viewer"] = viewer
  assertMessageIdentity(path, viewer, sessionToken)

  // Capture this action's token before any asynchronous fingerprint work.
  const turnstileToken = consumeTurnstileToken()
  // Only abuse-sensitive actions need to wait for device identification.
  // Public reads, profile loading and uploads never trigger expensive collection.
  const needsDeviceId = method === "POST" && ["/auth/login", "/auth/register", "/orders"].includes(path)
  const deviceId = needsDeviceId ? await ensureDeviceId() : cachedDeviceId
  if (deviceId) {
    headers["X-Device-Id"] = deviceId
  }

  // Turnstile token — 如果有 pending token 则发送（单次消费）
  if (turnstileToken) {
    headers["X-Turnstile-Token"] = turnstileToken
  }

  const res = await fetch(`${API_BASE}${path}`, {
    ...options,
    credentials: "include",
    headers,
  })
  assertMessageIdentity(path, viewer, sessionToken)

  // Capture session token from response
  const newSessionToken = res.headers.get("X-Session-Token")
  if (newSessionToken) {
    setSessionToken(newSessionToken)
  }

  if (!res.ok) {
    if (res.status === 401 && path !== "/user/profile" && redirectOnUnauthorized) {
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

async function uploadRequest<T>(path: string, formData: FormData, redirectOnUnauthorized = true, signal?: AbortSignal): Promise<T> {
  const headers: Record<string, string> = {}
  const csrf = getCsrfToken()
  if (csrf) headers["X-CSRF-Token"] = csrf
  const sessionToken = getSessionToken()
  if (sessionToken) headers["X-Session-Token"] = sessionToken
  const viewer = messageViewer
  if (isMessagePath(path)) headers["X-Message-Viewer"] = viewer
  assertMessageIdentity(path, viewer, sessionToken)
  const deviceId = cachedDeviceId
  if (deviceId) headers["X-Device-Id"] = deviceId
  const turnstileToken = consumeTurnstileToken()
  if (turnstileToken) headers["X-Turnstile-Token"] = turnstileToken

  const res = await fetch(`${API_BASE}${path}`, {
    method: "POST",
    headers,
    credentials: "include",
    body: formData,
    signal,
  })
  assertMessageIdentity(path, viewer, sessionToken)

  if (!res.ok) {
    if (res.status === 401 && redirectOnUnauthorized) {
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

export interface SiteAnnouncement {
  id: string
  title: string
  body: string
  status: "DRAFT" | "PUBLISHED" | "WITHDRAWN"
  published_at: string | null
  publish_version: number
  unread?: boolean
}

export interface AnnouncementChannel {
  channel: "BANNER" | "POPUP"
  announcement_id: string | null
  enabled: boolean
}

export const announcementApi = {
  summary: (signal?: AbortSignal) => request<{ unread: number; latest: Pick<SiteAnnouncement, "id" | "title" | "body" | "published_at"> | null }>("/announcements/summary", { signal }, false),
  list: (page = 0, signal?: AbortSignal) => request<SiteAnnouncement[]>(`/announcements?page=${page}`, { signal }, false),
  detail: (id: string, signal?: AbortSignal) => request<SiteAnnouncement>(`/announcements/${encodeURIComponent(id)}`, { signal }, false),
  read: (id: string, version: number, signal?: AbortSignal) => request<null>(`/announcements/${encodeURIComponent(id)}/read`, { method: "POST", body: JSON.stringify({ version }), signal }, false),
  manage: (scope: "admin" | "subsite") => ({
    list: () => request<{ announcements: SiteAnnouncement[]; channels: AnnouncementChannel[] }>(`/${scope}/announcements`),
    create: (title: string, body: string) => request<{ id: string }>(`/${scope}/announcements`, { method: "POST", body: JSON.stringify({ title, body }) }),
    edit: (id: string, title: string, body: string) => request<null>(`/${scope}/announcements/${encodeURIComponent(id)}`, { method: "PUT", body: JSON.stringify({ title, body }) }),
    publish: (id: string) => request<null>(`/${scope}/announcements/${encodeURIComponent(id)}/publish`, { method: "POST" }),
    withdraw: (id: string) => request<null>(`/${scope}/announcements/${encodeURIComponent(id)}/withdraw`, { method: "POST" }),
    channel: (channel: "BANNER" | "POPUP", announcementId: string | null, enabled: boolean) => request<null>(`/${scope}/announcements/channels/${channel}`, { method: "PUT", body: JSON.stringify({ announcement_id: announcementId, enabled }) }),
  }),
}

export interface SupportConversation {
  id: string
  site_id: string | null
  site_name: string | null
  customer_name?: string
  order_id: string | null
  order_no: number | null
  order_status: string | null
  latest_at: string | null
  latest_type: string | null
  preview: string
  unread: number
  waiting_for: "ADMIN" | "CUSTOMER" | null
  needs_attention: boolean
}

export interface SupportAttachment { id: string; url: string; content_type: string; file_size: number }
export interface SupportActivity {
  id: string
  cursor: string
  source_type: "SUPPORT_MESSAGE" | "AFTER_SALE_MESSAGE" | "AFTER_SALE_APPLY" | "AFTER_SALE_REJECT" | "ORDER_REFUND" | "SUPPLIER_UPDATE"
  sender_type: "CUSTOMER" | "ADMIN" | "SYSTEM"
  content: string | null
  created_at: string | null
  request_id: string | null
  client_key: string | null
  round?: number
  refund_amount?: number | null
  refund_destination?: string | null
  order_status?: string
  supplier_status?: number
  attachments: SupportAttachment[]
}
export interface SupportActivityPage { items: SupportActivity[]; has_more: boolean; oldest_cursor: string | null; newest_cursor: string | null }

export const supportApi = {
  summary: (signal?: AbortSignal) => request<{ unread: number; latest: SupportConversation | null }>("/support/summary", { signal }, false),
  list: (admin = false, query = "", signal?: AbortSignal) => request<SupportConversation[]>(`${admin ? "/admin" : ""}/support/conversations${query ? `?${query}` : ""}`, { signal }, !admin ? false : true),
  detail: (id: string, admin = false, signal?: AbortSignal) => request<SupportConversation>(`${admin ? "/admin" : ""}/support/conversations/${encodeURIComponent(id)}`, { signal }, admin),
  activities: (id: string, admin = false, cursor?: { after?: string; before?: string }, signal?: AbortSignal) => {
    const params = new URLSearchParams(cursor)
    return request<SupportActivityPage>(`${admin ? "/admin" : ""}/support/conversations/${encodeURIComponent(id)}/activities?${params}`, { signal }, admin)
  },
  read: (id: string, cursor: string, admin = false, signal?: AbortSignal) => request<null>(`${admin ? "/admin" : ""}/support/conversations/${encodeURIComponent(id)}/read`, { method: "POST", body: JSON.stringify({ cursor }), signal }, admin),
  order: (id: string, signal?: AbortSignal) => request<{ conversation_id: string | null; order_id: string; order_no: number; order_status: string }>(`/support/orders/${encodeURIComponent(id)}`, { signal }, false),
  adminOrder: (id: string, signal?: AbortSignal) => request<{ conversation_id: string | null }>(`/admin/support/orders/${encodeURIComponent(id)}`, { signal }),
  general: (signal?: AbortSignal) => request<{ conversation_id: string | null }>("/support/general", { signal }, false),
  send: (data: { conversationId?: string; orderId?: string; clientKey: string; content: string; files: File[] }, admin = false, afterSale = false, signal?: AbortSignal) => {
    const form = new FormData()
    if (data.conversationId) form.append("conversation_id", data.conversationId)
    if (data.orderId) form.append("order_id", data.orderId)
    form.append("client_key", data.clientKey); form.append("content", data.content)
    data.files.forEach(file => form.append("attachments", file))
    const path = afterSale && data.conversationId
      ? `${admin ? "/admin" : ""}/support/conversations/${encodeURIComponent(data.conversationId)}/after-sale-reply`
      : `${admin ? "/admin" : ""}/support/conversations/send`
    return uploadRequest<{ conversation_id: string; message_id: string } | null>(path, form, admin, signal)
  },
  attachment: async (url: string, signal?: AbortSignal) => {
    if (!/^\/api\/(?:admin\/support|support|after-sale-attachments|supplier-complaint-attachments)\/[a-zA-Z0-9/-]+$/.test(url)) throw new Error("附件地址无效")
    const headers: Record<string, string> = {}
    const token = getSessionToken()
    if (token) headers["X-Session-Token"] = token
    const viewer = messageViewer
    headers["X-Message-Viewer"] = viewer
    if (viewer === "guest" && token !== expectedMessageGuest) throw new DOMException("身份已变化", "AbortError")
    const response = await fetch(url, { credentials: "include", headers, signal })
    if (viewer !== messageViewer || (viewer === "guest" && (token !== expectedMessageGuest || token !== getSessionToken()))) throw new DOMException("身份已变化", "AbortError")
    if (!response.ok) throw new Error("附件加载失败")
    return response.blob()
  },
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
  uploadAvatar: (file: File) => {
    const formData = new FormData()
    formData.append("file", file)
    return uploadRequest<UserProfile>("/user/avatar", formData)
  },
  getOrders: (params: { page?: number; page_size?: number; status?: string }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<OrderBrief>>(`/user/orders?${qs}`)
  },
  submitAfterSale: (orderId: string, reason: string, attachments: File[] = []) => {
    const formData = new FormData()
    formData.append("reason", reason)
    attachments.forEach(file => formData.append("attachments", file, file.name))
    return uploadRequest<AfterSaleApplyResult>(`/user/orders/${encodeURIComponent(orderId)}/after-sales`, formData)
  },
  sendAfterSaleMessage: (orderId: string, content: string, clientKey: string, attachments: File[] = []) => {
    const formData = new FormData()
    formData.append("content", content)
    formData.append("client_key", clientKey)
    attachments.forEach(file => formData.append("attachments", file, file.name))
    return uploadRequest<AfterSaleView>(`/user/orders/${encodeURIComponent(orderId)}/after-sale/messages`, formData)
  },
}

// ============================================================
// Product
// ============================================================

export const productApi = {
  getList: (params: ProductListQuery, options?: RequestInit) => {
    const qs = buildQuery(params)
    return request<PaginatedData<ProductCard>>(`/products?${qs}`, options)
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
  get: (orderId: string) =>
    request<OrderView>(`/orders/${encodeURIComponent(orderId)}`),
  refresh: (orderId: string) =>
    request<OrderView>(`/orders/${encodeURIComponent(orderId)}/refresh`, { method: "POST" }),
  exportKeys: (orderId: string) =>
    requestText(`/orders/${encodeURIComponent(orderId)}/export`),
  repay: (orderId: string, device?: string) =>
    request<import("@/types").PaymentCreateResult>(`/orders/${encodeURIComponent(orderId)}/repay`, {
      method: "POST",
      body: JSON.stringify({ device }),
    }),
}

// ============================================================
// Site Config (public)
// ============================================================

export const siteApi = {
  getConfig: () =>
    request<SiteConfig>("/site/config"),
}

export interface SubsiteInfo {
  id: string; owner_id: string; prefix: string; name: string; enabled: boolean
  favicon_url: string | null; announcement: string | null
  url: string | null; user_count: number
  owner_username?: string; owner_user_no?: number | null; order_count?: number; settled_profit?: number
}
export interface SubsiteSettings {
  self_service_enabled: boolean; opening_price: number; supply_level_id: string | null; allowed_level_ids: string[]
}
export interface SubsiteCustomer {
  id: string; user_no: number | null; username: string; user_level_id: string | null; created_at: string
}
export interface SubsiteLevel { id: string; name: string }
export interface SubsiteProduct {
  id: string; title: string; spec_enabled: boolean; retail_price: number; supply_price: number | null; underpriced_levels: string[]
  specs: { id: string; name: string; retail_price: number; supply_price: number; underpriced_levels: string[] }[]
}
export interface SubsitePrice { spec_id: string | null; user_level_id: string | null; price: number }
export interface SubsiteProductPricing { hidden: boolean; prices: SubsitePrice[] }
export interface SubsiteLevelMarkup { user_level_id: string; markup_rate: number }
export interface SubsiteOrder { id: string; order_no: string; actual_amount: number; status: string; created_at: string; subsite_profit: number; subsite_profit_state: "PENDING" | "SETTLED" | "CANCELED" | "REVERSED" | null; subsite_profit_settled_at: string | null; subsite_profit_reversed_at: string | null }

export const subsiteApi = {
  mine: () => request<SubsiteInfo | null>("/subsite/mine"),
  opening: () => request<{ enabled: boolean; opening_price: number; eligible: boolean }>("/subsite/opening"),
  open: (data: { prefix: string; name: string }) => request<SubsiteInfo>("/subsite/open", { method: "POST", body: JSON.stringify(data) }),
  update: (data: Pick<SubsiteInfo, "name" | "favicon_url">) => request<SubsiteInfo>("/subsite/mine", { method: "PUT", body: JSON.stringify(data) }),
  uploadFavicon: (file: File) => {
    const formData = new FormData()
    formData.append("file", file)
    return uploadRequest<SubsiteInfo>("/subsite/mine/favicon", formData)
  },
  customers: (page: number) => request<{ content: SubsiteCustomer[]; total_pages: number }>(`/subsite/customers?page=${page}&size=20`),
  overview: () => request<{ order_count: number; estimated_profit: number; pending_profit: number; settled_profit: number }>("/subsite/overview"),
  orders: (page: number) => request<{ content: SubsiteOrder[]; total_pages: number }>(`/subsite/orders?page=${page}&size=20`),
  setCustomerLevel: (id: string, level_id: string) => request<void>(`/subsite/customers/${encodeURIComponent(id)}/level`, { method: "PUT", body: JSON.stringify({ level_id }) }),
  levels: () => request<SubsiteLevel[]>("/subsite/pricing/levels/available"),
  products: (page: number) => request<PaginatedData<SubsiteProduct>>(`/subsite/pricing/products?page=${page}&page_size=20`),
  productPricing: (id: string) => request<SubsiteProductPricing>(`/subsite/pricing/products/${encodeURIComponent(id)}`),
  updateProductPricing: (id: string, data: SubsiteProductPricing) => request<SubsiteProductPricing>(`/subsite/pricing/products/${encodeURIComponent(id)}`, { method: "PUT", body: JSON.stringify(data) }),
  markups: () => request<SubsiteLevelMarkup[]>("/subsite/pricing/levels"),
  updateMarkups: (data: SubsiteLevelMarkup[]) => request<SubsiteLevelMarkup[]>("/subsite/pricing/levels", { method: "PUT", body: JSON.stringify(data) }),
}

export const adminSubsiteApi = {
  list: () => request<SubsiteInfo[]>("/admin/subsites"),
  settings: () => request<SubsiteSettings>("/admin/subsites/settings"),
  updateSettings: (data: SubsiteSettings) => request<SubsiteSettings>("/admin/subsites/settings", { method: "PUT", body: JSON.stringify(data) }),
  open: (data: { owner_id: string; prefix: string; name: string }) => request<SubsiteInfo>("/admin/subsites", { method: "POST", body: JSON.stringify(data) }),
  status: (id: string, enabled: boolean) => request<SubsiteInfo>(`/admin/subsites/${encodeURIComponent(id)}/status`, { method: "PUT", body: JSON.stringify({ enabled }) }),
  transfer: (id: string, user_id: string, level_id: string) => request<void>(`/admin/subsites/${encodeURIComponent(id)}/transfer`, { method: "POST", body: JSON.stringify({ user_id, level_id }) }),
  customers: (id: string, page: number) => request<{ content: SubsiteCustomer[]; total_pages: number }>(`/admin/subsites/${encodeURIComponent(id)}/customers?page=${page}&size=20`),
}

// ============================================================
// Payment Channels (public, for store display)
// ============================================================

export const paymentApi = {
  getChannels: () =>
    request<PublicPaymentChannel[]>("/payment-channels"),
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
    return uploadRequest<{ url: string; original_size: number; stored_size: number; reason: string }>("/upload/image", formData)
  },
}

export type MediaItem = { name: string; url: string; size: number; format: string; uploaded_at: number }
export type MediaPage = { items: MediaItem[]; total: number; page: number; page_size: number }
export type MediaInspection = { size: number; format: string; width?: number; height?: number; optimizable: boolean; references: { table: string; source: string; count: number }[] }
export type MediaOptimization = { url: string; original_size: number; optimized_size: number; replaced_covers: number; changed_products: { id: string; title: string }[]; retained_references: { table: string; source: string; count: number }[] }

export const adminMediaApi = {
  getList: (params: { page: number; page_size: number; query: string; order: "asc" | "desc" }, signal?: AbortSignal) =>
    request<MediaPage>(`/admin/media?${buildQuery(params)}`, { signal }),
  inspect: (filename: string, signal?: AbortSignal) =>
    request<MediaInspection>(`/admin/media/inspection?${buildQuery({ filename })}`, { signal }),
  delete: (filename: string) => request<void>(`/admin/media?${buildQuery({ filename })}`, { method: "DELETE" }),
  optimize: (filename: string) => request<MediaOptimization>(`/admin/media/optimize?${buildQuery({ filename })}`, { method: "POST" }),
}

// ============================================================
// Admin Category
// ============================================================

export const adminCategoryApi = {
  getList: () =>
    request<Category[]>("/admin/categories"),
  create: (data: { name: string; sort_order?: number; parent_id?: string | null }) =>
    request<null>("/admin/categories", { method: "POST", body: JSON.stringify(data) }),
  update: (id: string, data: { name?: string; sort_order?: number; parent_id?: string | null }) =>
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
  reviewPayment: (orderId: string, attemptId: string, note: string) =>
    request<null>(`/admin/orders/${encodeURIComponent(orderId)}/payment-reviews`, { method: "POST", body: JSON.stringify({ attempt_id: attemptId, note }) }),
  refreshSupplier: (orderId: string, purchaseId: string) =>
    request<unknown>(`/admin/orders/${encodeURIComponent(orderId)}/supplier-purchases/${encodeURIComponent(purchaseId)}/refresh`, { method: "POST" }),
  reviewSupplier: (orderId: string, purchaseId: string, outcome: string) =>
    request<unknown>(`/admin/orders/${encodeURIComponent(orderId)}/supplier-purchases/${encodeURIComponent(purchaseId)}/reviews`, { method: "POST", body: JSON.stringify({ outcome }) }),
  supplierRefund: (orderId: string, purchaseId: string, idempotencyKey: string) =>
    request<unknown>(`/admin/orders/${encodeURIComponent(orderId)}/supplier-purchases/${encodeURIComponent(purchaseId)}/refunds`, { method: "POST", body: JSON.stringify({ idempotency_key: idempotencyKey, confirmed: true }) }),
  supplierCardQuery: (orderId: string, purchaseId: string, cardId: string) =>
    request<unknown>(`/admin/orders/${encodeURIComponent(orderId)}/supplier-purchases/${encodeURIComponent(purchaseId)}/cards/${encodeURIComponent(cardId)}/query`, { method: "POST" }),
  supplierCardVoid: (orderId: string, purchaseId: string, cardId: string, idempotencyKey: string) =>
    request<unknown>(`/admin/orders/${encodeURIComponent(orderId)}/supplier-purchases/${encodeURIComponent(purchaseId)}/cards/${encodeURIComponent(cardId)}/void`, { method: "POST", body: JSON.stringify({ idempotency_key: idempotencyKey, confirmed: true }) }),
  supplierOperationRefresh: (orderId: string, purchaseId: string, operationId: string) =>
    request<unknown>(`/admin/orders/${encodeURIComponent(orderId)}/supplier-purchases/${encodeURIComponent(purchaseId)}/operations/${encodeURIComponent(operationId)}/refresh`, { method: "POST" }),
  supplierOperationReview: (orderId: string, purchaseId: string, operationId: string, outcome: string) =>
    request<unknown>(`/admin/orders/${encodeURIComponent(orderId)}/supplier-purchases/${encodeURIComponent(purchaseId)}/operations/${encodeURIComponent(operationId)}/reviews`, { method: "POST", body: JSON.stringify({ outcome }) }),
  supplierComplaint: (orderId: string, purchaseId: string, body: { idempotencyKey: string; confirmed: boolean; afterSaleId: string; topic: string; content: string; attachmentIds: string[] }) =>
    request<{ status: string }>(`/admin/orders/${encodeURIComponent(orderId)}/supplier-purchases/${encodeURIComponent(purchaseId)}/complaints`, { method: "POST", body: JSON.stringify({ idempotency_key: body.idempotencyKey, confirmed: body.confirmed, after_sale_id: body.afterSaleId, topic: body.topic, content: body.content, attachment_ids: body.attachmentIds }) }),
  markPaid: (id: string, reason: string) =>
    request<null>(`/admin/orders/${id}/mark-paid`, {
      method: "POST",
      body: JSON.stringify({ reason, confirmed: true }),
    }),
  refund: (id: string, reason: string, reclaimCardKeys: boolean) =>
    request<AdminOrderRefundResponse>(`/admin/orders/${id}/refund`, {
      method: "POST",
      body: JSON.stringify(buildAdminRefundPayload(reason, reclaimCardKeys)),
    }),
  rejectAfterSale: (id: string, rejectionReason: string) =>
    request<null>(`/admin/orders/${encodeURIComponent(id)}/after-sale/reject`, {
      method: "POST",
      body: JSON.stringify({ rejection_reason: rejectionReason }),
    }),
  sendAfterSaleMessage: (id: string, content: string, clientKey: string, attachments: File[] = []) => {
    const formData = new FormData()
    formData.append("content", content)
    formData.append("client_key", clientKey)
    attachments.forEach(file => formData.append("attachments", file, file.name))
    return uploadRequest<AfterSaleView>(`/admin/orders/${encodeURIComponent(id)}/after-sale/messages`, formData)
  },
  approveAfterSale: (id: string, reclaimCardKeys: boolean) =>
    request<AdminOrderRefundResponse>(`/admin/orders/${encodeURIComponent(id)}/after-sale/approve`, {
      method: "POST",
      body: JSON.stringify({ confirmed: true, reclaim_card_keys: reclaimCardKeys }),
    }),
  fulfill: (orderId: string, data: AdminManualFulfillmentRequest) =>
    request<AdminFulfillmentResult>(`/admin/orders/${orderId}/fulfill`, {
      method: "POST",
      body: JSON.stringify(data),
    }),
}

export const adminAttentionApi = {
  getSummary: (options?: RequestInit) =>
    request<AdminAttentionSummary>("/admin/attention-summary", options),
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
  stock_mode: "CARD_KEY" | "MANUAL" | "UNLIMITED" | "SUPPLIER"
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
  getSecretPrices: (id: string) => request<UserSecretPrice[]>(`/admin/users/${id}/secret-prices`),
  createSecretPrice: (id: string, data: { product_id: string; spec_id: string | null; price: string }) =>
    request<UserSecretPrice>(`/admin/users/${id}/secret-prices`, { method: "POST", body: JSON.stringify(data) }),
  updateSecretPrice: (id: string, priceId: string, price: string) =>
    request<UserSecretPrice>(`/admin/users/${id}/secret-prices/${priceId}`, { method: "PUT", body: JSON.stringify({ price }) }),
  deleteSecretPrice: (id: string, priceId: string) =>
    request<null>(`/admin/users/${id}/secret-prices/${priceId}`, { method: "DELETE" }),
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
  adjustBalance: (id: string, data: { type: "INCREASE" | "DECREASE"; amount: string; reason?: string | null }) =>
    request<BalanceTransaction>(`/admin/users/${id}/balance`, { method: "POST", body: JSON.stringify(data) }),
  toggleStatus: (id: string, isDeleted: 0 | 1) =>
    request<null>(`/admin/users/${id}/toggle`, { method: "POST", body: JSON.stringify({ is_deleted: isDeleted }) }),
  changeLevel: (id: string, userLevelId: string) =>
    request<UserLevel>(`/admin/users/${id}/level`, {
      method: "POST",
      body: JSON.stringify({ user_level_id: userLevelId }),
    }),
  resetPassword: (id: string) =>
    request<AdminResetPasswordResponse>(`/admin/users/${id}/reset-password`, { method: "POST" }),
}

// ============================================================
// Admin Payment
// ============================================================

export const adminPaymentApi = {
  getList: () =>
    request<AdminPaymentChannel[]>("/admin/payment-channels"),
  create: (data: { channel_name?: string; provider_type?: string; payment_type: string; config_data?: Record<string, unknown>; payment_hint?: string | null; badge_text?: string | null; is_enabled?: boolean; sort_order?: number }) =>
    request<null>("/admin/payment-channels", { method: "POST", body: JSON.stringify(data) }),
  update: (id: string, data: Partial<{ channel_code: string; channel_name: string; provider_type: string; payment_type: string; config_data: Record<string, unknown>; payment_hint: string | null; badge_text: string | null; is_enabled: boolean; sort_order: number }>) =>
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
  60002: "error.tooManyRequests",
  // Auth
  20001: "error.usernameExists",
  20002: "error.emailExists",
  20003: "error.captchaInvalid",
  20004: "error.invalidCredentials",
  20005: "error.oldPasswordWrong",
  20006: "error.accountDisabled",
  20008: "error.phoneExists",
  20009: "error.identifierConflict",
  20010: "my.avatarFormatError",
  20011: "my.avatarSizeError",
  // Product
  30001: "error.productNotFound",
  30002: "error.insufficientStock",
  30003: "error.specNotFound",
  30004: "error.purchaseLimitExceeded",
  30005: "error.specHasCardKeys",
  30006: "error.specNameDuplicate",
  // Order
  40001: "error.orderMissing",
  40002: "error.orderExpired",
  40003: "error.orderPaymentRequired",
  40004: "error.orderOutOfStock",
  40005: "error.orderProcessing",
  40006: "error.purchaseLimitPerUser",
  40007: "error.priceChanged",
  // Payment
  50001: "error.channelUnavailable",
  50003: "error.balancePaymentRequiresLogin",
  50004: "error.insufficientBalance",
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

export type SupplierAccount = { id: string; name: string; provider_type: string; base_url: string; app_id: string; secret_mask: string; enabled: boolean; goods_callback_url?: string | null }
export type SupplierAccountInput = { name: string; provider_type: string; base_url: string; app_id: string; secret?: string; enabled: boolean }
export type SupplierCategory = { id: string; name: string; children: SupplierCategory[] }
export type SupplierSummary = { id: string; name: string; goods_type: number; status: number; sku_type: number; cost: string }
export type SupplierSku = { id: string; name: string; group: string; cost: string; stock: number; status: number }
export type SupplierProduct = { id: string; name: string; goods_type: number; status: number; sku_type: number; multiple: number; min_quantity: number; max_quantity: number; skus: SupplierSku[]; snapshot: Record<string, unknown> }
export type SupplierMapping = { id: string; account_id: string; remote_goods_id: string; remote_sku: string; product_id: string; spec_id?: string | null; goods_type: number; sku_type: number; cost: string; stock: number; goods_status: number; sku_status: number; multiple: number; min_quantity: number; max_quantity: number; last_synced_at: string; last_attempt_at: string; sync_error?: string; remote_goods_name?: string; remote_sku_name?: string; product_name?: string; spec_name?: string | null; local_state?: string | null }
export const adminSupplierApi = {
  accounts: () => request<SupplierAccount[]>("/admin/suppliers"),
  create: (input: SupplierAccountInput) => request<SupplierAccount>("/admin/suppliers", { method: "POST", body: JSON.stringify(input) }),
  update: (id: string, input: SupplierAccountInput) => request<SupplierAccount>(`/admin/suppliers/${id}`, { method: "PUT", body: JSON.stringify(input) }),
  status: (id: string, enabled: boolean) => request<void>(`/admin/suppliers/${id}/status`, { method: "POST", body: JSON.stringify({ enabled, confirmed: true }) }),
  test: (id: string) => request<{ balance: string }>(`/admin/suppliers/${id}/test`, { method: "POST" }),
  categories: (id: string) => request<SupplierCategory[]>(`/admin/suppliers/${id}/categories`),
  goods: (id: string, keyword: string, categoryId: string, page: number) => request<{ items: SupplierSummary[]; reported_count?: number; reported_pages?: number }>(`/admin/suppliers/${id}/goods?${buildQuery({ keyword, category_id: categoryId, page })}`),
  detail: (id: string, goodsId: string) => request<SupplierProduct>(`/admin/suppliers/${id}/goods/${encodeURIComponent(goodsId)}`),
  import: (id: string, goodsId: string, categoryId: string) => request<{ product_id: string; created: boolean }>(`/admin/suppliers/${id}/goods/${encodeURIComponent(goodsId)}/import`, { method: "POST", body: JSON.stringify({ category_id: categoryId }) }),
  bind: (id: string, goodsId: string, input: { product_id: string; spec_id: string | null; remote_sku: string; confirmed: boolean; move_confirmed: boolean; expected_target_mapping_id: string | null; expected_source_mapping_id: string | null; expected_source_product_id: string | null; expected_source_spec_id: string | null }) => request<{ product_id: string; created: boolean }>(`/admin/suppliers/${id}/goods/${encodeURIComponent(goodsId)}/bind`, { method: "POST", body: JSON.stringify(input) }),
  mappings: (id: string) => request<SupplierMapping[]>(`/admin/suppliers/${id}/mappings`),
  productMappings: (productId: string) => request<SupplierMapping[]>(`/admin/suppliers/products/${productId}/mappings`),
  unbind: (id: string, mappingId: string) => request<void>(`/admin/suppliers/${id}/mappings/${mappingId}/unbind`, { method: "POST", body: JSON.stringify({ confirmed: true }) }),
  sync: (id: string, goodsId: string) => request<void>(`/admin/suppliers/${id}/goods/${encodeURIComponent(goodsId)}/sync`, { method: "POST" }),
  presentation: (id: string, goodsId: string, productId: string) => request<void>(`/admin/suppliers/${id}/goods/${encodeURIComponent(goodsId)}/presentation`, { method: "POST", body: JSON.stringify({ product_id: productId, confirmed: true }) }),
}
