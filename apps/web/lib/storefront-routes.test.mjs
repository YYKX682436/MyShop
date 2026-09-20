import test from "node:test"
import assert from "node:assert/strict"
import {
  getStorefrontActiveTab,
  getStorefrontDockDestination,
  getStorefrontRouteSection,
  isStorefrontHeaderVisible,
  isStorefrontDockVisible,
} from "./storefront-routes.ts"

test("uses exact Dock destinations for active state", () => {
  const cases = [
    ["/", "home"],
    ["///", "home"],
    ["/my/orders", "orders"],
    ["/my/orders/", "orders"],
    ["/my", "me"],
    ["/my///", "me"],
    ["/product/abc", null],
    ["/order/abc", null],
    ["/my/orders/receipt", null],
    ["/my/security", null],
    ["/my/settings", null],
    ["/login", null],
    ["/register", null],
  ]

  for (const [pathname, expected] of cases) {
    assert.equal(getStorefrontActiveTab(pathname), expected, pathname)
  }
})

test("keeps broad section matching separate from Dock visibility", () => {
  assert.equal(getStorefrontRouteSection("/product/abc"), "home")
  assert.equal(getStorefrontRouteSection("/order/abc"), "orders")
  assert.equal(getStorefrontRouteSection("/my/security"), "me")
  assert.equal(getStorefrontRouteSection("/my/settings"), "me")

  assert.equal(getStorefrontActiveTab("/product/abc"), null)
  assert.equal(getStorefrontActiveTab("/order/abc"), null)
  assert.equal(getStorefrontActiveTab("/my/security"), null)
  assert.equal(getStorefrontActiveTab("/my/settings"), null)
})

test("recognizes only exact first-level Dock destinations", () => {
  const visiblePaths = ["/", "///", "/my/orders", "/my/orders/", "/my", "/my///"]
  const hiddenPaths = [
    "/product/abc",
    "/product/abc/",
    "/order",
    "/order/abc",
    "/my/orders/receipt",
    "/my/orders/receipt/",
    "/my/security",
    "/my/settings",
    "/login",
    "/register",
    "/categories",
  ]

  for (const pathname of visiblePaths) assert.equal(isStorefrontDockVisible(pathname), true, pathname)
  for (const pathname of hiddenPaths) assert.equal(isStorefrontDockVisible(pathname), false, pathname)
})

test("exposes one canonical destination identity for each Dock item", () => {
  assert.deepEqual(getStorefrontDockDestination("/"), { key: "home", pathname: "/" })
  assert.deepEqual(getStorefrontDockDestination("/my/orders/"), { key: "orders", pathname: "/my/orders" })
  assert.deepEqual(getStorefrontDockDestination("/my"), { key: "me", pathname: "/my" })
  assert.equal(getStorefrontDockDestination("/my/orders/receipt"), null)
})

test("keeps the orders route out of the Me tab", () => {
  assert.equal(getStorefrontActiveTab("/my/orders"), "orders")
  assert.equal(getStorefrontRouteSection("/my/orders/receipt"), "orders")
  assert.equal(getStorefrontRouteSection("/my/security"), "me")
})

test("hides the shared storefront header only on product detail routes", () => {
  for (const pathname of ["/product/abc", "/product/abc/", "/product/abc///"]) {
    assert.equal(isStorefrontHeaderVisible(pathname), false, pathname)
  }

  for (const pathname of ["/", "/my", "/my/orders", "/my/settings", "/order/abc", "/login"]) {
    assert.equal(isStorefrontHeaderVisible(pathname), true, pathname)
  }
})

test("StoreShellClient applies the centralized header policy and keeps Dock policy separate", async () => {
  const { readFileSync } = await import("node:fs")
  const source = readFileSync(new URL("../app/(store)/store-shell-client.tsx", import.meta.url), "utf8")

  assert.match(source, /isStorefrontHeaderVisible\(pathname\)/)
  assert.match(source, /\{hasHeader && header\}/)
  assert.match(source, /isStorefrontDockVisible\(pathname\)/)
})
