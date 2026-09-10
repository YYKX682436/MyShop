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
  assert.match(detailSource, /卡券与交付/)
  assert.match(detailSource, /商品与客户填写/)
  assert.doesNotMatch(detailSource, /技术信息 \/ 高级信息/)
  assert.match(detailSource, /admin\.orderDetails/)
  assert.match(detailSource, /order\.order_no/)
  assert.doesNotMatch(detailSource, /用户 UUID|商品 UUID|规格 UUID|下单时等级 UUID/)
  assert.doesNotMatch(detailSource, /\b[A-G]\.\s/)
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
