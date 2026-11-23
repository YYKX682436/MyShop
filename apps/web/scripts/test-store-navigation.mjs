import assert from "node:assert/strict"
import { spawn } from "node:child_process"
import { createServer } from "node:http"
import { existsSync } from "node:fs"
import { createRequire } from "node:module"
import { fileURLToPath } from "node:url"
import { chromium } from "playwright"
import sharp from "sharp"

// An isolated backend: all products, messages and images are test data.
const root = fileURLToPath(new URL("..", import.meta.url))
const require = createRequire(import.meta.url)
const nextBin = require.resolve("next/dist/bin/next")
const image = await sharp(Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" width="400" height="400"><rect width="400" height="400" fill="#3188dc"/><path d="M0 100H400V200H0z" fill="#f4dd36"/><path d="M200 0V400" stroke="#f43f5e" stroke-width="28"/><text x="20" y="290" font-size="48">PRODUCT</text></svg>`)).png().toBuffer()
const products = Array.from({ length: 30 }, (_, index) => ({
  id: `fixture-${index}`, title: index === 10 ? "原始中文商品标题包含很多文字用于验证两行省略和真实背景截图".repeat(5) : `测试商品 ${index}`, price: index + 1,
  cover_url: `/api/uploads/nav-fixture-${index}.png`, stock_available: 10, unlimited: false, has_specs: false,
  retail_price: index + 1, description: "测试商品", detail_md: null, specs: [], delivery_type: "AUTO",
  sales_count: 0, initial_sales: 0, customer_input_fields: [],
}))
const config = { site_name: "导航测试", maintenance_enabled: false, popup_enabled: false }
const response = data => JSON.stringify({ code: 0, message: "ok", data })
let announcementUnread = 0
let supportUnread = 0
const backend = createServer((req, res) => {
  const url = new URL(req.url, "http://fixture")
  if (url.pathname.startsWith("/api/uploads/") || url.pathname === "/cors-image.png") {
    res.writeHead(200, { "content-type": "image/png", "cache-control": "no-store" }); res.end(image); return
  }
  let data = null
  if (url.pathname === "/api/site/config") data = config
  else if (url.pathname === "/api/categories") data = [{ id: "cat", name: "测试分类", parent_id: null, children: [] }]
  else if (url.pathname === "/api/products") {
    const page = Number(url.searchParams.get("page") || 1)
    const selected = url.searchParams.has("category_id") ? products.slice(0, 4) : products
    data = { list: selected.slice((page - 1) * 24, page * 24), pagination: { page, page_size: 24, total: selected.length } }
  } else if (url.pathname.startsWith("/api/products/")) data = products.find(product => product.id === url.pathname.split("/").at(-1)) ?? null
  else if (url.pathname === "/api/announcements/summary") data = { unread: announcementUnread, latest: null }
  else if (url.pathname === "/api/support/summary") data = { unread: supportUnread, latest: null }
  else if (url.pathname === "/api/payment-channels") data = []
  res.writeHead(200, { "content-type": "application/json", "cache-control": "no-store" }); res.end(response(data))
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
  cwd: root, env: { ...process.env, BACKEND_URL: `http://127.0.0.1:${backendPort}`, PUBLIC_SITE_URL: base, NEXT_TELEMETRY_DISABLED: "1" },
  stdio: ["ignore", "pipe", "pipe"],
})
let logs = ""
server.stdout.on("data", chunk => { logs += chunk })
server.stderr.on("data", chunk => { logs += chunk })
let browser
const center = box => ({ x: box.x + box.width / 2, y: box.y + box.height / 2 })
try {
  for (let attempt = 0; ; attempt++) {
    if (server.exitCode !== null) throw new Error(`Preview exited: ${logs}`)
    try { if ((await fetch(base)).ok) break } catch { /* starting */ }
    if (attempt > 120) throw new Error(`Preview did not start: ${logs}`)
    await new Promise(resolve => setTimeout(resolve, 100))
  }
  const macChrome = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
  const executablePath = process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH ?? (existsSync(macChrome) ? macChrome : undefined)
  browser = await chromium.launch({ headless: true, executablePath, args: ["--enable-webgl", "--ignore-gpu-blocklist", "--enable-unsafe-swiftshader"] })
  const context = await browser.newContext({ viewport: { width: 390, height: 600 }, deviceScaleFactor: 1, hasTouch: true })
  await context.addInitScript(() => {
    window.__navResources = { textures: 0, framebuffers: 0, draws: 0 }
    const data = Object.getOwnPropertyDescriptor(CharacterData.prototype, "data")
    Object.defineProperty(CharacterData.prototype, "data", {
      ...data,
      set(value) {
        data.set.call(this, value)
        if (this.parentElement === window.__clampTarget && String(value).endsWith("…")) {
          window.__clampWrites = (window.__clampWrites || 0) + 1
          if (window.__businessTitleToWrite) {
            const next = window.__businessTitleToWrite
            window.__businessTitleToWrite = null
            queueMicrotask(() => {
              this.data = next
              window.__navTextureLog = []
              window.__recordNavTextures = true
              window.__businessTitleInjected = true
            })
          }
        }
        if (this.parentElement === window.__ellipsisTarget && String(value).endsWith("…")) window.__ellipsisWrites = (window.__ellipsisWrites || 0) + 1
      },
    })
    for (const [create, remove, field] of [["createTexture", "deleteTexture", "textures"], ["createFramebuffer", "deleteFramebuffer", "framebuffers"]]) {
      const nativeCreate = WebGLRenderingContext.prototype[create]
      const nativeDelete = WebGLRenderingContext.prototype[remove]
      const live = new WeakSet()
      WebGLRenderingContext.prototype[create] = function (...args) {
        const value = nativeCreate.apply(this, args)
        if (value && this.canvas.classList.contains("store-liquid-canvas")) { live.add(value); window.__navResources[field]++ }
        return value
      }
      WebGLRenderingContext.prototype[remove] = function (value) {
        if (value && live.has(value)) { live.delete(value); window.__navResources[field]-- }
        return nativeDelete.call(this, value)
      }
    }
    const nativeDraw = WebGLRenderingContext.prototype.drawArrays
    WebGLRenderingContext.prototype.drawArrays = function (...args) {
      if (this.canvas.classList.contains("store-liquid-canvas")) window.__navResources.draws++
      const result = nativeDraw.apply(this, args)
      if (window.__checkNavBoundary && this.canvas.classList.contains("store-liquid-canvas") && !this.getParameter(this.FRAMEBUFFER_BINDING)) {
        const width = this.drawingBufferWidth, height = this.drawingBufferHeight
        const pixels = new Uint8Array(width * height * 4)
        this.readPixels(0, 0, width, height, this.RGBA, this.UNSIGNED_BYTE, pixels)
        for (let x = 0; x < width; x++) {
          window.__navBoundaryMax = Math.max(window.__navBoundaryMax, pixels[x * 4 + 3], pixels[((height - 1) * width + x) * 4 + 3])
        }
        for (let y = 0; y < height; y++) {
          window.__navBoundaryMax = Math.max(window.__navBoundaryMax, pixels[y * width * 4 + 3], pixels[(y * width + width - 1) * 4 + 3])
        }
      }
      return result
    }
    for (const method of ["texImage2D", "texSubImage2D"]) {
      const nativeUpload = WebGLRenderingContext.prototype[method]
      WebGLRenderingContext.prototype[method] = function (...args) {
        const source = args.at(-1)
        if (this.canvas.classList.contains("store-liquid-canvas") && source instanceof HTMLCanvasElement &&
            source.width === this.canvas.width && source.height === this.canvas.height) {
          window.__navBackdrop = source.toDataURL()
          if (window.__recordNavTextures) window.__navTextureLog.push(window.__navBackdrop)
        }
        return nativeUpload.apply(this, args)
      }
    }
    const nativeFetch = window.fetch
    window.fetch = async (...args) => {
      const url = String(args[0])
      if (window.__holdImageFetch && url.includes("nav-slow")) {
        window.__imageFetchBlocked = true
        await new Promise(resolve => { window.__releaseImageFetch = resolve })
      }
      return nativeFetch(...args)
    }
  })
  const page = await context.newPage()
  // Next's production rewrites are fixed at build time. Route browser API
  // requests to this test's isolated backend; SSR already uses BACKEND_URL.
  await page.route("**/api/**", async route => {
    const url = new URL(route.request().url())
    const result = await route.fetch({ url: `http://127.0.0.1:${backendPort}${url.pathname}${url.search}` })
    await route.fulfill({ response: result })
  })
  await page.route("**/api/announcements/summary", route => route.fulfill({ status: 200, contentType: "application/json", body: response({ unread: announcementUnread, latest: null }) }))
  await page.route("**/api/support/summary", route => route.fulfill({ status: 200, contentType: "application/json", body: response({ unread: supportUnread, latest: null }) }))
  page.on("pageerror", error => console.error("pageerror:", error.message))
  const gotoHome = async () => {
    await page.goto(base, { waitUntil: "domcontentloaded" })
    await page.locator('.store-liquid-nav[data-mode="webgl"]').waitFor({ timeout: 20000 })
  }
  const boxes = async () => Promise.all((await page.locator(".store-liquid-link").all()).map(link => link.boundingBox()))
  const clickTab = async index => { await page.locator(".store-liquid-link").nth(index).click() }
  const checkMutedContent = async () => {
    const color = await page.locator('.segmented-control-item[data-active="false"]').first().evaluate(element => getComputedStyle(element).color)
    assert.equal(await page.locator(".store-liquid-nav").evaluate(element => getComputedStyle(element).color), color)
    const target = color.match(/\d+/g).slice(0, 3).map(Number)
    const bounds = await page.locator(".store-liquid-link").nth(1).boundingBox()
    const pixels = await sharp(await page.screenshot()).extract({ left: Math.round(bounds.x), top: Math.round(bounds.y), width: Math.round(bounds.width), height: Math.round(bounds.height) }).removeAlpha().raw().toBuffer()
    let matches = 0
    for (let i = 0; i < pixels.length; i += 3) {
      if (target.every((value, channel) => Math.abs(pixels[i + channel] - value) <= 5)) matches++
    }
    assert.ok(matches > 5, `unselected WebGL content should use the category color ${color}; found ${matches} matching pixels`)
  }
  await gotoHome()
  await checkMutedContent()

  // Exercise SnapDOM's real live-text clamp and single-line ellipsis work in
  // the capture window, then leave the page idle long enough to catch loops.
  const longTitle = page.locator(".product-card-title").nth(10)
  await longTitle.waitFor()
  await page.evaluate(() => {
    const title = document.querySelectorAll(".product-card-title")[10]
    const single = document.createElement("span")
    single.className = "truncate"
    single.style.cssText = "display:block;max-width:100%;font-size:14px"
    single.textContent = "这一行很长的中文内容必须触发单行省略并参与背景捕获".repeat(5)
    title.after(single)
    window.__clampTarget = title
    window.__ellipsisTarget = single
    window.scrollTo(0, window.scrollY + title.getBoundingClientRect().top - (innerHeight - 77))
  })
  await page.waitForFunction(() => {
    const title = window.__clampTarget, single = window.__ellipsisTarget
    const nav = document.querySelector(".store-liquid-nav")
    return title?.scrollHeight > title?.clientHeight && single?.scrollWidth > single?.clientWidth &&
      window.__clampWrites > 0 && window.__ellipsisWrites > 0 && nav?.dataset.mode === "webgl" && nav.dataset.backdropPending === "false"
  }, null, { timeout: 20000 })
  await page.waitForTimeout(1000)
  const idleCount = Number(await page.locator(".store-liquid-nav").getAttribute("data-capture-count"))
  await page.waitForTimeout(5200)
  assert.equal(await page.locator(".store-liquid-nav").getAttribute("data-mode"), "webgl", "long clamped text must remain in WebGL mode")
  assert.equal(Number(await page.locator(".store-liquid-nav").getAttribute("data-capture-count")), idleCount, "clamped text must not trigger endless captures")
  const oldBackdrop = await page.evaluate(() => window.__navBackdrop)
  const changedTitle = "更新后的真实商品内容显示新的中文名称与另一组文字".repeat(5)
  await page.evaluate(next => {
    window.__businessTitleToWrite = next
    window.__clampTarget.classList.toggle("nav-test-trigger")
  }, changedTitle)
  await page.waitForFunction(() => window.__businessTitleInjected, null, { timeout: 15000 })
  await page.waitForFunction(() => window.__navTextureLog?.length > 0, null, { timeout: 15000 })
  await page.waitForFunction(() => {
    const nav = document.querySelector(".store-liquid-nav")
    return nav?.dataset.mode === "webgl" && nav.dataset.backdropPending === "false"
  }, null, { timeout: 15000 })
  assert.equal(await longTitle.textContent(), changedTitle, "SnapDOM must not restore the old business title")
  const firstNewBackdrop = await page.evaluate(() => {
    window.__recordNavTextures = false
    return window.__navTextureLog[0]
  })
  const titleBox = await longTitle.boundingBox()
  const drawingBox = await page.locator(".store-liquid-canvas").boundingBox()
  const region = { left: Math.ceil(titleBox.x), top: Math.ceil(titleBox.y),
    width: Math.floor(titleBox.width) - 1, height: Math.floor(titleBox.height) - 1 }
  assert.ok(region.top >= 0 && region.top + region.height <= 600 && region.left >= 0 && region.left + region.width <= 390)
  await page.evaluate(() => { document.querySelector(".store-liquid-surface").style.display = "none" })
  const pagePixels = await sharp(await page.screenshot()).extract(region).removeAlpha().raw().toBuffer()
  await page.evaluate(() => { document.querySelector(".store-liquid-surface").style.display = "" })
  const textureRegion = { ...region, left: Math.round(region.left - drawingBox.x), top: Math.round(region.top - drawingBox.y) }
  const readTexture = data => sharp(Buffer.from(data.split(",")[1], "base64")).extract(textureRegion).removeAlpha().raw().toBuffer()
  const previousPixels = await readTexture(oldBackdrop)
  const newPixels = await readTexture(firstNewBackdrop)
  const meanError = pixels => pixels.reduce((sum, value, i) => sum + Math.abs(value - pagePixels[i]), 0) / pixels.length
  const oldError = meanError(previousPixels), newError = meanError(newPixels)
  assert.ok(oldError > 1 && newError + 0.5 < oldError, `first accepted frame should contain the new title (old error ${oldError}, new error ${newError})`)
  await page.evaluate(() => { window.__ellipsisTarget.remove(); window.scrollTo(0, 0) })
  await page.locator('.store-liquid-nav[data-mode="webgl"]').waitFor({ timeout: 15000 })
  await page.waitForTimeout(500)

  await page.evaluate(() => {
    window.__originalCanvas = document.querySelector(".store-liquid-canvas")
    window.__navReplacements = []
    window.__navModes = []
    const nav = document.querySelector(".store-liquid-nav")
    window.__navModeObserver = new MutationObserver(records => {
      for (const record of records) window.__navModes.push(record.oldValue, nav.dataset.mode)
    })
    window.__navModeObserver.observe(nav, { attributes: true, attributeFilter: ["data-mode"], attributeOldValue: true })
    const replace = history.replaceState.bind(history)
    history.replaceState = (state, unused, url) => { window.__navReplacements.push(String(url)); return replace(state, unused, url) }
  })
  const originalHistoryLength = await page.evaluate(() => history.length)
  await clickTab(0)
  await page.waitForTimeout(250)
  assert.equal(new URL(page.url()).pathname, "/")
  assert.equal(await page.evaluate(() => history.length), originalHistoryLength)
  assert.equal(await page.evaluate(() => window.__navReplacements.length), 0, "current tab must not navigate again")

  let box = await boxes()
  let start = center(box[0]); let dest = center(box[2])
  await page.mouse.move(start.x, start.y); await page.mouse.down(); await page.waitForTimeout(300)
  assert.equal(new URL(page.url()).pathname, "/", "hold must not navigate")
  await page.mouse.move(dest.x, dest.y, { steps: 12 })
  assert.equal(new URL(page.url()).pathname, "/", "drag must not navigate before release")
  await page.mouse.up(); await page.waitForURL("**/messages")
  assert.equal(await page.evaluate(() => window.__navReplacements.filter(url => new URL(url, location.href).pathname === "/messages").length), 1, "one gesture must cause one navigation")
  assert.equal(await page.locator('.store-liquid-link[aria-current="page"]').getAttribute("href"), "/messages")
  assert.equal(await page.evaluate(() => window.__originalCanvas === document.querySelector(".store-liquid-canvas")), true)
  assert.equal(await page.evaluate(() => history.length), originalHistoryLength)
  await clickTab(1); await page.waitForURL("**/my/orders")
  await clickTab(3); await page.waitForURL("**/my")
  await clickTab(0); await page.waitForURL(base + "/")
  await page.locator('.store-liquid-nav[data-mode="webgl"]').waitFor({ timeout: 15000 })
  await page.waitForTimeout(300)
  assert.equal(await page.evaluate(() => {
    window.__navModeObserver.disconnect()
    return window.__navModes.includes("fallback")
  }), false, "healthy route changes must not flash the fallback navigation")

  // A hold released vertically outside its original tab is a cancellation,
  // even though pointer capture delivers pointerup back to the navigation.
  box = await boxes(); start = center(box[1])
  await page.mouse.move(start.x, start.y); await page.mouse.down()
  await page.mouse.move(start.x, box[1].y - 30, { steps: 4 }); await page.mouse.up()
  await page.waitForTimeout(300)
  assert.equal(new URL(page.url()).pathname, "/", "a non-drag release outside the initial tab must not navigate")
  assert.equal(await page.locator('.store-liquid-link[aria-current="page"]').getAttribute("href"), "/")

  // A blur can leave a suppressed pointer id without a corresponding click.
  // The same mouse pointer must still be allowed to perform native modified clicks.
  await page.evaluate(() => {
    window.__cancelClicks = 0
    document.querySelector(".store-liquid-nav").addEventListener("click", () => window.__cancelClicks++, { capture: true })
  })
  box = await boxes(); start = center(box[1])
  await page.mouse.move(start.x, start.y); await page.mouse.down()
  await page.evaluate(() => window.dispatchEvent(new Event("blur")))
  await page.mouse.move(start.x, box[1].y - 30); await page.mouse.up()
  assert.equal(await page.evaluate(() => window.__cancelClicks), 0, "cancel setup must not consume its suppression with a click")
  const ctrlAllowed = await page.evaluate(() => {
    const nav = document.querySelector(".store-liquid-nav"), link = nav.querySelectorAll("a")[1]
    let reached = false
    nav.addEventListener("click", event => { reached = !event.defaultPrevented; event.preventDefault() }, { once: true })
    link.dispatchEvent(new PointerEvent("click", { bubbles: true, cancelable: true, pointerId: 1, button: 0, detail: 1, ctrlKey: true }))
    return reached
  })
  assert.equal(ctrlAllowed, true, "Ctrl-click must not be suppressed after cancellation")
  // Chromium may open a modified-link tab with noopener, so observe the
  // browser context instead of requiring a page "popup" event.
  const popupPromise = context.waitForEvent("page", { timeout: 7000 })
  await page.locator(".store-liquid-link").nth(1).click({ modifiers: ["Meta"] })
  const popup = await popupPromise
  await popup.waitForURL("**/my/orders")
  assert.equal(new URL(page.url()).pathname, "/", "modified click should leave the original page in place")
  await popup.close()

  const cdp = await context.newCDPSession(page)
  box = await boxes(); start = center(box[0]); dest = center(box[2])
  await cdp.send("Input.dispatchTouchEvent", { type: "touchStart", touchPoints: [{ ...start, id: 1 }] })
  await cdp.send("Input.dispatchTouchEvent", { type: "touchMove", touchPoints: [{ ...dest, id: 1 }] })
  await cdp.send("Input.dispatchTouchEvent", { type: "touchCancel", touchPoints: [] })
  await page.waitForTimeout(500)
  assert.equal(new URL(page.url()).pathname, "/", "cancel must not commit")
  await cdp.send("Input.dispatchTouchEvent", { type: "touchStart", touchPoints: [{ ...start, id: 1 }] })
  await cdp.send("Input.dispatchTouchEvent", { type: "touchStart", touchPoints: [{ ...start, id: 1 }, { ...dest, id: 2 }] })
  await cdp.send("Input.dispatchTouchEvent", { type: "touchEnd", touchPoints: [{ ...start, id: 1 }] })
  await cdp.send("Input.dispatchTouchEvent", { type: "touchCancel", touchPoints: [] })
  await page.waitForTimeout(500)
  assert.equal(new URL(page.url()).pathname, "/", "second touch must not navigate")
  box = await boxes(); dest = center(box[2])
  await page.mouse.move(dest.x, dest.y); await page.mouse.down()
  await page.evaluate(() => window.dispatchEvent(new Event("blur")))
  await page.mouse.up(); await page.waitForTimeout(400)
  assert.equal(new URL(page.url()).pathname, "/", "focus loss must cancel the gesture and its click")
  await page.locator(".store-liquid-link").nth(3).focus(); await page.keyboard.press("Enter"); await page.waitForURL("**/my")
  await clickTab(0); await page.waitForURL(base + "/")

  // Three synchronous activations model a newer intent overtaking older routes.
  await page.evaluate(() => {
    const links = document.querySelectorAll(".store-liquid-link")
    links[1].click(); links[2].click(); links[3].click()
  })
  await page.waitForURL("**/my")
  await page.waitForTimeout(800)
  assert.equal(new URL(page.url()).pathname, "/my")
  assert.equal(await page.locator('.store-liquid-link[aria-current="page"]').getAttribute("href"), "/my")
  await clickTab(0); await page.waitForURL(base + "/")

  // Pointer capture remains active after leaving the hit area.
  box = await boxes(); start = center(box[0])
  await page.mouse.move(start.x, start.y); await page.mouse.down()
  await page.mouse.move(389, start.y, { steps: 12 })
  assert.equal(new URL(page.url()).pathname, "/")
  await page.mouse.up(); await page.waitForURL("**/my")
  await clickTab(0); await page.waitForURL(base + "/")

  for (const width of [320, 390, 430, 1200]) {
    await page.setViewportSize({ width, height: 600 })
    await page.waitForTimeout(300)
    const dimensions = await page.evaluate(() => {
      const nav = document.querySelector(".store-liquid-nav").getBoundingClientRect()
      return { scrollWidth: document.documentElement.scrollWidth, width: innerWidth, left: nav.left, right: nav.right, navWidth: nav.width }
    })
    assert.ok(dimensions.scrollWidth <= dimensions.width, `${width}px horizontal overflow: ${JSON.stringify(dimensions)}`)
    assert.ok(dimensions.left >= 0 && dimensions.right <= width, `${width}px navigation overflow`)
    if (width <= 430) assert.ok(Math.abs(dimensions.navWidth / width - 0.8) < 0.01, `${width}px navigation proportion: ${dimensions.navWidth}`)
    await page.locator('.store-liquid-nav[data-mode="webgl"]').waitFor({ timeout: 15000 })
    box = await boxes(); start = center(box[0]); dest = center(box[3])
    await page.evaluate(() => { window.__navBoundaryMax = 0; window.__checkNavBoundary = true })
    await page.mouse.move(start.x, start.y); await page.mouse.down(); await page.waitForTimeout(300)
    await page.mouse.move(dest.x, dest.y, { steps: 8 }); await page.waitForTimeout(300)
    await page.mouse.move(start.x, start.y, { steps: 8 }); await page.mouse.up(); await page.waitForTimeout(300)
    const boundaryMax = await page.evaluate(() => { window.__checkNavBoundary = false; return window.__navBoundaryMax })
    assert.equal(boundaryMax, 0, `${width}px canvas clipped visible glass or shadow`)
  }
  await page.setViewportSize({ width: 390, height: 600 })
  await page.waitForTimeout(700)
  announcementUnread = 2; supportUnread = 3
  await page.evaluate(() => window.dispatchEvent(new Event("myshop:messages-changed")))
  await page.locator(".store-liquid-badge").filter({ hasText: "5" }).waitFor({ timeout: 10000 })
  announcementUnread = 99; supportUnread = 3
  await page.evaluate(() => window.dispatchEvent(new Event("myshop:messages-changed")))
  await page.locator(".store-liquid-badge").filter({ hasText: "99+" }).waitFor({ timeout: 10000 })
  announcementUnread = 0; supportUnread = 0
  await page.evaluate(() => window.dispatchEvent(new Event("myshop:messages-changed")))
  await page.locator(".store-liquid-badge").waitFor({ state: "hidden", timeout: 10000 })

  // Hold a new image fetch inside a capture, navigate, then release it. An old
  // capture must never displace the new route or its selected link.
  await page.locator(".product-card img").first().waitFor({ timeout: 10000 })
  const productCount = await page.locator(".product-card img").count()
  assert.ok(productCount > 0, "the home fixture must show product images")
  await page.evaluate(() => {
    const images = document.querySelectorAll(".product-card img")
    const image = images[Math.min(6, images.length - 1)]
    window.scrollTo(0, Math.max(0, image.getBoundingClientRect().top + scrollY - (innerHeight - 120)))
  })
  await page.waitForTimeout(500)
  await page.evaluate(() => {
    window.__holdImageFetch = true
    const surface = document.querySelector(".store-liquid-canvas").getBoundingClientRect()
    const image = [...document.querySelectorAll(".product-card img")].find(item => {
      const rect = item.getBoundingClientRect()
      return rect.top < surface.bottom && rect.bottom > surface.top
    })
    if (!image) throw new Error("No product image beneath the drawing surface")
    image.removeAttribute("srcset")
    image.src = `/api/uploads/nav-slow-${Date.now()}.png`
  })
  await page.waitForFunction(() => window.__imageFetchBlocked, null, { timeout: 15000 })
  await clickTab(1); await page.waitForURL("**/my/orders")
  assert.equal(await page.locator(".store-liquid-nav").getAttribute("data-mode"), "webgl", "a delayed old capture must not blank the previous frame")
  assert.equal(await page.locator(".store-liquid-nav").getAttribute("data-backdrop-pending"), "true")
  await page.waitForTimeout(850)
  assert.equal(await page.locator(".store-liquid-nav").getAttribute("data-mode"), "fallback", "an indefinitely delayed capture must not display a stale frame forever")
  await page.evaluate(() => { window.__holdImageFetch = false; window.__releaseImageFetch?.() })
  await page.locator('.store-liquid-nav[data-mode="webgl"]').waitFor({ timeout: 15000 })
  await page.waitForTimeout(300)
  assert.equal(new URL(page.url()).pathname, "/my/orders")
  assert.equal(await page.locator('.store-liquid-link[aria-current="page"]').getAttribute("href"), "/my/orders")
  const surface = await page.locator(".store-liquid-canvas").boundingBox()
  const backdrop = Buffer.from((await page.evaluate(() => window.__navBackdrop)).split(",")[1], "base64")
  await page.evaluate(() => { document.querySelector(".store-liquid-surface").style.display = "none" })
  const screenshot = await page.screenshot()
  await page.evaluate(() => { document.querySelector(".store-liquid-surface").style.display = "" })
  const viewport = page.viewportSize()
  const left = Math.max(0, Math.round(surface.x)), top = Math.max(0, Math.round(surface.y))
  const visibleWidth = Math.min(viewport.width, Math.round(surface.x + surface.width)) - left
  const visibleHeight = Math.min(viewport.height, Math.round(surface.y + surface.height)) - top
  const expected = await sharp(screenshot).extract({ left, top, width: visibleWidth, height: visibleHeight }).removeAlpha().raw().toBuffer()
  const actual = await sharp(backdrop).extract({ left: Math.round(left - surface.x), top: Math.round(top - surface.y), width: visibleWidth, height: visibleHeight }).removeAlpha().raw().toBuffer()
  assert.equal(actual.length, expected.length)
  const meanDifference = actual.reduce((sum, value, index) => sum + Math.abs(value - expected[index]), 0) / actual.length
  assert.ok(meanDifference < 18, `late capture does not match the current page (mean RGB error ${meanDifference})`)
  await clickTab(0); await page.waitForURL(base + "/")
  await page.locator('.store-liquid-nav[data-mode="webgl"]').waitFor({ timeout: 15000 })

  const capturesBeforeFilter = Number(await page.locator(".store-liquid-nav").getAttribute("data-capture-count"))
  await page.getByRole("button", { name: "测试分类" }).click()
  await page.getByText("测试商品 3", { exact: true }).waitFor()
  await page.waitForFunction(previous => Number(document.querySelector(".store-liquid-nav")?.dataset.captureCount) > previous, capturesBeforeFilter, { timeout: 15000 })
  await page.getByRole("button", { name: "测试分类" }).click()
  await page.getByText("测试商品 23", { exact: true }).waitFor()
  await page.getByRole("navigation", { name: "分页导航" }).getByRole("button", { name: "2", exact: true }).click()
  await page.getByText("测试商品 24", { exact: true }).waitFor()
  await page.getByRole("navigation", { name: "分页导航" }).getByRole("button", { name: "1", exact: true }).click()
  await page.getByText("测试商品 0", { exact: true }).waitFor()

  // Repeated scrolling should recycle GPU textures/framebuffers and go idle.
  for (let cycle = 0; cycle < 5; cycle++) {
    await page.evaluate(() => scrollTo(0, 400)); await page.waitForTimeout(200)
    await page.evaluate(() => scrollTo(0, 0)); await page.waitForTimeout(200)
  }
  await page.waitForTimeout(1500)
  const before = await page.evaluate(() => ({ ...window.__navResources }))
  for (let cycle = 0; cycle < 5; cycle++) {
    await page.evaluate(() => scrollTo(0, 400)); await page.waitForTimeout(200)
    await page.evaluate(() => scrollTo(0, 0)); await page.waitForTimeout(200)
  }
  await page.waitForTimeout(1500)
  const after = await page.evaluate(() => ({ ...window.__navResources }))
  assert.ok(after.textures - before.textures <= 4, `texture growth: ${JSON.stringify({ before, after })}`)
  assert.ok(after.framebuffers - before.framebuffers <= 4, `framebuffer growth: ${JSON.stringify({ before, after })}`)
  await page.waitForTimeout(800)
  assert.equal(await page.evaluate(() => window.__navResources.draws), after.draws, "renderer must stop drawing when idle")

  await page.emulateMedia({ reducedMotion: "reduce" })
  box = await boxes(); start = center(box[0])
  await page.mouse.move(start.x, start.y); await page.mouse.down(); await page.waitForTimeout(500)
  const reducedDraws = await page.evaluate(() => window.__navResources.draws)
  await page.waitForTimeout(500)
  assert.equal(await page.evaluate(() => window.__navResources.draws), reducedDraws, "reduced-motion hold must settle immediately")
  await page.mouse.up(); await page.emulateMedia({ reducedMotion: "no-preference" })

  await clickTab(3); await page.waitForURL("**/my")
  await page.getByRole("link", { name: "设置" }).click(); await page.waitForURL("**/my/settings")
  assert.equal(await page.locator(".store-liquid-nav").count(), 0, "secondary pages must not show the root navigation")
  await page.getByRole("button", { name: "深色" }).click()
  await page.getByRole("link", { name: /返回/ }).first().click(); await page.waitForURL("**/my")
  assert.equal(await page.evaluate(() => document.documentElement.classList.contains("dark")), true)
  await page.locator('.store-liquid-nav[data-mode="webgl"]').waitFor({ timeout: 15000 })
  await clickTab(0); await page.waitForURL(base + "/")
  await page.locator('.store-liquid-nav[data-mode="webgl"]').waitFor({ timeout: 15000 })
  await checkMutedContent()

  await page.locator(".product-card").first().click(); await page.waitForURL("**/product/fixture-0")
  assert.equal(await page.locator(".store-liquid-nav").count(), 0)
  await page.goBack(); await page.waitForURL(base + "/")
  await page.locator('.store-liquid-nav[data-mode="webgl"]').waitFor({ timeout: 15000 })
  await page.goForward(); await page.waitForURL("**/product/fixture-0")
  await page.goBack(); await page.waitForURL(base + "/")

  await page.route("**/api/site/config", route => route.fulfill({ status: 200, contentType: "application/json", body: response({ ...config, popup_enabled: true, popup_id: "fixture", popup_version: 1, popup_content: "测试公告" }) }))
  await page.reload({ waitUntil: "domcontentloaded" })
  const dialog = page.getByRole("dialog")
  await dialog.waitFor()
  const modalAboveNavigation = await page.evaluate(() => {
    const nav = document.querySelector(".store-liquid-nav").getBoundingClientRect()
    return !!document.elementFromPoint(nav.left + nav.width / 2, nav.top + nav.height / 2)?.closest(".myshop-overlay")
  })
  assert.equal(modalAboveNavigation, true, "modal overlay must cover the navigation")
  await dialog.getByRole("button", { name: "Close" }).click()
  await page.unroute("**/api/site/config")

  // The browser's real context-loss extension exercises teardown and recovery.
  await page.locator('.store-liquid-nav[data-mode="webgl"]').waitFor({ timeout: 15000 })
  const canLoseContext = await page.evaluate(() => {
    const gl = document.querySelector(".store-liquid-canvas").getContext("webgl")
    const extension = gl?.getExtension("WEBGL_lose_context")
    if (!extension) return false
    extension.loseContext(); setTimeout(() => extension.restoreContext(), 600)
    return true
  })
  assert.equal(canLoseContext, true)
  await page.locator('.store-liquid-nav[data-mode="fallback"]').waitFor({ timeout: 5000 })
  await page.locator('.store-liquid-nav[data-mode="webgl"]').waitFor({ timeout: 15000 })
  await page.waitForTimeout(1200)
  const beforeHidden = await page.evaluate(() => ({ draws: window.__navResources.draws, captures: Number(document.querySelector(".store-liquid-nav").dataset.captureCount) }))
  await page.evaluate(() => {
    Object.defineProperty(document, "hidden", { configurable: true, value: true })
    document.dispatchEvent(new Event("visibilitychange"))
    window.scrollTo(0, 200)
  })
  await page.waitForTimeout(700)
  const whileHidden = await page.evaluate(() => ({ draws: window.__navResources.draws, captures: Number(document.querySelector(".store-liquid-nav").dataset.captureCount) }))
  assert.deepEqual(whileHidden, beforeHidden, "hidden page must not capture or draw")
  await page.evaluate(() => { delete document.hidden; document.dispatchEvent(new Event("visibilitychange")) })
  await page.locator('.store-liquid-nav[data-mode="webgl"]').waitFor({ timeout: 15000 })

  // A normally displayed cross-origin image without CORS cannot be read into
  // the capture: it must produce a reported fallback, not a claimed frame.
  await page.locator(".product-card img").nth(6).waitFor()
  await page.evaluate(() => {
    const image = document.querySelectorAll(".product-card img")[6]
    window.scrollTo(0, Math.max(0, image.getBoundingClientRect().top + scrollY - (innerHeight - 120)))
  })
  await page.waitForTimeout(500)
  await page.evaluate(url => {
    const surface = document.querySelector(".store-liquid-canvas").getBoundingClientRect()
    const image = [...document.querySelectorAll(".product-card img")].find(item => {
      const rect = item.getBoundingClientRect()
      return rect.top < surface.bottom && rect.bottom > surface.top
    })
    if (!image) throw new Error("No fixture image beneath the navigation")
    image.removeAttribute("srcset"); image.src = url
  }, `http://127.0.0.1:${backendPort}/cors-image.png`)
  await page.waitForFunction(() => [...document.images].some(image => image.currentSrc.includes("cors-image") && image.complete && image.naturalWidth > 0))
  await page.waitForFunction(() => {
    const nav = document.querySelector(".store-liquid-nav")
    return nav?.dataset.mode === "fallback" && nav.dataset.captureWarnings?.includes("image-fallback")
  }, null, { timeout: 15000 })
  await context.close()

  // The centered surface at this real desktop size begins halfway between
  // SnapDOM's 1.5-DPR pixels. It must not remain in fallback or recapture
  // forever, including on short pages whose surface extends past the document.
  const desktop = await browser.newContext({ viewport: { width: 1674, height: 1098 }, deviceScaleFactor: 2 })
  const desktopPage = await desktop.newPage()
  await desktopPage.route("**/api/**", async route => {
    const url = new URL(route.request().url())
    const result = await route.fetch({ url: `http://127.0.0.1:${backendPort}${url.pathname}${url.search}` })
    await route.fulfill({ response: result })
  })
  const desktopCaptures = {}
  for (const path of ["/", "/my/orders", "/messages", "/my"]) {
    await desktopPage.goto(base + path, { waitUntil: "domcontentloaded" })
    await desktopPage.locator('.store-liquid-nav[data-mode="webgl"]').waitFor({ timeout: 20000 })
    await desktopPage.waitForTimeout(1200)
    const nav = desktopPage.locator(".store-liquid-nav")
    const count = Number(await nav.getAttribute("data-capture-count"))
    const gap = await nav.evaluate(element => innerHeight - element.getBoundingClientRect().bottom)
    assert.ok(Math.abs(gap - 12) < 1, `${path}: unexpected desktop bottom gap ${gap}`)
    await desktopPage.waitForTimeout(1000)
    assert.equal(await nav.getAttribute("data-mode"), "webgl", `${path}: desktop must keep WebGL mode`)
    assert.equal(Number(await nav.getAttribute("data-capture-count")), count, `${path}: idle capture must stop`)
    assert.equal(await desktopPage.locator('.store-liquid-link[aria-current="page"]').getAttribute("href"), path)
    desktopCaptures[path] = count
  }
  await desktopPage.evaluate(() => document.addEventListener("contextmenu", event => {
    setTimeout(() => { window.__rightClickPrevented = event.defaultPrevented }, 0)
  }, { once: true }))
  await desktopPage.locator(".store-liquid-link").nth(1).click({ button: "right" })
  await desktopPage.waitForFunction(() => window.__rightClickPrevented !== undefined)
  assert.equal(await desktopPage.evaluate(() => window.__rightClickPrevented), false, "mouse context menu must remain available")
  await desktop.close()

  const mobile = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true })
  const mobilePage = await mobile.newPage()
  await mobilePage.route("**/api/**", async route => {
    const url = new URL(route.request().url())
    const result = await route.fetch({ url: `http://127.0.0.1:${backendPort}${url.pathname}${url.search}` })
    await route.fulfill({ response: result })
  })
  await mobilePage.goto(base, { waitUntil: "domcontentloaded" })
  await mobilePage.locator('.store-liquid-nav[data-mode="webgl"]').waitFor({ timeout: 20000 })
  assert.equal(await mobilePage.evaluate(() => matchMedia("(pointer: coarse)").matches), true)
  const mobileBoxes = await Promise.all((await mobilePage.locator(".store-liquid-link").all()).map(link => link.boundingBox()))
  const mobileStart = center(mobileBoxes[0]), mobileEnd = center(mobileBoxes[2])
  assert.equal(await mobilePage.evaluate(({ x, y }) => !!document.elementFromPoint(x, y)?.closest("a"), mobileStart), true,
    "idle links must remain available to ordinary pointer input")
  const mobileCdp = await mobile.newCDPSession(mobilePage)
  await mobileCdp.send("Input.dispatchTouchEvent", { type: "touchStart", touchPoints: [{ ...mobileStart, id: 1 }] })
  await mobilePage.waitForTimeout(1300)
  assert.equal(await mobilePage.evaluate(({ x, y }) => document.elementFromPoint(x, y)?.classList.contains("store-liquid-nav"), mobileStart), true,
    "an active touch hold must not target the anchor")
  assert.equal(new URL(mobilePage.url()).pathname, "/", "touch hold must not navigate")
  await mobileCdp.send("Input.dispatchTouchEvent", { type: "touchMove", touchPoints: [{ ...mobileEnd, id: 1 }] })
  await mobileCdp.send("Input.dispatchTouchEvent", { type: "touchEnd", touchPoints: [] })
  await mobilePage.waitForURL("**/messages")
  await mobilePage.locator('.store-liquid-nav[data-mode="webgl"]').waitFor({ timeout: 15000 })
  assert.equal(await mobilePage.locator(".store-liquid-nav").getAttribute("data-touch-active"), null)
  await mobilePage.locator(".store-liquid-link").nth(1).focus()
  await mobilePage.keyboard.press("Enter")
  await mobilePage.waitForURL("**/my/orders")
  await mobile.close()
  console.log(JSON.stringify({ result: "passed", chromium: browser.version(), resourcesBefore: before, resourcesAfter: after, desktopCaptures }))
} finally {
  await browser?.close()
  server.kill("SIGTERM")
  await new Promise(resolve => backend.close(resolve))
}
