import assert from "node:assert/strict"
import { existsSync } from "node:fs"
import { chromium } from "playwright"

const base = new URL(process.env.BASE_URL ?? "http://localhost:3011")
if (!["localhost", "127.0.0.1"].includes(base.hostname)) {
  throw new Error("Layout checks require an isolated loopback web service")
}

const macChrome = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
const executablePath = process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH
  ?? (existsSync(macChrome) ? macChrome : undefined)
const browser = await chromium.launch({ headless: true, executablePath })
const envelope = data => ({ code: 0, message: "Success", data })
const paginated = { list: [], pagination: { page: 1, page_size: 20, total: 0 } }

const mobileUa = "Mozilla/5.0 (Linux; Android 15; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
const desktopUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
const adminProfile = {
  id: "layout-test-admin",
  username: "layout-test",
  email: null,
  avatar_url: null,
  role: "ADMIN",
  user_no: 1,
  user_level_id: null,
  user_level_name: null,
  balance: 0,
  unlimited: false,
  created_at: "2026-01-01T00:00:00",
}
const adminLists = [
  { path: "/admin/orders", tableClass: "admin-orders-table", listClass: "admin-order-mobile-list", stateClass: "admin-order-mobile-state" },
  { path: "/admin/products", tableClass: "admin-products-table", listClass: "admin-product-mobile-list", stateClass: "admin-product-mobile-state" },
  { path: "/admin/users", tableClass: "admin-users-table", listClass: "admin-user-mobile-list", stateClass: "admin-user-mobile-state" },
  { path: "/admin/users", tableClass: "admin-recharges-table", listClass: "admin-recharge-mobile-list", stateClass: "admin-recharge-mobile-state", rechargeTab: true },
]

async function mockApi(page, profile = null) {
  await page.route("**/api/**", async route => {
    const pathname = new URL(route.request().url()).pathname.replace(/^\/api/, "")
    if (pathname === "/user/profile") {
      if (!profile) {
        await route.fulfill({
          status: 401,
          contentType: "application/json",
          body: JSON.stringify({ code: 401, message: "Unauthenticated", data: null }),
        })
      } else {
        await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(envelope(profile)) })
      }
      return
    }

    let data = {}
    if (pathname === "/site/config") data = { site_name: "Layout R1 test" }
    else if (pathname === "/admin/orders/search" || pathname === "/admin/products" || pathname === "/admin/users" || pathname === "/admin/users/recharges") data = paginated
    else if (/^\/admin\/(categories|user-levels|payment-channels)(?:\/|$)/.test(pathname)) data = []
    else if (pathname.startsWith("/admin/attention")) data = { total: 0, orders: 0, after_sale: 0, support: 0 }

    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(envelope(data)) })
  })
}

async function mobileContext(userAgent = mobileUa) {
  return browser.newContext({
    viewport: { width: 390, height: 844 },
    screen: { width: 390, height: 844 },
    isMobile: true,
    hasTouch: true,
    userAgent,
  })
}

async function nativeDesktopContext() {
  return browser.newContext({
    viewport: { width: 980, height: 900 },
    screen: { width: 390, height: 844 },
    hasTouch: true,
    userAgent: mobileUa,
  })
}

async function snapshotViewport(page) {
  return page.evaluate(() => {
    const viewport = document.querySelector('meta[name="viewport"]')
    return {
      preference: document.documentElement.dataset.layoutPreference,
      source: document.documentElement.dataset.layoutSource,
      mode: document.documentElement.dataset.layoutMode,
      innerWidth: window.innerWidth,
      visualWidth: window.visualViewport?.width ?? window.innerWidth,
      scale: window.visualViewport?.scale ?? 1,
      screenWidth: window.screen.width,
      storedPreference: localStorage.getItem("myshop-layout-preference"),
      meta: viewport?.content ?? "",
    }
  })
}

function assertFitToDesktopViewport(state) {
  assert.match(state.meta, /width=1024/)
  assert.match(state.meta, /user-scalable=yes/)
  assert.ok(state.visualWidth >= 990, "1024px desktop width should fit initially: " + JSON.stringify(state))
  assert.ok(state.scale < 0.6 && state.scale > 0.1, "desktop fallback should start zoomed to fit: " + JSON.stringify(state))
}

