import assert from "node:assert/strict"
import { existsSync } from "node:fs"
import { chromium } from "playwright"

// Run against an isolated test service; all card and product APIs below
// are intercepted, so the test never creates or modifies inventory.
const base = new URL(process.env.BASE_URL ?? "http://localhost:3000")
if (!["localhost", "127.0.0.1"].includes(base.hostname)) throw new Error("Card UI tests require an isolated loopback test service")
if (!process.env.MYSHOP_TEST_ADMIN || !process.env.MYSHOP_TEST_PASSWORD) throw new Error("Set MYSHOP_TEST_ADMIN and MYSHOP_TEST_PASSWORD for the isolated test service")

const productId = "ca4d0000-0000-4000-8000-000000000099"
const orderId = "ca4d0000-0000-4000-8000-000000000098"
const specs = ["规格 A", "规格 B"].map((name, index) => ({
  id: `ca4d0000-0000-4000-8000-0000000000${81 + index}`,
  name, cost_price: 5, retail_price: 10, margin: 5, margin_rate: 50,
  is_visible: true, sort_order: index, is_deleted: 0, stock_available: 0, unlimited: false, card_key_count: 0,
}))
const product = {
  id: productId, product_no: 9999, title: "卡券界面测试商品", description: null, detail_md: null, cover_url: null,
  cost_price: null, retail_price: 10, category_id: "5ce00000-0000-4000-8000-000000000001",
  category_name: "测试分类", primary_category_id: "5ce00000-0000-4000-8000-000000000001",
  primary_category_name: "测试分类", secondary_category_id: null, secondary_category_name: null,
  stock_available: 0, unlimited: false, has_specs: true, delivery_type: "AUTO", customer_input_fields: [],
  stock_mode: "CARD_KEY", card_content_column_count: null, card_content_column_counts: [], sales_count: 0,
  initial_sales: 0, spec_enabled: true, is_enabled: true, sort_weight: 0, low_stock_threshold: 10,
  specs, overrides: [], pricing_matrix: [], warnings: [], created_at: "2026-01-01T00:00:00", updated_at: "2026-01-01T00:00:00",
}
const items = [1, 2, 3].map(width => ({
  id: `item-${width}`, product_id: `product-${width}`, product_no: width, product_title: `${width}列示例`,
  quantity: 1, unit_price: 10, subtotal: 10, delivery_type: "AUTO", customer_inputs: [],
  stock_mode: "CARD_KEY", fulfillment_status: "DELIVERED", delivered_at: "2026-01-01T00:00:00",
}))
const fields = [["single"], ["first", "second"], ["one", "two", "three"]]
const orderView = {
  order: { id: orderId, order_no: 1000000010, total_amount: 30, actual_amount: 30, status: "DELIVERED",
    order_type: "NORMAL", payment_method: "BALANCE", created_at: "2026-01-01T00:00:00", items, after_sale_history: [] },
  payment: { order_id: orderId, status: "DELIVERED", paid: true, payment_method: "BALANCE", remaining_seconds: 0 },
  delivery: { groups: items.map((item, index) => ({ product_no: item.product_no, product_title: item.product_title,
    quantity: 1, delivery_type: "AUTO", fulfillment_status: "DELIVERED", delivery_items: [{ content_parts: fields[index] }] })),
    item_statuses: [] }, after_sale_history: [],
}
const response = data => ({ status: 200, contentType: "application/json", body: JSON.stringify({ code: 0, data, message: "Success" }) })
const macChrome = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
const executablePath = process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH ?? (existsSync(macChrome) ? macChrome : undefined)
const browser = await chromium.launch({ headless: true, executablePath })

