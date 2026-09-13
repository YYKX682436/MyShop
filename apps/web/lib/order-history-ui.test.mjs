import assert from "node:assert/strict"
import test from "node:test"
import { readFileSync } from "node:fs"

const readSource = (relativePath) => readFileSync(new URL(relativePath, import.meta.url), "utf8")

test("storefront order history is a compact overview with one detail entry for every status", () => {
  const source = readSource("../app/(store)/my/orders/page.tsx")

  assert.match(source, /Image from "next\/image"/)
  assert.match(source, /function OrderCover/)
  assert.match(source, /item\.product_cover_url/)
  assert.match(source, /<Package className="size-6/)
  assert.match(source, /onError=\{\(\) => setImageFailed\(true\)\}/)
  assert.match(source, /formatDateTime\(order\.created_at, locale\)/)
  assert.match(source, /<OrderStatusBadge status=\{order\.status\} items=\{order\.items\} \/>/)
  assert.match(source, /t\("order\.orderDetail"\)/)
  assert.match(source, /t\("order\.applyAfterSales"\)/)
  assert.match(source, /t\("order\.afterSalesUnavailable"\)/)
  assert.match(source, /disabled\s+title=\{t\("order\.afterSalesUnavailable"\)\}/)
  assert.match(source, /order\.status === "PENDING"/)
  assert.match(source, /order\.status === "PAID" \|\| order\.status === "DELIVERED"/)
  assert.match(source, /buildOrderPageHref\(order\.id\)/)
  assert.match(source, /flex min-w-0 flex-col gap-3 border-t/)
  assert.match(source, /min-w-0 flex-1/)

  for (const removedToken of [
    "PaymentIcon",
    "getPaymentLabel",
    "payment_method",
    "payment_channel_code",
    "customer_inputs",
    "customer_delivery_content",
    "reclaimed_card_key_count",
    "ExternalLink",
    "isManualFulfillmentItem",
    "order.refundAmount",
  ]) {
    assert.doesNotMatch(source, new RegExp(removedToken.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")), removedToken)
  }
})

test("storefront order details keep snapshots, customer information, and expired orders visible", () => {
  const source = readSource("../app/(store)/order/[orderId]/page.tsx")
  const details = source.match(/<details className="surface overflow-hidden">/g) ?? []

  assert.equal(details.length, 2)
  assert.doesNotMatch(source, /if \(status === "EXPIRED"\)\s*\{/)
  assert.match(source, /const itemsWithCustomerInputs = orderItems\.filter\(item => item\.customer_inputs\.length > 0\)/)
  assert.match(source, /itemsWithCustomerInputs\.length > 0/)
  assert.match(source, /t\("order\.customerInputs"\)/)
  assert.match(source, /input\.label/)
  assert.match(source, /input\.value/)
  assert.match(source, /ProductCover coverUrl=\{item\.product_cover_url\}/)
  assert.match(source, /function ProductCover\(\{ coverUrl, title \}/)
  assert.match(source, /id="customer-inputs-panel"/)
  assert.match(source, /whitespace-pre-wrap text-sm text-foreground \[overflow-wrap:anywhere\]/)
  assert.match(source, /status === "PENDING"/)
  assert.match(source, /status === "PAID" \|\| status === "DELIVERED" \|\| status === "REFUNDED"/)
  assert.match(source, /ProductDescription/)

  const orderInfoPosition = source.indexOf('t("order.orderInformation")')
  const customerInfoPosition = source.indexOf('t("order.customerInputs")')
  const paymentPosition = source.indexOf('{status === "PENDING" && (')
  assert.ok(orderInfoPosition >= 0 && customerInfoPosition > orderInfoPosition)
  assert.ok(paymentPosition > customerInfoPosition)
})

test("order history API and browser types separate brief items from detail items", () => {
  const typeSource = readSource("../types/index.ts")
  const apiSource = readSource("../../api/src/main/java/com/myshop/service/impl/UserServiceImpl.java")

  assert.match(typeSource, /export interface OrderItemBrief/)
  assert.match(typeSource, /items: OrderItemBrief\[\]/)
  assert.match(typeSource, /export interface OrderItemDetail extends OrderItemBrief/)
  assert.match(typeSource, /product_cover_url: string \| null/)
  assert.doesNotMatch(typeSource, /export interface OrderDetail extends OrderBrief/)
  assert.match(apiSource, /orderItemRepository\.findByOrderIdIn\(orderIds\)/)
  assert.match(apiSource, /groupingBy\(OrderItem::getOrderId\)/)
  assert.doesNotMatch(apiSource, /orderItemRepository\.findByOrderId\(o\.getId\(\)\)/)
  assert.doesNotMatch(apiSource, /map\.put\("customer_inputs"/)
  assert.doesNotMatch(apiSource, /map\.put\("customer_delivery_content"/)
  assert.doesNotMatch(apiSource, /map\.put\("payment_method"/)
  assert.doesNotMatch(apiSource, /map\.put\("product_id", item\.getProductId\(\)\)/)
  assert.doesNotMatch(apiSource, /map\.put\("subtotal", item\.getSubtotal\(\)\)/)
  assert.match(apiSource, /map\.put\("product_cover_url", item\.getProductCoverUrl\(\)\)/)
})

test("order history copy is localized in both storefront dictionaries", () => {
  const dictionary = readSource("./i18n.ts")

  for (const key of ["order.orderDetail", "order.customerInputs", "order.applyAfterSales", "order.afterSalesUnavailable"]) {
    assert.equal((dictionary.match(new RegExp(`"${key.replace(".", "\\.")}"`, "g")) || []).length, 2, key)
  }
  assert.doesNotMatch(dictionary, /"order\.cardKeys"|"order\.viewDelivery"|"order\.amount"/)
})
