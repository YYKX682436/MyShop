import assert from "node:assert/strict"
import { createServer } from "node:http"
import { spawn } from "node:child_process"
import { dirname, resolve } from "node:path"
import { fileURLToPath } from "node:url"
import { existsSync, mkdirSync } from "node:fs"
import { chromium } from "playwright"

const webRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..")
const baseEnvelope = data => ({ code: 0, data, message: "Success" })
const attentionSummary = {
  orders_total: 0,
  after_sale_needs_attention: 0,
  manual_fulfillment_orders: 0,
  supplier_exception_orders: 0,
  auto_delivery_exception_orders: 0,
  payment_exception_orders: 0,
  support_total: 0,
  support_unread: 0,
  support_pending: 0,
  supplier_needs_attention: 0,
  low_stock_products: 0,
  total: 0,
}
const profile = {
  id: "isolated-admin-fixture",
  username: "隔离管理员",
  email: null,
  avatar_url: null,
  role: "ADMIN",
  user_no: 900001,
  user_level_id: null,
  user_level_name: null,
  balance: 0,
  unlimited: false,
  created_at: "2026-11-27T00:00:00+08:00",
}

function makeOrder(id, status, { kind = "automatic", afterSale = false } = {}) {
  const manual = kind === "manual"
  const item = {
    id: `item-${id}`,
    product_id: "fixture-product",
    product_no: 90001,
    product_title: "隔离回归商品",
    product_cover_url: null,
    spec_id: null,
    spec_name: null,
    quantity: 1,
    unit_price: 12,
    subtotal: 12,
    delivery_type: manual ? "MANUAL" : "AUTO",
    stock_mode: manual ? "MANUAL" : "CARD_KEY",
    fulfillment_status: status === "EXPIRED" ? "CANCELLED" : manual ? "WAITING_MANUAL" : status === "PENDING" ? "PENDING" : "DELIVERED",
    customer_inputs: [],
    customer_delivery_content: null,
    cost_price_snapshot: 0,
    retail_price_snapshot: 12,
    pricing_source: "RETAIL",
    gross_profit: 12,
    gross_margin: 100,
    manual_stock_reserved: manual && status === "PAID" ? 1 : 0,
  }
  return {
    id,
    order_no: 2026112700000097,
    total_amount: 12,
    actual_amount: 12,
    status,
    order_type: "NORMAL",
    payment_method: "ONLINE",
    payment_channel_code: "wxpay",
    created_at: "2026-11-27T10:00:00+08:00",
    expires_at: status === "EXPIRED" ? "2026-11-27T10:10:00+08:00" : "2026-11-27T10:10:00+08:00",
    paid_at: status === "PAID" ? "2026-11-27T10:05:00+08:00" : null,
    delivered_at: status === "DELIVERED" ? "2026-11-27T10:06:00+08:00" : null,
    refunded_at: null,
    items: [item],
    payment_attempts: [],
    late_payments: [],
    payment_reviews: [],
    after_sale: afterSale ? {
      id: `after-sale-${id}`,
      order_id: id,
      user_id: "fixture-customer",
      status: "PENDING",
      source_order_status: "DELIVERED",
      reason: "隔离回归申请",
      rejection_reason: null,
      waiting_for: "ADMIN",
      last_activity_at: "2026-11-27T10:20:00+08:00",
      submitted_at: "2026-11-27T10:15:00+08:00",
      handled_at: null,
      handled_by: null,
      attachments: [],
      messages: [],
    } : null,
    after_sale_history: [],
    customer_type: "REGISTERED",
    user_id: "fixture-customer",
    user_no: 900002,
    username: "隔离测试顾客",
    is_risk_flagged: false,
    registered_email: null,
    phone: null,
    user_level_id_snapshot: null,
    user_level_name_snapshot: null,
    client_ip: null,
    cost_total: 0,
    gross_profit_total: 12,
    gross_margin_total: 100,
    subsite_profit: null,
    subsite_profit_state: null,
    subsite_profit_canceled_at: null,
    subsite_profit_settled_at: null,
    subsite_profit_reversed_at: null,
  }
}

