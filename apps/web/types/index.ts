// ============================================================
// API Response Types
// ============================================================

export interface ApiResponse<T = unknown> {
  code: number
  message: string
  data: T
  params?: Record<string, string | number>
}
export interface Pagination {
  page: number
  page_size: number
  total: number
}

export interface PaginatedData<T> {
  list: T[]
  pagination: Pagination
}

// ============================================================
// Auth
// ============================================================

export interface LoginRequest {
  account: string
  password: string
}

export interface RegisterRequest {
  username: string
  password: string
  email: string
  captcha_id: string
  captcha: string
}

export interface CaptchaResult {
  captcha_id: string
  captcha_image: string
}

export interface AuthResult {
  user: UserProfile
}

export interface UserProfile {
  id: string
  username: string
  email: string
  role: 'USER' | 'ADMIN'
  points: number
  created_at: string
}

// ============================================================
// Product & Category
// ============================================================

export interface Category {
  id: string
  name: string
  sort_order: number
}

export interface ProductSpec {
  id: string
  name: string
  price: number
  stock_available: number | null
  unlimited?: boolean
  stock_mode?: "CARD_KEY" | "MANUAL" | "UNLIMITED"
  manual_stock?: number | null
  card_key_count?: number
  is_visible?: boolean
  sort_order?: number
}

export interface WholesaleRule {
  min_quantity: number
  unit_price: number
}

export interface CustomerInputField {
  id: string
  label: string
}

export interface CustomerInputValue {
  id: string
  label: string
  value: string
}

/** Product list item (returned by GET /products) */
export interface ProductCard {
  id: string
  title: string
  description?: string
  cover_url?: string
  base_price: number
  currency?: string
  category_id: string
  stock_available: number | null
  unlimited?: boolean
  has_specs: boolean
  delivery_type?: string
  customer_input_fields: CustomerInputField[]
  stock_mode?: "CARD_KEY" | "MANUAL" | "UNLIMITED"
  sales_count?: number
  initial_sales?: number
  is_enabled?: boolean
  sort_order?: number
  created_at?: string
}

/** Full product detail (returned by GET /products/{id}) */
export interface ProductDetail extends ProductCard {
  detail_md?: string
  specs: ProductSpec[]
  spec_enabled?: boolean
  wholesale_enabled: boolean
  wholesale_rules: WholesaleRule[]
  low_stock_threshold?: number
  category_name?: string
  updated_at?: string
}

// ============================================================
// Cart
// ============================================================

export interface CartItem {
  id: string
  product_id: string
  spec_id: string | null
  product_title: string
  spec_name: string | null
  cover_url?: string
  currency?: string
  unit_price: number
  quantity: number
  subtotal: number
  stock_available?: number | null
  unlimited?: boolean
  stock_mode?: "CARD_KEY" | "MANUAL" | "UNLIMITED"
  customer_input_fields: CustomerInputField[]
  customer_inputs: CustomerInputValue[]
  customer_inputs_complete?: boolean
  customer_input_error?: string
}

export interface Cart {
  items: CartItem[]
  total_amount: number
}

// ============================================================
// Order
// ============================================================

export type OrderStatus = 'PENDING' | 'PAID' | 'DELIVERED' | 'EXPIRED'

export type OrderType = 'DIRECT' | 'CART'

export interface OrderBrief {
  id: string
  total_amount: number
  actual_amount: number
  status: OrderStatus
  order_type: OrderType
  payment_method: string
  created_at: string
  items?: OrderItemDetail[]
}

export interface OrderItemDetail {
  id: string
  product_id: string
  product_title: string
  spec_name: string | null
  quantity: number
  unit_price: number
  subtotal: number
  delivery_type?: string
  customer_inputs: CustomerInputValue[]
  stock_mode?: "CARD_KEY" | "MANUAL" | "UNLIMITED"
  fulfillment_status?: "PENDING" | "WAITING_MANUAL" | "DELIVERED" | "CANCELLED"
  delivered_at?: string | null
  manual_stock_reserved?: number
}

