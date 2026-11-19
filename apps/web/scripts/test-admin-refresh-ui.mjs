import assert from "node:assert/strict"
import { existsSync, mkdirSync } from "node:fs"
import { chromium } from "playwright"

// This test creates only uniquely named products in the documented local
// preview database. It removes them in finally; card imports are intercepted.
const base = new URL(process.env.BASE_URL ?? "http://localhost:3000")
if (!["localhost", "127.0.0.1"].includes(base.hostname)) throw new Error("A local preview is required")
const macChrome = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
const executablePath = process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH ?? (existsSync(macChrome) ? macChrome : undefined)
const browser = await chromium.launch({ headless: true, executablePath })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const page = await context.newPage()
const createdIds = []
const unique = `本地刷新回归-${Date.now()}`
let createRequests = 0
const recentRequests = []
page.on("request", request => {
  if (new URL(request.url()).pathname.startsWith("/api/")) {
    recentRequests.push(new URL(request.url()).pathname)
    if (recentRequests.length > 30) recentRequests.shift()
  }
  if (new URL(request.url()).pathname === "/api/admin/products" && request.method() === "POST") createRequests++
})
page.on("requestfailed", request => {
  if (new URL(request.url()).pathname.startsWith("/api/admin/") && request.failure()?.errorText !== "net::ERR_ABORTED") console.error(`Request failed: ${new URL(request.url()).pathname}: ${request.failure()?.errorText}`)
})
page.on("response", response => {
  const path = new URL(response.url()).pathname
  if (path.startsWith("/api/") && response.status() === 429) console.error(`API returned 429: ${path}; recent: ${recentRequests.join(", ")}`)
})

const screenshotDir = process.env.MYSHOP_SCREENSHOT_DIR
if (screenshotDir) mkdirSync(screenshotDir, { recursive: true })
const screenshot = async name => { if (screenshotDir) await page.screenshot({ path: `${screenshotDir}/${name}.png` }) }
const activePanel = name => page.getByTestId(`product-tab-panel-${name}`)
const next = () => page.getByRole("button", { name: "下一步", exact: true }).click()
const previous = () => page.getByRole("button", { name: "上一步", exact: true }).click()
const tab = name => page.getByTestId(`product-tab-${name}`).click()
const waitPanel = name => activePanel(name).waitFor({ state: "visible" })
const responseForCreate = () => page.waitForResponse(response => new URL(response.url()).pathname === "/api/admin/products" && response.request().method() === "POST")