async function listSnapshot(page, tableClass, listClass, stateClass) {
  const region = page.locator("." + tableClass)
  await region.waitFor()
  await page.locator("." + tableClass + " ." + stateClass).waitFor({ state: "attached" })
  return page.evaluate(({ tableClass, listClass, stateClass }) => {
    const region = document.querySelector("." + tableClass)
    const list = region?.querySelector("." + listClass)
    const table = region?.querySelector(".admin-table-scroll table")
    const empty = region?.querySelector("." + stateClass)
    return {
      containerWidth: region?.clientWidth ?? 0,
      listDisplay: list ? getComputedStyle(list).display : "missing",
      tableDisplay: table ? getComputedStyle(table).display : "missing",
      tableScrollWidth: region?.querySelector(".admin-table-scroll")?.scrollWidth ?? 0,
      tableScrollClientWidth: region?.querySelector(".admin-table-scroll")?.clientWidth ?? 0,
      stateDisplay: empty ? getComputedStyle(empty).display : "missing",
      stateMinHeight: empty ? Number.parseFloat(getComputedStyle(empty).minHeight) : 0,
    }
  }, { tableClass, listClass, stateClass })
}

async function checkAdminList(page, path, tableClass, listClass, stateClass, {
  rechargeTab = false,
  layoutMode = "auto",
} = {}) {
  await page.goto(new URL(path, base).href, { waitUntil: "domcontentloaded" })
  if (layoutMode === "desktop") {
    await page.waitForFunction(() => document.documentElement.dataset.layoutMode === "desktop"
      && window.innerWidth >= 768, undefined, { timeout: 5000 })
  }
  if (rechargeTab) await page.getByRole("button", { name: "充值记录", exact: true }).click()
  const result = await listSnapshot(page, tableClass, listClass, stateClass)
  const compact = layoutMode !== "desktop" && result.containerWidth <= 767
  assert.equal(result.listDisplay, compact ? "grid" : "none", tableClass + " compact-list visibility at " + result.containerWidth + "px")
  assert.equal(result.tableDisplay, compact ? "none" : "table", tableClass + " table visibility at " + result.containerWidth + "px")
  if (layoutMode === "desktop") {
    assert.equal(result.listDisplay, "none", tableClass + " desktop mode retains the full table")
    assert.equal(result.tableDisplay, "table", tableClass + " desktop mode retains the full table")
    if (result.containerWidth <= 767) {
      assert.ok(result.tableScrollWidth > result.tableScrollClientWidth, tableClass + " scrolls within its table region")
    }
  }
  if (compact) {
    assert.equal(result.stateDisplay, "flex", tableClass + " compact state style at " + result.containerWidth + "px")
    assert.ok(result.stateMinHeight >= 80, tableClass + " compact state has readable height")
  }
  return result
}

