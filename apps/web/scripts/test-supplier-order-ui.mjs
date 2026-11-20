import assert from "node:assert/strict"
import { createServer } from "node:http"
import { spawn } from "node:child_process"
import { existsSync } from "node:fs"
import { chromium } from "playwright"

// Entirely local fixtures: no payment or supplier request leaves this process.
const product = { id: "fixture-product", title: "模拟直充商品", description: "", detail_md: "", cover_url: null,
  price: 10, retail_price: 10, category_id: "cat", category_name: "测试", stock_available: 10,
  unlimited: false, has_specs: false, delivery_type: "AUTO", stock_mode: "SUPPLIER", sales_count: 0,
  initial_sales: 0, spec_enabled: false, specs: [], created_at: "2026-01-01T00:00:00", customer_input_fields: [
    { id: "account", label: "充值账号", type: "TEXT", required: true, regex: "^[a-z]{2,5}$" },
    { id: "amount", label: "充值数量", type: "NUMBER", required: true },
    { id: "zone", label: "游戏区服", type: "SELECT", required: true, options: [{ name: "一区", value: "zone-1" }] },
    { id: "note", label: "备注", type: "MULTILINE", required: false },
  ] }
const channel = { id: "channel", channel_code: "test", name: "模拟支付", type: "alipay" }
const item = { id: "fixture-item", product_id: product.id, product_no: 1, product_title: product.title,
  product_cover_url: null, spec_name: null, quantity: 1, unit_price: 10, subtotal: 10, customer_inputs: [],
  delivery_type: "AUTO", stock_mode: "SUPPLIER", fulfillment_status: "PENDING", supplier_status: "PROCESSING", supplier_result: "充值排队中" }
const order = { id: "fixture-order", order_no: 100001, total_amount: 10, actual_amount: 10, status: "PAID",
  order_type: "NORMAL", payment_method: "ONLINE", payment_channel_code: "test", created_at: "2026-01-01T00:00:00",
  expires_at: null, paid_at: "2026-01-01T00:00:00", delivered_at: null, refunded_at: null, items: [item], after_sale_history: [] }
const supplier = { id: "purchase-1", account_name: "模拟货源", outer_number: "outer-1", upstream_order_number: "upstream-1",
  status: "PROCESSING", upstream_status: 2, buyer_quantity: 1, actual_quantity: 2, safe_price: "0.02",
  actual_cost: "0.02", refunded_amount: "0", last_checked_at: null, error_code: null, can_refresh: true }
const adminOrder = { ...order, customer_type: "GUEST", username: null, user_no: null, is_risk_flagged: false,
  cost_total: 0.02, gross_profit_total: 9.98, gross_margin_total: 99.8, after_sale: null,
  items: [{ ...item, cost_price_snapshot: 0.02, retail_price_snapshot: 10, pricing_source: "RETAIL",
    gross_profit: 9.98, gross_margin: 99.8, supplier_purchase: supplier }] }
const ok = data => ({ code: 0, message: "ok", data })
const mock = createServer((req,res) => {
  const path = new URL(req.url, "http://localhost").pathname
  const data = path.startsWith("/api/products/") ? product : path === "/api/payment-channels" ? [channel] : path === "/api/categories" ? [] : {}
  res.writeHead(200, { "content-type": "application/json" }); res.end(JSON.stringify(ok(data)))
})
await new Promise(resolve => mock.listen(0, "127.0.0.1", resolve))
const backend = `http://127.0.0.1:${mock.address().port}`
const reserve = createServer()
await new Promise(resolve => reserve.listen(0, "127.0.0.1", resolve))
const port = reserve.address().port
await new Promise(resolve => reserve.close(resolve))
const web = spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "--hostname", "127.0.0.1", "--port", String(port)], {
  env: { ...process.env, BACKEND_URL: backend, PUBLIC_SITE_URL: `http://127.0.0.1:${port}` }, stdio: "ignore",
})
const base = `http://127.0.0.1:${port}`
let browser
try {
  for (let attempt=0; attempt<100; attempt++) {
    try { if ((await fetch(`${base}/product/fixture-product`)).ok) break } catch { /* server starting */ }
    if (attempt===99) throw new Error("Local preview did not start")
    await new Promise(resolve => setTimeout(resolve, 100))
  }
  const chrome = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
  browser = await chromium.launch({ headless: true, executablePath: existsSync(chrome) ? chrome : undefined })
  const page = await browser.newPage({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true })
  page.setDefaultTimeout(8000)
  let payload = null
  let admin = false, refreshed = 0
  await page.route("**/api/**", async route => {
    const url=new URL(route.request().url())
    if (url.pathname === "/api/orders" && route.request().method() === "POST") {
      payload=route.request().postDataJSON()
      return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ code: 999, message: "模拟订单已拦截", data: null }) })
    }
    if (url.pathname === "/api/admin/orders/fixture-order/supplier-purchases/purchase-1/refresh") refreshed++
    const data = url.pathname === "/api/user/profile" ? (admin ? { id: "admin", username: "admin", role: "ADMIN", balance: 0 } : null)
      : url.pathname === "/api/orders/fixture-order" ? { order, payment: { order_id: order.id, status: "PAID", paid: true, expires_at: null }, delivery: { groups: [] } }
      : url.pathname === "/api/admin/orders/fixture-order" ? adminOrder
      : url.pathname === "/api/products/fixture-product" ? product : {}
    return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(ok(data)) })
  })
  await page.goto(`${base}/product/fixture-product`)
  await page.getByRole("button", { name: "立即购买" }).click()
  const dialog=page.getByRole("dialog")
  await dialog.getByLabel("充值账号").fill("123")
  await dialog.getByLabel("充值账号").blur()
  await dialog.getByText("充值账号：格式不符合要求").waitFor()
  assert.equal(await dialog.getByLabel("充值账号").getAttribute("aria-invalid"), "true")
  await dialog.getByLabel("充值账号").fill("alice")
  await dialog.getByLabel("充值数量").fill("12")
  await dialog.getByLabel("游戏区服").selectOption("zone-1")
  await dialog.getByLabel("备注").fill("第一行\n第二行")
  await Promise.all([
    page.waitForResponse(response => new URL(response.url()).pathname === "/api/orders" && response.request().method() === "POST"),
    dialog.locator('button[type="submit"]').click(),
  ])
  assert.equal(payload.customer_inputs.find(value => value.id === "zone").value, "zone-1")
  assert.equal(payload.customer_inputs.find(value => value.id === "note").value, "第一行\n第二行")
  assert.ok(await page.locator("body").evaluate(element => element.scrollWidth <= innerWidth))
  await page.goto(`${base}/order/fixture-order`)
  await page.getByText("正在处理", { exact: true }).waitFor()
  await page.getByText("充值排队中", { exact: true }).waitFor()
  assert.ok(await page.locator("body").evaluate(element => element.scrollWidth <= innerWidth))
  admin=true
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto(`${base}/admin/orders/fixture-order`)
  await page.getByText("模拟货源", { exact: true }).waitFor()
  await page.getByText("upstream-1", { exact: true }).waitFor()
  await page.getByRole("button", { name: "查询最新状态" }).click()
  await page.waitForFunction(() => document.body.innerText.includes("模拟货源"))
  assert.equal(refreshed,1)
  console.log("Supplier order UI: mobile inputs and order progress, admin status refresh, and selected upstream value passed; requests were intercepted locally")
} finally {
  if (browser) await browser.close()
  web.kill("SIGTERM")
  await new Promise(resolve => mock.close(resolve))
}