function rectsIntersect(a, b) {
  return a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height
}

async function listen(server) {
  await new Promise((resolveListen, reject) => {
    server.once("error", reject)
    server.listen(0, "127.0.0.1", resolveListen)
  })
  return server.address().port
}

async function reservePort() {
  const server = createServer()
  const port = await listen(server)
  await new Promise((resolveClose, reject) => server.close(error => error ? reject(error) : resolveClose()))
  return port
}

async function startLocalServers() {
  const backend = createServer((request, response) => {
    const path = new URL(request.url, "http://127.0.0.1").pathname
    const data = path === "/api/site/config" ? { site_name: "MyShop 隔离验收", favicon_url: null } : {}
    response.writeHead(200, { "content-type": "application/json" })
    response.end(JSON.stringify(baseEnvelope(data)))
  })
  const backendPort = await listen(backend)
  const webPort = await reservePort()
  const base = `http://127.0.0.1:${webPort}`
  const nextBin = resolve(webRoot, "node_modules/next/dist/bin/next")
  const nextMode = process.env.MYSHOP_NEXT_MODE ?? "dev"
  if (nextMode !== "dev" && nextMode !== "start") throw new Error("MYSHOP_NEXT_MODE must be dev or start")
  const web = spawn(process.execPath, [nextBin, nextMode, "--hostname", "127.0.0.1", "--port", String(webPort)], {
    cwd: webRoot,
    env: {
      ...process.env,
      BACKEND_URL: `http://127.0.0.1:${backendPort}`,
      PUBLIC_SITE_URL: base,
      NEXT_PUBLIC_SITE_URL: base,
      NEXT_TELEMETRY_DISABLED: "1",
    },
    stdio: ["ignore", "pipe", "pipe"],
  })
  let serverOutput = ""
  for (const stream of [web.stdout, web.stderr]) {
    stream?.on("data", chunk => {
      serverOutput = `${serverOutput}${chunk}`.slice(-8000)
    })
  }
  return { backend, web, base, serverOutput: () => serverOutput }
}

async function waitForWeb(base, web, serverOutput) {
  for (let attempt = 0; attempt < 240; attempt++) {
    if (web.exitCode !== null) throw new Error(`Next dev exited early (${web.exitCode}):\n${serverOutput()}`)
    try {
      const response = await fetch(`${base}/admin/orders`)
      if (response.ok) return
    } catch { /* wait for Next to bind */ }
    await new Promise(resolveDelay => setTimeout(resolveDelay, 250))
  }
  throw new Error(`Next dev did not become ready:\n${serverOutput()}`)
}

