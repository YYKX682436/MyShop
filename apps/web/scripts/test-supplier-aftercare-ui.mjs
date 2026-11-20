import assert from "node:assert/strict"
import { createServer } from "node:http"
import { spawn } from "node:child_process"
import { existsSync } from "node:fs"
import { chromium } from "playwright"

// Every API response is intercepted locally; no supplier or payment request is sent.
const sale = { id: "sale-1", order_id: "order-1", user_id: "user-1", status: "PENDING", source_order_status: "DELIVERED",
  reason: "测试售后申请", waiting_for: "ADMIN", submitted_at: "2026-01-01T00:00:00", last_activity_at: "2026-01-01T00:00:00",
  attachments: [{ id: "attachment-1", original_filename: "proof.png", url: "/api/after-sale-attachments/attachment-1", content_type: "image/png", file_size: 20 }], messages: [] }
const supplier = { id: "purchase-1", account_name: "模拟货源", outer_number: "outer-1", upstream_order_number: "up-1", status: "COMPLETED",
  upstream_status: 3, buyer_quantity: 1, actual_quantity: 1, safe_price: "0.01", actual_cost: "0.01", refunded_amount: "0",
  last_checked_at: null, error_code: null, can_refresh: true, reviews: [], aftercare_operations: [],
  supplier_cards: [{ id: "card-1", remote_card_id: "remote-1", status: 0, cancel_request: 1, last_checked_at: null, result: null }] }
const item = { id: "item-1", product_id: "product-1", product_no: 1, product_title: "测试商品", product_cover_url: null, spec_name: null,
  quantity: 1, unit_price: 10, subtotal: 10, customer_inputs: [], delivery_type: "AUTO", stock_mode: "SUPPLIER", fulfillment_status: "DELIVERED",
  cost_price_snapshot: 0.01, retail_price_snapshot: 10, pricing_source: "RETAIL", gross_profit: 9.99, gross_margin: 99.9, supplier_purchase: supplier }
const order = { id: "order-1", order_no: 100001, total_amount: 10, actual_amount: 10, status: "AFTER_SALE", order_type: "NORMAL",
  payment_method: "ONLINE", payment_channel_code: "test", created_at: "2026-01-01T00:00:00", paid_at: "2026-01-01T00:00:00",
  items: [item], after_sale: sale, after_sale_history: [sale], customer_type: "REGISTERED", username: "customer", user_no: 1001,
  is_risk_flagged: false, cost_total: 0.01, gross_profit_total: 9.99, gross_margin_total: 99.9 }
const ok = data => ({ code: 0, message: "ok", data })
const backend = createServer((req, res) => { res.writeHead(200, { "content-type": "application/json" }); res.end(JSON.stringify(ok({}))) })
await new Promise(resolve => backend.listen(0, "127.0.0.1", resolve))
const reserve = createServer()
await new Promise(resolve => reserve.listen(0, "127.0.0.1", resolve))
const port = reserve.address().port
await new Promise(resolve => reserve.close(resolve))
const web = spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "--hostname", "127.0.0.1", "--port", String(port)], {
  env: { ...process.env, BACKEND_URL: `http://127.0.0.1:${backend.address().port}`, PUBLIC_SITE_URL: `http://127.0.0.1:${port}` }, stdio: "ignore",
})
const base = `http://127.0.0.1:${port}`
let browser
try {
  for (let i=0; i<100; i++) {
    try { if ((await fetch(`${base}/admin/orders/order-1`)).ok) break } catch { /* starting */ }
    if (i===99) throw new Error("Local preview did not start")
    await new Promise(resolve => setTimeout(resolve, 100))
  }
  const chrome = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
  browser = await chromium.launch({ headless: true, executablePath: existsSync(chrome) ? chrome : undefined })
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 } })
  page.setDefaultTimeout(8000)
  const calls = []
  await page.route("**/api/**", async route => {
    const path = new URL(route.request().url()).pathname
    const method = route.request().method()
    let data = path === "/api/user/profile" ? { id: "admin", username: "admin", role: "ADMIN", balance: 0 }
      : path === "/api/admin/orders/order-1" ? order : {}
    if (method === "POST" && path.includes("/supplier-purchases/")) {
      const body = route.request().postDataJSON()
      calls.push({ path, body })
      if (path.endsWith("/query")) supplier.supplier_cards[0].last_checked_at = "2026-01-01T00:00:00"
      if (path.endsWith("/void")) supplier.supplier_cards[0].status = 2
      if (path.endsWith("/refunds")) supplier.aftercare_operations.push({ id: "refund-1", kind: "REFUND", status: "ACCEPTED", upstream_status: 9, created_at: "2026-01-01T00:00:00", reviews: [] })
      if (path.endsWith("/complaints")) data = { status: "ACCEPTED" }
    }
    return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(ok(data)) })
  })
  await page.goto(`${base}/admin/orders/order-1`)
  await page.getByText("模拟货源", { exact: true }).waitFor()
  await page.getByRole("button", { name: "查询卡券" }).click()
  await page.waitForFunction(() => document.body.innerText.includes("供应商允许申请作废"))
  await page.getByRole("button", { name: "申请作废" }).click()
  let dialog = page.getByRole("dialog", { name: "确认申请作废" })
  await dialog.getByRole("button", { name: "取消" }).click()
  assert.equal(calls.filter(call => call.path.endsWith("/void")).length, 0)
  await page.getByRole("button", { name: "申请作废" }).click()
  dialog = page.getByRole("dialog", { name: "确认申请作废" })
  await Promise.all([page.waitForResponse(response => new URL(response.url()).pathname.endsWith("/void")), dialog.getByRole("button", { name: "确认提交" }).click()])
  await page.getByText("卡券 remote-1 · 已作废 / 已退款").waitFor()
  await page.getByRole("button", { name: "申请上游退款" }).click()
  dialog = page.getByRole("dialog", { name: "确认申请上游退款" })
  await Promise.all([page.waitForResponse(response => new URL(response.url()).pathname.endsWith("/refunds")), dialog.getByRole("button", { name: "确认提交" }).click()])
  await page.getByText("上游退款 · 上游处理中").waitFor()
  await page.getByLabel("申诉主题").fill("测试主题")
  await page.getByLabel("申诉内容").fill("测试申诉内容")
  await page.getByLabel("proof.png").check()
  await Promise.all([page.waitForResponse(response => new URL(response.url()).pathname.endsWith("/complaints")), page.getByRole("button", { name: "提交申诉" }).click()])
  const voidCall=calls.find(call => call.path.endsWith("/void"))
  const refundCall=calls.find(call => call.path.endsWith("/refunds"))
  const complaintCall=calls.find(call => call.path.endsWith("/complaints"))
  assert.equal(voidCall.body.confirmed, true); assert.ok(voidCall.body.idempotency_key)
  assert.equal(refundCall.body.confirmed, true); assert.ok(refundCall.body.idempotency_key)
  assert.deepEqual(complaintCall.body.attachment_ids, ["attachment-1"])
  assert.equal(complaintCall.body.after_sale_id, "sale-1")
  assert.ok(await page.locator("body").evaluate(element => element.scrollWidth <= innerWidth))
  await page.setViewportSize({ width: 390, height: 844 })
  assert.ok(await page.locator("body").evaluate(element => element.scrollWidth <= innerWidth))
  console.log("Supplier aftercare UI: local card query, confirmation/cancel, void, refund and complaint payloads passed")
} finally {
  if (browser) await browser.close()
  web.kill("SIGTERM")
  await new Promise(resolve => backend.close(resolve))
}