export interface OrderDetail extends OrderBrief {
  email: string
  points_deducted: number
  points_discount: number
  expires_at: string
  paid_at: string | null
  delivered_at: string | null
  items: OrderItemDetail[]
}

export interface PaymentCreateResult {
  order_id: string
  payment_url: string
  qrcode_url?: string
  pay_url?: string
  expires_at: string
}

export interface CreateOrderResult {
  order: OrderDetail
  payment: PaymentCreateResult
  lookup_token?: string
}

export interface DeliverResultGroup {
  product_title: string
  spec_name: string | null
  card_keys: string[]
  quantity?: number
  delivery_type?: string
  fulfillment_status?: "PENDING" | "WAITING_MANUAL" | "DELIVERED" | "CANCELLED"
  delivery_items?: { parts: string[]; content_parts?: string[] }[]
}

export interface DeliverResult {
  order_id: string
  status: OrderStatus
  groups: DeliverResultGroup[]
  item_statuses?: { id: string; product_title: string; fulfillment_status: "PENDING" | "WAITING_MANUAL" | "DELIVERED" | "CANCELLED"; delivery_type: string }[]
}

// ============================================================
// Currency
// ============================================================

export interface CurrencyItem {
  code: string
  name: string
  symbol: string
}

// ============================================================
// Payment
// ============================================================

export type ProviderType = string

export interface PaymentChannelConfig {
  [key: string]: string | number | boolean | undefined
}

export type ProviderConfigFieldType = "text" | "password" | "secret" | "url" | "number" | "switch" | "select"

export interface PaymentProviderOption {
  value: string
  label: string
}

export interface PaymentProviderConfigField {
  name: string
  label: string
  type: ProviderConfigFieldType
  secret: boolean
  required: boolean
  placeholder?: string | null
  default_value?: string | number | boolean | null
  options?: PaymentProviderOption[]
}

export interface PaymentProviderMetadata {
  provider_id: string
  display_name: string
  payment_types: string[]
  config_schema: PaymentProviderConfigField[]
}

export interface PaymentChannelItem {
  id: string
  channel_code: string
  channel_name: string
  provider_type: ProviderType
  payment_type: string
  icon?: string
  config_data?: PaymentChannelConfig | null
  is_enabled: boolean
  sort_order: number
  created_at: string
}

// ============================================================
// Site Config
// ============================================================

export interface SiteConfig {
  site_name: string
  site_slogan?: string
  site_description?: string
  logo_url?: string
  favicon_url?: string
  announcement_enabled: boolean
  announcement?: string
  popup_enabled: boolean
  popup_content?: string
  contact_email?: string
  contact_telegram?: string
  contact_telegram_group?: string
  points_enabled: boolean
  points_rate: number
  maintenance_enabled: boolean
  maintenance_message?: string
  footer_text?: string
  github_url?: string
  custom_css?: string
}

export interface SiteConfigKV {
  config_key: string
  config_value: string
  config_group?: string
}

// ============================================================
// Create Order Requests
// ============================================================

export interface CreateOrderRequest {
  product_id: string
  spec_id: string | null
  quantity: number
  email: string
  payment_method: string
  use_points?: boolean
  idempotency_key: string
  idempotency_fingerprint?: string
  device?: string
  customer_inputs?: { id: string; value: string }[]
}

export interface CreateCartOrderRequest {
  email: string
  payment_method: string
  use_points?: boolean
  idempotency_key: string
  idempotency_fingerprint?: string
  device?: string
}

// ============================================================
// Points
// ============================================================

export interface PointRecord {
  change_amount: number
  balance_after: number
  reason: string
  order_id: string | null
  created_at: string
}

export interface PointsData {
  total_points: number
  list: PointRecord[]
  pagination: Pagination
}

// ============================================================
// Admin Dashboard
// ============================================================

export interface LowStockProduct {
  product_id: string
  title: string
  available_stock: number
  threshold: number
}

export interface DashboardTopProduct {
  product_id: string
  title: string
  quantity: number
  sales_amount: number
}

export interface DashboardRecentUser {
  id: string
  username: string
  email: string
  created_at: string
}

