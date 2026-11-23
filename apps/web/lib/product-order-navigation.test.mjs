import assert from "node:assert/strict"
import test from "node:test"
import { readFileSync } from "node:fs"

const readSource = (relativePath) => readFileSync(new URL(relativePath, import.meta.url), "utf8")

test("the purchase flow preserves the order detail history boundary", () => {
  const productPurchaseDockSource = readSource("../app/(store)/product/[id]/product-purchase-dock.tsx")
  const ordersPageSource = readSource("../app/(store)/my/orders/page.tsx")

  assert.match(productPurchaseDockSource, /router\.push\(buildOrderPageHref\(result\.order\.id\)\)/)
  assert.doesNotMatch(productPurchaseDockSource, /router\.replace\(buildOrderPageHref/)
  assert.match(ordersPageSource, /href=\{buildOrderPageHref\(order\.id\)\}/)
})