function apiMock(page, order, behavior = {}) {
  const counts = {
    orderSearch: 0,
    orderDetail: 0,
    attentionSummary: 0,
    markPaid: 0,
    refund: 0,
    fulfill: 0,
    rejectAfterSale: 0,
    approveAfterSale: 0,
  }
  let failuresRemaining = behavior.failures ?? 0
  const delayMs = behavior.delayMs ?? 0

  const routeHandler = async route => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    const method = request.method()
    let data = {}

    if (path === "/api/user/profile") {
      data = profile
    } else if (path === "/api/admin/attention-summary") {
      counts.attentionSummary += 1
      data = attentionSummary
    } else if (path === "/api/admin/orders/search" && method === "POST") {
      counts.orderSearch += 1
      data = { list: [order], pagination: { page: 1, page_size: 20, total: 1 } }
    } else if (path === `/api/admin/orders/${order.id}` && method === "GET") {
      counts.orderDetail += 1
      data = order
    } else if (path === `/api/admin/orders/${order.id}/mark-paid` && method === "POST") {
      counts.markPaid += 1
      if (failuresRemaining > 0) {
        failuresRemaining -= 1
        await route.fulfill({
          status: 503,
          contentType: "application/json",
          body: JSON.stringify({ code: 503, message: "隔离模拟：支付确认暂不可用" }),
        })
        return
      }
      if (delayMs) await new Promise(resolveDelay => setTimeout(resolveDelay, delayMs))
      order.status = "PAID"
      order.paid_at = "2026-11-27T10:11:00+08:00"
      for (const item of order.items) {
        if (item.fulfillment_status === "CANCELLED") item.fulfillment_status = "PENDING"
      }
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(baseEnvelope(null)) })
      return
    } else if (path === `/api/admin/orders/${order.id}/refund` && method === "POST") {
      counts.refund += 1
    } else if (path === `/api/admin/orders/${order.id}/fulfill` && method === "POST") {
      counts.fulfill += 1
    } else if (path === `/api/admin/orders/${order.id}/after-sale/reject` && method === "POST") {
      counts.rejectAfterSale += 1
    } else if (path === `/api/admin/orders/${order.id}/after-sale/approve` && method === "POST") {
      counts.approveAfterSale += 1
    } else if (path.startsWith("/api/admin/card-keys/by-order/")) {
      data = []
    }

    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(baseEnvelope(data)) })
  }

  return page.route("**/api/**", routeHandler).then(() => counts)
}

async function newFixturePage(browser, base, { viewport = { width: 1440, height: 900 }, status = "PENDING", kind = "automatic", afterSale = false, behavior = {} } = {}) {
  const context = await browser.newContext({ viewport })
  const page = await context.newPage()
  page.setDefaultTimeout(15_000)
  const id = `isolated-${status.toLowerCase()}-${kind}-${afterSale ? "aftersale" : "normal"}`
  const order = makeOrder(id, status, { kind, afterSale })
  const counts = await apiMock(page, order, behavior)
  return { context, page, order, counts, url: `${base}/admin/orders/${id}` }
}

async function openInterceptedWorkspace(page, url, orderId) {
  await page.goto(new URL("/admin/orders", url).href)
  await page.getByRole("link", { name: "查看详情" }).first().click()
  await page.waitForURL(new URL(`/admin/orders/${orderId}`, url).href)
  const workspace = page.getByTestId("product-workspace-sheet")
  await workspace.waitFor({ state: "visible" })
  return workspace
}

async function waitForAnimations(locator) {
  await locator.evaluate(element => Promise.all(
    element.getAnimations({ subtree: true }).map(animation => animation.finished.catch(() => undefined)),
  ))
}

async function checkHeaderActionGeometry(workspace, expectedCount) {
  await waitForAnimations(workspace)
  const closeButton = workspace.getByTestId("workspace-sheet-close")
  const closeRect = await closeButton.boundingBox()
  const sheetRect = await workspace.boundingBox()
  const actions = workspace.getByTestId("workspace-header-actions").getByRole("button")
  const actionCount = await actions.count()
  assert.ok(actionCount > 0, "the workspace must expose its current header actions")
  if (expectedCount !== undefined) assert.equal(actionCount, expectedCount, "the expected number of workspace actions must be present")
  assert.ok(closeRect && sheetRect, "the workspace close button and sheet must be visible")
  for (let index = 0; index < actionCount; index++) {
    const action = actions.nth(index)
    const actionRect = await action.boundingBox()
    assert.ok(actionRect, `workspace header action ${index + 1} must be visible`)
    assert.equal(rectsIntersect(actionRect, closeRect), false, `workspace header action ${index + 1} must not overlap the close button`)
    assert.ok(actionRect.x >= sheetRect.x - 1 && actionRect.x + actionRect.width <= sheetRect.x + sheetRect.width + 1, `workspace header action ${index + 1} must stay within the sheet`)
    const receivesPointer = await action.evaluate(element => {
      const rect = element.getBoundingClientRect()
      const target = document.elementFromPoint(rect.left + rect.width / 2, rect.top + rect.height / 2)
      return target === element || Boolean(target && element.contains(target))
    })
    assert.equal(receivesPointer, true, `workspace header action ${index + 1} must be hit-testable`)
  }
}

