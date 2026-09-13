import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import test from "node:test"

import { buildAdminRefundPayload, canReclaimCardKeys, getRefundCardKeyAvailability, normalizeRefundCardKeySelection } from "./admin-refund-ui.ts"
import { formatAdminOrderCustomer, formatAdminOrderProductMeta, limitAdminOrderProductItems } from "./admin-order-display.ts"
import { buildAdminOrderSearchBody } from "./admin-order-search.ts"

const readSource = (relativePath) => readFileSync(new URL(relativePath, import.meta.url), "utf8")

test("admin order search sends defaults and all filters in the JSON body", () => {
  assert.deepEqual(
    JSON.parse(JSON.stringify(buildAdminOrderSearchBody({
      page: 3,
      page_size: 20,
      keyword: "  card-content  ",
      status: " PAID ",
      created_from: "2026-09-01",
      created_to: "2026-09-02",
      sort_direction: "asc",
    }))),
    {
      page: 3,
      page_size: 20,
      keyword: "card-content",
      status: "PAID",
      created_from: "2026-09-01",
      created_to: "2026-09-02",
      sort_direction: "asc",
    },
  )
  assert.equal(buildAdminOrderSearchBody().sort_direction, "desc")
})

test("admin order display uses business identities and compactly limits products", () => {
  const items = [
    { product_title: "商品 A", product_no: 12, spec_name: "标准", quantity: 2 },
    { product_title: "商品 B", product_no: 13, spec_name: null, quantity: 1 },
    { product_title: "商品 C", product_no: 14, spec_name: "高级", quantity: 3 },
  ]
  const summary = limitAdminOrderProductItems(items)
  assert.equal(summary.visible.length, 2)
  assert.equal(summary.additionalCount, 1)
  assert.equal(formatAdminOrderProductMeta(items[0]), "商品 #12 · 标准 · ×2")
  assert.equal(formatAdminOrderProductMeta(items[1]), "商品 #13 · 无规格 · ×1")
})

test("admin order customer identity keeps guests to one line and uses the user number as the registered fallback", () => {
  assert.deepEqual(formatAdminOrderCustomer({ customer_type: "GUEST", username: null, user_no: null }), {
    primary: "游客",
    secondary: null,
  })
  assert.deepEqual(formatAdminOrderCustomer({ customer_type: "REGISTERED", username: "buyer", user_no: 10001 }), {
    primary: "buyer",
    secondary: "用户号 10001",
  })
  assert.deepEqual(formatAdminOrderCustomer({ customer_type: "REGISTERED", username: "", user_no: 10001 }), {
    primary: "用户号 10001",
    secondary: null,
  })
})

test("checkout no longer renders or sends an order email", () => {
  const source = readSource("../app/(store)/product/[id]/product-purchase-dock.tsx")
  assert.doesNotMatch(source, /\bemail\b/i)
  assert.doesNotMatch(source, /emailInputRef|emailError|validateEmail|handleEmailChange/)
  assert.match(source, /customer_inputs: normalizedCustomerInputs/)
  assert.doesNotMatch(source, /fingerprint[\s\S]*email/i)
})