try {
  const buyer = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  await buyer.addInitScript(() => {
    Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText: async value => { window.__copied = value } } })
  })
  const page = await buyer.newPage()
  await page.route(`**/api/orders/${orderId}`, route => route.fulfill(response(orderView)))
  await page.goto(new URL(`/order/${orderId}`, base).href)
  await page.getByRole("heading", { name: "发货结果" }).waitFor()
  const cards = page.locator("article li")
  assert.equal(await cards.count(), 3)
  for (let i = 0; i < fields.length; i++) {
    const card = cards.nth(i)
    assert.deepEqual(await card.locator("code").allTextContents(), fields[i])
    for (let j = 0; j < fields[i].length; j++) {
      await card.getByRole("button", { name: `复制内容 ${j + 1}` }).click()
      assert.equal(await page.evaluate(() => window.__copied), fields[i][j])
    }
    const whole = fields[i].length === 1 ? card.getByRole("button", { name: "复制内容 1" }) : card.getByRole("button", { name: "复制卡密" })
    await whole.click()
    assert.equal(await page.evaluate(() => window.__copied), fields[i].join("\n"))
  }
  await page.getByRole("button", { name: "复制全部" }).click()
  assert.equal(await page.evaluate(() => window.__copied), fields.map(parts => parts.join("\n")).join("\n\n"))
  assert.equal(await cards.nth(0).getByRole("button").count(), 1)
  await page.setViewportSize({ width: 320, height: 800 })
  const fits = await page.evaluate(() => {
    const code = document.querySelector("article code")
    const original = code.textContent
    code.textContent = "LONG-UNBROKEN-CARD-".repeat(30)
    const result = document.documentElement.scrollWidth <= innerWidth && code.getBoundingClientRect().height > 20
    code.textContent = original
    return result
  })
  assert.ok(fits, "long card fields must wrap without horizontal page overflow")
  await buyer.close()

  const admin = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  const adminPage = await admin.newPage()
  await adminPage.goto(new URL("/login", base).href)
  await adminPage.getByRole("textbox", { name: "用户名 / 邮箱 / 手机号" }).fill(process.env.MYSHOP_TEST_ADMIN)
  await adminPage.getByRole("textbox", { name: "密码" }).fill(process.env.MYSHOP_TEST_PASSWORD)
  await adminPage.getByRole("button", { name: "登录", exact: true }).click()
  await adminPage.waitForURL(url => url.pathname !== "/login")

  await adminPage.route("**/api/admin/products?*", route => route.fulfill(response({ list: [product], pagination: { page: 1, page_size: 20, total: 1 } })))
  await adminPage.route(`**/api/admin/products/${productId}`, route => route.fulfill(response(product)))
  let importCount = 0
  await adminPage.route("**/api/admin/card-keys/import", async route => {
    const body = route.request().postDataJSON()
    assert.deepEqual(body.content_columns, ["A\nB", "1\n2"])
    assert.equal(body.spec_id, specs[0].id)
    product.card_content_column_count = 2
    importCount++
    await route.fulfill(response({ total_count: 2, success_count: 2, duplicate_count: 0, fail_count: 0 }))
  })
  await adminPage.goto(new URL("/admin/products", base).href)
  const openImport = adminPage.getByRole("button", { name: `导入卡密: ${product.title}` })
  await openImport.click()
  let dialog = adminPage.getByRole("dialog", { name: `导入卡密 · ${product.title}` })
  await dialog.getByRole("button", { name: "新增一列" }).click()
  await dialog.getByLabel("选择规格").selectOption(specs[0].id)
  await dialog.getByRole("textbox", { name: "卡券内容 1 *" }).fill("A\nB")
  await dialog.getByRole("textbox", { name: "卡券内容 2 *" }).fill("1\n2")
  await dialog.getByLabel("选择规格").selectOption(specs[1].id)
  assert.equal(await dialog.getByRole("textbox", { name: "卡券内容 1 *" }).inputValue(), "A\nB")
  assert.equal(await dialog.getByRole("textbox", { name: "卡券内容 2 *" }).inputValue(), "1\n2")
  await dialog.getByLabel("选择规格").selectOption(specs[0].id)
  await dialog.getByRole("button", { name: "导入", exact: true }).click()
  await dialog.waitFor({ state: "hidden" })
  assert.equal(importCount, 1)
  await openImport.click()
  dialog = adminPage.getByRole("dialog", { name: `导入卡密 · ${product.title}` })
  await dialog.getByRole("textbox", { name: "卡券内容 2 *" }).waitFor()
  assert.equal(await dialog.locator("textarea").count(), 2)
  assert.equal(await dialog.getByRole("button", { name: "新增一列" }).count(), 0)
  assert.equal(await dialog.getByRole("button", { name: /删除卡券内容/ }).count(), 0)
  await dialog.getByLabel("选择规格").selectOption(specs[1].id)
  await dialog.getByRole("textbox", { name: "卡券内容 1 *" }).fill("preserved")
  await dialog.getByLabel("选择规格").selectOption(specs[0].id)
  assert.equal(await dialog.getByRole("textbox", { name: "卡券内容 1 *" }).inputValue(), "preserved")
  console.log("Card UI: one/two/three fields, copy, mobile wrapping, first import, reopen and spec switching passed")
  await admin.close()
} finally {
  await browser.close()
}
