import assert from "node:assert/strict"
import { existsSync } from "node:fs"
import { chromium } from "playwright"

// The product list and mutations are intercepted; no catalog data is changed.
const base = new URL(process.env.BASE_URL ?? "http://localhost:3000")
if (!["localhost", "127.0.0.1"].includes(base.hostname)) throw new Error("A local preview is required")
const chrome = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
const executablePath = process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH ?? (existsSync(chrome) ? chrome : undefined)
const browser = await chromium.launch({ headless: true, executablePath })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const page = await context.newPage()
const item = (id, title, number, weight) => ({
  id, product_no: number, title, description: null, detail_md: null, cover_url: null,
  cost_price: 1, retail_price: 2, category_id: "5ce00000-0000-4000-8000-000000000001",
  category_name: "本地演示", primary_category_id: "5ce00000-0000-4000-8000-000000000001", primary_category_name: "本地演示",
  secondary_category_id: null, secondary_category_name: null, stock_available: null, unlimited: true,
  has_specs: false, delivery_type: "MANUAL", customer_input_fields: [], stock_mode: "UNLIMITED",
  card_content_column_count: null, card_content_column_counts: [], sales_count: 0, initial_sales: 0,
  spec_enabled: false, is_enabled: true, sort_weight: weight, low_stock_threshold: 10, specs: [], overrides: [],
  pricing_matrix: [], warnings: [], created_at: "2026-01-01T00:00:00", updated_at: "2026-01-01T00:00:00",
})
const first = item("ca4d0000-0000-4000-8000-000000000051", "排序回归甲", 101, 0)
const second = item("ca4d0000-0000-4000-8000-000000000052", "排序回归乙", 102, 1)
let products = [first, second]
let listRequests = 0
const envelope = data => ({ status: 200, contentType: "application/json", body: JSON.stringify({ code: 0, data, message: "Success" }) })

try {
  await page.goto(new URL("/login", base).href)
  await page.getByRole("textbox", { name: "用户名 / 邮箱 / 手机号" }).fill(process.env.MYSHOP_PREVIEW_ADMIN ?? "muchen-admin")
  await page.getByRole("textbox", { name: "密码" }).fill(process.env.MYSHOP_PREVIEW_PASSWORD ?? "LocalPreview123!")
  await page.getByRole("button", { name: "登录", exact: true }).click()
  await page.waitForURL(url => url.pathname !== "/login")
  await page.waitForTimeout(1300)
  await page.route("**/api/admin/products?*", async route => {
    listRequests++
    const sorted = [...products].sort((a, b) => b.sort_weight - a.sort_weight)
    const pageNumber = Number(new URL(route.request().url()).searchParams.get("page") || 1)
    await route.fulfill(envelope({ list: sorted.slice((pageNumber - 1) * 20, pageNumber * 20), pagination: { page: pageNumber, page_size: 20, total: sorted.length } }))
  })
  await page.route(`**/api/admin/products/${first.id}/sort-weight`, async route => {
    first.sort_weight = route.request().postDataJSON().sort_weight
    await route.fulfill(envelope(null))
  })
  await page.route(`**/api/admin/products/${first.id}`, async route => {
    if (route.request().method() === "DELETE") { products = products.filter(product => product.id !== first.id); await route.fulfill(envelope(null)) }
    else await route.fulfill(envelope(first))
  })
  await page.goto(new URL("/admin/products", base).href)
  await page.getByText("排序回归乙", { exact: true }).first().waitFor()
  const rows = page.locator("tbody tr")
  assert.match(await rows.first().textContent(), /排序回归乙/)
  await page.getByRole("button", { name: "快捷编辑权重: 排序回归甲" }).click()
  await page.getByRole("textbox", { name: "排序权重" }).fill("10")
  const beforeSortSave = listRequests
  await page.getByRole("button", { name: "保存", exact: true }).click()
  await page.waitForFunction(() => document.querySelector("tbody tr")?.textContent?.includes("排序回归甲"))
  assert.ok(listRequests > beforeSortSave)
  const beforeDelete = listRequests
  await rows.first().getByRole("button", { name: "删除" }).click()
  const confirm = page.getByRole("alertdialog", { name: "确认删除" })
  await confirm.getByRole("button", { name: "删除", exact: true }).click()
  await page.getByText("排序回归甲", { exact: true }).waitFor({ state: "hidden" })
  await page.getByText("共 1 条记录", { exact: true }).waitFor()
  assert.ok(listRequests > beforeDelete)
  assert.match(await rows.first().textContent(), /排序回归乙/)
  console.log("Weight edits re-sort the authoritative list; deletion updates rows and total")

  products = Array.from({ length: 21 }, (_, index) => item(`test-page-${index}`, `分页商品 ${index + 1}`, 200 + index, 21 - index))
  await page.getByRole("button", { name: "刷新", exact: true }).click()
  await page.getByText("共 21 条记录", { exact: true }).waitFor()
  await page.getByRole("button", { name: "下一页", exact: true }).click()
  await page.getByText("分页商品 21", { exact: true }).waitFor()
  const pageTwoRequest = page.waitForRequest(request => new URL(request.url()).pathname === "/api/admin/products" && new URL(request.url()).searchParams.get("page") === "2")
  await page.getByRole("button", { name: "刷新", exact: true }).click()
  await pageTwoRequest
  assert.equal(await page.getByRole("button", { name: "前往第 2 页" }).getAttribute("aria-current"), "page")
  const last = products[20]
  await page.route(`**/api/admin/products/${last.id}`, async route => {
    if (route.request().method() === "DELETE") { products = products.filter(product => product.id !== last.id); await route.fulfill(envelope(null)) }
    else await route.fulfill(envelope(last))
  })
  await page.locator("tr").filter({ hasText: "分页商品 21" }).getByRole("button", { name: "删除" }).click()
  await page.getByRole("alertdialog", { name: "确认删除" }).getByRole("button", { name: "删除", exact: true }).click()
  await page.getByText("共 20 条记录", { exact: true }).waitFor()
  await page.getByText("分页商品 1", { exact: true }).waitFor()
  assert.equal(await page.getByRole("button", { name: "前往第 2 页" }).count(), 0)
  console.log("Refresh retains the current page; deleting the last row selects the last valid page")

  await page.waitForTimeout(1300)
  await page.goto(new URL("/admin/risk", base).href)
  const flagged = page.getByRole("tab", { name: "高风险订单" })
  await flagged.click()
  await page.getByRole("button", { name: "刷新", exact: true }).click()
  await page.getByRole("button", { name: "刷新", exact: true }).waitFor()
  assert.equal(await flagged.getAttribute("data-state"), "active")
  console.log("Risk refresh retains the selected tab")
} finally {
  await context.close()
  await browser.close()
}
