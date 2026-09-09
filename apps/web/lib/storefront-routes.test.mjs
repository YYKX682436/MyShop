import test from "node:test"
import assert from "node:assert/strict"
import { getStorefrontActiveTab } from "./storefront-routes.ts"

test("maps storefront routes to the correct primary tab", () => {
  const cases = [
    ["/", "home"],
    ["/product/abc", "home"],
    ["/my/orders", "orders"],
    ["/order/abc", "orders"],
    ["/my", "me"],
    ["/profile", "me"],
    ["/profile/security", "me"],
    ["/login", null],
    ["/register", null],
  ]

  for (const [pathname, expected] of cases) {
    assert.equal(getStorefrontActiveTab(pathname), expected, pathname)
  }
})

test("keeps the orders route out of the Me tab", () => {
  assert.equal(getStorefrontActiveTab("/my/orders"), "orders")
  assert.equal(getStorefrontActiveTab("/my/orders/receipt"), "orders")
})