test("shopper checkout renders both payment method entries while administrators render pickup only", () => {
  const source = readSource("../app/(store)/product/[id]/product-purchase-dock.tsx")

  assert.match(source, /adminPickup \? \(/)
  assert.match(source, /t\("payment\.adminPickup"\)/)
  assert.match(source, /t\("product\.balancePayment"\)/)
  assert.match(source, /t\("product\.onlinePayment"\)/)
  assert.match(source, /PaymentSelector/)
})

test("order details use the wide workspace route and keep the workbench structure", () => {
  const routeSource = readSource("../components/admin/orders/order-detail-route.tsx")
  const detailSource = readSource("../components/admin/orders/order-detail-sheet.tsx")
  const overlaySource = readSource("../components/admin/route-overlay.tsx")

  assert.match(routeSource, /WorkspaceSheet/)
  assert.doesNotMatch(routeSource, /InspectorSheet/)
  assert.match(overlaySource, /sm:w-\[88vw\].*lg:w-\[64vw\].*max-w-\[1100px\]/s)
  assert.match(detailSource, /t\("admin\.cardDelivery"\)/)
  assert.match(detailSource, /t\("admin\.productAndCustomerInputs"\)/)
  assert.doesNotMatch(detailSource, /技术信息 \/ 高级信息/)
  assert.match(detailSource, /admin\.orderDetails/)
  assert.match(detailSource, /order\.order_no/)
  assert.doesNotMatch(detailSource, /用户 UUID|商品 UUID|规格 UUID|下单时等级 UUID/)
  assert.doesNotMatch(detailSource, /\b[A-G]\.\s/)
})

test("admin order navigation uses fixed horizontal status tabs including refunds", () => {
  const source = readSource("../app/admin/orders/page.tsx")

  assert.match(source, /const ORDER_STATUS_TABS/)
  for (const status of ["ALL", "PENDING", "PAID", "DELIVERED", "REFUNDED", "EXPIRED"]) {
    assert.match(source, new RegExp(`value: "${status}"`))
  }
  assert.match(source, /overflow-x-auto/)
  assert.match(source, /status: statusFilter === "ALL" \? undefined : statusFilter/)
  assert.doesNotMatch(source, /value=\{statusFilter\}[\s\S]*option/)
})

test("the order lifecycle page owns status, payment, delivery, copy, and export", () => {
  const source = readSource("../app/(store)/order/[orderId]/page.tsx")
  assert.match(source, /orderApi\.get\(orderId\)/)
  assert.match(source, /orderApi\.refresh\(orderId\)/)
  assert.match(source, /orderApi\.repay\(orderId/)
  assert.match(source, /orderApi\.exportKeys\(orderId\)/)
  assert.match(source, /PaymentActionRenderer/)
  assert.match(source, /deliveryWaitingManual/)
  assert.doesNotMatch(source, /t\("order\.deliveryReady"\)/)
  assert.match(source, /paymentReturn/)
})

test("the order lifecycle page polls paid fulfillment and refreshes after visibility or focus recovery", () => {
  const source = readSource("../app/(store)/order/[orderId]/page.tsx")

  assert.match(source, /shouldPollOrderStatus/)
  assert.match(source, /getOrderStatusPollInterval/)
  assert.match(source, /getOrderStatusPollInterval\(view\.order\.status, checkingReturn\)/)
  assert.match(source, /document\.addEventListener\("visibilitychange"/)
  assert.match(source, /window\.addEventListener\("focus"/)
  assert.match(source, /document\.visibilityState !== "visible"/)
  assert.match(source, /setPageVisible\(visible\)/)
  assert.doesNotMatch(source, /window\.location\.reload/)
})

test("storefront order header owns the compact manual refresh action", () => {
  const source = readSource("../app/(store)/order/[orderId]/page.tsx")
  const headerStart = source.indexOf("<header")
  const headerEnd = source.indexOf("</header>", headerStart)
  const header = source.slice(headerStart, headerEnd)

  assert.match(header, /<OrderStatusBadge status=\{status\} items=\{orderItems\} \/>/)
  assert.match(header, /status !== "REFUNDED"/)
  assert.match(header, /onClick=\{\(\) => void manualRefresh\(\)\}/)
  assert.match(header, /disabled=\{refreshCooldown > 0 \|\| refreshing\}/)
  assert.match(header, /aria-label=\{refreshButtonLabel\}/)
  assert.match(header, /title=\{refreshButtonLabel\}/)
  assert.match(header, /h-9 w-9/)
  assert.match(header, /refreshing && "animate-spin"/)
  assert.doesNotMatch(header, /t\("order\.refresh"\)/)

  assert.match(source, /const refreshButtonLabel = refreshing[\s\S]*t\("order\.refreshing"\)[\s\S]*t\("order\.refresh"\)/)
  assert.equal((source.match(/disabled=\{refreshCooldown > 0 \|\| refreshing\}/g) ?? []).length, 1)
  assert.equal((source.match(/t\("order\.refresh"\)/g) ?? []).length, 1)

  const productDetailsEnd = source.indexOf("{purchasedProducts.map")
  const contactStart = source.indexOf("<ContactSupport", productDetailsEnd)
  const bottom = source.slice(productDetailsEnd, contactStart)
  assert.doesNotMatch(bottom, /manualRefresh|刷新订单状态|order\.refresh/)
  assert.match(source.slice(productDetailsEnd), /ProductDescription[\s\S]*\/>\)\}\s*<ContactSupport/)
})

test("refund detail and storefront lifecycle expose the refund facts without payment actions", () => {
  const adminRoute = readSource("../components/admin/orders/order-detail-route.tsx")
  const adminSheet = readSource("../components/admin/orders/order-detail-sheet.tsx")
  const orderPage = readSource("../app/(store)/order/[orderId]/page.tsx")
  const ordersPage = readSource("../app/(store)/my/orders/page.tsx")

  assert.match(adminRoute, /adminOrderApi\.refund/)
  assert.match(adminRoute, /refundDeliveredWarning/)
  assert.match(adminSheet, /order\.status === "REFUNDED"/)
  assert.match(adminSheet, /refund_reason/)
  assert.match(adminSheet, /refund_operator_username/)
  assert.match(adminSheet, /guestRefundUnsupported/)
  assert.match(orderPage, /order\.refundedDescription/)
  assert.match(orderPage, /order\.refundToBalance/)
  assert.match(orderPage, /order\.deliveredRefundNotice/)
  assert.match(orderPage, /status !== "REFUNDED"/)
  assert.match(ordersPage, /"REFUNDED"/)
  assert.match(ordersPage, /order\.refundAmount/)
})

test("refund reclaim payload and surfaces keep the safe default and never require a reason", () => {
  const apiSource = readSource("../services/api.ts")
  const routeSource = readSource("../components/admin/orders/order-detail-route.tsx")
  const sheetSource = readSource("../components/admin/orders/order-detail-sheet.tsx")
  const orderPage = readSource("../app/(store)/order/[orderId]/page.tsx")
  const ordersPage = readSource("../app/(store)/my/orders/page.tsx")

  assert.match(apiSource, /buildAdminRefundPayload\(reason, reclaimCardKeys\)/)
  assert.match(routeSource, /const \[reclaimCardKeys, setReclaimCardKeys\] = useState\(false\)/)
  assert.match(routeSource, /reclaimableCardKeyCount/)
  assert.match(routeSource, /refundReclaimRiskWarning/)
  assert.doesNotMatch(routeSource, /refundReasonTooShort/)
  assert.match(sheetSource, /refundCardHandling/)
  assert.match(sheetSource, /refundNotReclaimed/)
  assert.match(sheetSource, /refundReclaimed/)
  assert.match(orderPage, /reclaimedCardKeys/)
  assert.match(ordersPage, /reclaimed_card_key_count/)
})

test("refund card-key availability keeps the checkbox visible while gating reclaim safely", () => {
  const available = { loading: false, error: false, count: 3 }
  assert.equal(getRefundCardKeyAvailability(available), "available")
  assert.equal(canReclaimCardKeys(available), true)
  assert.equal(normalizeRefundCardKeySelection(true, available), true)
  assert.equal(normalizeRefundCardKeySelection(false, available), false)

  const empty = { loading: false, error: false, count: 0 }
  assert.equal(getRefundCardKeyAvailability(empty), "empty")
  assert.equal(canReclaimCardKeys(empty), false)
  assert.equal(normalizeRefundCardKeySelection(true, empty), false)

  const loading = { loading: true, error: false, count: 0 }
  assert.equal(getRefundCardKeyAvailability(loading), "loading")
  assert.equal(canReclaimCardKeys(loading), false)
  assert.equal(normalizeRefundCardKeySelection(true, loading), false)

  const failed = { loading: false, error: true, count: 3 }
  assert.equal(getRefundCardKeyAvailability(failed), "error")
  assert.equal(canReclaimCardKeys(failed), false)
  assert.equal(normalizeRefundCardKeySelection(true, failed), false)
})

test("refund payload preserves the reason and sends the explicit reclaim boolean", () => {
  assert.deepEqual(buildAdminRefundPayload("人工退款", true), {
    reason: "人工退款",
    confirmed: true,
    reclaim_card_keys: true,
  })
  assert.deepEqual(buildAdminRefundPayload("", false), {
    reason: "",
    confirmed: true,
    reclaim_card_keys: false,
  })
})

test("refund dialog always exposes card handling states and resets reclaim selection", () => {
  const routeSource = readSource("../components/admin/orders/order-detail-route.tsx")
  const i18nSource = readSource("./i18n.ts")

  assert.doesNotMatch(routeSource, /reclaimableCardKeyCount > 0 &&/)
  assert.match(routeSource, /t\("admin\.refundCardHandling"\)/)
  assert.match(routeSource, /refundCardKeyAvailability === "available"/)
  assert.match(routeSource, /refundCardKeyAvailability === "empty"/)
  assert.match(routeSource, /refundCardKeyAvailability === "loading"/)
  assert.match(routeSource, /refundCardKeyAvailability === "error"/)
  assert.match(routeSource, /disabled=\{refundLoading \|\| !reclaimCardKeysEnabled\}/)
  assert.match(routeSource, /normalizeRefundCardKeySelection\(reclaimCardKeys/)
  assert.match(routeSource, /setRefundOpen\(true\); setRefundReason\(""\); setReclaimCardKeys\(false\)/)
  assert.match(routeSource, /setRefundOpen\(false\); setRefundReason\(""\); setReclaimCardKeys\(false\)/)
  assert.match(routeSource, /setCardKeys\(\[\]\)[\s\S]*setCardKeysLoading\(false\)[\s\S]*setCardKeysError\(false\)[\s\S]*setReclaimCardKeys\(false\)/)
  assert.match(i18nSource, /"admin\.refundReclaimEstimate": "可回收 \{count\} 张已发卡密"/)
  assert.match(i18nSource, /"admin\.refundReclaimEmpty": "当前订单没有已发出的可回收卡密"/)
  assert.match(i18nSource, /"admin\.refundReclaimLoading": "正在检查可回收卡密…"/)
  assert.match(i18nSource, /"admin\.refundReclaimError": "无法确认当前订单的卡密状态，请刷新订单后重试"/)
  assert.match(routeSource, /refundReasonPlaceholder/)
  assert.doesNotMatch(routeSource, /confirmDisabled=\{refundReason/)
})

test("pending payment polling uses the one-second database GET path and not provider refresh", () => {
  const source = readSource("../app/(store)/order/[orderId]/page.tsx")
  const pollingSource = readSource("./order-polling.ts")
  const pollingStart = source.indexOf("const timer = window.setInterval(() => {")
  const pollingEnd = source.indexOf("}, interval)", pollingStart)
  const pollingBody = source.slice(pollingStart, pollingEnd)

  assert.match(pollingSource, /PAYMENT_STATUS_POLL_INTERVAL = 1000/)
  assert.match(source, /getOrderStatusPollInterval\(view\.order\.status, checkingReturn\)/)
  assert.match(source, /forceRefresh \? await orderApi\.refresh\(orderId\) : await orderApi\.get\(orderId\)/)
  assert.match(pollingBody, /fetchView\("poll"\)/)
  assert.doesNotMatch(pollingBody, /orderApi\.refresh/)
})

test("manual storefront delivery is separate from automatic card-key delivery", () => {
  const source = readSource("../app/(store)/order/[orderId]/page.tsx")

  assert.match(source, /isManualFulfillmentItem/)
  assert.match(source, /ManualDeliveryItem/)
  assert.match(source, /MarkdownContent/)
  assert.match(source, /deliveryWaitingManual/)
  assert.match(source, /manualDeliveryContentTitle/)
  assert.match(source, /manualDeliveryEmpty/)
  assert.match(source, /delivery_type\?\.toUpperCase\(\) === "AUTO"/)
  assert.doesNotMatch(source, /ReactMarkdown/)
})

test("admin manual fulfillment confirms in a form dialog before sending one batch request", () => {
  const routeSource = readSource("../components/admin/orders/order-detail-route.tsx")
  const detailSource = readSource("../components/admin/orders/order-detail-sheet.tsx")
  const dialogSource = readSource("../components/admin/orders/manual-fulfillment-dialog.tsx")

  assert.doesNotMatch(detailSource, /onOpenManualFulfillment/)
  assert.match(routeSource, /setManualFulfillmentOpen\(true\)/)
  assert.match(routeSource, /completeDelivery/)
  assert.match(routeSource, /adminOrderApi\.fulfill/)
  assert.match(routeSource, /confirmed: true/)
  assert.match(dialogSource, /<MarkdownEditor/)
  assert.match(dialogSource, /customer_delivery_content/)
  assert.match(dialogSource, /manualFulfillmentConfirm/)
  assert.doesNotMatch(routeSource, /completeManualItem/)
  assert.doesNotMatch(dialogSource, /window\.confirm/)
})

test("admin order mutations publish one shared invalidation after the detail refresh", () => {
  const listSource = readSource("../app/admin/orders/page.tsx")
  const routeSource = readSource("../components/admin/orders/order-detail-route.tsx")
  const invalidationSource = readSource("./admin-order-invalidation.ts")

  assert.match(invalidationSource, /useSyncExternalStore/)
  assert.match(invalidationSource, /invalidateAdminOrders/)
  assert.match(listSource, /useAdminOrderInvalidation/)
  assert.match(listSource, /\[loadOrders, orderInvalidationRevision\]/)
  assert.doesNotMatch(listSource, /invalidateAdminOrders\(\)/)
  assert.doesNotMatch(listSource, /await loadOrders\(\)/)
  assert.match(routeSource, /await load\(\)[\s\S]*invalidateAdminOrders\(\)/)
  assert.equal((routeSource.match(/invalidateAdminOrders\(\)/g) ?? []).length, 3)
})

test("storefront markdown surfaces use the shared safe renderer", () => {
  const productSource = readSource("../components/shared/product-description.tsx")
  const productPageSource = readSource("../app/(store)/product/[id]/page.tsx")
  const shellSource = readSource("../app/(store)/store-shell-client.tsx")

  assert.match(productSource, /MarkdownContent/)
  assert.match(productPageSource, /@\/components\/shared\/product-description/)
  assert.match(shellSource, /MarkdownContent/)
  assert.doesNotMatch(productSource, /ReactMarkdown/)
  assert.doesNotMatch(shellSource, /ReactMarkdown/)
})

test("storefront order details fetch unique product metadata and keep card content out of UI labels", () => {
  const orderSource = readSource("../app/(store)/order/[orderId]/page.tsx")
  const i18nSource = readSource("./i18n.ts")

  assert.match(orderSource, /productApi\.getDetail/)
  assert.match(orderSource, /new Set\(orderItems\.map\(item => item\.product_id\)/)
  assert.match(orderSource, /formatCardContent\(item\.content_parts\)/)
  assert.match(orderSource, /formatCardContents\(groups\.flatMap/)
  assert.match(orderSource, /startNumber \+ itemIndex/)
  assert.doesNotMatch(orderSource, /\{index \+ 1\}\. \{group\.product_title\}/)
  assert.doesNotMatch(orderSource, /console\.(log|info|debug|warn|error)/)
  assert.match(i18nSource, /"myOrders\.paid": "待发货"/)
  assert.match(i18nSource, /"status\.PAID_FILTER": "待发货"/)
  assert.match(i18nSource, /"status\.WAITING_AUTO": "待发货"/)
  assert.match(i18nSource, /"myOrders\.paid": "Awaiting fulfillment"/)
  assert.match(i18nSource, /"status\.PAID_FILTER": "Awaiting fulfillment"/)
  assert.match(i18nSource, /"status\.WAITING_AUTO": "Awaiting fulfillment"/)
  assert.doesNotMatch(i18nSource, /已支付（未完成发货）|待自动发货|Paid \(not fully fulfilled\)|Awaiting automatic delivery/)
})

test("storefront order information is an accessible closed details section", () => {
  const orderSource = readSource("../app/(store)/order/[orderId]/page.tsx")

  assert.match(orderSource, /<details className="surface overflow-hidden">/)
  assert.doesNotMatch(orderSource, /<details[^>]*\bopen\b/)
  assert.match(orderSource, /<summary[^>]*className="group flex min-h-12/)
  assert.match(orderSource, /group-open:rotate-180/)
  assert.match(orderSource, /view\.order\.order_no/)
  for (const key of ["orderNo", "createdAt", "paidAt", "deliveredAt", "paymentMethod"]) {
    assert.match(orderSource, new RegExp(`order\\.${key}`))
  }
  assert.match(orderSource, /id="order-information-panel"/)
})

test("all card surfaces keep one card per number and stack ordered content parts vertically", () => {
  const sharedSource = readSource("../components/shared/card-content-parts.tsx")
  const buyerSource = readSource("../app/(store)/order/[orderId]/page.tsx")
  const adminOrderSource = readSource("../components/admin/orders/order-detail-sheet.tsx")
  const adminCardSource = readSource("../app/admin/card-keys/page.tsx")

  assert.doesNotMatch(sharedSource, /useState|slice\(0, 2\)|flex-wrap/)
  assert.match(sharedSource, /resolvedParts\.map/)
  assert.match(sharedSource, /whitespace-pre-wrap/)
  assert.match(sharedSource, /\[overflow-wrap:anywhere\]/)
  assert.match(buyerSource, /<CardContentParts[^>]*showPartCopy=\{false\}[^>]*showCopyAll=\{false\}/)
  assert.doesNotMatch(buyerSource, /item\.content_parts\.map/)
  assert.match(adminOrderSource, /<CardContentParts[^>]*showCopyAll=\{false\}/)
  assert.doesNotMatch(adminOrderSource, /overflow-x-auto[^\n]*group\.cards/)
  assert.doesNotMatch(adminOrderSource, /min-w-max/)
  assert.doesNotMatch(adminCardSource, /expandedByDefault/)
  assert.match(adminCardSource, /formatCardContents\(pickupResult\.cards\)/)
})

test("operation-log surfaces mask UUIDs instead of rendering technical target identifiers", () => {
  const sharedSource = readSource("../components/admin/shared/technical-id.tsx")
  const globalLogsSource = readSource("../app/admin/operation-logs/page.tsx")
  const userLogsSource = readSource("../app/admin/users/[id]/page.tsx")
  const administratorLogsSource = readSource("../app/admin/administrators/page.tsx")

  assert.match(sharedSource, /UUID_PATTERN/)
  assert.match(sharedSource, /maskTechnicalIdsInText/)
  assert.match(globalLogsSource, /maskTechnicalIdsInText\(log\.detail\)/)
  assert.match(userLogsSource, /maskTechnicalIdsInText\(log\.detail\)/)
  assert.match(administratorLogsSource, /maskTechnicalIdsInText\(log\.detail\)/)
  assert.doesNotMatch(globalLogsSource, /log\.target_id/)
})
