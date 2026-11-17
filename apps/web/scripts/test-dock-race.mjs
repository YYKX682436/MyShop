import assert from "node:assert/strict"
import { chromium } from "playwright"

const BASE_URL = process.env.BASE_URL ?? "http://localhost:3010"

/**
 * Creates an asynchronous gate for RSC navigation requests.
 * Explicitly blocks and verifies that an expected network request arrives.
 * If the request does not arrive within `timeoutMs`, `waitIntercepted()` throws an assertion failure.
 */
function createRscGate(label = "RSC") {
  let releaseHandler = null
  let intercepted = false
  let notifyIntercepted = null

  const interceptedPromise = new Promise((resolve) => {
    notifyIntercepted = resolve
  })
  const releasePromise = new Promise((resolve) => {
    releaseHandler = resolve
  })

  return {
    async handleRoute(route) {
      if (!intercepted) {
        intercepted = true
        console.log(`  [ROUTE GATE] Successfully intercepted expected ${label} request`)
        notifyIntercepted()
      }
      await releasePromise
      await route.continue()
    },
    async waitIntercepted(timeoutMs = 5000) {
      let timer
      const timeoutPromise = new Promise((_, reject) => {
        timer = setTimeout(() => {
          reject(new Error(`[ROUTE GATE FAILURE] Expected ${label} request was NOT intercepted within ${timeoutMs}ms!`))
        }, timeoutMs)
      })
      try {
        await Promise.race([interceptedPromise, timeoutPromise])
      } finally {
        clearTimeout(timer)
      }
    },
    release() {
      if (releaseHandler) {
        console.log(`  [ROUTE GATE] Released held ${label} request`)
        releaseHandler()
      }
    },
  }
}

