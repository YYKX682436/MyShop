import test from "node:test"
import assert from "node:assert/strict"
import {
  getStorefrontDockAction,
  isCurrentStorefrontDockAction,
  resolveStorefrontDockNavigation,
} from "./storefront-dock.ts"

test("keeps the Dock action model focused on configured primary routes", () => {
  const cases = [
    ["home", "/", null],
    ["home", "/product/abc", "/"],
    ["home", "/my/orders/", "/"],
    ["orders", "/", "/my/orders"],
    ["orders", "/order/abc", "/my/orders"],
    ["orders", "/my/orders/", null],
    ["me", "/my", null],
    ["me", "/my/", null],
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

test("stale Dock actions cannot commit after a newer action starts", () => {
  assert.equal(isCurrentStorefrontDockAction(7, 7), true)
  assert.equal(isCurrentStorefrontDockAction(7, 8), false)
})

test("resolves navigation against the real pathname", () => {
  const attempt = {
    id: 3,
    fromPathname: "/",
    targetPathname: "/my/orders",
    phase: "navigating",
  }

  assert.equal(resolveStorefrontDockNavigation(attempt, "/my/orders/"), "succeeded")
  assert.equal(resolveStorefrontDockNavigation(attempt, "/"), "pending")
  assert.equal(resolveStorefrontDockNavigation(attempt, "/my"), "interrupted")
})

test("a failed navigation stays tied to the real source route for recovery", () => {
  const attempt = {
    fromPathname: "/my/orders",
    targetPathname: "/my",
  }

  assert.equal(resolveStorefrontDockNavigation(attempt, "/my/orders/"), "pending")
  assert.equal(resolveStorefrontDockNavigation(attempt, "/my"), "succeeded")
})
