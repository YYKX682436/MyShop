import assert from "node:assert/strict"
import { existsSync } from "node:fs"
import { chromium } from "playwright"

// Every API response is local fixture data; no supplier or production calls.
const base = new URL(process.env.BASE_URL ?? "http://127.0.0.1:3012")
if (!["localhost", "127.0.0.1"].includes(base.hostname)) throw new Error("Local web server required")
const chrome = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
const browser = await chromium.launch({ headless: true, executablePath: existsSync(chrome) ? chrome : undefined })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
page.setDefaultTimeout(10000)
const ok = data => ({ status: 200, contentType: "application/json", body: JSON.stringify({ code: 0, message: "Success", data }) })
const account = { id: "account-1", name: "模拟供货站", provider_type: "FIXTURE", enabled: true }
const catalog = ["月卡上游", "季卡上游", "年卡上游", "单规格上游"].map((name, index) => ({
  id: String(100 + index), name, goods_type: 1, status: 1, sku_type: 0, cost: "1.00", multiple: 1,
  min_quantity: 1, max_quantity: 10, skus: [{ id: "", name: "", group: "", cost: "1.00", stock: 10, status: 1 }], snapshot: {},
}))
let products = [], mappings = [], bindRequests = [], failGoods = null
let productCounter = 0

try {
  await page.route("**/api/**", async route => {
    const url = new URL(route.request().url()), path = url.pathname, method = route.request().method()
    if (path === "/api/user/profile") return route.fulfill(ok({ id: "admin", username: "admin", role: "SUPER_ADMIN", balance: 0 }))
    if (path === "/api/site/config" || path === "/api/admin/attention-summary") return route.fulfill(ok({}))
    if (path === "/api/admin/categories") return route.fulfill(ok([{ id: "category", name: "测试分类", parent_id: null, children: [] }]))
    if (path === "/api/admin/user-levels") return route.fulfill(ok([]))
    if (path === "/api/admin/products/pricing-preview") return route.fulfill(ok({ pricing_matrix: [], warnings: [] }))
    if (path === "/api/admin/products" && method === "GET") return route.fulfill(ok({ list: products, total: products.length, page: 1, page_size: 30 }))
    if (path === "/api/admin/products" && method === "POST") {
      const body = route.request().postDataJSON(); assert.equal(body.is_enabled, false)
      const product = { id: `product-${++productCounter}`, product_no: productCounter, ...body, stock_available: 0,
        unlimited: false, has_specs: body.spec_enabled, pricing_matrix: [], warnings: [], created_at: "", updated_at: "",
        specs: body.specs.map(spec => ({ ...spec, is_visible: spec.visible, is_deleted: 0, card_key_count: 0, manual_stock: null })) }
      products.push(product); return route.fulfill(ok(product))
    }
    const productMatch = path.match(/^\/api\/admin\/products\/(product-\d+)$/)
    if (productMatch) {
      const product = products.find(row => row.id === productMatch[1])
      if (method === "PUT") {
        const body = route.request().postDataJSON(); assert.equal(body.is_enabled, false)
        Object.assign(product, body, { specs: body.specs.map(spec => ({ ...spec, is_visible: spec.visible, is_deleted: 0, card_key_count: 0, manual_stock: null })) })
      }
      return route.fulfill(ok(product))
    }
    if (path === "/api/admin/suppliers") return route.fulfill(ok([account]))
    if (path === "/api/admin/suppliers/account-1/mappings") return route.fulfill(ok(mappings))
    const productMappings = path.match(/^\/api\/admin\/suppliers\/products\/(product-\d+)\/mappings$/)
    if (productMappings) return route.fulfill(ok(mappings.filter(row => row.product_id === productMappings[1])))
    if (path === "/api/admin/suppliers/account-1/goods") {
      const keyword = url.searchParams.get("keyword") || ""
      return route.fulfill(ok({ items: catalog.filter(item => item.name.includes(keyword)), reported_count: 1, reported_pages: 1 }))
    }
    const goods = path.match(/^\/api\/admin\/suppliers\/account-1\/goods\/(\d+)(?:\/(bind))?$/)
    if (goods) {
      const item = catalog.find(row => row.id === goods[1])
      if (goods[2] === "bind") {
        const input = route.request().postDataJSON()
        bindRequests.push({ goods: item.id, spec: input.spec_id })
        if (failGoods === item.id) { failGoods = null; return route.fulfill({ status: 400, contentType: "application/json", body: JSON.stringify({ code: 10001, message: "模拟绑定失败" }) }) }
        assert.equal(input.confirmed, true)
        assert.equal(input.expected_target_mapping_id, null)
        mappings.push({ id: `mapping-${mappings.length + 1}`, account_id: account.id, remote_goods_id: item.id, remote_sku: "", product_id: input.product_id, spec_id: input.spec_id,
          remote_goods_name: item.name, remote_sku_name: "单规格", product_name: products.find(p => p.id === input.product_id).title })
        products.find(p => p.id === input.product_id).stock_mode = "SUPPLIER"
        return route.fulfill(ok({ product_id: input.product_id, created: false }))
      }
      return route.fulfill(ok(item))
    }
    return route.fulfill(ok({}))
  })

  async function start(name) {
    await page.goto(new URL("/admin/products/new", base).href)
    await page.getByTestId("product-title-input").fill(name)
    await page.getByTestId("product-category-select").selectOption("category")
    await page.getByLabel("上游供货").check()
  }
  async function choose(button, name) {
    await button.click()
    const dialog = page.getByRole("dialog", { name: "选择货源" })
    assert.ok(await page.locator("body").evaluate(el => el.scrollWidth <= window.innerWidth), "supplier picker must fit the viewport")
    await dialog.getByLabel("搜索上游商品").fill(name)
    await dialog.getByRole("button", { name: "搜索", exact: true }).click()
    await dialog.getByRole("button", { name: new RegExp(name) }).click()
    await dialog.getByRole("button", { name: "使用此货源" }).click()
  }
  async function finish() {
    await page.getByTestId("product-tab-details").click()
    await page.getByRole("button", { name: "保存并完成" }).click()
  }

  await start("单规格测试")
  await choose(page.getByRole("button", { name: "选择货源" }), "单规格上游")
  await page.getByTestId("product-tab-pricing").click()
  await page.getByTestId("product-tab-panel-pricing").locator("tbody tr").first().locator('input[type="number"]').nth(1).fill("5")
  await finish()
  await page.waitForURL(url => url.pathname === "/admin/products")
  assert.equal(mappings.filter(row => row.product_id === "product-1").length, 1)

  await page.setViewportSize({ width: 390, height: 844 })
  await start("多规格测试")
  await page.getByTestId("product-tab-specs").click()
  await page.getByRole("switch", { name: "启用多规格" }).click()
  await page.getByRole("button", { name: "添加规格" }).click()
  await page.getByRole("button", { name: "添加规格" }).click()
  const specRows = page.getByTestId("product-tab-panel-specs").locator("tbody tr")
  for (let i = 0; i < 3; i++) {
    await specRows.nth(i).getByPlaceholder("规格名称（如：1个月、6个月）").fill(["月卡", "季卡", "年卡"][i])
    await choose(specRows.nth(i).getByRole("button", { name: "选择货源" }), catalog[i].name)
  }
  await page.getByTestId("product-tab-pricing").click()
  const prices = page.getByTestId("product-tab-panel-pricing").locator("tbody tr")
  for (let i = 0; i < 3; i++) await prices.nth(i).locator('input[type="number"]').nth(1).fill(String(10 + i))
  failGoods = "101"
  await finish()
  await page.getByText(/季卡关联失败：模拟绑定失败/).waitFor()
  assert.equal(products[1].is_enabled, false)
  assert.equal(mappings.filter(row => row.product_id === "product-2").length, 1)
  await finish()
  await page.waitForURL(url => url.pathname === "/admin/products")
  assert.equal(mappings.filter(row => row.product_id === "product-2").length, 3)
  assert.equal(bindRequests.filter(row => row.goods === "100").length, 1)
  assert.ok(await page.locator("body").evaluate(el => el.scrollWidth <= window.innerWidth), "mobile page must not overflow")
  console.log("Supplier editor: single, three independent sources, partial failure and retry passed on desktop/mobile")
} finally { await browser.close() }
