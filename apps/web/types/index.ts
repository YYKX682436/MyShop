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

export type ProductListQuery = {
  page?: number
  page_size?: number
  category_id?: string
  keyword?: string
  sort?: "default" | "hot" | "price_low" | "price_high" | "new"
  in_stock?: boolean
  price_min?: string
  price_max?: string
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
  avatar_url: string | null
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
  parent_id: string | null
  children: Category[]
}

export interface ProductSpec {
  id: string
  name: string
  price: number
  zero_price_purchase_allowed?: boolean
  retail_price: number
  stock_available: number | null
  stock_status?: string
  min_quantity?: number | null
  max_quantity?: number | null
  unlimited?: boolean
  stock_mode?: "CARD_KEY" | "MANUAL" | "UNLIMITED" | "SUPPLIER"
  manual_stock?: number | null
  card_key_count?: number
  is_visible?: boolean
  sort_order?: number
  supplier_input_fields?: CustomerInputField[]
}

export type PricingType = "RETAIL" | "COST_MARKUP"
export type PricingSource = "RETAIL" | "LEVEL_MARKUP" | "LEVEL_OVERRIDE" | "USER_SECRET" | "SALES_CHANNEL" | "SUBSITE"

export interface CustomerInputField {
  id: string
  label: string
  type?: "TEXT" | "IMAGE" | "NUMBER" | "SELECT" | "MULTILINE" | "CASCADE"
  required?: boolean
  placeholder?: string | null
  regex?: string | null
  options?: { name: string; value: string }[] | null
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
  zero_price_purchase_allowed?: boolean
  retail_price: number
  category_id: string
  category_name?: string | null
  primary_category_id?: string | null
  primary_category_name?: string | null
  secondary_category_id?: string | null
  secondary_category_name?: string | null
  stock_available: number | null
  stock_status?: string
  min_quantity?: number | null
  max_quantity?: number | null
  unlimited?: boolean
  has_specs: boolean
  delivery_type?: string
  customer_input_fields: CustomerInputField[]
  stock_mode?: "CARD_KEY" | "MANUAL" | "UNLIMITED" | "SUPPLIER"
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
  stock_status?: string
  min_quantity?: number | null
  max_quantity?: number | null
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
  primary_category_id: string | null
  primary_category_name: string | null
  secondary_category_id: string | null
  secondary_category_name: string | null
  stock_available: number | null
  stock_status?: string
  min_quantity?: number | null
  max_quantity?: number | null
  unlimited: boolean
  has_specs: boolean
  delivery_type: "AUTO" | "MANUAL"
  customer_input_fields: CustomerInputField[]
  stock_mode: "CARD_KEY" | "MANUAL" | "UNLIMITED" | "SUPPLIER"
  /** null: unconfigured; 0: conflicting historical formats; positive: fixed column count. */
  card_content_column_count: number | null
  card_content_column_counts: number[]
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
  upgrade_rank: number
  self_upgrade_enabled: boolean
  upgrade_price: number | null
  public_price_enabled: boolean
  remark: string | null
  user_count: number
  created_at: string
  updated_at: string
}

// ============================================================
// Order
// ============================================================

export type OrderStatus = 'PENDING' | 'PAID' | 'DELIVERED' | 'EXPIRED' | 'REFUNDED' | 'AFTER_SALE'
export type OrderType = 'NORMAL' | 'ADMIN_PICKUP'
export type OrderPaymentMethod = 'BALANCE' | 'ONLINE'

export type AfterSaleStatus = "PENDING" | "REJECTED" | "REFUNDED"
export type AfterSaleWaitingFor = "ADMIN" | "CUSTOMER"
export type AfterSaleMessageSenderType = "CUSTOMER" | "ADMIN"

export interface AfterSaleAttachment {
  id: string
  message_id?: string | null
  original_filename: string
  content_type: "image/jpeg" | "image/png" | "image/webp" | string
  file_size: number
  url: string
  created_at?: string | null
}

export interface AfterSaleMessage {
  id: string
  request_id: string
  sender_type: AfterSaleMessageSenderType
  sender_user_id: string
  content: string
  created_at: string
  attachments: AfterSaleAttachment[]
}

