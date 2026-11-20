import assert from "node:assert/strict"
import { existsSync } from "node:fs"
import { chromium } from "playwright"

// All API responses below are fixtures; this test never contacts a supplier.
const base = new URL(process.env.BASE_URL ?? "http://127.0.0.1:3012")
if (!["localhost", "127.0.0.1"].includes(base.hostname)) throw new Error("Local web server required")
const chrome = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
const browser = await chromium.launch({ headless: true, executablePath: existsSync(chrome) ? chrome : undefined })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
page.setDefaultTimeout(8000)
let accounts = []
let mappings = []
let listReads = 0
let editsPreservedSecret = false
let sensitiveOperations = { transfers: [], cdkeys: [] }
let transferPosts = 0, cdkeyPosts = 0
let presentationPosts = 0, bindingPosts = []
const ok = data => ({ status: 200, contentType: "application/json", body: JSON.stringify({ code: 0, message: "Success", data }) })
const summary = { id: "31397", name: "本地模拟测试商品", goods_type: 1, status: 1, sku_type: 0, cost: "0.01000000" }
const item = { ...summary, multiple: 1, min_quantity: 1, max_quantity: 1, skus: [{ id: "", name: "", group: "", cost: "0.01000000", stock: 12, status: 1 }], snapshot: {} }
const multiSummary = { id: "31396", name: "模拟多规格商品", goods_type: 1, status: 1, sku_type: 2, cost: "0.02000000" }
const multiItem = { ...multiSummary, multiple: 1, min_quantity: 1, max_quantity: 10, skus: [
  { id: "A", name: "上游月卡", group: "", cost: "0.02", stock: 10, status: 1 },
  { id: "B", name: "上游季卡", group: "", cost: "0.03", stock: 10, status: 1 },
], snapshot: {} }
const local = { id: "manual-product", product_no: 100, title: "我的会员商品", spec_enabled: true, specs: [
  { id: "month", name: "月卡", is_deleted: 0 }, { id: "quarter", name: "季卡", is_deleted: 0 }, { id: "year", name: "年卡", is_deleted: 0 },
] }