async function assertPaymentDialog(page) {
  const dialog = page.getByRole("alertdialog", { name: "确认支付" })
  await dialog.waitFor({ state: "visible" })
  await waitForAnimations(dialog)
  await dialog.getByText("确定该订单已收到付款吗？确认后系统将按正常流程发货。", { exact: true }).waitFor({ state: "visible" })
  assert.equal(await dialog.locator("input, textarea, select").count(), 0, "confirmation must not ask for extra information")
  const cancel = dialog.getByRole("button", { name: "取消", exact: true })
  const confirm = dialog.getByRole("button", { name: "确认支付", exact: true })
  assert.equal(await cancel.count(), 1)
  assert.equal(await confirm.count(), 1)
  const [cancelRect, confirmRect] = await Promise.all([cancel.boundingBox(), confirm.boundingBox()])
  assert.ok(cancelRect && confirmRect)
  const mobile = await page.evaluate(() => innerWidth < 640)
  assert.ok(mobile ? cancelRect.y < confirmRect.y : cancelRect.x < confirmRect.x, "cancel must precede confirmation at the active viewport")
  return dialog
}

const screenshotDir = process.env.MYSHOP_SCREENSHOT_DIR
if (screenshotDir) mkdirSync(screenshotDir, { recursive: true })

let backend
let web
let browser
let serverOutput = () => ""
const results = []