export interface DashboardStats {
  today_sales: number
  month_sales: number
  today_orders: number
  month_orders: number
  conversion_rate: number
  today_pv: number
  today_uv: number
  today_sales_change_pct: number | null
  today_orders_change_pct: number | null
  today_new_users: number
  today_new_users_change_pct: number | null
  month_new_users: number
  total_users: number
  today_buyers: number
  pending_orders: number
  paid_waiting_delivery: number
  delivered_today: number
  expired_today: number
  today_payment_success_rate: number
  today_payment_success_sample_count: number
  active_products: number
  available_card_keys: number
  zero_stock_products: number
  low_stock_products: LowStockProduct[]
  top_products: DashboardTopProduct[]
  recent_users: DashboardRecentUser[]
}

export interface SalesTrend {
  date: string
  sales_amount: number
  order_count: number
}

export type ServiceStatus = "UP" | "DOWN" | "UNKNOWN"

export interface ServiceHealth {
  status: ServiceStatus
}

export interface SystemResourceMetric {
  total_bytes: number | null
  used_bytes: number | null
  available_bytes: number | null
  usage_pct: number | null
}

export interface SystemCpuMetric {
  usage_pct: number | null
  cores: number | null
}

export interface SystemStatus {
  available: boolean
  sampled_at: string
  cpu: SystemCpuMetric | null
  memory: SystemResourceMetric | null
  disk: SystemResourceMetric | null
  data_disk: SystemResourceMetric | null
  uptime_seconds: number | null
  load_1m: number | null
  services: {
    api: ServiceHealth
    web: ServiceHealth
    database: ServiceHealth
    caddy: ServiceHealth
  }
}

// ============================================================
// Admin Card Keys
// ============================================================

export interface CardKeyStockSummary {
  product_id: string
  product_title: string
  spec_id: string | null
  spec_name: string | null
  spec_enabled?: boolean
  total: number | null
  available: number | null
  sold: number
  locked: number
  invalid: number
  reserved?: number
  unlimited?: boolean
  stock_mode?: "CARD_KEY" | "MANUAL" | "UNLIMITED"
}

export interface CardKeyListItem {
  id: string
  content: string
  content_parts?: string[]
  status: 'AVAILABLE' | 'LOCKED' | 'SOLD' | 'INVALID'
  order_id: string | null
  created_at: string
  sold_at: string | null
}

export interface CardImportBatch {
  id: string
  product_id: string
  spec_id: string | null
  imported_by: string
  total_count: number
  success_count: number
  duplicate_count: number
  fail_count: number
  column_count: number
  check_duplicate: boolean
  fail_detail: string | null
  created_at: string
}

export interface OrderCardKey {
  card_key_id: string
  order_item_id?: string | null
  content: string
  content_parts?: string[]
  product_title: string
  spec_name: string | null
  status: 'AVAILABLE' | 'LOCKED' | 'SOLD' | 'INVALID'
}

// ============================================================
// Admin Orders
// ============================================================

export interface AdminOrderItem extends OrderDetail {
  user_id: string | null
  username: string | null
  is_risk_flagged: boolean
}

// ============================================================
// Admin Users
// ============================================================

export interface AdminUserItem {
  id: string
  username: string
  email: string
  role: string
  points: number
  is_deleted: 0 | 1
  created_at: string
}

// ============================================================
// Admin Operation Logs
// ============================================================

export interface OperationLog {
  id: string
  user_id: string
  username: string
  action: string
  target_type: string
  target_id?: string
  detail?: string
  ip_address: string
  created_at: string
}

// ============================================================
// Admin Risk
// ============================================================

export interface RiskConfig {
  // 人机验证
  turnstile_enabled: boolean
  // 设备指纹限流
  device_rate_limit_enabled: boolean
  device_order_limit_per_hour: number
  device_query_limit_per_hour: number
  device_login_limit_per_hour: number
  device_register_limit_per_hour: number
  // 已有配置
  rate_limit_per_second: number
  login_attempt_limit: number
  max_purchase_per_user: number
  max_pending_orders_per_ip: number
  max_pending_orders_per_user: number
  order_expire_minutes: number
}
