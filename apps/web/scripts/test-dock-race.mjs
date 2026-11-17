import assert from "node:assert/strict"
import { chromium } from "playwright"

const BASE_URL = process.env.BASE_URL ?? "http://localhost:3010"

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
    assert.ok(diff <= 5, `Capsule misaligned! diff=${diff}px from ${expectedKey}`)

    if (expectedContentSnippet) {
      const text = await page.locator("main").innerText()
      assert.ok(text.includes(expectedContentSnippet), `Main content should contain '${expectedContentSnippet}'`)
    }

    console.log(`[PASS] Path: ${pathname} | Tab: ${expectedKey} | Capsule diff: ${diff.toFixed(1)}px`)
    return { pathname, activeTestId }
  }

  try {
    // -----------------------------------------------------------------
    // Scenario 1: Home -> Orders (delayed) -> Home; release old request
    // -----------------------------------------------------------------
    console.log("\n--- Scenario 1: Home -> Orders (delayed) -> Home; release old request ---")
    let delayOrders = true
    let releaseOrders
    const ordersDeferred = new Promise((resolve) => { releaseOrders = resolve })

    await page.route("**/my/orders*", async (route) => {
      if (delayOrders) {
        console.log("  [ROUTE] Holding /my/orders RSC request...")
        await ordersDeferred
        console.log("  [ROUTE] Released /my/orders RSC request")
      }
      await route.continue()
    })

    await page.goto(`${BASE_URL}/`, { waitUntil: "domcontentloaded" })
    await page.waitForSelector(".store-dock")
    await checkDockState("home")

    console.log("  1. Clicking Orders (request will be held)...")
    await page.locator('[data-testid="store-dock-orders"]').click()
    await page.waitForTimeout(100)

    console.log("  2. Clicking Home before Orders resolves...")
    await page.locator('[data-testid="store-dock-home"]').click()
    await page.waitForTimeout(200)

    console.log("  3. Releasing held Orders request...")
    delayOrders = false
    releaseOrders()
    await page.waitForTimeout(800)

    const s1 = await checkDockState("home")
    assert.equal(s1.pathname, "/", "Scenario 1: final URL must stay '/'")
    await page.unroute("**/my/orders*")

    // -----------------------------------------------------------------
    // Scenario 2: Home -> Orders (delayed) -> Me; release old request
    // -----------------------------------------------------------------
    console.log("\n--- Scenario 2: Home -> Orders (delayed) -> Me; release old request ---")
    delayOrders = true
    const ordersDeferred2 = new Promise((resolve) => { releaseOrders = resolve })

    await page.route("**/my/orders*", async (route) => {
      if (delayOrders) {
        console.log("  [ROUTE] Holding /my/orders RSC request...")
        await ordersDeferred2
        console.log("  [ROUTE] Released /my/orders RSC request")
      }
      await route.continue()
    })

    console.log("  1. Clicking Orders (request held)...")
    await page.locator('[data-testid="store-dock-orders"]').click()
    await page.waitForTimeout(100)

    console.log("  2. Clicking Me (/my) before Orders completes...")
    await page.locator('[data-testid="store-dock-me"]').click()
    await page.waitForTimeout(300)

    console.log("  3. Releasing held Orders request...")
    delayOrders = false
    releaseOrders()
    await page.waitForTimeout(800)

    const s2 = await checkDockState("me", "我的")
    assert.equal(s2.pathname, "/my", "Scenario 2: final URL must be '/my'")
    await page.unroute("**/my/orders*")

    // -----------------------------------------------------------------
    // Scenario 3: Home -> Orders -> Home -> Me (out-of-order completion)
    // -----------------------------------------------------------------
    console.log("\n--- Scenario 3: Home -> Orders -> Home -> Me (out-of-order completion) ---")
    await page.locator('[data-testid="store-dock-home"]').click()
    await page.waitForTimeout(500)

    let resolveOrdersHold, resolveHomeHold
    const ordersHoldPromise = new Promise((res) => { resolveOrdersHold = res })
    const homeHoldPromise = new Promise((res) => { resolveHomeHold = res })
    let holdOrders = true
    let holdHome = false

    await page.route("**/my/orders*", async (route) => {
      if (holdOrders) {
        console.log("  [ROUTE] Holding /my/orders...")
        await ordersHoldPromise
        console.log("  [ROUTE] Releasing /my/orders...")
      }
      await route.continue()
    })

    await page.route("http://localhost:3010/?_rsc=*", async (route) => {
      if (holdHome) {
        console.log("  [ROUTE] Holding Home RSC...")
        await homeHoldPromise
        console.log("  [ROUTE] Releasing Home RSC...")
      }
      await route.continue()
    })

    console.log("  1. Triggering rapid chain: Orders -> Home -> Me...")
    await page.locator('[data-testid="store-dock-orders"]').click()
    await page.waitForTimeout(50)

    holdHome = true
    await page.locator('[data-testid="store-dock-home"]').click()
    await page.waitForTimeout(50)

    await page.locator('[data-testid="store-dock-me"]').click()
    await page.waitForTimeout(300)

    console.log("  2. Resolving out-of-order: Home first, then Orders...")
    holdHome = false
    resolveHomeHold()
    await page.waitForTimeout(200)

    holdOrders = false
    resolveOrdersHold()
    await page.waitForTimeout(1000)

    const s3 = await checkDockState("me", "我的")
    assert.equal(s3.pathname, "/my", "Scenario 3: final target '/my' must win")
    await page.unroute("**/my/orders*")
    await page.unroute("http://localhost:3010/?_rsc=*")

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
    console.log("  1. Starting navigation from Orders to Me (delayed)...")
    let delayMe = true
    let resolveMe
    const meDeferred = new Promise((res) => { resolveMe = res })

    await page.route("**/my*", async (route) => {
      if (delayMe && (route.request().url().includes("_rsc") || route.request().headers()["rsc"])) {
        console.log("  [ROUTE] Holding /my RSC request...")
        await meDeferred
        console.log("  [ROUTE] Released /my RSC request")
      }
      await route.continue()
    })

    await page.locator('[data-testid="store-dock-me"]').click()
    await page.waitForTimeout(100)

    console.log("  2. Interrupting back to Orders before Me completes...")
    await page.locator('[data-testid="store-dock-orders"]').click()
    await page.waitForTimeout(200)

    console.log("  3. Releasing old /my request...")
    delayMe = false
    resolveMe()
    await page.waitForTimeout(1000)

    const s5A = await checkDockState("orders", "游客")
    assert.equal(s5A.pathname, "/my/orders", "Scenario 5A: interrupted navigation must cleanly stay on Orders")
    await page.unroute("**/my*")

    console.log("  4. Testing drag cancellation without triggering navigation...")
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

    console.log("\n[SUCCESS] All 5 controlled async dock race scenarios passed verification!")
  } finally {
    await browser.close()
  }
}

runDockRaceVerification().catch((err) => {
  console.error("\n[FAILURE] Dock race verification failed:", err)
  process.exit(1)
})