try {
  {
    const context = await mobileContext()
    await context.addInitScript(() => {
      if (!localStorage.getItem("myshop-layout-preference")) localStorage.setItem("myshop-layout-preference", "auto")
    })
    const page = await context.newPage()
    await mockApi(page, adminProfile)
    await page.goto(new URL("/admin/orders", base).href, { waitUntil: "domcontentloaded" })
    await page.locator(".admin-orders-table").waitFor()

    const menuTrigger = page.locator(".admin-toolbar-actions button[title]")
    await menuTrigger.click()
    const desktopButton = page.getByRole("menuitem", { name: "切换到电脑排版", exact: true })
    await desktopButton.waitFor({ state: "visible" })
    await desktopButton.click()
    await page.waitForFunction(() => document.documentElement.dataset.layoutPreference === "desktop"
      && document.documentElement.dataset.layoutSource === "user"
      && document.querySelector('meta[name="viewport"]')?.content.includes("width=1024"))
    const forced = await snapshotViewport(page)
    assert.equal(forced.storedPreference, "desktop", "the user selection persists across routes")
    assertFitToDesktopViewport(forced)
    const forcedOrder = await listSnapshot(page, "admin-orders-table", "admin-order-mobile-list", "admin-order-mobile-state")
    assert.equal(forcedOrder.listDisplay, "none")
    assert.equal(forcedOrder.tableDisplay, "table")

    await page.goto(new URL("/admin/products", base).href, { waitUntil: "domcontentloaded" })
    await page.waitForFunction(() => document.documentElement.dataset.layoutPreference === "desktop"
      && document.querySelector('meta[name="viewport"]')?.content.includes("width=1024"), undefined, { timeout: 5000 })
    await page.goBack()
    await page.waitForURL(url => url.pathname === "/admin/orders")
    await page.waitForFunction(() => document.documentElement.dataset.layoutPreference === "desktop")

    for (const item of adminLists) {
      const result = await checkAdminList(page, item.path, item.tableClass, item.listClass, item.stateClass, {
        layoutMode: "desktop",
        rechargeTab: item.rechargeTab,
      })
      assert.equal(result.listDisplay, "none")
      assert.equal(result.tableDisplay, "table")
    }

    await page.waitForTimeout(200)
    await menuTrigger.click()
    await page.getByRole("menuitem", { name: "恢复自动排版", exact: true }).waitFor({ state: "visible" })
    await page.waitForTimeout(200)
    await page.getByRole("menuitem", { name: "恢复自动排版", exact: true }).click()
    await page.waitForFunction(() => document.documentElement.dataset.layoutPreference === "auto")
    await page.waitForFunction(() => document.documentElement.dataset.layoutSource === "auto")
    assert.match((await snapshotViewport(page)).meta, /width=device-width/)
    await page.waitForFunction(() => window.innerWidth < 768)
    const restoredList = await listSnapshot(page, "admin-recharges-table", "admin-recharge-mobile-list", "admin-recharge-mobile-state")
    assert.equal(restoredList.listDisplay, "grid")
    assert.equal(restoredList.tableDisplay, "none")
    console.log("explicit desktop table mode, route persistence, and ordinary mobile restore passed")
    await context.close()
  }

  {
    const context = await mobileContext(desktopUa)
    await context.addInitScript(() => localStorage.setItem("myshop-layout-preference", "auto"))
    const page = await context.newPage()
    await mockApi(page, adminProfile)
    await page.goto(new URL("/admin/orders", base).href, { waitUntil: "domcontentloaded" })
    await page.waitForFunction(() => document.documentElement.dataset.layoutSource === "browser", undefined, { timeout: 5000 })
    await page.waitForFunction(() => document.querySelector('meta[name="viewport"]')?.content.includes("width=1024"), undefined, { timeout: 5000 })
    const state = await snapshotViewport(page)
    assert.equal(state.preference, "auto", "saved auto still respects a browser desktop request")
    assert.equal(state.mode, "desktop")
    await page.locator(".admin-toolbar-actions button[title]").click()
    assert.equal(await page.getByRole("menuitem", { name: "切换到电脑排版", exact: true }).count(), 0)
    assertFitToDesktopViewport(state)

    for (const item of adminLists) {
      await checkAdminList(page, item.path, item.tableClass, item.listClass, item.stateClass, {
        layoutMode: "desktop",
        rechargeTab: item.rechargeTab,
      })
    }
    console.log("saved auto UA detection and 1024px fit viewport passed")
    await context.close()
  }

  {
    const context = await mobileContext(desktopUa)
    await context.addInitScript(() => {
      localStorage.setItem("myshop-layout-preference", "auto")
      const poll = window.setInterval(() => {
        if (document.documentElement.dataset.layoutSource !== "browser") return
        window.clearInterval(poll)
        window.setTimeout(() => {
          const viewport = document.querySelector('meta[name="viewport"]')
          delete viewport.dataset.myshopDesktopWidth
          delete viewport.dataset.myshopDesktopContent
          viewport.content = "width=1280, user-scalable=1"
          window.dispatchEvent(new Event("resize"))
        }, 100)
      }, 10)
    })
    const page = await context.newPage()
    await mockApi(page, adminProfile)
    await page.goto(new URL("/admin/orders", base).href, { waitUntil: "domcontentloaded" })
    await page.waitForFunction(() => document.documentElement.dataset.layoutSource === "native")
    await page.waitForTimeout(900)
    assert.equal((await snapshotViewport(page)).meta, "width=1280, user-scalable=1", "native viewport wins over a pending fallback")
    console.log("native viewport arriving after UA detection is preserved")
    await context.close()
  }

  {
    const context = await nativeDesktopContext()
    await context.addInitScript(() => {
      if (!localStorage.getItem("myshop-layout-preference")) localStorage.setItem("myshop-layout-preference", "auto")
    })
    const page = await context.newPage()
    await mockApi(page, adminProfile)

    for (const item of adminLists) {
      const result = await checkAdminList(page, item.path, item.tableClass, item.listClass, item.stateClass, {
        layoutMode: "desktop",
        rechargeTab: item.rechargeTab,
      })
      await page.waitForFunction(() => document.documentElement.dataset.layoutSource === "native")
      assert.ok(result.containerWidth <= 767, item.tableClass + " native 980px case exercises the expanded-sidebar width")
    }

    await checkAdminList(page, "/admin/orders", "admin-orders-table", "admin-order-mobile-list", "admin-order-mobile-state", {
      layoutMode: "desktop",
    })
    await page.waitForFunction(() => document.documentElement.dataset.layoutSource === "native")
    await page.waitForFunction(() => document.querySelector("[data-collapsible][data-state]")?.getAttribute("data-state") === "expanded")
    await page.locator("button[data-sidebar=trigger]").first().click()
    await page.waitForFunction(() => document.querySelector("[data-collapsible][data-state]")?.getAttribute("data-state") === "collapsed")
    await page.waitForTimeout(250)
    const collapsed = await listSnapshot(page, "admin-orders-table", "admin-order-mobile-list", "admin-order-mobile-state")
    assert.equal(collapsed.listDisplay, "none")
    assert.equal(collapsed.tableDisplay, "table")
    console.log("recognized native 980px desktop request keeps all four full tables with expanded and collapsed sidebars")
    await context.close()
  }

  {
    const context = await mobileContext()
    await context.addInitScript(() => {
      localStorage.setItem("myshop-layout-preference", "desktop")
      const poll = window.setInterval(() => {
        if (!document.documentElement.dataset.layoutPreference) return
        window.clearInterval(poll)
        window.dispatchEvent(new CustomEvent("myshop:layout-preference-request", { detail: { preference: "auto" } }))
      }, 10)
    })
    const page = await context.newPage()
    await mockApi(page, adminProfile)
    await page.goto(new URL("/admin/orders", base).href, { waitUntil: "domcontentloaded" })
    await page.waitForFunction(() => document.documentElement.dataset.layoutPreference === "auto")
    await page.waitForTimeout(1000)
    const state = await snapshotViewport(page)
    assert.equal(state.preference, "auto")
    assert.equal(state.source, "auto")
    assert.match(state.meta, /width=device-width/)
    assert.doesNotMatch(state.meta, /width=1024/)
    console.log("stale desktop fallback cannot overwrite a newer auto preference")
    await context.close()
  }

  {
    const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, userAgent: desktopUa })
    const page = await context.newPage()
    await mockApi(page, adminProfile)
    await page.goto(new URL("/admin/orders", base).href, { waitUntil: "domcontentloaded" })
    await page.waitForFunction(() => document.documentElement.dataset.layoutMode === "auto")
    await page.locator(".admin-toolbar-actions button[title]").click()
    assert.equal(await page.getByRole("menuitem", { name: "切换到电脑排版", exact: true }).count(), 0, "no desktop toggle where width already provides desktop layout")
    await context.close()
    console.log("ineffective desktop-layout entry is hidden on a naturally wide desktop viewport")
  }

  {
    const context = await browser.newContext({ viewport: { width: 980, height: 900 }, userAgent: desktopUa })
    const page = await context.newPage()
    await mockApi(page, adminProfile)
    await page.goto(new URL("/admin/orders", base).href, { waitUntil: "domcontentloaded" })
    await page.waitForFunction(() => document.querySelector("[data-collapsible][data-state]")?.getAttribute("data-state") === "expanded")
    const expanded = await listSnapshot(page, "admin-orders-table", "admin-order-mobile-list", "admin-order-mobile-state")
    await page.locator("button[data-sidebar=trigger]").first().click()
    await page.waitForFunction(() => document.querySelector("[data-collapsible][data-state]")?.getAttribute("data-state") === "collapsed")
    await page.waitForTimeout(250)
    const collapsed = await listSnapshot(page, "admin-orders-table", "admin-order-mobile-list", "admin-order-mobile-state")
    assert.ok(collapsed.containerWidth > expanded.containerWidth + 100, "collapsing the sidebar increases the list container width: " + JSON.stringify({ expanded, collapsed }))
    assert.notEqual(collapsed.listDisplay, expanded.listDisplay, "sidebar state crosses the compact-list container breakpoint")
    console.log("sidebar expanded/collapsed container behavior passed", { expanded, collapsed })

    for (const width of [720, 767, 768, 769, 800]) {
      await page.setViewportSize({ width, height: 900 })
      const lists = [
        ["/admin/orders", "admin-orders-table", "admin-order-mobile-list", "admin-order-mobile-state", false],
        ["/admin/products", "admin-products-table", "admin-product-mobile-list", "admin-product-mobile-state", false],
        ["/admin/users", "admin-users-table", "admin-user-mobile-list", "admin-user-mobile-state", false],
        ["/admin/users", "admin-recharges-table", "admin-recharge-mobile-list", "admin-recharge-mobile-state", true],
      ]
      for (const [path, tableClass, listClass, stateClass, rechargeTab] of lists) {
        const result = await checkAdminList(page, path, tableClass, listClass, stateClass, { rechargeTab })
        console.log("admin list " + tableClass + " at " + width + "px", result)
      }
    }
    await context.close()
  }
} finally {
  await browser.close()
}
