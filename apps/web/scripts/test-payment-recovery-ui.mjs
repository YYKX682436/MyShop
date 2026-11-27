import assert from "node:assert/strict"
import { createServer } from "node:http"
import { spawn } from "node:child_process"
import { existsSync } from "node:fs"
import { chromium } from "playwright"

// All HTTP responses are local fixtures. No real payment is initiated.
const product = { id: "fixture-product", title: "模拟商品", description: "", detail_md: "", cover_url: null,
  price: 10, retail_price: 10, category_id: "cat", category_name: "测试", stock_available: 10, unlimited: true,
  has_specs: false, delivery_type: "MANUAL", stock_mode: "UNLIMITED", sales_count: 0, initial_sales: 0,
  spec_enabled: false, specs: [], created_at: "2026-01-01T00:00:00", customer_input_fields: [] }
const channels = [
  { id: "alipay", channel_code: "test-alipay", name: "模拟支付宝", type: "alipay" },
  { id: "wxpay", channel_code: "test-wxpay", name: "模拟微信", type: "wxpay" },
]
const item = { id: "item", product_id: product.id, product_no: 1, product_title: product.title,
  product_cover_url: null, spec_name: null, quantity: 1, unit_price: 10, subtotal: 10,
  delivery_type: "MANUAL", stock_mode: "UNLIMITED", fulfillment_status: "PENDING", customer_inputs: [] }
const order = { id: "fixture-order", order_no: 100001, total_amount: 10, actual_amount: 10, status: "PENDING",
  order_type: "NORMAL", payment_method: "ONLINE", payment_channel_code: "test-alipay", created_at: "2026-01-01T00:00:00",
  expires_at: "2099-01-01T00:00:00", paid_at: null, delivered_at: null, refunded_at: null, items: [item], after_sale_history: [] }
