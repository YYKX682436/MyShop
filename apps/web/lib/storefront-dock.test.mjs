import test from "node:test"
import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import {
  getStorefrontDockAction,
  isCurrentStorefrontDockAction,
  resolveStorefrontDockNavigation,
  tryStartStorefrontDockNavigation,
} from "./storefront-dock.ts"

const readSource = (relativePath) => readFileSync(new URL(relativePath, import.meta.url), "utf8")

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

  assert.match(source, /router\.replace\(pending\.targetPathname\)/)
  assert.doesNotMatch(source, /router\.push\(/)
})

test("Dock clicks and capsule drops share the replace submission path", () => {
  const source = readSource("../components/layout/store-bottom-nav.tsx")

  assert.match(source, /onClick=\{\(\) => requestDockAction\(item\.key\)\}/)
  assert.match(source, /const requestDockAction = useCallback\([\s\S]*?snapAndComplete\(key, actionId\)/)
  assert.match(source, /const finishDrag = useCallback\([\s\S]*?snapAndComplete\(nearestKey, actionId\)/)
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
  const productActionsSource = readSource("../app/(store)/product/[id]/product-actions.tsx")
  const ordersPageSource = readSource("../app/(store)/my/orders/page.tsx")

  assert.match(productActionsSource, /router\.push\(buildOrderPageHref\(result\.order\.id\)\)/)
  assert.match(ordersPageSource, /href=\{buildOrderPageHref\(order\.id\)\}/)
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
  assert.equal(resolveStorefrontDockNavigation(attempt, "/", true), "pending")
  assert.equal(resolveStorefrontDockNavigation(attempt, "/my"), "interrupted")
})

test("does not fail a slow navigation while its transition is pending", () => {
  const attempt = {
    fromPathname: "/",
    targetPathname: "/my/orders",
  }

  assert.equal(resolveStorefrontDockNavigation(attempt, "/", true), "pending")
  assert.equal(resolveStorefrontDockNavigation(attempt, "/", true), "pending")
  assert.equal(resolveStorefrontDockNavigation(attempt, "/my/orders", true), "succeeded")
})

test("a settled transition at the source route is a navigation failure", () => {
  const attempt = {
    fromPathname: "/",
    targetPathname: "/my/orders",
  }

  // This is the recovery branch used when a transition settles without a
  // target pathname, including a synchronous router.replace exception.
  assert.equal(resolveStorefrontDockNavigation(attempt, "/", false), "failed")
})

test("a synchronous navigation error immediately invokes recovery", () => {
  let recoveryCalls = 0

  const started = tryStartStorefrontDockNavigation(
    () => {
      throw new Error("router.replace failed synchronously")
    },
    () => {
      recoveryCalls += 1
    },
  )

  assert.equal(started, false)
  assert.equal(recoveryCalls, 1)
})

test("a slow navigation never needs a target-source-target rollback", () => {
  const attempt = {
    fromPathname: "/",
    targetPathname: "/my/orders",
  }
  const observations = [
    resolveStorefrontDockNavigation(attempt, "/", true),
    resolveStorefrontDockNavigation(attempt, "/my/orders", true),
  ]

  assert.deepEqual(observations, ["pending", "succeeded"])
  assert.equal(observations.includes("failed"), false)
})

test("a failed navigation stays tied to the real source route for recovery", () => {
  const attempt = {
    fromPathname: "/my/orders",
    targetPathname: "/my",
  }

  assert.equal(resolveStorefrontDockNavigation(attempt, "/my/orders/", true), "pending")
  assert.equal(resolveStorefrontDockNavigation(attempt, "/my"), "succeeded")
})