try {
  await page.goto(new URL("/login", base).href)
  await page.getByRole("textbox", { name: "用户名 / 邮箱 / 手机号" }).fill(process.env.MYSHOP_PREVIEW_ADMIN ?? "muchen-admin")
  await page.getByRole("textbox", { name: "密码" }).fill(process.env.MYSHOP_PREVIEW_PASSWORD ?? "LocalPreview123!")
  await page.getByRole("button", { name: "登录", exact: true }).click()
  await page.waitForURL(url => url.pathname !== "/login")
  await page.waitForTimeout(1500)

  await page.goto(new URL("/admin/products", base).href)
  await page.getByText("演示商品", { exact: true }).first().waitFor()
  await page.getByLabel("搜索商品名称 / 商品编号").fill("不会匹配的筛选")
  await page.getByTestId("admin-add-product").click()
  await page.getByTestId("product-form").waitFor()
  await next()
  await waitPanel("basic")
  assert.equal(createRequests, 0)
  await page.getByTestId("product-title-input").fill(unique)
  await page.getByPlaceholder("简短描述商品特点").fill("步骤草稿")
  const category = page.getByTestId("product-category-select")
  await category.waitFor({ state: "visible" })
  await page.waitForFunction(() => document.querySelector('[data-testid="product-category-select"]')?.options.length > 1)
  await category.selectOption({ index: 1 })
  await next()
  await waitPanel("specs")
  await previous()
  await waitPanel("basic")
  assert.equal(await page.getByTestId("product-title-input").inputValue(), unique)
  assert.equal(await page.getByPlaceholder("简短描述商品特点").inputValue(), "步骤草稿")
  await next()
  await next()
  await waitPanel("pricing")
  await next()
  await waitPanel("pricing")
  await tab("details")
  await waitPanel("details")
  await page.getByRole("button", { name: "保存并完成" }).click()
  await waitPanel("pricing")
  assert.equal(createRequests, 0)
  const prices = activePanel("pricing").locator('tbody tr').first().locator('input[type="number"]')
  await prices.nth(0).fill("3.50")
  await prices.nth(1).fill("6.50")
  await next()
  await waitPanel("inventory")
  await next()
  await waitPanel("details")
  const markdown = activePanel("details").locator("textarea")
  await markdown.fill("本地回归详情")
  await previous()
  await next()
  await waitPanel("details")
  assert.equal(await activePanel("details").locator("textarea").inputValue(), "本地回归详情")
  await page.waitForTimeout(4500)
  await screenshot("product-steps-desktop")
  await page.setViewportSize({ width: 390, height: 844 })
  await previous()
  await next()
  await waitPanel("details")
  const actionFits = await page.getByTestId("product-form").locator(".action-bar-material").evaluate(element =>
    [...element.querySelectorAll("button")].every(button => button.getBoundingClientRect().right <= innerWidth))
  assert.ok(actionFits, "mobile step controls must fit")
  const activeTabFits = await page.getByTestId("product-tab-details").evaluate(element => {
    const tab = element.getBoundingClientRect()
    const list = element.parentElement.getBoundingClientRect()
    return tab.left >= list.left && tab.right <= list.right
  })
  assert.ok(activeTabFits, "the current step must be visible in the mobile tab strip")
  await screenshot("product-steps-mobile")
  await page.setViewportSize({ width: 1440, height: 900 })

  await page.route("**/api/admin/products", async route => {
    if (route.request().method() === "POST") await route.fulfill({ status: 500, contentType: "application/json", body: JSON.stringify({ code: 500, message: "模拟保存失败" }) })
    else await route.continue()
  })
  await page.getByRole("button", { name: "保存并完成" }).click()
  await page.getByText("模拟保存失败").first().waitFor()
  await tab("basic")
  assert.equal(await page.getByTestId("product-title-input").inputValue(), unique)
  await tab("details")
  await page.unroute("**/api/admin/products")
  const beforeSuccessfulCreate = createRequests
  const createResponse = responseForCreate()
  await page.getByRole("button", { name: "保存并继续补货" }).evaluate(button => { button.click(); button.click() })
  const created = (await (await createResponse).json()).data
  assert.ok(created?.id)
  createdIds.push(created.id)
  await page.waitForURL(url => url.pathname === `/admin/products/${created.id}/edit`)
  await waitPanel("inventory")
  assert.equal(createRequests, beforeSuccessfulCreate + 1)
  const importButton = page.getByRole("button", { name: "导入卡密", exact: true })
  await importButton.click()
  const importDialog = page.getByRole("dialog", { name: `导入卡密 · ${unique}` })
  await importDialog.getByRole("textbox", { name: "卡券内容 1 *" }).fill("local-test-card")
  let importedProduct = null
  await page.route("**/api/admin/card-keys/import", async route => {
    importedProduct = route.request().postDataJSON().product_id
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ code: 0, data: { total_count: 1, success_count: 1, duplicate_count: 0, fail_count: 0 }, message: "Success" }) })
  })
  await importDialog.getByRole("button", { name: "导入", exact: true }).click()
  await importDialog.waitFor({ state: "hidden" })
  assert.equal(importedProduct, created.id)
  assert.equal(createRequests, beforeSuccessfulCreate + 1)
  await page.unroute("**/api/admin/card-keys/import")
  await page.getByTestId("product-form").getByRole("button", { name: "取消", exact: true }).click()
  await page.waitForURL(url => url.pathname === "/admin/products")
  await page.getByText(unique, { exact: true }).first().waitFor()
  assert.equal(await page.getByLabel("搜索商品名称 / 商品编号").inputValue(), String(created.product_no))
  await page.waitForTimeout(4500)
  await screenshot("product-list-desktop")

  await page.getByRole("link", { name: "编辑商品" }).first().click()
  await page.getByTestId("product-title-input").fill(`${unique}-已编辑`)
  await page.getByTestId("product-form").getByRole("button", { name: "保存", exact: true }).click()
  await page.waitForURL(url => url.pathname === "/admin/products")
  await page.getByText(`${unique}-已编辑`, { exact: true }).first().waitFor()
  console.log("Product steps, validation, one create, restock ID, list and intercepted edit passed")

  // A direct full-page editor must also return to the refreshed list.
  await page.goto(new URL("/admin/products/new", base).href)
  await page.getByTestId("product-title-input").fill(`${unique}-全页`)
  await page.waitForFunction(() => document.querySelector('[data-testid="product-category-select"]')?.options.length > 1)
  await page.getByTestId("product-category-select").selectOption({ index: 1 })
  await tab("pricing")
  const fullPrices = activePanel("pricing").locator('tbody tr').first().locator('input[type="number"]')
  await fullPrices.nth(0).fill("1")
  await fullPrices.nth(1).fill("2")
  await tab("details")
  const fullResponse = responseForCreate()
  await page.getByRole("button", { name: "保存并完成" }).click()
  const fullCreated = (await (await fullResponse).json()).data
  createdIds.push(fullCreated.id)
  await page.waitForURL(url => url.pathname === "/admin/products")
  await page.getByText(`${unique}-全页`, { exact: true }).first().waitFor()
  assert.equal(createRequests, beforeSuccessfulCreate + 2)
  console.log("Full-page create returns to the new product passed")

  const pages = [
    ["dashboard", ["/api/admin/dashboard", "/api/admin/dashboard/sales-trend", "/api/admin/dashboard/system-status", "/api/admin/orders/search", "/api/admin/attention-summary"]],
    ["products", ["/api/admin/products", "/api/admin/categories"]],
    ["categories", ["/api/admin/categories"]],
    ["card-keys", ["/api/admin/products", "/api/admin/card-keys/stock", "/api/admin/card-keys/import-batches"]],
    ["orders", ["/api/admin/orders/search", "/api/admin/attention-summary"]],
    ["payment-channels", ["/api/admin/payment-channels", "/api/payment-providers"]],
    ["users", ["/api/admin/users", "/api/admin/user-levels"]],
    ["user-levels", ["/api/admin/user-levels"]],
    ["risk", ["/api/admin/risk-config", "/api/admin/risk/flagged-orders"]],
    ["operation-logs", ["/api/admin/operation-logs"]],
    ["site-config", ["/api/admin/site-config", "/api/admin/site-config/user-numbering"]],
    ["media", ["/api/admin/media"]],
    ["administrators", ["/api/admin/administrators"]],
  ]
  const requests = []
  const responses = []
  page.on("request", request => requests.push(new URL(request.url()).pathname))
  page.on("response", response => responses.push({ path: new URL(response.url()).pathname, status: response.status() }))
  for (const [path, expected] of pages) {
    // The local preview applies the production per-IP rate limiter. Leave its
    // bucket time to refill between deliberately rapid page navigations.
    await page.waitForTimeout(1300)
    await page.goto(new URL(`/admin/${path}`, base).href)
    const refresh = page.getByRole("button", { name: "刷新", exact: true })
    await refresh.waitFor()
    await page.waitForTimeout(500)
    await refresh.waitFor({ state: "visible" })
    await page.waitForFunction(() => !document.querySelector('header button[aria-label="刷新"]')?.disabled)
    requests.length = 0
    responses.length = 0
    await refresh.click()
    await page.waitForFunction(() => {
      const button = document.querySelector('header button[aria-label="刷新"]')
      return button && !button.disabled
    })
    for (const endpoint of expected) assert.ok(requests.includes(endpoint), `${path}: refresh did not request ${endpoint}`)
    for (const endpoint of expected) assert.ok(responses.some(response => response.path === endpoint && response.status < 400), `${path}: ${endpoint} failed`)
  }
  console.log("All 13 page refresh buttons requested their page data")

  await page.getByRole("button", { name: "操作记录", exact: true }).first().click()
  const logsDialog = page.getByRole("dialog", { name: "管理员操作记录" })
  await logsDialog.getByRole("button", { name: "刷新", exact: true }).waitFor()
  const logRequest = page.waitForRequest(request => /\/api\/admin\/administrators\/[^/]+\/operation-logs/.test(new URL(request.url()).pathname))
  await logsDialog.getByRole("button", { name: "刷新", exact: true }).click()
  await logRequest
  await page.keyboard.press("Escape")
  await logsDialog.waitFor({ state: "hidden" })
  console.log("An open administrator log dialog can refresh its records")

  await page.waitForTimeout(1300)
  await page.goto(new URL("/admin/card-keys", base).href)
  const stockRow = page.locator("tr").filter({ hasText: `${unique}-已编辑` })
  await stockRow.getByRole("button", { name: "查看详情" }).click()
  const stockDetail = page.getByRole("dialog", { name: "卡密详情" })
  await stockDetail.getByRole("button", { name: "刷新", exact: true }).waitFor()
  let releaseOldDetail
  let oldDetailStarted
  const oldDetailPending = new Promise(resolve => { releaseOldDetail = resolve })
  const oldDetailRequested = new Promise(resolve => { oldDetailStarted = resolve })
  let detailRequests = 0
  await page.route("**/api/admin/card-keys/list?*", async route => {
    detailRequests++
    if (detailRequests !== 1) return route.continue()
    oldDetailStarted()
    await oldDetailPending
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ code: 0, data: { list: [{ id: "stale-card", status: "AVAILABLE", content_parts: ["过期卡密响应"], created_at: "2026-01-01T00:00:00" }], pagination: { page: 1, page_size: 20, total: 1 } }, message: "Success" }) })
  })
  await stockDetail.getByRole("button", { name: "刷新", exact: true }).click()
  await oldDetailRequested
  await page.keyboard.press("Escape")
  await stockDetail.waitFor({ state: "hidden" })
  await stockRow.getByRole("button", { name: "查看详情" }).click()
  await stockDetail.waitFor()
  releaseOldDetail()
  await page.waitForTimeout(500)
  assert.equal(await stockDetail.getByText("过期卡密响应").count(), 0)
  await page.unroute("**/api/admin/card-keys/list?*")
  await page.keyboard.press("Escape")
  console.log("Stock details refresh and ignore responses from a closed sheet")

  await page.waitForTimeout(1300)
  await page.goto(new URL("/admin/site-config", base).href)
  const siteName = page.getByLabel("网站名称")
  await siteName.waitFor()
  await siteName.fill("未保存的本地草稿")
  page.once("dialog", dialog => dialog.dismiss())
  await page.getByRole("button", { name: "刷新", exact: true }).click()
  assert.equal(await siteName.inputValue(), "未保存的本地草稿")
  let releaseConfig
  const delayedConfig = new Promise(resolve => { releaseConfig = resolve })
  await page.route("**/api/admin/site-config", async route => { await delayedConfig; await route.continue() })
  page.once("dialog", dialog => dialog.accept())
  const configRequest = page.waitForRequest(request => new URL(request.url()).pathname === "/api/admin/site-config" && request.method() === "GET")
  await page.getByRole("button", { name: "刷新", exact: true }).click()
  await configRequest
  await siteName.fill("刷新期间继续编辑")
  releaseConfig()
  await page.getByRole("alert").filter({ hasText: "加载失败" }).first().waitFor()
  assert.equal(await siteName.inputValue(), "刷新期间继续编辑")
  await page.unroute("**/api/admin/site-config")
  console.log("Configuration drafts survive declined and in-flight refreshes")

  await page.waitForTimeout(1300)
  await page.goto(new URL("/admin/risk", base).href)
  const riskLimit = page.getByLabel("每秒请求数限制")
  await riskLimit.waitFor()
  await riskLimit.fill("123")
  page.once("dialog", dialog => dialog.dismiss())
  await page.getByRole("button", { name: "刷新", exact: true }).click()
  assert.equal(await riskLimit.inputValue(), "123")
  console.log("Risk draft survives a declined refresh")

  await page.waitForTimeout(1300)
  await page.goto(new URL("/admin/media", base).href)
  const mediaSearch = page.getByLabel("搜索图片名称")
  await mediaSearch.fill("local-filter")
  const mediaOrder = page.getByLabel("最新上传")
  await mediaOrder.selectOption("asc")
  await page.waitForTimeout(500)
  let failMedia = true
  await page.route("**/api/admin/media?*", async route => {
    if (failMedia) { failMedia = false; await route.fulfill({ status: 500, contentType: "application/json", body: JSON.stringify({ code: 500, message: "failed" }) }) }
    else await route.continue()
  })
  await page.getByRole("button", { name: "刷新", exact: true }).click()
  const mediaError = page.getByRole("alert").filter({ hasText: "加载失败" })
  await mediaError.getByRole("button", { name: "重试" }).click()
  await mediaError.waitFor({ state: "hidden" })
  assert.equal(await mediaSearch.inputValue(), "local-filter")
  assert.equal(await mediaOrder.inputValue(), "asc")
  await page.unroute("**/api/admin/media?*")
  console.log("Media refresh retries after failure while preserving filters")

  await page.waitForTimeout(1300)
  await page.goto(new URL("/admin/products", base).href)
  const search = page.getByLabel("搜索商品名称 / 商品编号")
  await search.waitFor()
  let slowStarted
  const slowRequestStarted = new Promise(resolve => { slowStarted = resolve })
  await page.route("**/api/admin/products?*", async route => {
    const query = new URL(route.request().url()).searchParams.get("keyword")
    if (query !== "race-slow" && query !== "race-fast") return route.continue()
    if (query === "race-slow") { slowStarted(); await new Promise(resolve => setTimeout(resolve, 1200)) }
    const item = { ...created, title: query }
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ code: 0, data: { list: [item], pagination: { page: 1, page_size: 20, total: 1 } }, message: "Success" }) })
  })
  await search.fill("race-slow")
  await slowRequestStarted
  await search.fill("race-fast")
  await page.getByText("race-fast", { exact: true }).first().waitFor()
  await page.waitForTimeout(1400)
  assert.equal(await page.getByText("race-slow", { exact: true }).count(), 0)
  assert.equal(await search.inputValue(), "race-fast")
  await page.unroute("**/api/admin/products?*")
  console.log("A delayed older product request cannot replace the latest filter")

  await page.waitForTimeout(1300)
  await page.goto(new URL("/admin/products", base).href)
  await page.getByLabel("搜索商品名称 / 商品编号").fill("演示")
  await page.getByLabel("排序").selectOption("retail_asc")
  await page.waitForTimeout(500)
  let refreshRequests = 0
  await page.route("**/api/admin/products?*", async route => {
    refreshRequests++
    await new Promise(resolve => setTimeout(resolve, 500))
    await route.continue()
  })
  await page.getByRole("button", { name: "刷新", exact: true }).evaluate(button => { button.click(); button.click() })
  await page.getByRole("button", { name: "刷新中...", exact: true }).waitFor()
  await page.getByRole("button", { name: "刷新", exact: true }).waitFor()
  assert.equal(refreshRequests, 1)
  assert.equal(await page.getByLabel("搜索商品名称 / 商品编号").inputValue(), "演示")
  assert.equal(await page.getByLabel("排序").inputValue(), "retail_asc")
  await page.unroute("**/api/admin/products?*")
  console.log("Rapid refresh is deduplicated and preserves filters and sorting")

  // Intercept the mutation so this exercises the existing order notification
  // without writing an order or touching payment data.
  await page.waitForTimeout(1300)
  const orderId = "ca4d0000-0000-4000-8000-000000000077"
  let orderStatus = "PENDING"
  let releaseOrderDetail
  let orderDetailFinished = false
  const delayedOrderDetail = new Promise(resolve => { releaseOrderDetail = resolve })
  const order = { id: orderId, order_no: 123456789, status: "PENDING", order_type: "NORMAL", payment_method: "ONLINE", payment_channel_code: "test", customer_type: "GUEST", user_no: null, username: null, items: [], is_risk_flagged: false, actual_amount: 10, total_amount: 10, cost_total: 0, gross_profit_total: 0, gross_margin_total: null, created_at: "2026-01-01T00:00:00" }
  const envelope = data => ({ status: 200, contentType: "application/json", body: JSON.stringify({ code: 0, data, message: "Success" }) })
  await page.route("**/api/admin/orders/search", route => route.fulfill(envelope({ list: [{ ...order, status: orderStatus }], pagination: { page: 1, page_size: 20, total: 1 } })))
  await page.route(`**/api/admin/orders/${orderId}`, async route => {
    if (orderStatus === "PENDING") return route.fulfill(envelope(order))
    await delayedOrderDetail
    orderDetailFinished = true
    await route.fulfill({ status: 500, contentType: "application/json", body: JSON.stringify({ code: 500, message: "detail unavailable" }) })
  })
  await page.route(`**/api/admin/orders/${orderId}/mark-paid`, async route => { orderStatus = "PAID"; await route.fulfill(envelope(null)) })
  await page.goto(new URL("/admin/orders", base).href)
  await page.getByRole("link", { name: "查看详情" }).first().click()
  await page.getByRole("button", { name: "标记已付", exact: true }).click()
  await page.getByLabel("请填写人工核验依据（必填）").fill("本地模拟核验")
  const reloadedOrders = page.waitForResponse(response => new URL(response.url()).pathname === "/api/admin/orders/search" && response.request().method() === "POST")
  await page.getByRole("button", { name: "确认标记", exact: true }).click()
  const updatedOrders = (await (await reloadedOrders).json()).data
  assert.equal(updatedOrders.list[0].status, "PAID")
  assert.equal(orderDetailFinished, false, "the list refresh must not wait for the detail request")
  releaseOrderDetail()
  await page.getByText("加载失败，请重试").first().waitFor()
  await page.keyboard.press("Escape")
  await page.unroute("**/api/admin/orders/search")
  await page.unroute(`**/api/admin/orders/${orderId}`)
  await page.unroute(`**/api/admin/orders/${orderId}/mark-paid`)
  console.log("Order status invalidation refreshes the list even when detail reload fails")

  await page.waitForTimeout(1300)
  await page.goto(new URL("/admin/products", base).href)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.getByText("演示商品", { exact: true }).first().waitFor()
  const mobileHeader = page.locator(".admin-page-header").first()
  const fit = await mobileHeader.evaluate(element => {
    const heading = element.querySelector("h1").getBoundingClientRect()
    const button = element.querySelector('button[aria-label="刷新"]').getBoundingClientRect()
    return heading.right <= button.left && button.right <= innerWidth && button.width >= 44 && button.height >= 44
  })
  assert.ok(fit, "mobile heading and refresh button must fit")
  await screenshot("product-list-mobile")
  console.log("Mobile refresh control layout passed")
} finally {
  // The API requires the same CSRF cookie as the browser UI.
  let cleanupFailed = false
  for (const id of createdIds) {
    const result = await page.evaluate(async productId => {
      const cookie = document.cookie.split(";").map(value => value.trim()).find(value => value.startsWith("myshop_csrf="))
      const csrf = cookie ? decodeURIComponent(cookie.slice("myshop_csrf=".length)) : ""
      const response = await fetch(`/api/admin/products/${productId}`, { method: "DELETE", credentials: "include", headers: { "X-CSRF-Token": csrf } })
      return response.ok
    }, id).catch(() => false)
    if (!result) { cleanupFailed = true; console.error(`Could not remove local test product ${id}`) }
  }
  await context.close()
  await browser.close()
  if (cleanupFailed) throw new Error("Local test product cleanup failed")
}