const ok = data => ({ code: 0, message: "ok", data })
const backend = createServer((req, res) => {
  const path = new URL(req.url, "http://localhost").pathname
  const data = path.startsWith("/api/products/") ? product : path === "/api/payment-channels" ? channels : path === "/api/categories" ? [] : {}
  res.writeHead(200, { "content-type": "application/json" }); res.end(JSON.stringify(ok(data)))
})
await new Promise(resolve => backend.listen(0, "127.0.0.1", resolve))
const reserve = createServer()
await new Promise(resolve => reserve.listen(0, "127.0.0.1", resolve))
const port = reserve.address().port
await new Promise(resolve => reserve.close(resolve))
const base = `http://127.0.0.1:${port}`
const web = spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "--hostname", "127.0.0.1", "--port", String(port)], {
  env: { ...process.env, BACKEND_URL: `http://127.0.0.1:${backend.address().port}`, PUBLIC_SITE_URL: base }, stdio: "ignore",
})
let browser
try {
  for (let attempt = 0; attempt < 150; attempt++) {
    try { if ((await fetch(`${base}/product/fixture-product`)).ok) break } catch { /* starting */ }
    if (attempt === 149) throw new Error("Local preview did not start")
    await new Promise(resolve => setTimeout(resolve, 100))
  }
  const chrome = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
  browser = await chromium.launch({ headless: true, executablePath: existsSync(chrome) ? chrome : undefined })
  for (const viewport of [{ width: 390, height: 844 }, { width: 1440, height: 900 }]) {
    const page = await browser.newPage({ viewport })
    page.setDefaultTimeout(10000)
    let creation = "UNKNOWN", paymentType = "alipay", orderStatus = "PENDING", createCount = 0, repayCount = 0, refreshCount = 0
    const payment = () => ({ order_id: order.id, order_no: order.order_no, payment_id: "attempt", status: orderStatus === "PAID" ? "PAID" : "PENDING",
      payment_method: "ONLINE", payment_type: paymentType, payment_channel_code: `test-${paymentType}`, creation_status: creation,
      can_retry: creation === "INTENT" || creation === "READY" || creation === "REJECTED", expires_at: order.expires_at,
      remaining_seconds: 600, next_action: creation === "READY" && orderStatus === "PENDING" ? { type: "qr_code", value: "fake-qr" } : null })
    await page.route("**/api/**", async route => {
      const url = new URL(route.request().url()), method = route.request().method()
      if (url.pathname === "/api/user/profile") {
        await route.fulfill({ status: 401, contentType: "application/json", body: JSON.stringify({ code: 401, message: "未登录" }) })
        return
      }
      let data = {}
      if (url.pathname === "/api/payment-channels") data = channels
      else if (url.pathname === "/api/orders" && method === "POST") {
        createCount++
        paymentType = route.request().postDataJSON().payment_channel_code === "test-wxpay" ? "wxpay" : "alipay"
        data = { order: { ...order, payment_channel_code: `test-${paymentType}` }, payment: payment() }
      } else if (url.pathname === "/api/orders/fixture-order/repay") {
        repayCount++; creation = "READY"; data = payment()
      } else if (url.pathname === "/api/orders/fixture-order/refresh") {
        refreshCount++; data = { order: { ...order, status: orderStatus }, payment: payment(), delivery: { groups: [] } }
      } else if (url.pathname === "/api/orders/fixture-order") {
        data = { order: { ...order, status: orderStatus }, payment: payment(), delivery: { groups: [] } }
      } else if (url.pathname === "/api/products/fixture-product") data = product
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(ok(data)) })
    })
    await page.goto(`${base}/product/fixture-product`)
    await page.getByRole("button", { name: "立即购买" }).click()
    const dialog = page.getByRole("dialog")
    if (viewport.width === 390) await dialog.getByRole("button", { name: "模拟微信" }).click()
    await dialog.locator('button[type="submit"]').click()
    await page.waitForURL("**/order/fixture-order")
    await page.getByText("支付结果尚待核实，请刷新状态或联系客服，勿重复付款。").waitFor()
    assert.ok(await page.locator("body").evaluate(element => element.scrollWidth <= innerWidth))
    assert.equal(createCount, 1)
    assert.equal(await page.getByRole("button", { name: "重新发起支付" }).count(), 0)
    assert.equal(paymentType, viewport.width === 390 ? "wxpay" : "alipay")
    await page.evaluate(() => sessionStorage.setItem("myshop:payment-action:fixture-order", JSON.stringify({ type: "qr_code", value: "stale-qr" })))
    await page.reload()
    await page.getByText("支付结果尚待核实，请刷新状态或联系客服，勿重复付款。").waitFor()
    assert.equal(await page.evaluate(() => sessionStorage.getItem("myshop:payment-action:fixture-order")), null)
    assert.equal(await page.getByLabel("支付二维码").count(), 0)
    await page.getByRole("button", { name: "重新加载支付信息" }).click()
    assert.equal(refreshCount, 1)
    creation = "SUBMITTING"
    await page.reload()
    await page.getByText("支付信息正在创建，请稍后刷新。").waitFor()
    assert.ok(await page.locator("body").evaluate(element => element.scrollWidth <= innerWidth))
    assert.equal(await page.getByRole("button", { name: "重新发起支付" }).count(), 0)
    creation = "INTENT"
    await page.reload()
    await page.getByRole("button", { name: "重新发起支付" }).click()
    await page.getByLabel("支付二维码").waitFor()
    assert.equal(repayCount, 1)
    orderStatus = "PAID"
    await page.reload()
    await page.getByText("支付结果尚待核实，请刷新状态或联系客服，勿重复付款。").waitFor({ state: "hidden" })
    assert.equal(await page.getByLabel("支付二维码").count(), 0)
    assert.ok(await page.locator("body").evaluate(element => element.scrollWidth <= innerWidth))
    await page.close()
    console.log(`Payment recovery UI ${viewport.width}px: checkout, unknown, submitting, safe resume, paid and stale-cache clearing passed`)
  }
} finally {
  if (browser) await browser.close()
  web.kill("SIGTERM")
  await new Promise(resolve => backend.close(resolve))
}
