import assert from "node:assert/strict"
import { spawn } from "node:child_process"
import { existsSync } from "node:fs"
import { createServer } from "node:http"
import { createRequire } from "node:module"
import { fileURLToPath } from "node:url"
import { chromium } from "playwright"
import sharp from "sharp"

// Run the real built page with a temporary, isolated backend. Fixtures exist
// only in memory; no production products, orders, or image files are changed.
const webRoot = fileURLToPath(new URL("..", import.meta.url))
const require = createRequire(import.meta.url)
const nextBin = require.resolve("next/dist/bin/next")
const names = {
  square: `${"a".repeat(64)}.png`,
  portrait: `${"b".repeat(64)}.png`,
  landscape: `${"c".repeat(64)}.png`,
}
const images = {
  [names.square]: await sharp({ create: { width: 600, height: 600, channels: 3, background: "#2c65a3" } }).png().toBuffer(),
  [names.portrait]: await sharp({ create: { width: 500, height: 5000, channels: 3, background: "#2c65a3" } }).png().toBuffer(),
  [names.landscape]: await sharp({ create: { width: 1200, height: 300, channels: 3, background: "#2c65a3" } }).png().toBuffer(),
}
const config = { site_name: "测试商城", favicon_url: null, maintenance_enabled: false }
const specs = [
  { id: "spec-a", name: "月卡", price: 4, stock_available: 5, unlimited: false },
  { id: "spec-b", name: "季卡", price: 8, stock_available: 5, unlimited: false },
]
const product = (id) => {
  const noImage = id === "missing"
  const cover = id === "portrait" ? names.portrait : id === "landscape" ? names.landscape : names.square
  const soldOut = id === "soldout"
  return {
    id, title: id === "long-title" ? `超长商品标题MixedEnglish${"VeryLongUnbrokenText".repeat(4)}中文标题` : `测试商品 ${id}`,
    price: id === "long-title" ? 123456789012.34 : 4, retail_price: 4,
    cover_url: noImage ? null : `${id === "absolute" ? base : ""}/api/uploads/${cover}`,
    description: "商品详情正文。".repeat(60), detail_md: null,
    stock_available: soldOut ? 0 : 10, unlimited: false, has_specs: !soldOut,
    specs: soldOut ? [] : specs, delivery_type: id === "long-title" ? "MANUAL" : "AUTO",
    sales_count: 20, initial_sales: 2, customer_input_fields: [],
  }
}
const response = (data) => JSON.stringify({ code: 0, message: "ok", data })
const backend = createServer((req, res) => {
  const path = new URL(req.url, "http://fixture").pathname
  if (path.startsWith("/api/uploads/")) {
    const body = images[path.split("/").at(-1)]
    res.writeHead(body ? 200 : 404, { "Content-Type": "image/png", "Cache-Control": "public, max-age=3600" })
    res.end(body)
    return
  }
  let data = null
  if (path === "/api/site/config") data = config
  else if (path === "/api/categories" || path === "/api/payment-channels") data = []
  else if (path === "/api/products") data = { list: [product("square")], pagination: { page: 1, page_size: 24, total: 1 } }
  else if (path.startsWith("/api/products/")) data = product(path.split("/").at(-1))
  res.writeHead(200, { "Content-Type": "application/json" })
  res.end(response(data))
})
await new Promise(resolve => backend.listen(0, "127.0.0.1", resolve))
const backendPort = backend.address().port
const reservation = createServer()
await new Promise(resolve => reservation.listen(0, "127.0.0.1", resolve))
const webPort = reservation.address().port
await new Promise(resolve => reservation.close(resolve))
const base = `http://127.0.0.1:${webPort}`
config.main_site_url = base
const server = spawn(process.execPath, [nextBin, "start", "--hostname", "127.0.0.1", "--port", String(webPort)], {
  cwd: webRoot,
  env: { ...process.env, BACKEND_URL: `http://127.0.0.1:${backendPort}`, PUBLIC_SITE_URL: base, NEXT_TELEMETRY_DISABLED: "1" },
  stdio: ["ignore", "pipe", "pipe"],
})
let logs = ""
server.stdout.on("data", chunk => { logs += chunk })
server.stderr.on("data", chunk => { logs += chunk })
const macChrome = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
const executablePath = process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH ?? (existsSync(macChrome) ? macChrome : undefined)
let browser
try {
  for (let attempt = 0; ; attempt++) {
    if (server.exitCode !== null) throw new Error(`Built web server exited: ${logs}`)
    try { if ((await fetch(base)).ok) break } catch { /* starting */ }
    if (attempt >= 80) throw new Error(`Built web server did not start: ${logs}`)
    await new Promise(resolve => setTimeout(resolve, 100))
  }
  browser = await chromium.launch({ headless: true, executablePath })
  const context = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 1, hasTouch: true })
  await context.addInitScript(() => {
    const NativeWatcher = window.CloseWatcher
    if (NativeWatcher) window.CloseWatcher = class extends NativeWatcher {
      constructor() { super(); window.__viewerCloseWatcher = this }
    }
  })
  const page = await context.newPage()
  let closeWatcherTested = false
  if (process.env.DEBUG_PRODUCT_VIEWER) {
    page.on("response", result => { if (result.url().includes("/_next/image") || result.url().includes("/api/uploads/")) console.log("IMAGE_RESPONSE", result.status(), result.url()) })
    page.on("console", message => { if (message.type() === "error") console.log("BROWSER_ERROR", message.text()) })
    page.on("pageerror", error => console.log("PAGE_ERROR", error.message))
  }
  let failOriginal = false
  const originals = []
  page.on("request", req => {
    if (new URL(req.url()).pathname.startsWith("/api/uploads/")) originals.push(req.url())
  })
  await page.route("**/api/**", async route => {
    const url = new URL(route.request().url())
    if (url.pathname.startsWith("/api/uploads/")) {
      const data = images[url.pathname.split("/").at(-1)]
      await route.fulfill(failOriginal || !data ? { status: 404, body: "missing" } : { status: 200, contentType: "image/png", body: data })
      return
    }
    const data = url.pathname === "/api/site/config" ? config
      : url.pathname === "/api/products" ? { list: [product("square")], pagination: { page: 1, page_size: 24, total: 1 } }
      : url.pathname.endsWith("/summary") ? { unread: 0, latest: null }
      : ["/api/payment-channels", "/api/categories"].includes(url.pathname) ? [] : null
    await route.fulfill({ status: 200, contentType: "application/json", body: response(data) })
  })
  const imageButton = () => page.getByRole("button", { name: /查看商品原图/ })
  const dialog = () => page.getByRole("dialog")
  const area = () => page.locator(".product-image-viewer-scroll")
  async function navigate(id) {
    await page.goto(`${base}/product/${id}`, { waitUntil: "domcontentloaded" })
    await page.getByRole("heading", { name: new RegExp(`测试商品 ${id}|超长商品标题`) }).waitFor()
    if (id !== "missing") await imageButton().waitFor()
  }
  async function closeBackdrop() {
    const box = await area().boundingBox()
    await page.mouse.click(box.x + 3, box.y + Math.min(50, box.height / 2))
    await dialog().waitFor({ state: "hidden" })
  }
  for (const [width, expected] of [[320, 110], [390, 132.6], [430, 145], [768, 192]]) {
    await page.setViewportSize({ width, height: 844 })
    await navigate("long-title")
    const measurements = await page.evaluate(() => {
      const rect = selector => {
        const r = document.querySelector(selector).getBoundingClientRect()
        return { left: r.left, right: r.right, top: r.top, bottom: r.bottom, width: r.width, height: r.height }
      }
      return { image: rect(".product-image-stage--detail"), title: rect(".product-summary h1"), price: rect(".product-summary-main div"), meta: rect(".product-summary-meta"), breadcrumb: getComputedStyle(document.querySelector(".product-breadcrumb")).display, scrollWidth: document.documentElement.scrollWidth, viewport: innerWidth, delivery: document.querySelector(".product-summary-delivery").textContent }
    })
    assert.ok(Math.abs(measurements.image.width - expected) < 2, `${width}px preview width: ${JSON.stringify(measurements)}`)
    assert.ok(Math.abs(measurements.image.width - measurements.image.height) < 1, `${width}px preview must be square`)
    assert.ok(measurements.title.left >= measurements.image.right && measurements.title.right <= width, "title must fit beside image")
    assert.ok(measurements.price.top >= measurements.title.bottom - 1 && measurements.price.right <= width, "price must follow title without overflow")
    assert.ok(measurements.meta.left >= measurements.image.right && measurements.meta.right <= width && measurements.meta.top >= measurements.price.bottom - 1, "stock and sales must fit beside the image after the price")
    assert.equal(measurements.breadcrumb, "none", "mobile detail should not reserve a back row")
    assert.ok(measurements.scrollWidth <= measurements.viewport, "page must not overflow horizontally")
    assert.equal(measurements.delivery, "手动发货")
  }
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto(base, { waitUntil: "domcontentloaded" })
  const card = page.locator(".product-image-stage--card img").first()
  await card.waitFor()
  if (process.env.DEBUG_PRODUCT_VIEWER) {
    await page.waitForTimeout(700)
    console.log("CARD_DEBUG", await page.evaluate(() => { const img = document.querySelector(".product-image-stage--card img"); return { img: img?.outerHTML, style: img && getComputedStyle(img).objectFit, body: document.body.innerText.slice(0, 300), html: document.body.innerHTML.slice(0, 500), styles: document.styleSheets.length } }))
  }
  await page.waitForFunction(() => { const img = document.querySelector(".product-image-stage--card img"); return img && getComputedStyle(img).objectFit === "cover" }, null, { timeout: 8000 })
  const cardStyle = await page.evaluate(() => { const style = getComputedStyle(document.querySelector(".product-image-stage--card img")); return [style.objectFit, style.objectPosition] })
  await navigate("square")
  await page.waitForFunction(() => { const img = document.querySelector(".product-image-stage--detail img"); return img && getComputedStyle(img).objectFit === "cover" })
  const detailStyle = await page.evaluate(() => { const style = getComputedStyle(document.querySelector(".product-image-stage--detail img")); return [style.objectFit, style.objectPosition] })
  assert.deepEqual(detailStyle, cardStyle, "home and detail preview cropping must match")
  assert.deepEqual(detailStyle, ["cover", "50% 0%"])
  originals.length = 0
  await page.reload({ waitUntil: "domcontentloaded" })
  await imageButton().waitFor()
  assert.equal(originals.length, 0, "managed original must not load before the viewer opens")
  const previewUrl = await page.locator(".product-image-stage--detail img").evaluate(img => img.currentSrc)
  assert.ok(new URL(previewUrl).pathname === "/_next/image" && Number(new URL(previewUrl).searchParams.get("w")) <= 384,
    "mobile preview must request a small optimized image")
  assert.equal(await imageButton().locator("svg").count(), 0, "preview should not cover the image with an icon")
  const separators = await page.evaluate(() => [
    getComputedStyle(document.querySelector(".product-description-region")).borderTopWidth,
    getComputedStyle(document.querySelector(".product-description-content > div")).borderBottomWidth,
  ])
  assert.deepEqual(separators, ["0px", "0px"], "product details should not be separated by lines")
  await page.getByRole("button", { name: "立即购买" }).click()
  const purchase = page.getByRole("dialog", { name: "立即购买" })
  await purchase.getByRole("button", { name: "季卡" }).click()
  assert.ok((await purchase.innerText()).includes("¥8.00"), "specification selection must update the purchase price")
  await page.keyboard.press("Escape")
  await purchase.waitFor({ state: "hidden" })
  await page.evaluate(() => window.scrollTo(0, 35))
  const before = await page.evaluate(() => window.scrollY)
  await imageButton().click()
  await dialog().waitFor()
  await page.locator(".product-image-viewer-original").waitFor()
  await page.waitForFunction(() => document.querySelector(".product-image-viewer-original")?.naturalWidth > 0)
  assert.ok(originals.length > 0, "opening viewer must load the original")
  const original = page.locator(".product-image-viewer-original")
  await original.click()
  assert.equal(await dialog().count(), 1, "clicking the image must not close the viewer")
  const squareImage = await original.boundingBox()
  await page.touchscreen.tap(squareImage.x + squareImage.width / 2, squareImage.y + squareImage.height / 2)
  assert.equal(await dialog().count(), 1, "tapping the image must not close the viewer")
  assert.equal(await dialog().locator("svg").count(), 0, "viewer must not show an X icon")
  const squareArea = await area().boundingBox()
  await page.mouse.move(squareArea.x + 3, squareArea.y + 30)
  await page.mouse.down()
  await page.mouse.move(squareArea.x + 3, squareArea.y + 70, { steps: 4 })
  await page.mouse.up()
  assert.equal(await dialog().count(), 1, "dragging on the background must not dismiss the viewer")
  await closeBackdrop()
  await page.waitForFunction(y => window.scrollY === y, before)
  await imageButton().click()
  await dialog().waitFor()
  await dialog().getByRole("button", { name: "关闭" }).focus()
  await page.keyboard.press("Enter")
  await dialog().waitFor({ state: "hidden" })
  await imageButton().click()
  await dialog().waitFor()
  await page.keyboard.press("Escape")
  await dialog().waitFor({ state: "hidden" })
  await imageButton().click()
  await dialog().waitFor()
  if (await page.evaluate(() => Boolean(window.__viewerCloseWatcher))) {
    await page.evaluate(() => window.__viewerCloseWatcher.requestClose())
    await dialog().waitFor({ state: "hidden" })
    closeWatcherTested = true
  } else {
    await closeBackdrop()
  }
  await imageButton().click()
  await dialog().waitFor()
  await page.goBack()
  await dialog().waitFor({ state: "hidden" })
  assert.equal(new URL(page.url()).pathname, "/product/square", "browser back should close the viewer before leaving the product")
  for (let i = 0; i < 3; i++) {
    await imageButton().click()
    await dialog().waitFor()
    await closeBackdrop()
  }
  await navigate("absolute")
  originals.length = 0
  await page.reload({ waitUntil: "domcontentloaded" })
  await imageButton().waitFor()
  assert.equal(originals.length, 0, "an absolute managed upload must not load the original before opening")
  const absolutePreview = await page.locator(".product-image-stage--detail img").evaluate(img => img.currentSrc)
  assert.equal(new URL(absolutePreview).pathname, "/_next/image", "trusted absolute uploads must use the managed optimizer")
  await imageButton().click()
  await page.waitForFunction(() => document.querySelector(".product-image-viewer-original")?.naturalWidth > 0)
  assert.ok(originals.length > 0, "the original absolute URL must load when the viewer opens")
  await closeBackdrop()
  await page.setViewportSize({ width: 320, height: 568 })
  await navigate("portrait")
  await imageButton().click()
  await page.waitForFunction(() => document.querySelector(".product-image-viewer-original")?.naturalHeight === 5000)
  const dimensions = await original.evaluate(img => {
    const rect = img.getBoundingClientRect()
    return { width: rect.width, height: rect.height, naturalWidth: img.naturalWidth, naturalHeight: img.naturalHeight }
  })
  assert.ok(Math.abs(dimensions.width / dimensions.height - dimensions.naturalWidth / dimensions.naturalHeight) < 0.001, "original must not stretch or crop")
  const scrollSize = await area().evaluate(el => ({ height: el.clientHeight, full: el.scrollHeight }))
  assert.ok(scrollSize.full > scrollSize.height * 3, "long original must use the available width and scroll")
  const backgroundBefore = await page.evaluate(() => window.scrollY)
  const touchArea = await area().boundingBox()
  const cdp = await context.newCDPSession(page)
  const x = touchArea.x + touchArea.width / 2
  const y = touchArea.y + touchArea.height * 0.75
  await cdp.send("Input.dispatchTouchEvent", { type: "touchStart", touchPoints: [{ x, y }] })
  await cdp.send("Input.dispatchTouchEvent", { type: "touchMove", touchPoints: [{ x, y: y - 180 }] })
  await cdp.send("Input.dispatchTouchEvent", { type: "touchEnd", touchPoints: [] })
  await page.waitForFunction(() => document.querySelector(".product-image-viewer-scroll").scrollTop > 0)
  assert.equal(await dialog().count(), 1, "touch scrolling must not dismiss the viewer")
  await cdp.detach()
  await area().hover()
  await page.mouse.wheel(0, 10000)
  await page.waitForFunction(() => { const el = document.querySelector(".product-image-viewer-scroll"); return el.scrollTop + el.clientHeight >= el.scrollHeight - 2 })
  const bottom = await area().evaluate(el => ({ scrollTop: el.scrollTop, full: el.scrollHeight, client: el.clientHeight, imageBottom: el.querySelector("img").getBoundingClientRect().bottom, areaBottom: el.getBoundingClientRect().bottom }))
  assert.ok(bottom.imageBottom <= bottom.areaBottom, "last part of the long image must be reachable")
  assert.equal(await page.evaluate(() => window.scrollY), backgroundBefore, "background must remain still while scrolling the image")
  const bottomArea = await area().boundingBox()
  await page.touchscreen.tap(bottomArea.x + 3, bottomArea.y + 30)
  await dialog().waitFor({ state: "hidden" })
  await page.waitForFunction(y => window.scrollY === y, backgroundBefore)
  await navigate("landscape")
  await imageButton().click()
  await page.waitForFunction(() => document.querySelector(".product-image-viewer-original")?.naturalWidth === 1200)
  const landscape = await original.evaluate(img => ({ shape: img.dataset.shape, width: img.getBoundingClientRect().width, height: img.getBoundingClientRect().height }))
  assert.equal(landscape.shape, "wide")
  assert.ok(Math.abs(landscape.width / landscape.height - 4) < 0.01, "landscape original must keep its aspect ratio")
  await closeBackdrop()
  failOriginal = true
  await navigate("square")
  await imageButton().click()
  await page.getByRole("alert").getByText("图片加载失败").waitFor()
  await closeBackdrop()
  failOriginal = false
  await navigate("missing")
  assert.equal(await imageButton().count(), 0, "missing covers should keep the placeholder without a viewer")
  const missing = await page.locator(".product-image-stage--detail").boundingBox()
  assert.ok(Math.abs(missing.width - missing.height) < 1)
  await navigate("soldout")
  assert.ok((await page.locator(".product-summary-meta").innerText()).includes("已售罄"))
  await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight))
  const end = await page.evaluate(() => ({ description: document.querySelector(".product-description-content").getBoundingClientRect().bottom, dock: document.querySelector(".product-purchase-dock-viewport").getBoundingClientRect().top }))
  assert.ok(end.description <= end.dock + 1, "the last product description must scroll above the purchase dock")
  await page.setViewportSize({ width: 1280, height: 800 })
  await navigate("square")
  const desktop = await page.locator(".product-image-stage--detail").boundingBox()
  assert.ok(Math.abs(desktop.width / desktop.height - 0.8) < 0.01, "desktop preview retains the existing 4:5 layout")
  assert.equal(await page.locator(".product-breadcrumb").isVisible(), true, "desktop breadcrumb should remain available")
  await page.emulateMedia({ colorScheme: "dark" })
  await page.setViewportSize({ width: 390, height: 844 })
  await navigate("square")
  assert.equal(await page.locator("html").evaluate(el => el.classList.contains("dark")), true, "detail must follow dark theme")
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), "dark theme must not overflow")
  await page.goto(base, { waitUntil: "domcontentloaded" })
  await page.getByRole("link", { name: "测试商品 square" }).click()
  await imageButton().click()
  await dialog().waitFor()
  await closeBackdrop()
  await page.waitForFunction(() => !window.history.state?.__myshopProductImageViewer)
  await page.goBack()
  assert.equal(new URL(page.url()).pathname, "/", "closing the viewer must not leave an extra back step")
  await context.close()
  console.log(`PRODUCT_VIEWER_UI_OK widths=320,390,430,768 desktop=1280 themes=light,dark thumbnail=ok original=on-demand square=ok portrait-scroll=mouse,touch landscape=ok backdrop=mouse,touch drag=ok keyboard=ok browser-back=ok close-watcher=${closeWatcherTested ? "request-tested" : "unsupported"} restore=ok purchase=ok failure=ok no-image=ok`)
} finally {
  if (browser) await browser.close()
  server.kill("SIGTERM")
  await new Promise(resolve => { if (server.exitCode !== null) resolve(); else server.once("exit", resolve) })
  await new Promise(resolve => backend.close(resolve))
}