try {
  const local = await startLocalServers()
  backend = local.backend
  web = local.web
  serverOutput = local.serverOutput
  await waitForWeb(local.base, web, serverOutput)

  const chrome = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
  const executablePath = process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH ?? (existsSync(chrome) ? chrome : undefined)
  browser = await chromium.launch({ headless: true, executablePath })

  for (const viewport of [{ width: 1440, height: 900 }, { width: 390, height: 844 }]) {
    const fixture = await newFixturePage(browser, local.base, { viewport })
    const { page, context, order, counts } = fixture
    try {
      const workspace = await openInterceptedWorkspace(page, fixture.url, order.id)
      await checkHeaderActionGeometry(workspace, 1)
      const headerButton = workspace.getByRole("button", { name: "确认支付", exact: true })
      await headerButton.click()
      const firstDialog = await assertPaymentDialog(page)
      if (screenshotDir) await page.screenshot({ path: resolve(screenshotDir, `confirm-payment-${viewport.width}.png`) })
      await firstDialog.getByRole("button", { name: "取消", exact: true }).click()
      await firstDialog.waitFor({ state: "hidden" })
      await workspace.waitFor({ state: "visible" })
      assert.equal(counts.markPaid, 0, `${viewport.width}px cancel must not submit payment`)
      assert.equal(new URL(page.url()).pathname, `/admin/orders/${order.id}`, "cancel must preserve the intercepted workspace route")

      await headerButton.click()
      const secondDialog = await assertPaymentDialog(page)
      const confirmResponse = page.waitForResponse(response => new URL(response.url()).pathname === `/api/admin/orders/${order.id}/mark-paid`)
      const detailRefresh = page.waitForResponse(response => new URL(response.url()).pathname === `/api/admin/orders/${order.id}` && response.request().method() === "GET")
      const listRefresh = page.waitForResponse(response => new URL(response.url()).pathname === "/api/admin/orders/search" && response.request().method() === "POST")
      const attentionRefresh = page.waitForResponse(response => new URL(response.url()).pathname === "/api/admin/attention-summary")
      await secondDialog.getByRole("button", { name: "确认支付", exact: true }).click()
      assert.equal((await confirmResponse).status(), 200)
      await Promise.all([detailRefresh, listRefresh, attentionRefresh])
      await secondDialog.waitFor({ state: "hidden" })
      await workspace.getByText("待发货", { exact: true }).first().waitFor({ state: "visible" })
      assert.equal(counts.markPaid, 1, `${viewport.width}px confirmation must submit exactly once`)
      assert.ok(counts.orderDetail >= 2, "success must reload order detail")
      assert.ok(counts.orderSearch >= 2, "success must refresh the underlying order list")
      assert.ok(counts.attentionSummary >= 2, "success must refresh admin attention data")
      results.push({ scenario: `intercepted-${viewport.width}`, cancelRequests: 0, confirmRequests: counts.markPaid, orderDetailReads: counts.orderDetail, listReads: counts.orderSearch, attentionReads: counts.attentionSummary })
    } finally {
      await context.close()
    }
  }

  {
    const fixture = await newFixturePage(browser, local.base, { behavior: { failures: 1 } })
    const { page, context, order, counts } = fixture
    try {
      const workspace = await openInterceptedWorkspace(page, fixture.url, order.id)
      await workspace.getByRole("button", { name: "确认支付", exact: true }).click()
      let dialog = await assertPaymentDialog(page)
      await dialog.getByRole("button", { name: "确认支付", exact: true }).click()
      await dialog.getByRole("alert").getByText("隔离模拟：支付确认暂不可用", { exact: true }).waitFor({ state: "visible" })
      await workspace.waitFor({ state: "visible" })
      assert.equal(counts.markPaid, 1, "the failed first attempt must send one request")
      dialog = page.getByRole("alertdialog", { name: "确认支付" })
      await dialog.getByRole("button", { name: "确认支付", exact: true }).click()
      await dialog.waitFor({ state: "hidden" })
      await workspace.getByText("待发货", { exact: true }).first().waitFor({ state: "visible" })
      assert.equal(counts.markPaid, 2, "failure followed by retry must send exactly two requests")
      results.push({ scenario: "failure-retry", requests: counts.markPaid, displayedError: "隔离模拟：支付确认暂不可用", retrySucceeded: true })
    } finally {
      await context.close()
    }
  }

  {
    const fixture = await newFixturePage(browser, local.base, { status: "EXPIRED", behavior: { delayMs: 350 } })
    const { page, context, order, counts } = fixture
    try {
      const workspace = await openInterceptedWorkspace(page, fixture.url, order.id)
      const button = workspace.getByRole("button", { name: "确认支付", exact: true })
      await button.waitFor({ state: "visible" })
      await checkHeaderActionGeometry(workspace, 1)
      await button.click()
      const dialog = await assertPaymentDialog(page)
      await dialog.getByRole("button", { name: "确认支付", exact: true }).dblclick({ delay: 15 })
      await dialog.waitFor({ state: "hidden" })
      await workspace.getByText("待发货", { exact: true }).first().waitFor({ state: "visible" })
      assert.equal(counts.markPaid, 1, "expired-order rapid double click must submit once")
      results.push({ scenario: "expired-rapid-double-click", requests: counts.markPaid })
    } finally {
      await context.close()
    }
  }

  {
    const fixture = await newFixturePage(browser, local.base)
    const { page, context, counts } = fixture
    try {
      await page.goto(fixture.url)
      await page.getByRole("heading", { name: "订单工作台" }).waitFor({ state: "visible" })
      assert.equal(await page.getByTestId("product-workspace-sheet").count(), 0, "direct detail page must not render the intercepted sheet")
      const actionButton = page.getByRole("button", { name: "确认支付", exact: true })
      await actionButton.click()
      const dialog = await assertPaymentDialog(page)
      await dialog.getByRole("button", { name: "确认支付", exact: true }).click()
      await dialog.waitFor({ state: "hidden" })
      await page.getByText("待发货", { exact: true }).first().waitFor({ state: "visible" })
      assert.equal(counts.markPaid, 1, "direct detail page must submit once")
      results.push({ scenario: "direct-detail", requests: counts.markPaid })
    } finally {
      await context.close()
    }
  }

  for (const viewport of [{ width: 1440, height: 900 }, { width: 390, height: 844 }]) {
    const fixture = await newFixturePage(browser, local.base, { viewport, status: "PAID", kind: "manual" })
    const { page, context, order, counts } = fixture
    try {
      const workspace = await openInterceptedWorkspace(page, fixture.url, order.id)
      await checkHeaderActionGeometry(workspace, 2)
      await workspace.getByRole("button", { name: "完成发货", exact: true }).click()
      const fulfillmentDialog = page.getByRole("dialog").filter({ hasText: "确认完成发货？" })
      await fulfillmentDialog.waitFor({ state: "visible" })
      await fulfillmentDialog.getByRole("button", { name: "取消", exact: true }).click()
      await fulfillmentDialog.waitFor({ state: "hidden" })
      await workspace.getByRole("button", { name: "退款", exact: true }).click()
      const refundDialog = page.getByRole("alertdialog", { name: "确认整单退款？" })
      await refundDialog.waitFor({ state: "visible" })
      await refundDialog.getByRole("button", { name: "取消", exact: true }).click()
      await refundDialog.waitFor({ state: "hidden" })
      await workspace.waitFor({ state: "visible" })
      assert.equal(counts.fulfill, 0)
      assert.equal(counts.refund, 0)
      results.push({ scenario: `manual-fulfillment-and-refund-cancel-${viewport.width}`, headerActions: 2, fulfillmentRequests: counts.fulfill, refundRequests: counts.refund })
    } finally {
      await context.close()
    }
  }

  for (const viewport of [{ width: 1440, height: 900 }, { width: 390, height: 844 }]) {
    const fixture = await newFixturePage(browser, local.base, { viewport, status: "AFTER_SALE", kind: "manual", afterSale: true })
    const { page, context, order, counts } = fixture
    try {
      const workspace = await openInterceptedWorkspace(page, fixture.url, order.id)
      await checkHeaderActionGeometry(workspace, 2)
      await workspace.getByRole("button", { name: "拒绝申请", exact: true }).click()
      const rejectDialog = page.getByRole("alertdialog", { name: "确认拒绝该售后申请？" })
      await rejectDialog.waitFor({ state: "visible" })
      await rejectDialog.getByRole("button", { name: "取消", exact: true }).click()
      await rejectDialog.waitFor({ state: "hidden" })
      await workspace.getByRole("button", { name: "同意退款", exact: true }).click()
      const approveDialog = page.getByRole("alertdialog", { name: "确认同意售后并退款？" })
      await approveDialog.waitFor({ state: "visible" })
      await approveDialog.getByRole("button", { name: "取消", exact: true }).click()
      await approveDialog.waitFor({ state: "hidden" })
      await workspace.waitFor({ state: "visible" })
      assert.equal(counts.rejectAfterSale, 0)
      assert.equal(counts.approveAfterSale, 0)
      results.push({ scenario: `after-sale-cancel-${viewport.width}`, headerActions: 2, rejectRequests: counts.rejectAfterSale, approveRequests: counts.approveAfterSale })
    } finally {
      await context.close()
    }
  }

  console.log(JSON.stringify({ status: "PASS", browser: "Playwright Chromium", results }, null, 2))
} catch (error) {
  console.error(error)
  if (serverOutput()) console.error(serverOutput())
  process.exitCode = 1
} finally {
  if (browser) await browser.close()
  if (web && web.exitCode === null) {
    web.kill("SIGTERM")
    await new Promise(resolveClose => {
      const timeout = setTimeout(() => resolveClose(), 3000)
      web.once("exit", () => { clearTimeout(timeout); resolveClose() })
    })
  }
  if (backend?.listening) await new Promise(resolveClose => backend.close(() => resolveClose()))
}