export interface AfterSaleRequest {
  id: string
  order_id: string
  user_id: string
  status: AfterSaleStatus
  source_order_status: "PAID" | "DELIVERED"
  reason: string
  rejection_reason?: string | null
  waiting_for: AfterSaleWaitingFor | null
  last_activity_at: string
  handled_by?: string | null
  submitted_at: string
  handled_at?: string | null
  attachments: AfterSaleAttachment[]
  messages: AfterSaleMessage[]
  supplier_updates?: { id: string; status: number; reply: string; created_at: string; attachments: { id: string; url: string; content_type: string; file_size: number }[] }[]
}

export type AfterSaleSummary = Pick<AfterSaleRequest,
  "id" | "order_id" | "user_id" | "status" | "source_order_status"
  | "waiting_for" | "last_activity_at" | "submitted_at" | "handled_at">

export interface AdminAttentionSummary {
  after_sale_needs_attention: number
}

export interface AfterSaleView {
  after_sale: AfterSaleRequest | null
  after_sale_history: AfterSaleRequest[]
}

export interface AfterSaleApplyResult {
  order_id: string
  status: "AFTER_SALE"
  after_sale: AfterSaleRequest
}

export interface OrderItemBrief {
  id: string
  product_cover_url: string | null
  product_title: string
  spec_name: string | null
  quantity: number
  unit_price: number
  delivery_type: string
  stock_mode: "CARD_KEY" | "MANUAL" | "UNLIMITED" | "SUPPLIER"
  fulfillment_status: "PENDING" | "WAITING_MANUAL" | "DELIVERED" | "CANCELLED"
}

export interface OrderBrief {
  id: string
  order_no: number
  actual_amount: number
  status: OrderStatus
  created_at: string
  items: OrderItemBrief[]
  after_sale?: AfterSaleSummary | null
}

export interface OrderItemDetail extends OrderItemBrief {
  product_id: string
  product_no: number
  spec_id?: string | null
  subtotal: number
  customer_inputs: CustomerInputValue[]
  delivered_at?: string | null
  customer_delivery_content?: string | null
  manual_stock_reserved?: number
  supplier_status?: string | null
  supplier_result?: string | null
  supplier_start_count?: string | null
  supplier_now_count?: string | null
  supplier_end_count?: string | null
}

export interface OrderDetail {
  id: string
  guest?: boolean
  order_no: number
  total_amount: number
  actual_amount: number
  status: OrderStatus
  order_type: OrderType
  payment_method: OrderPaymentMethod | null
  payment_channel_code: string | null
  created_at: string
  expires_at: string | null
  paid_at: string | null
  delivered_at: string | null
  refunded_at: string | null
  reclaimed_card_key_count?: number | null
  after_sale?: AfterSaleRequest | null
  after_sale_history?: AfterSaleRequest[]
  items: OrderItemDetail[]
}

export interface PaymentCreateResult {
  payment_id?: string | null
  order_id: string
  order_no: number
  status: PaymentStatus
  creation_status?: "INTENT" | "SUBMITTING" | "READY" | "REJECTED" | "UNKNOWN"
  can_retry?: boolean
  payment_method?: OrderPaymentMethod | null
  payment_type?: string | null
  payment_channel_code?: string | null
  next_action?: PaymentNextAction | null
  expires_at: string | null
  remaining_seconds?: number
  order_type?: OrderType
  admin_pickup?: boolean
  paid?: boolean
}

export interface AccountPaymentCreateResult {
  payment_id?: string | null
  order_id?: null
  order_no?: null
  account_transaction_id?: string | null
  transaction_id?: string | null
  account_status?: AccountPaymentStatus
  account_type?: AccountPaymentType
  amount?: number
  status: PaymentStatus
  creation_status?: PaymentCreateResult["creation_status"]
  can_retry?: boolean
  payment_method?: OrderPaymentMethod | null
  payment_type?: string | null
  payment_channel_code?: string | null
  next_action?: PaymentNextAction | null
  expires_at: string | null
  remaining_seconds?: number
}

export interface CreateOrderResult {
  order: OrderDetail
  payment: PaymentCreateResult
}

export interface OrderDeliveryGroup {
  product_no: number
  product_title: string
  spec_name: string | null
  quantity?: number
  delivery_type?: string
  fulfillment_status?: "PENDING" | "WAITING_MANUAL" | "DELIVERED" | "CANCELLED"
  delivery_items: { content_parts: string[]; supplier_card_status?: number }[]
}