async function runDockRaceVerification() {
  console.log(`[TEST] Starting bottom dock async race condition verification on ${BASE_URL}...`)

  const browser = await chromium.launch({
    executablePath: "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    headless: true,
  })

  const context = await browser.newContext({
    viewport: { width: 390, height: 844 },
    deviceScaleFactor: 2,
    isMobile: true,
    hasTouch: true,
  })

  const page = await context.newPage()

  // Mock API routes to avoid external backend dependency
  await page.route("**/api/site/config*", (route) => {
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ code: 200, data: { maintenance_enabled: false } }),
    })
  })

  await page.route("**/api/user/profile*", (route) => {
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ code: 200, data: null }),
    })
  })

  await page.route("**/api/visit/track*", (route) => {
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ code: 200, data: true }),
    })
  })

  async function checkDockState(expectedKey, expectedContentSnippet = null) {
    const pathname = await page.evaluate(() => window.location.pathname)
    const activeItem = page.locator('.store-dock-item[aria-current="page"]')
    const activeCount = await activeItem.count()
    assert.equal(activeCount, 1, `Expected exactly 1 active dock item with aria-current="page", found ${activeCount}`)

    const activeTestId = await activeItem.getAttribute("data-testid")
    assert.equal(activeTestId, `store-dock-${expectedKey}`, `Active tab data-testid expected 'store-dock-${expectedKey}', got '${activeTestId}'`)

    const capsuleBox = await page.locator(".store-dock-active").boundingBox()
    const targetBox = await page.locator(`[data-testid="store-dock-${expectedKey}"]`).boundingBox()
    assert.ok(capsuleBox, "Capsule .store-dock-active must exist")
    assert.ok(targetBox, `Target item store-dock-${expectedKey} must exist`)

    const capsuleCenter = capsuleBox.x + capsuleBox.width / 2
    const targetCenter = targetBox.x + targetBox.width / 2
    const diff = Math.abs(capsuleCenter - targetCenter)
    assert.ok(diff <= 5, `Capsule misaligned! diff=${diff.toFixed(2)}px from ${expectedKey}`)

    if (expectedContentSnippet) {
      const text = await page.locator("main").innerText()
      assert.ok(text.includes(expectedContentSnippet), `Main content should contain '${expectedContentSnippet}'`)
    }

    console.log(`[PASS] Path: ${pathname} | Tab: ${expectedKey} | Capsule diff: ${diff.toFixed(1)}px`)
    return { pathname, activeTestId }
  }

  try {
    // -----------------------------------------------------------------
    // Scenario 1: Home -> Orders (delayed & confirmed) -> Home; release old request
    // -----------------------------------------------------------------
    console.log("\n--- Scenario 1: Home -> Orders (gated) -> Home; release old request ---")
    const ordersGate1 = createRscGate("Orders (/my/orders)")
    const ordersUrlPattern = new URL("/my/orders*", BASE_URL).href

    await page.route(ordersUrlPattern, async (route) => {
      const isRsc = route.request().url().includes("_rsc") || Boolean(route.request().headers()["rsc"])
      if (isRsc) {
        return ordersGate1.handleRoute(route)
      }
      return route.continue()
    })

    await page.goto(`${BASE_URL}/`, { waitUntil: "domcontentloaded" })
    await page.waitForSelector(".store-dock")
    await checkDockState("home")

    console.log("  1. Clicking Orders button...")
    await page.locator('[data-testid="store-dock-orders"]').click()

    console.log("  2. Explicitly awaiting Orders RSC request interception...")
    await ordersGate1.waitIntercepted()

    console.log("  3. Clicking Home before Orders request is released...")
    await page.locator('[data-testid="store-dock-home"]').click()
    await page.waitForTimeout(200)

    console.log("  4. Releasing held Orders request...")
    ordersGate1.release()
    await page.waitForTimeout(800)

    const s1 = await checkDockState("home")
    assert.equal(s1.pathname, "/", "Scenario 1: final URL must stay '/'")

    console.log("  5. Verifying viewport resize alignment (confirms pendingTargetRef is cleaned)...")
    await page.setViewportSize({ width: 360, height: 800 })
    await page.waitForTimeout(300)
    await checkDockState("home")

    await page.setViewportSize({ width: 430, height: 932 })
    await page.waitForTimeout(300)
    await checkDockState("home")

    await page.setViewportSize({ width: 390, height: 844 })
    await page.waitForTimeout(300)
    await checkDockState("home")

    await page.unroute(ordersUrlPattern)

    // -----------------------------------------------------------------
    // Scenario 2: Home -> Orders (gated) -> Me; release old request
    // -----------------------------------------------------------------
    console.log("\n--- Scenario 2: Home -> Orders (gated) -> Me; release old request ---")
    const ordersGate2 = createRscGate("Orders (/my/orders)")

    await page.route(ordersUrlPattern, async (route) => {
      const isRsc = route.request().url().includes("_rsc") || Boolean(route.request().headers()["rsc"])
      if (isRsc) {
        return ordersGate2.handleRoute(route)
      }
      return route.continue()
    })

    console.log("  1. Clicking Orders button...")
    await page.locator('[data-testid="store-dock-orders"]').click()

    console.log("  2. Explicitly awaiting Orders RSC request interception...")
    await ordersGate2.waitIntercepted()

    console.log("  3. Clicking Me (/my) before Orders completes...")
    await page.locator('[data-testid="store-dock-me"]').click()
    await page.waitForTimeout(300)

    console.log("  4. Releasing held Orders request...")
    ordersGate2.release()
    await page.waitForTimeout(800)

    const s2 = await checkDockState("me", "我的")
    assert.equal(s2.pathname, "/my", "Scenario 2: final URL must be '/my'")
    await page.unroute(ordersUrlPattern)

    // -----------------------------------------------------------------
    // Scenario 3: Home -> Orders -> Home -> Me (out-of-order RSC resolution)
    // -----------------------------------------------------------------
    console.log("\n--- Scenario 3: Home -> Orders -> Home -> Me (out-of-order resolution) ---")
    await page.locator('[data-testid="store-dock-home"]').click()
    await page.waitForTimeout(500)

    const ordersGate3 = createRscGate("Orders (/my/orders)")
    const homeGate3 = createRscGate("Home (/)")
    const homeRscPattern = new URL("/?_rsc=*", BASE_URL).href

    await page.route(ordersUrlPattern, async (route) => {
      return ordersGate3.handleRoute(route)
    })

    await page.route(homeRscPattern, async (route) => {
      return homeGate3.handleRoute(route)
    })

    console.log("  1. Triggering rapid chain: Orders -> Home -> Me...")
    await page.locator('[data-testid="store-dock-orders"]').click()
    await ordersGate3.waitIntercepted()

    await page.locator('[data-testid="store-dock-home"]').click()
    await page.waitForTimeout(100)

    await page.locator('[data-testid="store-dock-me"]').click()
    await page.waitForTimeout(300)

    console.log("  2. Resolving out-of-order: Home first, then Orders...")
    homeGate3.release()
    await page.waitForTimeout(200)

    ordersGate3.release()
    await page.waitForTimeout(1000)

    const s3 = await checkDockState("me", "我的")
    assert.equal(s3.pathname, "/my", "Scenario 3: final target '/my' must win")
    await page.unroute(ordersUrlPattern)
    await page.unroute(homeRscPattern)

    // -----------------------------------------------------------------
    // Scenario 4: Drag capsule interrupted by explicit click
    // -----------------------------------------------------------------
    console.log("\n--- Scenario 4: Drag capsule interrupted by explicit click ---")
    await page.locator('[data-testid="store-dock-home"]').click()
    await page.waitForTimeout(500)

    const dock = page.locator(".store-dock")
    const dockBox = await dock.boundingBox()
    assert.ok(dockBox, "Dock bounding box must exist")

    const homeBox = await page.locator('[data-testid="store-dock-home"]').boundingBox()
    const startX = homeBox.x + homeBox.width / 2
    const startY = homeBox.y + homeBox.height / 2
    const targetX = dockBox.x + dockBox.width * 0.85

    console.log("  1. Dragging capsule from Home towards Me...")
    await page.mouse.move(startX, startY)
    await page.mouse.down()
    await page.mouse.move(targetX, startY, { steps: 5 })
    await page.mouse.up()

    console.log("  2. Immediately clicking Orders before Me settles...")
    await page.locator('[data-testid="store-dock-orders"]').click()
    await page.waitForTimeout(1000)

    const s4 = await checkDockState("orders", "游客")
    assert.equal(s4.pathname, "/my/orders", "Scenario 4: explicit operation must override drag")

    // -----------------------------------------------------------------
    // Scenario 5: In-flight interruption and drag-cancel recovery
    // -----------------------------------------------------------------
    console.log("\n--- Scenario 5: Navigation interruption & drag cancel recovery ---")
    console.log("  1. Starting navigation from Orders to Me (gated)...")
    const meGate5 = createRscGate("Me (/my)")
    const meUrlPattern = new URL("/my*", BASE_URL).href

    await page.route(meUrlPattern, async (route) => {
      const isRsc = route.request().url().includes("_rsc") || Boolean(route.request().headers()["rsc"])
      if (isRsc) {
        return meGate5.handleRoute(route)
      }
      return route.continue()
    })

    await page.locator('[data-testid="store-dock-me"]').click()
    console.log("  2. Explicitly awaiting Me RSC request interception...")
    await meGate5.waitIntercepted()

    console.log("  3. Interrupting back to Orders before Me completes...")
    await page.locator('[data-testid="store-dock-orders"]').click()
    await page.waitForTimeout(200)

    console.log("  4. Releasing old /my request...")
    meGate5.release()
    await page.waitForTimeout(1000)

    const s5A = await checkDockState("orders", "游客")
    assert.equal(s5A.pathname, "/my/orders", "Scenario 5A: interrupted navigation must cleanly stay on Orders")

    console.log("  5. Verifying viewport resize on returned Orders page...")
    await page.setViewportSize({ width: 360, height: 800 })
    await page.waitForTimeout(300)
    await checkDockState("orders", "游客")

    await page.setViewportSize({ width: 390, height: 844 })
    await page.waitForTimeout(300)
    await checkDockState("orders", "游客")
    await page.unroute(meUrlPattern)

    console.log("  6. Testing drag cancellation without triggering navigation...")
    const ordersBox = await page.locator('[data-testid="store-dock-orders"]').boundingBox()
    const ordX = ordersBox.x + ordersBox.width / 2
    const ordY = ordersBox.y + ordersBox.height / 2

    // Slight drag that doesn't reach threshold, then mouse up
    await page.mouse.move(ordX, ordY)
    await page.mouse.down()
    await page.mouse.move(ordX + 15, ordY, { steps: 3 })
    await page.mouse.up()
    await page.waitForTimeout(500)

    const s5B = await checkDockState("orders", "游客")
    assert.equal(s5B.pathname, "/my/orders", "Scenario 5B: canceled drag must snap back to active tab Orders")

    console.log("\n[SUCCESS] All 5 hardened async dock race scenarios passed verification!")
  } finally {
    await browser.close()
  }
}

runDockRaceVerification().catch((err) => {
  console.error("\n[FAILURE] Dock race verification failed:", err)
  process.exit(1)
})
