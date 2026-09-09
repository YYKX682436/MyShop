import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import test from "node:test"

import { formatAdminOrderCustomer, formatAdminOrderNumber, formatAdminOrderProductMeta, limitAdminOrderProductItems } from "./admin-order-display.ts"
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

test("admin order display keeps the numeric order number and compactly limits products", () => {
  assert.equal(formatAdminOrderNumber(1000000001), "1000000001")
  assert.notEqual(formatAdminOrderNumber(1000000001), "11111111-1111-1111-1111-111111111111")

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

test("admin order customer identity keeps guests to one line and uses UID as the registered fallback", () => {
  assert.deepEqual(formatAdminOrderCustomer({ customer_type: "GUEST", username: null, user_no: null }), {
    primary: "游客",
    secondary: null,
  })
  assert.deepEqual(formatAdminOrderCustomer({ customer_type: "REGISTERED", username: "buyer", user_no: 10001 }), {
    primary: "buyer",
    secondary: "UID 10001",
  })
  assert.deepEqual(formatAdminOrderCustomer({ customer_type: "REGISTERED", username: "", user_no: 10001 }), {
    primary: "UID 10001",
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
  assert.match(detailSource, /技术信息 \/ 高级信息/)
  assert.doesNotMatch(detailSource, /\b[A-G]\.\s/)
})

test("order query lets authenticated owners omit lookup tokens while guests still need one", () => {
  const source = readSource("../app/(store)/order/query/page.tsx")
  assert.match(source, /useAuth/)
  assert.match(source, /!user && !token/)
  assert.match(source, /lookup_token/)
  assert.match(source, /if \(storedToken \|\| user\)/)
  assert.match(source, /else void search\(requested, storedToken\)/)
})
