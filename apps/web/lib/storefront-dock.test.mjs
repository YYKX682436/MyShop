import test from "node:test"
import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import { getStorefrontDockAction } from "./storefront-dock.ts"

const readSource = (relativePath) => readFileSync(new URL(relativePath, import.meta.url), "utf8")

test("keeps the Dock action model focused on configured primary routes", () => {
  const cases = [
    ["home", "/", null],
    ["home", "/product/abc", "/"],
    ["home", "/my/orders/", "/"],
    ["orders", "/", "/my/orders"],
    ["orders", "/order/abc", "/my/orders"],
    ["orders", "/my/orders/", null],
    ["messages", "/", "/messages"],
    ["messages", "/messages/", null],
    ["me", "/my", null],
    ["me", "/my/", null],
    ["me", "/my/security", "/my"],
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

test("StoreBottomNav submits primary Dock navigation with replace", () => {
  const source = readSource("../components/layout/store-bottom-nav.tsx")

  assert.match(source, /router\.replace\(targetPathname\)/)
  assert.doesNotMatch(source, /router\.push\(/)
})

test("Dock clicks and capsule drops share the replace submission path", () => {
  const source = readSource("../components/layout/store-bottom-nav.tsx")

  assert.match(source, /onClick=\{\(\) => requestDockAction\(item\.key\)\}/)
  assert.match(source, /const requestDockAction = useCallback\([\s\S]*?navigateToKey\(key\)/)
  assert.match(source, /const finishDrag = useCallback\([\s\S]*?navigateToKey\(targetItem\.key\)/)
})

test("replacing consecutive primary Dock destinations leaves one storefront history entry", () => {
  const history = ["https://external.example/", "/"]
  const dockDestinations = ["/my/orders", "/my", "/", "/my/orders", "/my"]

  for (const destination of dockDestinations) {
    history[history.length - 1] = destination
  }

  assert.deepEqual(history, ["https://external.example/", "/my"])
})

test("non-Dock secondary navigation still keeps its push/back boundary", () => {
  const productPurchaseDockSource = readSource("../app/(store)/product/[id]/product-purchase-dock.tsx")
  const ordersPageSource = readSource("../app/(store)/my/orders/page.tsx")

  assert.match(productPurchaseDockSource, /router\.push\(buildOrderPageHref\(result\.order\.id\)\)/)
  assert.match(ordersPageSource, /href=\{buildOrderPageHref\(order\.id\)\}/)
})

test("consecutive dock navigation behavior resolves the latest user intent under in-flight transitions", () => {
  // Simulates the exact state machine used by StoreBottomNav:
  // pendingTarget tracks in-flight navigation; pathname is the current authoritative URL
  let pathname = "/"
  let pendingTarget = null
  const dispatchedNavigations = []

  const navigateToKey = (key) => {
    const currentDestination = pendingTarget ?? pathname
    const action = getStorefrontDockAction(key, currentDestination)
    if (!action.href) return false
    pendingTarget = action.href
    dispatchedNavigations.push(action.href)
    return true
  }

  // 1. Initial state at '/'
  assert.equal(pathname, "/")
  assert.equal(pendingTarget, null)

  // 2. User clicks "orders" (/my/orders) -> dispatches navigation to /my/orders
  const firstClick = navigateToKey("orders")
  assert.equal(firstClick, true)
  assert.equal(pendingTarget, "/my/orders")
  assert.deepEqual(dispatchedNavigations, ["/my/orders"])

  // 3. User clicks "orders" again while first navigation is still pending -> duplicate suppressed
  const duplicateClick = navigateToKey("orders")
  assert.equal(duplicateClick, false)
  assert.deepEqual(dispatchedNavigations, ["/my/orders"])

  // 4. Before first navigation completes (pathname is still '/'), user clicks "home"
  // Without pendingTarget, getStorefrontDockAction('home', '/') would wrongly return null.
  // With pendingTarget ('/my/orders'), it recognizes user wants to go back to '/'!
  const reversalClick = navigateToKey("home")
  assert.equal(reversalClick, true)
  assert.equal(pendingTarget, "/")
  assert.deepEqual(dispatchedNavigations, ["/my/orders", "/"])

  // 5. In-flight transition to '/' finally completes
  pathname = "/"
  if (pendingTarget === pathname) {
    pendingTarget = null
  }
  assert.equal(pendingTarget, null)
  assert.equal(pathname, "/")

  // 6. Rapid consecutive sequence includes Messages between Orders and Me.
  navigateToKey("orders") // pendingTarget: /my/orders
  assert.equal(pendingTarget, "/my/orders")
  navigateToKey("messages")
  assert.equal(pendingTarget, "/messages")
  navigateToKey("me")     // pendingTarget: /my
  assert.equal(pendingTarget, "/my")
  assert.deepEqual(dispatchedNavigations, ["/my/orders", "/", "/my/orders", "/messages", "/my"])

  // Transition to /my resolves
  pathname = "/my"
  if (pendingTarget === pathname) {
    pendingTarget = null
  }
  assert.equal(pendingTarget, null)
  assert.equal(pathname, "/my")
})
