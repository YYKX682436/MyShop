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
  phone: string
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
  email: string | null
  role: 'USER' | 'ADMIN' | 'SUPER_ADMIN'
  user_no: number | null
  user_level_id: string | null
  user_level_name: string | null
  balance: number
  unlimited: boolean
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
  retail_price: number
  stock_available: number | null
  unlimited?: boolean
  stock_mode?: "CARD_KEY" | "MANUAL" | "UNLIMITED"
  manual_stock?: number | null
  card_key_count?: number
  is_visible?: boolean
  sort_order?: number
}

export type PricingType = "RETAIL" | "COST_MARKUP"
export type PricingSource = "RETAIL" | "LEVEL_MARKUP" | "LEVEL_OVERRIDE"

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
  price: number
  retail_price: number
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
  sort_weight?: number
  created_at?: string
}

/** Full product detail (returned by GET /products/{id}) */
export interface ProductDetail extends ProductCard {
  detail_md?: string
  specs: ProductSpec[]
  spec_enabled?: boolean
  low_stock_threshold?: number
  category_name?: string
  updated_at?: string
}

export interface AdminProductSpec {
  id: string
  name: string
  cost_price: number
  retail_price: number
  margin: number
  margin_rate: number | null
  is_visible: boolean
  is_deleted: 0 | 1
  sort_order: number
  stock_available: number | null
  unlimited: boolean
  manual_stock: number | null
  card_key_count: number
}

export interface AdminPriceOverride {
  id: string
  user_level_id: string
  spec_id: string | null
  price: number
}

export interface AdminPricingMatrixRow {
  user_level_id: string
  user_level_name: string
  pricing_type: PricingType
  markup_rate: number | null
  spec_id: string | null
  default_price: number
  override_price: number | null
  final_price: number
  pricing_source: PricingSource
  retail_capped: boolean
  below_cost: boolean
}

export type PricingPreviewStatus = "READY" | "WAITING_COST" | "WAITING_RETAIL"

export interface AdminPricingPreviewRow {
  user_level_id: string
  user_level_name: string
  pricing_type: PricingType
  markup_rate: number | null
  spec_id: string | null
  rule_price: number | null
  override_price: number | null
  final_price: number | null
  pricing_status: PricingPreviewStatus
  retail_capped: boolean
  below_cost: boolean
}

export interface AdminPricingPreview {
  pricing_matrix: AdminPricingPreviewRow[]
  warnings: string[]
}

export interface AdminProductDetail {
  id: string
  product_no: number
  title: string
  description: string | null
  detail_md: string | null
  cover_url: string | null
  cost_price: number | null
  retail_price: number
  category_id: string
  category_name: string | null
  stock_available: number | null
  unlimited: boolean
  has_specs: boolean
  delivery_type: "AUTO" | "MANUAL"
  customer_input_fields: CustomerInputField[]
  stock_mode: "CARD_KEY" | "MANUAL" | "UNLIMITED"
  sales_count: number
  initial_sales: number
  spec_enabled: boolean
  is_enabled: boolean
  sort_weight: number
  low_stock_threshold: number
  specs: AdminProductSpec[]
  overrides: AdminPriceOverride[]
  pricing_matrix: AdminPricingMatrixRow[]
  warnings: string[]
  created_at: string
  updated_at: string
}

export interface UserLevel {
  id: string
  name: string
  pricing_type: PricingType
  markup_rate: number | null
  is_default: boolean
  enabled: boolean
  sort_order: number
  remark: string | null
  user_count: number
  created_at: string
  updated_at: string
}

// ============================================================
// Order
// ============================================================

export type OrderStatus = 'PENDING' | 'PAID' | 'DELIVERED' | 'EXPIRED'
export type OrderType = 'NORMAL' | 'ADMIN_PICKUP'

export interface OrderBrief {
  id: string
  order_no: number
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
  product_no: number
  product_title: string
  spec_id?: string | null
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
  expires_at: string | null
  paid_at: string | null
  delivered_at: string | null
  items: OrderItemDetail[]
}

export interface PaymentCreateResult {
  payment_id?: string | null
  order_id: string
  order_no: number
  status: PaymentStatus
  next_action?: PaymentNextAction | null
  expires_at: string | null
  order_type?: OrderType
  admin_pickup?: boolean
  paid?: boolean
}

export interface CreateOrderResult {
  order: OrderDetail
  payment: PaymentCreateResult
  lookup_token?: string
}

export interface DeliverResultGroup {
  product_no: number
  product_title: string
  spec_name: string | null
  quantity?: number
  delivery_type?: string
  fulfillment_status?: "PENDING" | "WAITING_MANUAL" | "DELIVERED" | "CANCELLED"
  delivery_items: { content_parts: string[] }[]
}

export interface DeliverResult {
  order_id: string
  order_no: number
  status: OrderStatus
  groups: DeliverResultGroup[]
  item_statuses?: { id: string; product_no: number; product_title: string; fulfillment_status: "PENDING" | "WAITING_MANUAL" | "DELIVERED" | "CANCELLED"; delivery_type: string }[]
}

// ============================================================
// Payment
// ============================================================

export type PaymentStatus = "PENDING" | "PAID" | "FAILED" | "CLOSED"
export type PaymentClient = "wechat" | "alipay"