export interface OrderDeliveryView {
  groups: OrderDeliveryGroup[]
  item_statuses?: { id: string; product_no: number; product_title: string; fulfillment_status: "PENDING" | "WAITING_MANUAL" | "DELIVERED" | "CANCELLED"; delivery_type: string }[]
}

export interface OrderView {
  order: OrderDetail
  payment: PaymentCreateResult
  delivery: OrderDeliveryView
  after_sale?: AfterSaleRequest | null
  after_sale_history?: AfterSaleRequest[]
}

export interface AdminManualFulfillmentItem {
  order_item_id: string
  customer_delivery_content: string | null
}

export interface AdminManualFulfillmentRequest {
  confirmed: true
  items: AdminManualFulfillmentItem[]
}

export interface AdminFulfillmentResult {
  order_id: string
  order_no: number
  status: OrderStatus
  groups: OrderDeliveryGroup[]
  item_statuses: {
    id: string
    product_no: number
    product_title: string
    fulfillment_status: "PENDING" | "WAITING_MANUAL" | "DELIVERED" | "CANCELLED"
    delivery_type: string
  }[]
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

export interface SiteConfig {
  site_name: string
  favicon_url?: string | null
  main_site_url?: string
  maintenance_enabled: boolean
  maintenance_message?: string
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
  payment_method?: OrderPaymentMethod
  payment_channel_code?: string
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
  after_sale_orders: number
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
  stock_mode?: "CARD_KEY" | "MANUAL" | "UNLIMITED" | "SUPPLIER"
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
  order_id?: string | null
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
  subsite_supply_price_snapshot?: number | null
  retail_price_snapshot: number
  pricing_source: PricingSource
  gross_profit: number | null
  gross_margin: number | null
  supplier_purchase?: {
    id: string
    account_name: string
    goods_type: number
    outer_number: string
    upstream_order_number: string | null
    status: string
    upstream_status: number | null
    buyer_quantity: number
    actual_quantity: number
    safe_price: number | string
    actual_cost: number | string | null
    refunded_amount: number | string | null
    last_checked_at: string | null
    submit_started_at: string | null
    result: string | null
    start_count: string | null
    now_count: string | null
    end_count: string | null
    error_code: string | null
    can_refresh: boolean
    can_recover: boolean
    can_local_refund: boolean
    reviews?: { outcome: string; operator: string; created_at: string }[]
    aftercare_operations?: { id: string; idempotency_key: string; kind: string; status: string; card_id: string | null; after_sale_id: string | null; supplier_req_id: number | null; upstream_status: number | null; result: string | null; error_code: string | null; topic: string | null; content: string | null; attachment_ids: string[]; created_at: string; last_checked_at: string | null; reviews: { outcome: string; operator: string; created_at: string }[] }[]
    late_payments?: { id: string; attempt_id: string; channel: string; recorded_at: string }[]
    supplier_cards?: { id: string; remote_card_id: string; card_no: string | null; card_pwd: string | null; content_parts: string[]; status: number; cancel_request: number; result: string | null; last_checked_at: string | null }[]
  } | null
}

export interface AdminOrderItem extends Omit<OrderDetail, "items" | "payment_method" | "expires_at" | "paid_at" | "delivered_at" | "refunded_at" | "after_sale"> {
  items: AdminOrderItemDetail[]
  payment_attempts?: { id: string; merchant_trade_no: string; provider_trade_no: string | null; create_response_trade_no?: string | null; channel: string; amount: number; status: string; creation_status: string; created_at: string }[]
  late_payments?: { id: string; attempt_id: string; channel: string; event_id: string; reported_trade_no?: string | null; process_result: string; recorded_at: string }[]
  payment_reviews?: { id: string; operator: string; detail: string; created_at: string }[]
  payment_method?: OrderPaymentMethod | null
  payment_channel_code: string | null
  expires_at?: string | null
  paid_at?: string | null
  delivered_at?: string | null
  refunded_at?: string | null
  refund_amount?: number | null
  refund_destination?: "BALANCE" | string | null
  refund_reason?: string | null
  refund_operator_id?: string | null
  refund_operator_username?: string | null
  reclaimed_card_key_count?: number | null
  after_sale?: AfterSaleRequest | AfterSaleSummary | null
  after_sale_history?: AfterSaleRequest[]
  customer_type: "REGISTERED" | "GUEST"
  user_id?: string | null
  user_no: number | null
  username: string | null
  is_risk_flagged: boolean
  registered_email?: string | null
  phone?: string | null
  user_level_id_snapshot?: string | null
  user_level_name_snapshot?: string | null
  client_ip?: string | null
  cost_total: number
  gross_profit_total: number
  gross_margin_total: number | null
  subsite_profit?: number | null
  subsite_profit_state?: "PENDING" | "SETTLED" | "CANCELED" | "REVERSED" | null
  subsite_profit_canceled_at?: string | null
  subsite_profit_settled_at?: string | null
  subsite_profit_reversed_at?: string | null
}

export interface AdminOrderRefundResponse {
  order_id: string
  order_no: number
  status: "REFUNDED"
  refund_amount: number
  refunded_at: string
  refund_destination: "BALANCE" | string
  balance_before: number
  balance_after: number
  reclaimed_card_key_count: number
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
  delivery_type?: string | null
  stock_mode?: "CARD_KEY" | "MANUAL" | "UNLIMITED" | "SUPPLIER" | string | null
  fulfillment_status?: "PENDING" | "WAITING_MANUAL" | "DELIVERED" | "CANCELLED" | string | null
}

export interface AdminUserOrder {
  id: string
  order_no: number
  total_amount: number | null
  actual_amount: number | null
  status: OrderStatus
  order_type: OrderType
  payment_method: OrderPaymentMethod | null
  payment_channel_code: string | null
  created_at: string
  paid_at: string | null
  delivered_at: string | null
  refunded_at: string | null
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

export type BalanceTransactionType = 'ADMIN_ADJUSTMENT' | 'RECHARGE' | 'ORDER_PAYMENT' | 'REFUND' | 'SUBSITE_OPENING' | 'SUBSITE_PROFIT' | 'SUBSITE_PROFIT_REVERSAL' | 'LEVEL_UPGRADE' | 'SUBSITE_UPGRADE_PROFIT'

export type AccountPaymentType = 'RECHARGE' | 'LEVEL_UPGRADE'
export type AccountPaymentStatus = 'PENDING' | 'COMPLETED' | 'RECOVERED' | 'FAILED' | 'EXPIRED'

export interface AccountUpgradeOption {
  level_id: string
  name: string
  upgrade_rank: number
  level_price: number
  amount: number
  public_price_enabled: boolean
}

export interface UserAccountOverview {
  admin: boolean
  recharge_enabled: boolean
  recharge_min_amount: number
  recharge_max_amount: number
  balance: number | null
  current_level_name: string | null
  current_upgrade_rank: number
  current_level_price: number | null
  has_higher_level: boolean
  upgrade_available: boolean
  upgrades: AccountUpgradeOption[]
}

export interface AccountTransaction {
  id: string
  type: AccountPaymentType
  status: AccountPaymentStatus
  payment_method: OrderPaymentMethod
  amount: number
  channel_code: string | null
  payment_id?: string
  payment_status?: PaymentStatus
  creation_status?: PaymentCreateResult["creation_status"]
  payment_type?: string
  next_action?: PaymentNextAction | null
  expires_at?: string | null
  current_level_id: string | null
  target_level_id: string | null
  current_level_name: string | null
  target_level_name: string | null
  recovery_reason: string | null
  created_at: string
  completed_at: string | null
}

export interface ProductLevelPrice {
  level_id: string
  level_name: string
  price: number
  saving: number
}

export interface ProductPriceCheck {
  product_id: string
  product_name: string
  spec_id: string | null
  spec_name: string | null
  current_level_name: string
  current_price: number
  levels: ProductLevelPrice[]
}

export interface BalanceTransaction {
  id: string
  type: BalanceTransactionType
  amount: number
  delta: number
  balance_before: number
  balance_after: number
  reason: string | null
  operator_id: string
  operator_username: string | null
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
  turnstile_enabled: boolean
  device_rate_limit_enabled: boolean
  device_order_limit_per_hour: number
  device_login_limit_per_hour: number
  device_register_limit_per_hour: number
  rate_limit_per_second: number
  login_requests_per_minute: number
  register_requests_per_minute: number
  max_purchase_per_user: number
  order_expire_minutes: number
}

export interface UserSecretPrice {
  id: string
  product_id: string
  product_title: string
  spec_id?: string | null
  spec_name?: string | null
  price: number
  level_price: number
  retail_price: number
  cost_price?: number | null
  below_cost: boolean
}
