import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import test from "node:test"

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
  const source = readSource("../app/(store)/product/[id]/product-actions.tsx")
  assert.doesNotMatch(source, /\bemail\b/i)
  assert.doesNotMatch(source, /emailInputRef|emailError|validateEmail|handleEmailChange/)
  assert.match(source, /customer_inputs: normalizedCustomerInputs/)
  assert.doesNotMatch(source, /fingerprint[\s\S]*email/i)
})

test("shopper checkout renders both payment method entries while administrators render pickup only", () => {
  const source = readSource("../app/(store)/product/[id]/product-actions.tsx")
  const paymentMethodStart = source.indexOf("{adminPickup ? (")
  const shopperBranchStart = source.indexOf(") : (", paymentMethodStart)
  const paymentMethodEnd = source.indexOf("{/* Total */}", shopperBranchStart)

  assert.notEqual(paymentMethodStart, -1)
  assert.notEqual(shopperBranchStart, -1)
  assert.notEqual(paymentMethodEnd, -1)

  const administratorBranch = source.slice(paymentMethodStart, shopperBranchStart)
  const shopperBranch = source.slice(shopperBranchStart, paymentMethodEnd)

  assert.match(administratorBranch, /t\("payment\.adminPickup"\)/)
  assert.doesNotMatch(administratorBranch, /balancePayment|onlinePayment|PaymentSelector/)
  assert.match(shopperBranch, /t\("product\.balancePayment"\)/)
  assert.match(shopperBranch, /t\("product\.onlinePayment"\)/)
  assert.match(shopperBranch, /PaymentSelector/)
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
  assert.match(source, /deliveryReady/)
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

  assert.match(apiSource, /reclaim_card_keys: reclaimCardKeys/)
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
  const productSource = readSource("../app/(store)/product/[id]/product-description.tsx")
  const shellSource = readSource("../app/(store)/store-shell-client.tsx")

  assert.match(productSource, /MarkdownContent/)
  assert.match(shellSource, /MarkdownContent/)
  assert.doesNotMatch(productSource, /ReactMarkdown/)
  assert.doesNotMatch(shellSource, /ReactMarkdown/)
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