export type PaymentNextAction =
  | { type: "qr_code"; value: string }
  | { type: "redirect"; url: string }
  | { type: "open_in_client"; url: string; client: PaymentClient }
  | { type: "deep_link"; url: string; client: PaymentClient }
  | { type: "form_post"; url: string; fields: Record<string, string> }

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

/** Storefront-safe payment channel payload. It never contains provider config. */
export interface PublicPaymentChannel {
  id: string
  channel_code: string
  name: string
  payment_hint?: string | null
  badge_text?: string | null
  type: string
}

/** Admin channel payload; config_data is masked by the API. */
export interface AdminPaymentChannel {
  id: string
  channel_code: string
  channel_name: string
  provider_type: ProviderType
  payment_type: string
  config_data?: PaymentChannelConfig | null
  payment_hint?: string | null
  badge_text?: string | null
  is_enabled: boolean
  sort_order: number
  created_at: string
}

// ============================================================
// Site Config
// ============================================================

export interface ContactItem {
  name: string
  url?: string
  qr_url?: string
}

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
  contact_items?: ContactItem[]
  maintenance_enabled: boolean
  maintenance_message?: string
  footer_text?: string
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
  payment_method: string
  idempotency_key: string
  idempotency_fingerprint?: string
  expected_unit_price?: number
  device?: string
  customer_inputs?: { id: string; value: string }[]
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
  content_parts: string[]
  status: 'AVAILABLE' | 'LOCKED' | 'SOLD' | 'INVALID'
  order_id: string | null
  order_no: number | null
  created_at: string
  sold_at: string | null
}

export interface AdminCardKeyPickupCard {
  id: string
  content_parts: string[]
}

export interface AdminCardKeyPickupResult {
  order_id: string
  order_no: number
  count: number
  cards: AdminCardKeyPickupCard[]
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
  order_no?: number | null
  order_item_id?: string | null
  content_parts: string[]
  product_no?: number | null
  product_title: string
  spec_name: string | null
  status: 'AVAILABLE' | 'LOCKED' | 'SOLD' | 'INVALID'
}

// ============================================================
// Admin Orders
// ============================================================

export interface AdminOrderItemDetail extends OrderItemDetail {
  cost_price_snapshot: number
  retail_price_snapshot: number
  pricing_source: PricingSource
  gross_profit: number | null
  gross_margin: number | null
}

export interface AdminOrderItem extends Omit<OrderDetail, "items" | "payment_method" | "expires_at" | "paid_at" | "delivered_at"> {
  items: AdminOrderItemDetail[]
  payment_method?: string | null
  expires_at?: string | null
  paid_at?: string | null
  delivered_at?: string | null
  customer_type: "REGISTERED" | "GUEST"
  user_id?: string | null
  user_no: number | null
  username: string | null
  is_risk_flagged: boolean
  registered_email?: string | null
  phone?: string | null
  internal_order_id?: string
  user_level_id_snapshot?: string | null
  user_level_name_snapshot?: string | null
  client_ip?: string | null
  cost_total: number
  gross_profit_total: number
  gross_margin_total: number | null
}

// ============================================================
// Admin Users
// ============================================================

export interface AdminUserItem {
  id: string
  user_no: number
  username: string
  email: string
  user_level_id: string | null
  user_level_name: string | null
  balance: number
  order_count: number
  total_spent: number
  is_deleted: 0 | 1
  created_at: string
}

export interface UserNumberConfig {
  start_user_no: number
  next_user_no: number
  has_existing_users: boolean
}

export interface AdminUserDetail extends Omit<AdminUserItem, "order_count" | "total_spent"> {
  phone: string | null
  successful_order_count: number
  total_spent: number
  last_login_ip: string | null
  last_login_at: string | null
  last_login_ip_location: IpLocation | null
}

export interface IpLocation {
  country: string | null
  province: string | null
  city: string | null
  isp: string | null
  display_name: string
  resolved: boolean
}

export interface AdminResetPasswordResponse {
  temporary_password: string
}

export interface AdminUserOrderItem {
  id: string
  product_no: number
  product_title: string
  spec_name: string | null
  quantity: number
  unit_price: number
  subtotal: number
}

export interface AdminUserOrder {
  id: string
  order_no: number
  total_amount: number | null
  actual_amount: number | null
  status: OrderStatus
  order_type: OrderType
  payment_method: string | null
  created_at: string
  paid_at: string | null
  delivered_at: string | null
  items: AdminUserOrderItem[]
}

// ============================================================
// Administrator management
// ============================================================

export interface AdministratorItem {
  id: string
  username: string
  email: string | null
  phone: string | null
  role: "ADMIN" | "SUPER_ADMIN"
  is_deleted: 0 | 1
  created_at: string
}

export interface AdministratorOperationLog {
  id: string
  user_id: string
  username: string
  action: string
  target_type: string
  target_id: string
  detail: string | null
  ip_address: string
  created_at: string
}

export type BalanceTransactionType = 'ADMIN_ADJUSTMENT' | 'RECHARGE' | 'ORDER_PAYMENT' | 'REFUND'

export interface BalanceTransaction {
  id: string
  type: BalanceTransactionType
  amount: number
  delta: number
  balance_before: number
  balance_after: number
  reason: string | null
  operator_id: string
  order_id: string | null
  created_at: string
}

export interface AdminOperationLogDetail {
  id: string
  user_id: string
  username: string
  action: string
  target_type: string
  target_id: string
  detail: string
  ip_address: string
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
  order_expire_minutes: number
}
