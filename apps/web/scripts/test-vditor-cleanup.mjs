import assert from "node:assert/strict"
import { existsSync } from "node:fs"
import { chromium } from "playwright"

// Recover only artifacts from test-vditor-real after an interrupted run.
const base = new URL(process.env.BASE_URL || "http://127.0.0.1:3012")
if (!["127.0.0.1", "localhost", "[::1]"].includes(base.hostname) || process.env.MYSHOP_TEST_ISOLATED !== "1") {
  throw new Error("A local disposable environment and MYSHOP_TEST_ISOLATED=1 are required")
}
if (!process.env.MYSHOP_TEST_ADMIN_USER || !process.env.MYSHOP_TEST_ADMIN_PASSWORD) throw new Error("Isolated admin credentials are required")
const chrome = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
const browser = await chromium.launch({ headless:true, executablePath:existsSync(chrome) ? chrome : undefined })
const context = await browser.newContext()
const page = await context.newPage()
page.setDefaultTimeout(30000)
async function request(method, path) {
  const csrf = (await context.cookies()).find(cookie => cookie.name === "myshop_csrf")?.value
  for (let attempt = 0; attempt < 4; attempt++) {
    const response = await context.request.fetch(new URL(path, base).href, { method, headers:csrf ? { "X-CSRF-Token":csrf } : {} })
    if (response.status() === 429 && attempt < 3) {
      const seconds = Math.min(5, Math.max(1, Number(response.headers()["retry-after"]) || 1))
      console.warn(`${method} ${path}: HTTP 429; retrying after ${seconds}s`)
      await new Promise(resolve => setTimeout(resolve, seconds * 1000))
      continue
    }
    assert.ok(response.ok(), `${method} ${path}: HTTP ${response.status()}`)
    const body = await response.json()
    assert.equal(body.code, 0, `${method} ${path}: ${body.message}`)
    return body.data
  }
  throw new Error(`${method} ${path}: rate limit retry exhausted`)
}
try {
  await page.goto(new URL("/login?redirect=%2Fadmin%2Fproducts", base).href)
  await page.locator("#account").fill(process.env.MYSHOP_TEST_ADMIN_USER)
  await page.locator("#password").fill(process.env.MYSHOP_TEST_ADMIN_PASSWORD)
  const login = page.waitForResponse(response => new URL(response.url()).pathname === "/api/auth/login" && response.request().method() === "POST")
  await page.locator('form button[type="submit"]').click()
  const auth = await login
  assert.ok(auth.ok(), `Login failed: HTTP ${auth.status()}`)
  await page.waitForURL(url => url.pathname === "/admin/products")
  const products = await request("GET", `/api/admin/products?keyword=${encodeURIComponent("编辑器实测-")}&page=1&page_size=100`)
  const deletedProducts = []
  for (const item of products.list.filter(row => /^编辑器实测-\d+$/.test(row.title))) {
    await request("DELETE", `/api/admin/products/${encodeURIComponent(item.id)}`)
    deletedProducts.push(item.id)
  }
  const androidProductId = process.env.MYSHOP_TEST_ANDROID_PRODUCT_ID
  if (androidProductId) {
    const product = await request("GET", `/api/admin/products/${encodeURIComponent(androidProductId)}`)
    assert.equal(product.title, "myshop_android_ime_test", "The Android test product must match its expected title")
    await request("DELETE", `/api/admin/products/${encodeURIComponent(androidProductId)}`)
    deletedProducts.push(androidProductId)
  }
  const media = await request("GET", `/api/admin/media?query=${encodeURIComponent("myshop-editor-")}&page=1&page_size=100`)
  const deletedAssets = []
  for (const item of media.items.filter(row => /^myshop-editor-\d+\[[12]\]\.(?:jpe?g|png)$/i.test(row.name))) {
    const inspection = await request("GET", `/api/admin/media/inspection?filename=${encodeURIComponent(item.id)}`)
    assert.equal(inspection.references.length, 0, `Test asset ${item.id} still has active references`)
    await request("DELETE", `/api/admin/media?filename=${encodeURIComponent(item.id)}`)
    deletedAssets.push(item.id)
  }
  console.log(JSON.stringify({ deleted_test_products:deletedProducts, deleted_unreferenced_test_assets:deletedAssets }))
} finally { await browser.close() }
