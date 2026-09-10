import test from "node:test"
import assert from "node:assert/strict"
import {
  getStorefrontDockAction,
  getStorefrontDockKey,
} from "./storefront-dock.ts"

test("maps route and search state to the Dock selection", () => {
  const cases = [
    ["/", false, "home"],
    ["/", true, "search"],
    ["/product/abc", true, "home"],
    ["/my/orders", false, "orders"],
    ["/my", false, "me"],
  ]

  for (const [pathname, searchOpen, expected] of cases) {
    assert.equal(getStorefrontDockKey(pathname, searchOpen), expected, `${pathname} searchOpen=${searchOpen}`)
  }
})

test("opens search and navigates home when invoked outside the home route", () => {
  assert.deepEqual(getStorefrontDockAction("search", "/my/orders", false), {
    href: "/",
    searchOpen: true,
  })
})

test("clears search state before leaving for orders or Me", () => {
  assert.deepEqual(getStorefrontDockAction("orders", "/", true), {
    href: "/my/orders",
    searchOpen: false,
  })
  assert.deepEqual(getStorefrontDockAction("me", "/product/abc", true), {
    href: "/my",
    searchOpen: false,
  })
})

test("toggles the home search panel without navigation", () => {
  assert.deepEqual(getStorefrontDockAction("search", "/", false), {
    href: null,
    searchOpen: true,
  })
  assert.deepEqual(getStorefrontDockAction("search", "/", true), {
    href: null,
    searchOpen: false,
  })
})