try {
  await page.route("**/api/**", async route => {
    const url = new URL(route.request().url())
    const path = url.pathname
    const method = route.request().method()
    if (path === "/api/user/profile") return route.fulfill(ok({ id: "local-admin", username: "admin", role: "SUPER_ADMIN", balance: 0 }))
    if (path === "/api/site/config") return route.fulfill(ok({}))
    if (path === "/api/admin/attention") return route.fulfill(ok({ after_sale_needs_attention: 0 }))
    if (path === "/api/admin/categories") return route.fulfill(ok([{ id: "local-category", name: "本地分类", parent_id: null, children: [] }]))
    if (path === "/api/admin/products" && method === "GET") return route.fulfill(ok({ list: [local], total: 1, page: 1, page_size: 30 }))
    if (path === "/api/admin/products/manual-product") return route.fulfill(ok(local))
    if (path === "/api/admin/suppliers/products/manual-product/mappings") return route.fulfill(ok(mappings.filter(row => row.product_id === local.id)))
    if (path === "/api/admin/suppliers" && method === "GET") return route.fulfill(ok(accounts))
    if (path === "/api/admin/suppliers" && method === "POST") {
      const body = route.request().postDataJSON()
      accounts = [{ id: "account-1", name: body.name, provider_type: body.provider_type, base_url: body.base_url, app_id: body.app_id, enabled: true, secret_mask: "••••••••" }]
      return route.fulfill(ok(accounts[0]))
    }
    if (path === "/api/admin/suppliers/account-1" && method === "PUT") {
      const body = route.request().postDataJSON(); editsPreservedSecret = body.secret === ""
      accounts[0] = { ...accounts[0], name: body.name }; return route.fulfill(ok(accounts[0]))
    }
    if (path === "/api/admin/suppliers/account-1/status") { assert.equal(route.request().postDataJSON().confirmed, true); accounts[0].enabled = route.request().postDataJSON().enabled; return route.fulfill(ok(null)) }
    if (path === "/api/admin/suppliers/account-1/test") return route.fulfill(ok({ balance: "10.00" }))
    if (path === "/api/admin/suppliers/account-1/categories") return route.fulfill(ok([{ id: "7", name: "上游分类", children: [] }]))
    if (path === "/api/admin/suppliers/account-1/mappings") return route.fulfill(ok(mappings))
    if (path === "/api/admin/suppliers/account-1/sensitive-capabilities") return route.fulfill(ok({ transfer_enabled: true, transfer_max_amount: "100", cdkey_enabled: true, cdkey_max_count: 20, cdkey_max_exposure: "100" }))
    if (path === "/api/admin/suppliers/account-1/transfers" || path === "/api/admin/suppliers/account-1/cdkeys") {
      const kind = path.endsWith("transfers") ? "transfers" : "cdkeys"
      if (method === "GET") return route.fulfill(ok(sensitiveOperations[kind]))
      const body = route.request().postDataJSON()
      assert.equal(body.confirmed, true); assert.ok(body.idempotency_key)
      if (kind === "transfers") { transferPosts++; assert.equal(body.to_user_no, "10001"); assert.equal(body.money, "1.25"); assert.equal(body.sale_pwd, "mock-password") }
      else { cdkeyPosts++; assert.equal(body.goods_id, "31397"); assert.equal(body.card_count, 2); assert.equal(body.count, 1); assert.equal(body.card_day, -1); assert.equal(body.safe_price, "0.01") }
      const op = { id: `${kind}-1`, kind: kind === "transfers" ? "TRANSFER" : "CDKEY", status: kind === "transfers" ? "UNKNOWN" : "SUCCEEDED", error_code: kind === "transfers" ? "CONNECTION" : null, account_name: "模拟货源", app_id: "fixture-id", created_at: "2026-11-20 14:00:00", operator_id: "admin", outer_number: "mock-outer", parameters: kind === "transfers" ? { to_user_no: body.to_user_no, money: body.money } : { goods_id: body.goods_id, card_count: body.card_count, count: body.count } }
      sensitiveOperations[kind].unshift(op); return route.fulfill(ok(op))
    }
    if (path === "/api/admin/suppliers/account-1/transfers/transfers-1/review") { sensitiveOperations.transfers[0].review_outcome = "MANUAL_HANDLING"; return route.fulfill(ok(sensitiveOperations.transfers[0])) }
    if (path === "/api/admin/suppliers/account-1/cdkeys/cdkeys-1/result") return route.fulfill(ok([{ pwd: "mock-cdkey-1", url: "" }, { pwd: "mock-cdkey-2", url: "" }]))
    if (path === "/api/admin/suppliers/account-1/goods") { listReads++; return route.fulfill(ok({ items: [summary, multiSummary], reported_count: 50, reported_pages: 1 })) }
    if (path === "/api/admin/suppliers/account-1/goods/31397" && method === "GET") return route.fulfill(ok(item))
    if (path === "/api/admin/suppliers/account-1/goods/31396" && method === "GET") return route.fulfill(ok(multiItem))
    if (path === "/api/admin/suppliers/account-1/goods/31397/import") {
      assert.equal(route.request().postDataJSON().category_id, "local-category")
      const created = mappings.length === 0
      mappings = [{ id: "mapping-1", account_id: "account-1", remote_goods_id: "31397", remote_sku: "", product_id: "local-product", goods_type: 1, sku_type: 0, cost: "0.01000000", stock: 12, goods_status: 1, sku_status: 1, multiple: 1, min_quantity: 1, max_quantity: 1, last_synced_at: "2026-11-20 14:00:00", last_attempt_at: "2026-11-20 14:00:00" }]
      return route.fulfill(ok({ product_id: "local-product", created }))
    }
    if (path === "/api/admin/suppliers/account-1/goods/31397/sync") { mappings[0].stock = 8; mappings[0].cost = "0.02000000"; return route.fulfill(ok(null)) }
    if (path === "/api/admin/suppliers/account-1/goods/31397/presentation") { assert.equal(route.request().postDataJSON().confirmed, true); presentationPosts++; return route.fulfill(ok(null)) }
    if (path.endsWith("/bind") && method === "POST") {
      const body = route.request().postDataJSON(); const goodsId = path.split("/").at(-2)
      bindingPosts.push({ ...body, goodsId })
      assert.equal(body.confirmed, true)
      if (goodsId === "31397") {
        assert.equal(body.move_confirmed, true); assert.equal(body.expected_source_mapping_id, "mapping-1")
        assert.equal(body.expected_source_product_id, "local-product"); assert.equal(body.spec_id, "month")
        mappings[0] = { ...mappings[0], product_id: local.id, spec_id: "month" }
      } else {
        assert.equal(body.move_confirmed, false); assert.equal(body.remote_sku, "B"); assert.equal(body.spec_id, "quarter")
        assert.equal(body.expected_source_mapping_id, null); assert.equal(body.expected_target_mapping_id, null)
        mappings.push({ ...mappings[0], id: "mapping-2", remote_goods_id: goodsId, remote_sku: "B", spec_id: "quarter", sku_type: 2 })
      }
      return route.fulfill(ok({ product_id: local.id, created: false }))
    }
    return route.fulfill(ok({}))
  })
  await page.goto(new URL("/admin/suppliers", base).href)
  await page.getByRole("heading", { name: "货源管理" }).waitFor()
  await page.getByRole("button", { name: "添加货源" }).click()
  const dialog = page.getByRole("dialog")
  await dialog.getByLabel("名称").fill("模拟货源")
  await dialog.getByLabel("站点地址").fill("https://fixture.example")
  await dialog.getByLabel("客户编号").fill("fixture-id")
  await dialog.getByLabel("API 密钥").fill("fixture-password")
  await dialog.getByRole("button", { name: "保存" }).click()
  await page.getByRole("button", { name: "测试连接" }).waitFor()
  assert.equal(await page.getByLabel("供货站").inputValue(), "account-1")
  await page.getByRole("button", { name: "测试连接" }).click()
  await page.getByText("余额 ¥10.00").waitFor()
  await page.getByRole("button", { name: "编辑", exact: true }).click()
  await dialog.getByLabel("名称").fill("模拟货源已编辑")
  await dialog.getByRole("button", { name: "保存" }).click()
  await page.locator("option").filter({ hasText: "模拟货源已编辑" }).waitFor({ state: "attached" })
  assert.ok(editsPreservedSecret)
  await page.getByLabel("商品名称或编号").fill("测试商品")
  await page.getByRole("button", { name: "搜索", exact: true }).click()
  await page.getByRole("button", { name: /本地模拟测试商品/ }).click()
  await page.getByRole("button", { name: "账户转账" }).click()
  await dialog.getByLabel("收款客户编号").fill("10001")
  await dialog.getByLabel(/转账金额/).fill("1.25")
  await dialog.getByLabel(/交易密码/).fill("mock-password")
  await dialog.getByRole("button", { name: "核对参数" }).click()
  await dialog.getByText(/向客户 10001 转账 ¥1.25/).waitFor()
  await dialog.getByRole("button", { name: "确认提交" }).click()
  await page.getByText("转账 · 结果未知").waitFor()
  assert.equal(transferPosts, 1)
  page.once("dialog", modal => modal.accept())
  await page.getByRole("button", { name: "记录人工核实" }).click()
  await page.getByText(/已人工核实并记录/).waitFor()
  await page.setViewportSize({ width: 390, height: 844 })
  await page.getByRole("button", { name: "生成所选商品 CDKEY" }).click()
  await dialog.getByLabel("生成张数").fill("2")
  await dialog.getByLabel("成本安全单价").fill("0.01")
  assert.ok(await page.locator("body").evaluate(el => el.scrollWidth <= window.innerWidth), "mobile dialog must not overflow")
  await dialog.getByRole("button", { name: "核对参数" }).click()
  await dialog.getByText(/成本风险上限/).waitFor()
  await dialog.getByRole("button", { name: "确认提交" }).click()
  await page.getByRole("button", { name: "查看兑换码" }).click()
  await dialog.getByLabel("CDKEY 结果").waitFor()
  assert.match(await dialog.getByLabel("CDKEY 结果").inputValue(), /mock-cdkey-2/)
  await page.keyboard.press("Escape")
  assert.equal(cdkeyPosts, 1)
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.getByLabel("本地分类").selectOption("local-category")
  await page.getByRole("button", { name: "导入商品" }).click()
  await page.getByRole("button", { name: "同步库存", exact: true }).waitFor()
  await page.getByRole("button", { name: "同步库存", exact: true }).click()
  await page.getByText("0.02000000", { exact: false }).waitFor()
  page.once("dialog", modal => { assert.match(modal.message(), /覆盖本地图片/); void modal.accept() })
  await page.getByRole("button", { name: "同步商品资料", exact: true }).click()
  await page.getByText("商品资料已同步").waitFor()
  assert.equal(presentationPosts, 1)
  await page.getByLabel("覆盖已有").check()
  await page.getByLabel("搜索本地商品").fill("会员")
  await page.getByLabel("搜索本地商品").locator("..").locator("..").getByRole("button", { name: "搜索" }).click()
  await page.getByLabel("本地商品").last().selectOption(local.id)
  await page.getByLabel("本地规格").selectOption("month")
  page.once("dialog", modal => { assert.match(modal.message(), /移动绑定/); void modal.accept() })
  await page.getByRole("button", { name: "确认绑定" }).click()
  await page.getByText("货源绑定已保存").waitFor()
  assert.equal(bindingPosts.length, 1)
  await page.getByRole("button", { name: /模拟多规格商品/ }).click()
  await page.getByLabel("本地规格").selectOption("quarter")
  await page.locator("label").filter({ hasText: "上游规格" }).locator("select").selectOption("B")
  await page.setViewportSize({ width: 390, height: 844 })
  assert.ok(await page.locator("body").evaluate(el => el.scrollWidth <= window.innerWidth), "mobile binding form must not overflow")
  page.once("dialog", modal => modal.accept())
  await Promise.all([page.waitForResponse(response => response.url().endsWith("/goods/31396/bind")), page.getByRole("button", { name: "确认绑定" }).click()])
  assert.equal(bindingPosts.length, 2)
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.getByRole("button", { name: "加载更多" }).click()
  await page.getByRole("button", { name: "加载更多" }).waitFor({ state: "hidden" })
  assert.equal(listReads, 2)
  assert.equal(await page.getByRole("button", { name: /本地模拟测试商品/ }).count(), 1)
  page.once("dialog", modal => modal.accept())
  await page.getByRole("button", { name: "停用", exact: true }).click()
  await page.getByRole("button", { name: "启用", exact: true }).waitFor()
  await page.setViewportSize({ width: 390, height: 844 })
  const scrollRegion = page.getByRole("region", { name: "货源商品同步状态" })
  await scrollRegion.focus()
  assert.ok(await scrollRegion.evaluate(element => document.activeElement === element), "table scroll region must be keyboard focusable")
  assert.ok(await page.locator("body").evaluate(el => el.scrollWidth <= window.innerWidth), "mobile page must not overflow")
  console.log("Supplier UI: import, source move, selected SKU binding, presentation sync and mobile layout passed; existing account and sensitive-operation fixtures passed")
} finally { await browser.close() }
