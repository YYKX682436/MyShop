import test from "node:test"
import assert from "node:assert/strict"
import { getStorefrontDockAction } from "./storefront-dock.ts"

test("keeps the Dock action model limited to the three primary routes", () => {
  const cases = [
    ["home", "/", null],
    ["home", "/product/abc", "/"],
    ["orders", "/", "/my/orders"],
    ["orders", "/order/abc", "/my/orders"],
    ["me", "/my", "/my"],
    ["me", "/profile/security", "/my"],
  ]

  for (const [key, pathname, href] of cases) {
    assert.deepEqual(getStorefrontDockAction(key, pathname), { href }, `${key} from ${pathname}`)
  }
})

test("does not expose a search action or search state in Dock results", () => {
  const action = getStorefrontDockAction("home", "/my/orders")
  assert.deepEqual(Object.keys(action), ["href"])
  assert.equal("searchOpen" in action, false)
})
