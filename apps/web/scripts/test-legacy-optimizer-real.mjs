import assert from "node:assert/strict"
import { existsSync } from "node:fs"
import { chromium } from "playwright"

// The caller creates one dedicated old-format asset in a disposable API/DB.
// This test deletes that fixture; it never accepts a non-loopback site.
const base = new URL(process.env.BASE_URL || "http://127.0.0.1:18781")
if (!["127.0.0.1", "localhost", "[::1]"].includes(base.hostname) || process.env.MYSHOP_TEST_ISOLATED !== "1")
  throw new Error("A local disposable environment and MYSHOP_TEST_ISOLATED=1 are required")
const account = process.env.MYSHOP_TEST_ADMIN_USER
const password = process.env.MYSHOP_TEST_ADMIN_PASSWORD
const assetId = process.env.MYSHOP_TEST_LEGACY_ASSET_ID || ""
if (!account || !password || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(assetId))
  throw new Error("Set test administrator credentials and the dedicated legacy asset UUID")
const filename = `${assetId}.png`
const expectedName = `myshop-optimizer-test-${assetId}.png`
const chrome = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
const browser = await chromium.launch({ headless: true, executablePath: existsSync(chrome) ? chrome : undefined })
const context = await browser.newContext()
const page = await context.newPage()
let mayDelete = false
let deleted = false

async function json(response) {
  assert.ok(response.ok(), `${new URL(response.url()).pathname}: HTTP ${response.status()}`)
  const body = await response.json()
  assert.equal(body.code, 0, body.message)
  return body.data
}
async function removeFixture() {
  const csrf = (await context.cookies()).find(cookie => cookie.name === "myshop_csrf")?.value
  const response = await context.request.delete(new URL(`/api/admin/media?filename=${assetId}`, base).href,
    { headers: csrf ? { "X-CSRF-Token": csrf } : {} })
  await json(response)
  deleted = true
}

try {
  await page.goto(new URL("/login?redirect=%2Fadmin%2Fmedia", base).href)
  await page.locator("#account").fill(account)
  await page.locator("#password").fill(password)
  await page.locator('form button[type="submit"]').click()
  await page.waitForURL(url => url.pathname === "/admin/media")
  const listing = await json(await context.request.get(new URL(`/api/admin/media?asset_id=${assetId}`, base).href))
  assert.equal(listing.items.length, 1)
  assert.equal(listing.items[0].name, expectedName, "Only the dedicated test fixture may be deleted")
  assert.equal(listing.items[0].url, `/api/uploads/${filename}`)
  mayDelete = true
  const source = `/image-source/${filename}`
  const optimized = new URL(`/_next/image?url=${encodeURIComponent(source)}&w=384&q=75`, base).href
  const first = await fetch(optimized, { headers: { Accept: "image/webp" } })
  const firstBytes = (await first.arrayBuffer()).byteLength
  assert.equal(first.status, 200); assert.equal(first.headers.get("x-nextjs-cache"), "MISS"); assert.ok(firstBytes > 0)
  const cached = await fetch(optimized, { headers: { Accept: "image/webp" } })
  assert.equal(cached.status, 200); assert.equal(cached.headers.get("x-nextjs-cache"), "HIT")
  assert.equal((await cached.arrayBuffer()).byteLength, firstBytes)
  await removeFixture()
  assert.equal((await fetch(new URL(`/api/uploads/${filename}`, base), { method: "HEAD" })).status, 404)
  assert.equal((await fetch(new URL(source, base))).status, 404)
  const after = await fetch(optimized, { headers: { Accept: "image/webp" } })
  assert.equal(after.status, 404); assert.equal(after.headers.get("cache-control"), "no-store")
  assert.equal((await fetch(optimized, { method: "HEAD" })).status, 404)
  console.log(JSON.stringify({ assetId, optimizedBefore: first.status, cache: cached.headers.get("x-nextjs-cache"), bytes: firstBytes, afterDeletion: after.status }))
} finally {
  if (mayDelete && !deleted) await removeFixture().catch(() => {})
  await browser.close()
}
