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
  goods_type: 1, upstream_status: 3, buyer_quantity: 1, actual_quantity: 1, safe_price: "0.01", actual_cost: "0.01", refunded_amount: "0",
  last_checked_at: null, error_code: null, can_refresh: true, reviews: [], aftercare_operations: [],
  submit_started_at: "2026-01-01T00:00:00", result: null, start_count: null, now_count: null, end_count: null,
  can_recover: false, can_local_refund: false,
  supplier_cards: [{ id: "card-1", remote_card_id: "remote-1", card_no: "真实卡号-123", card_pwd: "真实卡密-456",
    content_parts: ["真实卡号-123", "真实卡密-456"], status: 0, cancel_request: 1, last_checked_at: null, result: null }] }
const item = { id: "item-1", product_id: "product-1", product_no: 1, product_title: "测试商品", product_cover_url: null, spec_name: null,
  quantity: 1, unit_price: 10, subtotal: 10, customer_inputs: [], delivery_type: "AUTO", stock_mode: "SUPPLIER", fulfillment_status: "DELIVERED",
  cost_price_snapshot: 0.01, retail_price_snapshot: 10, pricing_source: "RETAIL", gross_profit: 9.99, gross_margin: 99.9, supplier_purchase: supplier }
const order = { id: "order-1", order_no: 100001, total_amount: 10, actual_amount: 10, status: "AFTER_SALE", order_type: "NORMAL",
  payment_method: "ONLINE", payment_channel_code: "test", created_at: "2026-01-01T00:00:00", paid_at: "2026-01-01T00:00:00",
  items: [item], after_sale: sale, after_sale_history: [sale], customer_type: "REGISTERED", username: "customer", user_no: 1001,
  is_risk_flagged: false, cost_total: 0.01, gross_profit_total: 9.99, gross_margin_total: 99.9 }
const rechargePurchase = { ...supplier, id: "purchase-recharge", goods_type: 3, supplier_cards: [], result: "充值完成",
  start_count: "10", now_count: "10", end_count: "10" }
const rechargeItem = { ...item, id: "recharge-item", product_title: "模拟直充", supplier_purchase: rechargePurchase }
const rechargeOrder = { ...order, id: "recharge-order", order_no: 100002, status: "DELIVERED", after_sale: null, after_sale_history: [], items: [rechargeItem] }
const recoveryPurchase = { ...supplier, id: "purchase-recovery", outer_number: "outer-recovery", upstream_order_number: null,
  status: "BLOCKED", upstream_status: null, submit_started_at: null, error_code: "CONNECTION", can_recover: true,
  can_local_refund: true, supplier_cards: [], aftercare_operations: [] }
const recoveryItem = { ...item, id: "recovery-item", fulfillment_status: "PENDING", supplier_purchase: recoveryPurchase }
const recoveryOrder = { ...order, id: "recovery-order", order_no: 100003, status: "PAID", after_sale: null, after_sale_history: [], items: [recoveryItem] }
const approvalPurchase = { ...recoveryPurchase, id: "purchase-approval", can_recover: false, can_local_refund: true }
const approvalItem = { ...recoveryItem, id: "approval-item", supplier_purchase: approvalPurchase }
const approvalOrder = { ...recoveryOrder, id: "approval-order", order_no: 100004, status: "AFTER_SALE", after_sale: sale, after_sale_history: [sale], items: [approvalItem] }
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
  await page.addInitScript(() => {
    Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText: async text => { window.__copiedText = text } } })
  })
  const calls = []
  await page.route("**/api/**", async route => {
    const path = new URL(route.request().url()).pathname
    const method = route.request().method()
    let data = path === "/api/user/profile" ? { id: "admin", username: "admin", role: "ADMIN", balance: 0 }
      : path === "/api/admin/orders/order-1" ? order
      : path === "/api/admin/orders/recharge-order" ? rechargeOrder
      : path === "/api/admin/orders/recovery-order" ? recoveryOrder
      : path === "/api/admin/orders/approval-order" ? approvalOrder : {}
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
  await page.getByText("真实卡号-123", { exact: true }).waitFor()
  await page.getByText("真实卡密-456", { exact: true }).waitFor()
  assert.ok(await page.getByRole("button", { name: /复制 卡券 1/ }).count())
  await page.getByRole("button", { name: "复制内容 1" }).first().click()
  await page.waitForFunction(() => window.__copiedText === "真实卡号-123")
  await page.getByRole("button", { name: /复制 卡券 1/ }).click()
  await page.waitForFunction(() => window.__copiedText === "真实卡号-123\n真实卡密-456")
  await page.getByRole("button", { name: "查询卡券" }).click()
  await page.waitForFunction(() => document.body.innerText.includes("供应商允许申请作废"))
  await page.getByRole("button", { name: "申请作废" }).click()
  let dialog = page.getByRole("dialog", { name: "确认申请作废" })
  await dialog.getByRole("button", { name: "取消" }).click()
  assert.equal(calls.filter(call => call.path.endsWith("/void")).length, 0)
  await page.getByRole("button", { name: "申请作废" }).click()
  dialog = page.getByRole("dialog", { name: "确认申请作废" })
  await Promise.all([page.waitForResponse(response => new URL(response.url()).pathname.endsWith("/void")), dialog.getByRole("button", { name: "确认提交" }).click()])
  await page.getByText("上游卡券编号 remote-1 · 已作废 / 已退款").waitFor()
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

  await page.setViewportSize({ width: 1280, height: 900 })
  await page.goto(`${base}/admin/orders/recharge-order`)
  await page.getByText("实际充值结果与进度").waitFor()
  await page.getByText("充值完成", { exact: true }).waitFor()
  for (const label of ["开始进度","当前进度","目标进度"]) await page.getByText(label,{exact:true}).waitFor()

  await page.goto(`${base}/admin/orders/recovery-order`)
  await page.getByRole("button", { name: "重新检查并继续采购" }).waitFor()
  await page.getByRole("button", { name: "退款", exact: true }).waitFor()
  await page.getByRole("button", { name: "重新检查并继续采购" }).click()
  const recoveryDialog=page.getByRole("dialog", { name: "重新检查采购" })
  await Promise.all([page.waitForResponse(response => new URL(response.url()).pathname.endsWith("/recover")),
    recoveryDialog.getByRole("button", { name: "确认提交" }).click()])
  const recoveryCall=calls.find(call => call.path.endsWith("/recover"))
  assert.equal(recoveryCall.body.confirmed,true)
  assert.ok(recoveryCall.body.idempotency_key)

  await page.goto(`${base}/admin/orders/approval-order`)
  await page.getByRole("button", { name: "同意退款" }).waitFor()
  console.log("Supplier aftercare UI: real card contents, recharge result/progress, safe purchase recovery, local refund and after-sale approval passed")
} finally {
  if (browser) await browser.close()
  web.kill("SIGTERM")
  await new Promise(resolve => backend.close(resolve))
}
