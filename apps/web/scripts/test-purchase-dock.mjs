import assert from "node:assert/strict"
import { spawn } from "node:child_process"
import { existsSync, mkdirSync, renameSync, rmSync, writeFileSync } from "node:fs"
import { createServer } from "node:http"
import { fileURLToPath } from "node:url"
import { chromium } from "playwright"

// Test the production build against an isolated in-memory backend only.
const webRoot = fileURLToPath(new URL("..", import.meta.url))
const output = process.env.PURCHASE_EVIDENCE_DIR || fileURLToPath(new URL("../../../dist/purchase-dock-2026-11-24/", import.meta.url))
mkdirSync(output, { recursive: true })
const videoDirectory = `${output}/.video-${process.pid}`
const config = { site_name: "测试商城", favicon_url: null, maintenance_enabled: false }
const channels = [{ id: "wx", channel_code: "test-wx", name: "微信支付", type: "wxpay" }]
const year = { id: "spec-year", name: "年卡", price: 52, retail_price: 52, stock_available: 5, unlimited: false }
const month = { id: "spec-month", name: "月卡", price: 20, retail_price: 20, stock_available: 5, unlimited: false }
let refreshedProduct = false
const product = id => {
  const noSpecs = ["plain", "zero", "zero-disallowed", "soldout"].includes(id)
  const fields = id === "required" ? [{ id: "contact", label: "联系方式", type: "TEXT", required: true }]
    : id === "long" ? Array.from({ length: 20 }, (_, i) => ({ id: `field-${i}`, label: `填写信息 ${i + 1}`, type: "TEXT", required: false })) : []
  return { id, title: "测试商品", price: id.startsWith("zero") ? 0 : 52, retail_price: 52, cover_url: null,
    description: "商品详情正文。".repeat(240), detail_md: null, stock_available: id === "soldout" ? 0 : 5,
    unlimited: false, has_specs: !noSpecs, specs: noSpecs ? [] : id === "huge"
      ? [{ ...year, name: "超级长规格名称MixedEnglishWithoutBreak混合中文".repeat(4), price: 123456789012.34 }, month]
      : [id === "refresh" && refreshedProduct ? { ...year, stock_available: 0 } : year, month],
    zero_price_purchase_allowed: id === "zero", delivery_type: "MANUAL", sales_count: 0, initial_sales: 0,
    customer_input_fields: fields }
}
const ok = data => JSON.stringify({ code: 0, message: "ok", data })
let serverChannels = channels
const backend = createServer((req, res) => {
  const path = new URL(req.url, "http://fixture").pathname
  const data = path === "/api/site/config" ? config : path === "/api/payment-channels" ? serverChannels
    : path === "/api/categories" ? [] : path.startsWith("/api/products/") ? product(path.split("/").at(-1)) : null
  res.writeHead(200, { "content-type": "application/json" }); res.end(ok(data))
})
await new Promise(resolve => backend.listen(0, "127.0.0.1", resolve))
const reservation = createServer()
await new Promise(resolve => reservation.listen(0, "127.0.0.1", resolve))
const port = reservation.address().port
await new Promise(resolve => reservation.close(resolve))
const base = `http://127.0.0.1:${port}`
config.main_site_url = base
const server = spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "--hostname", "127.0.0.1", "--port", String(port)], {
  cwd: webRoot, env: { ...process.env, BACKEND_URL: `http://127.0.0.1:${backend.address().port}`, PUBLIC_SITE_URL: base, NEXT_TELEMETRY_DISABLED: "1" }, stdio: "ignore",
})
let browser
const geometry = []
const user = { id: "fixture-user", username: "fixture", role: "USER", balance: 60 }
const waitUntil = async predicate => {
  for (let i = 0; i < 100; i++) { if (predicate()) return; await new Promise(resolve => setTimeout(resolve, 50)) }
  throw new Error("Timed out waiting for fixture request")
}
async function rect(locator) {
  return locator.evaluate(el => {
    const r = el.getBoundingClientRect(), s = getComputedStyle(el)
    return { x: r.x, y: r.y, width: r.width, height: r.height, bottom: r.bottom, right: r.right, fontSize: s.fontSize }
  })
}
async function measurements(page, open) {
  const root = open ? page.getByRole("dialog") : page.locator(".product-purchase-dock-closed")
  return { shell: await rect(root), row: await rect(root.locator('[data-slot="purchase-action-bar"]')),
    price: await rect(root.locator('[data-slot="purchase-total"]')), button: await rect(root.locator('button').last()) }
}
async function assertPlainPurchaseButton(root, expectedType) {
  const button = root.locator('[data-slot="purchase-action-bar"] button')
  assert.equal(await button.getAttribute("type"), expectedType)
  assert.equal(await button.innerText(), "立即购买")
  assert.equal(await button.locator("svg").count(), 0)
  const visual = await button.evaluate(el => {
    const text = el.querySelector("span"), box = el.getBoundingClientRect(), label = text.getBoundingClientRect()
    return { children: [...el.children].map(child => child.tagName), width: box.width, height: box.height, radius: getComputedStyle(el).borderTopLeftRadius,
      xOffset: (label.left + label.right - box.left - box.right) / 2,
      yOffset: (label.top + label.bottom - box.top - box.bottom) / 2 }
  })
  const shellRadius = await root.evaluate(el => getComputedStyle(el).borderTopLeftRadius)
  assert.deepEqual(visual.children, ["SPAN"], "the ordinary button has no icon or placeholder")
  assert.equal(visual.radius, shellRadius, "the button and purchase shell must use the same corner radius")
  assert.ok(Math.abs(visual.width - 130) <= 1 && Math.abs(visual.height - 40) <= 1, JSON.stringify(visual))
  assert.ok(Math.abs(visual.xOffset) <= 1 && Math.abs(visual.yOffset) <= 1, `button label must be centered: ${JSON.stringify(visual)}`)
}
async function shellAppearance(root) {
  return root.evaluate(el => {
    const style = getComputedStyle(el)
    return { backgroundImage: style.backgroundImage, borderWidth: style.borderTopWidth, radius: style.borderTopLeftRadius,
      borderColor: style.borderTopColor, shadow: style.boxShadow }
  })
}
async function fixture({ id = "standard", width = 390, height = 844, theme = "light", profile = null, turnstile = false, video = false, channelsAvailable = true, orderHandler } = {}) {
  serverChannels = channelsAvailable ? channels : []
  const context = await browser.newContext({ viewport: { width, height }, deviceScaleFactor: 1, hasTouch: width < 768,
    ...(video ? { recordVideo: { dir: videoDirectory, size: { width, height } } } : {}) })
  const requests = []
  const requestHeaders = []
  await context.addInitScript(({ theme }) => {
    localStorage.setItem("theme", theme)
    window.__fixtureSubmits = 0
    HTMLFormElement.prototype.submit = function () { window.__fixtureSubmits++ }
  }, { theme })
  if (video) await context.addInitScript(() => {
    document.addEventListener("pointerdown", event => {
      const marker = document.createElement("div")
      Object.assign(marker.style, { position: "fixed", left: `${event.clientX - 11}px`, top: `${event.clientY - 11}px`,
        width: "22px", height: "22px", border: "3px solid #e63980", borderRadius: "50%", background: "#e6398066",
        pointerEvents: "none", zIndex: "2147483647" })
      document.body.append(marker)
      setTimeout(() => marker.remove(), 350)
    }, true)
  })
  await context.route("https://challenges.cloudflare.com/**", route => route.fulfill({ contentType: "application/javascript", body: `window.__turnstileRenders=0;window.turnstile={render(el,o){window.__turnstileRenders++;el.textContent='Turnstile fixture';o.callback('fixture-token');return 'widget'},reset(){},remove(){}}` }))
  await context.route("**/api/**", async route => {
    const req = route.request(), path = new URL(req.url()).pathname
    if (path === "/api/orders" && req.method() === "POST") {
      requests.push(req.postDataJSON())
      requestHeaders.push(req.headers())
      if (orderHandler) return orderHandler(route, requests)
      return route.fulfill({ status: 500, contentType: "application/json", body: JSON.stringify({ code: 500, message: "fixture failure" }) })
    }
    const data = path === "/api/site/config" ? { ...config, ...(turnstile ? { turnstile_site_key: "fixture-key" } : {}) }
      : path === "/api/user/profile" ? profile : path.endsWith("/summary") ? { unread: 0, latest: null }
      : path === "/api/categories" ? [] : path === "/api/payment-channels" ? serverChannels : path.startsWith("/api/products/") ? product(id) : null
    await route.fulfill({ status: 200, contentType: "application/json", body: ok(data) })
  })
  const page = await context.newPage()
  const errors = []
  page.on("pageerror", error => errors.push(error.message))
  await page.goto(`${base}/product/${id}`, { waitUntil: "networkidle" })
  const trigger = page.locator(".product-purchase-dock-closed button")
  await trigger.waitFor({ state: "visible" })
  if (id !== "soldout") await page.waitForFunction(() => !document.querySelector('.product-purchase-dock-closed button')?.disabled)
  return { context, page, trigger, requests, requestHeaders, errors }
}
const dialog = page => page.getByRole("dialog", { name: "立即购买" })
const action = page => dialog(page).locator('[data-slot="purchase-action-bar"]')
async function closeFixture(f) {
  assert.deepEqual(f.errors, [], "page must not throw runtime errors")
  await f.context.close()
}

try {
  for (let i = 0; i < 120; i++) {
    try { if ((await fetch(`${base}/product/standard`)).ok) break } catch { /* startup */ }
    if (i === 119) throw new Error("Built web server did not start")
    await new Promise(resolve => setTimeout(resolve, 100))
  }
  const chrome = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
  browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH ?? (existsSync(chrome) ? chrome : undefined) })

  for (const { width, height, theme } of [
    { width: 320, height: 700, theme: "light" }, { width: 390, height: 844, theme: "light" },
    { width: 430, height: 932, theme: "dark" }, { width: 1440, height: 900, theme: "light" },
    { width: 320, height: 700, theme: "dark" }, { width: 390, height: 844, theme: "dark" },
    { width: 430, height: 932, theme: "light" }, { width: 1440, height: 900, theme: "dark" },
  ]) {
    const capture = width === 390 && theme === "light"
    const f = await fixture({ width, height, theme, video: capture })
    const { page, trigger, requests } = f
    await page.evaluate(() => window.scrollTo(0, 150))
    const scrollY = await page.evaluate(() => scrollY)
    await trigger.click()
    await dialog(page).waitFor()
    assert.equal(requests.length, 0, "opening must never submit an order")
    assert.equal(await page.evaluate(() => document.activeElement?.getAttribute("type")), "button", "opening focuses a button, not a text input")
    assert.equal(await page.evaluate(() => document.activeElement?.textContent?.trim()), "关闭")
    await dialog(page).getByRole("button", { name: "增加数量" }).click()
    assert.match(await action(page).innerText(), /年卡\s*×\s*2/)
    assert.match(await action(page).innerText(), /¥104\.00/)
    if (capture) { await page.waitForTimeout(450); await dialog(page).getByRole("button", { name: "关闭" }).click() }
    else await page.keyboard.press("Escape")
    await dialog(page).waitFor({ state: "hidden" })
    await page.waitForFunction(() => document.activeElement === document.querySelector('.product-purchase-dock-closed button'))
    assert.equal(await page.evaluate(() => scrollY), scrollY, "closing must preserve page scroll")
    assert.match(await page.locator(".product-purchase-dock-closed").innerText(), /年卡\s*×\s*2/)
    assert.match(await page.locator(".product-purchase-dock-closed").innerText(), /¥104\.00/)
    if (capture) await page.evaluate(() => window.scrollTo(0, 0))
    const closed = await measurements(page, false)
    await assertPlainPurchaseButton(page.locator(".product-purchase-dock-closed"), "button")
    const closedShell = await shellAppearance(page.locator(".product-purchase-dock-closed"))
    if (capture) { await page.waitForTimeout(450); await page.screenshot({ path: `${output}/closed-year-2.png` }) }
    await trigger.click()
    await dialog(page).waitFor()
    assert.equal(await dialog(page).getByRole("spinbutton", { name: "数量" }).inputValue(), "2")
    assert.match(await action(page).innerText(), /年卡\s*×\s*2/)
    const opened = await measurements(page, true)
    await assertPlainPurchaseButton(dialog(page), "submit")
    const openedShell = await shellAppearance(dialog(page))
    assert.deepEqual(openedShell, closedShell, "both shells must have the same edge treatment")
    assert.equal(closedShell.backgroundImage, "none", "purchase shells must not have a gradient")
    assert.equal(closedShell.borderWidth, "1px")
    assert.notEqual(closedShell.shadow, "none")
    assert.doesNotMatch(closedShell.shadow, /inset/)
    if (capture) { await page.waitForTimeout(450); await page.screenshot({ path: `${output}/open-year-2.png` }) }
    for (const part of ["row", "price", "button"]) {
      for (const key of ["x", "y", "width", "height", "bottom"]) {
        assert.ok(Math.abs(closed[part][key] - opened[part][key]) <= 1,
          `${width}px ${part}.${key} shifted: ${closed[part][key]} -> ${opened[part][key]}`)
      }
    }
    assert.equal(closed.price.fontSize, opened.price.fontSize)
    assert.ok(height - opened.shell.bottom >= 7, "panel must have an external bottom gap")
    assert.ok(Math.abs(closed.shell.x - opened.shell.x) <= 1 && Math.abs(closed.shell.width - opened.shell.width) <= 1)
    assert.equal(await page.locator('[data-slot="purchase-action-bar"]:visible').count(), 1)
    assert.equal(await page.locator('[data-slot="purchase-action-bar"]:visible button:enabled').count(), 1)
    assert.equal(await page.locator('.product-purchase-dock-viewport').getAttribute("inert"), "")
    const panelStyle = await dialog(page).evaluate(el => {
      const s = getComputedStyle(el), row = getComputedStyle(el.querySelector('[data-slot="purchase-action-bar"]'))
      return { radius: s.borderRadius, rowBackground: row.backgroundColor, rowBorder: row.borderTopWidth, rowShadow: row.boxShadow, bottomGap: innerHeight - el.getBoundingClientRect().bottom }
    })
    assert.equal(panelStyle.rowBorder, "0px")
    assert.equal(panelStyle.rowBackground, "rgba(0, 0, 0, 0)")
    assert.equal(panelStyle.rowShadow, "none")
    geometry.push({ width, height, theme, closed, opened, panelStyle, closedShell, openedShell })
    for (let i = 0; i < 12; i++) {
      await page.keyboard.press("Tab")
      assert.ok(await page.evaluate(() => Boolean(document.querySelector('[role="dialog"]')?.contains(document.activeElement))), "Tab must remain in the modal")
    }
    await page.keyboard.press("Escape")
    await dialog(page).waitFor({ state: "hidden" })
    for (let i = 0; i < 4; i++) {
      await trigger.click(); await dialog(page).waitFor(); await page.keyboard.press("Escape"); await dialog(page).waitFor({ state: "hidden" })
    }
    assert.equal(await page.locator('[data-slot="purchase-action-bar"]:visible').count(), 1)
    assert.equal(await page.locator('[data-state="open"][data-radix-popper-content-wrapper]').count(), 0)
    assert.equal(await page.evaluate(() => getComputedStyle(document.body).pointerEvents), "auto")
    const beforeWheel = await page.evaluate(() => scrollY)
    await page.mouse.move(10, Math.min(250, height - 20))
    await page.mouse.wheel(0, 300)
    await page.waitForFunction(y => scrollY > y, beforeWheel)
    assert.equal(requests.length, 0)
    const recording = capture ? page.video() : null
    await closeFixture(f)
    if (recording) renameSync(await recording.path(), `${output}/open-close-reopen.webm`)
  }
  writeFileSync(`${output}/geometry.json`, JSON.stringify(geometry, null, 2))
  console.log("Geometry, state retention, keyboard trap, scroll and repeated close: passed", geometry.map(x => `${x.width}px/${x.theme}`).join(", "))

  // Simulate a classic scrollbar disappearing while Radix preserves its gap.
  {
    const f = await fixture({ width: 1440, height: 900 })
    const { page, trigger } = f
    await page.addStyleTag({ content: `html { scrollbar-gutter: stable } ::-webkit-scrollbar { width: 17px }
      html:has(body[data-scroll-locked]) { scrollbar-gutter: auto }
      body[data-scroll-locked] { --removed-body-scroll-bar-size: 17px !important }` })
    const before = await measurements(page, false)
    const viewportBefore = await page.evaluate(() => ({ inner: innerWidth, layout: document.documentElement.getBoundingClientRect().width, gutter: getComputedStyle(document.documentElement).scrollbarGutter }))
    await trigger.click(); await dialog(page).waitFor()
    const after = await measurements(page, true)
    const viewportAfter = await page.evaluate(() => ({ inner: innerWidth, layout: document.documentElement.getBoundingClientRect().width, removed: getComputedStyle(document.body).getPropertyValue('--removed-body-scroll-bar-size') }))
    assert.ok(viewportAfter.layout - viewportBefore.layout >= 15, `test must remove the simulated desktop scrollbar gutter: ${JSON.stringify({ viewportBefore, viewportAfter })}`)
    assert.ok(Math.abs(before.row.x - after.row.x) <= 1 && Math.abs(before.button.x - after.button.x) <= 1,
      `scrollbar lock moved the dock: ${JSON.stringify({ before, after, viewportBefore, viewportAfter })}`)
    await page.mouse.click(3, 3)
    await dialog(page).waitFor({ state: "hidden" })
    await trigger.click(); await dialog(page).waitFor()
    await page.goto(`${base}/product/plain`, { waitUntil: "networkidle" })
    assert.equal(await page.locator('[role="dialog"]').count(), 0)
    assert.equal(await page.evaluate(() => getComputedStyle(document.body).pointerEvents), "auto")
    await page.evaluate(() => window.scrollTo(0, 200))
    assert.ok(await page.evaluate(() => scrollY > 0), "route change must release the scroll lock")
    await closeFixture(f)
    console.log("Simulated classic desktop scrollbar, backdrop dismissal and route unmount: passed", JSON.stringify({ viewportBefore, viewportAfter }))
  }

  {
    const f = await fixture()
    const cdp = await f.context.newCDPSession(f.page)
    await cdp.send("Emulation.setEmulatedMedia", { features: [{ name: "prefers-reduced-transparency", value: "reduce" }] })
    const material = await f.page.locator(".product-purchase-dock-closed").evaluate(el => ({ filter: getComputedStyle(el).backdropFilter, color: getComputedStyle(el).backgroundColor }))
    assert.equal(material.filter, "none", "reduced transparency must disable the local blur")
    await closeFixture(f)
    console.log("Reduced transparency: passed", JSON.stringify(material))
  }

  // First activation by touch and keyboard remains an opener, never an order action.
  for (const method of ["touch", "Enter", "Space"]) {
    const f = await fixture()
    const { page, trigger, requests } = f
    if (method === "touch") {
      const b = await trigger.boundingBox(); await page.touchscreen.tap(b.x + b.width / 2, b.y + b.height / 2)
    } else { await trigger.focus(); await page.keyboard.press(method) }
    await dialog(page).waitFor()
    assert.equal(requests.length, 0, `${method} opening must not submit`)
    await page.keyboard.press("Escape"); await dialog(page).waitFor({ state: "hidden" })
    await closeFixture(f)
  }
  console.log("Touch, Enter and Space opening: passed")

  // Long forms scroll independently while the header and action remain accessible.
  for (const [width, height] of [[320, 400], [844, 320], [390, 844]]) {
    const f = await fixture({ id: "long", width, height, turnstile: true })
    const { page, trigger } = f
    await trigger.click(); await dialog(page).waitFor()
    await page.waitForFunction(() => window.__turnstileRenders > 0)
    const box = await dialog(page).boundingBox(), row = await action(page).boundingBox()
    assert.ok(box.y >= 0 && box.y + box.height <= height && row.y + row.height <= height, "short viewport controls must remain reachable")
    const area = dialog(page).locator('[data-slot="purchase-content"]')
    assert.ok(await area.evaluate(el => el.scrollHeight > el.clientHeight), "long form must have an inner scroll region")
    const rowBefore = await rect(action(page))
    await area.evaluate(el => { el.scrollTop = el.scrollHeight })
    assert.ok(await area.evaluate(el => el.scrollTop > 0))
    const rowAfter = await rect(action(page))
    assert.ok(Math.abs(rowBefore.y - rowAfter.y) <= 1, "scrolling content must not move the action")
    await dialog(page).getByRole("button", { name: "关闭" }).click()
    await dialog(page).waitFor({ state: "hidden" })
    await closeFixture(f)
  }
  console.log("Long form, short/landscape viewport and stub Turnstile: passed")

  {
    const f = await fixture({ id: "long", width: 390, height: 844 })
    const { page, trigger } = f
    await trigger.click(); await dialog(page).waitFor()
    await dialog(page).getByLabel("填写信息 1", { exact: true }).focus()
    for (const offsetTop of [0, 120]) {
      await page.evaluate(top => {
        Object.defineProperty(window.visualViewport, "height", { configurable: true, value: 450 })
        Object.defineProperty(window.visualViewport, "offsetTop", { configurable: true, value: top })
        window.visualViewport.dispatchEvent(new Event("resize"))
      }, offsetTop)
      const panel = await rect(dialog(page)), row = await rect(action(page))
      const close = await rect(dialog(page).getByRole("button", { name: "关闭" }))
      assert.ok(panel.y >= offsetTop && row.bottom <= offsetTop + 450 && close.y >= offsetTop,
        `simulated keyboard viewport must contain the close button and purchase row: ${JSON.stringify({ offsetTop, panel, row, close })}`)
    }
    await dialog(page).getByRole("button", { name: "关闭" }).click()
    await dialog(page).waitFor({ state: "hidden" })
    await closeFixture(f)
    console.log("Simulated visual-viewport keyboard resize and pan: passed (not a physical keyboard)")
  }

  // Long names and large amounts must fit without colliding with the action.
  {
    const f = await fixture({ id: "huge", width: 320, height: 700, theme: "dark" })
    const { page, trigger } = f
    await trigger.click(); await dialog(page).waitFor()
    const row = action(page), amount = row.locator('[data-slot="purchase-total"]'), button = row.locator('button')
    const a = await rect(amount), b = await rect(button), shell = await rect(dialog(page))
    assert.ok(a.right <= b.x && b.right <= shell.right && a.x >= shell.x, "large amount and button must not overlap or leave the panel")
    assert.match(await row.innerText(), /×\s*1/)
    assert.match(await row.innerText(), /¥123456789012\.34/)
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
    await page.screenshot({ path: `${output}/dark-large-320.png` })
    await closeFixture(f)
  }
  {
    const f = await fixture({ id: "plain" })
    assert.match(await f.page.locator(".product-purchase-dock-closed").innerText(), /数量\s*×\s*1/)
    await closeFixture(f)
  }
  console.log("Large price, long spec and no-spec summary: passed")

  for (const [width, theme] of [[320, "light"], [320, "dark"], [390, "light"], [390, "dark"]]) {
    const f = await fixture({ width, theme, channelsAvailable: false })
    await f.trigger.click(); await dialog(f.page).waitFor()
    const row = action(f.page), button = row.locator('button[type="submit"]')
    assert.equal(await button.innerText(), "请选择可用的支付方式")
    const bounds = await button.evaluate(el => {
      const buttonBox = el.getBoundingClientRect(), textBox = el.querySelector("span").getBoundingClientRect()
      return { fits: textBox.left >= buttonBox.left && textBox.right <= buttonBox.right
        && textBox.top >= buttonBox.top && textBox.bottom <= buttonBox.bottom,
        scrollFits: el.scrollWidth <= el.clientWidth && el.scrollHeight <= el.clientHeight }
    })
    const price = await rect(row.locator('[data-slot="purchase-total"]')), buttonBox = await rect(button)
    assert.ok(bounds.fits && bounds.scrollFits && price.right <= buttonBox.x,
      `long business label must fit without covering the amount at ${width}px/${theme}: ${JSON.stringify(bounds)}`)
    await closeFixture(f)
  }
  console.log("Long business labels fit at 320px and 390px in both themes: passed")

  // Native form validation, single in-flight request, unit price, and retry key.
  {
    let release
    const pending = new Promise(resolve => { release = resolve })
    const f = await fixture({ id: "required", turnstile: true, orderHandler: async route => { await pending; await route.fulfill({ status: 500, contentType: "application/json", body: JSON.stringify({ code: 500, message: "fixture failure" }) }) } })
    const { page, trigger, requests, requestHeaders } = f
    await trigger.click(); await dialog(page).waitFor()
    const submit = action(page).locator('button[type="submit"]')
    await submit.click()
    assert.equal(requests.length, 0, "required field must block submission")
    await dialog(page).getByLabel("联系方式").fill("test contact")
    await dialog(page).getByRole("button", { name: "增加数量" }).click()
    const quantity = dialog(page).getByRole("spinbutton", { name: "数量" })
    for (const value of ["0", "6"]) {
      await quantity.evaluate((el, next) => { el.value = next }, value)
      await dialog(page).locator("form").evaluate(el => el.requestSubmit())
      assert.equal(requests.length, 0, `native range must reject ${value}`)
    }
    await quantity.evaluate(el => { el.value = "2" })
    await page.keyboard.press("Escape"); await dialog(page).waitFor({ state: "hidden" })
    await trigger.click(); await dialog(page).waitFor()
    assert.equal(await dialog(page).getByLabel("联系方式").inputValue(), "test contact")
    assert.equal(await dialog(page).getByRole("spinbutton", { name: "数量" }).inputValue(), "2")
    await page.waitForFunction(() => window.__turnstileRenders >= 2)
    await dialog(page).locator("form").evaluate(el => { el.requestSubmit(); el.requestSubmit() })
    await waitUntil(() => requests.length > 0)
    await page.waitForTimeout(250)
    assert.equal(requests.length, 1, "simultaneous submissions must share one in-flight request")
    assert.equal(requests[0].quantity, 2)
    assert.equal(requests[0].spec_id, "spec-year")
    assert.equal(requests[0].expected_unit_price, 52)
    assert.equal(requestHeaders[0]["x-turnstile-token"], "fixture-token")
    release()
    await submit.waitFor({ state: "visible" })
    await page.waitForFunction(() => !document.querySelector('[role="dialog"] button[type="submit"]')?.disabled)
    await submit.click()
    await waitUntil(() => requests.length === 2)
    assert.equal(requests[1].idempotency_key, requests[0].idempotency_key)
    assert.equal(requests[1].idempotency_fingerprint, requests[0].idempotency_fingerprint)
    await closeFixture(f)
  }
  console.log("Required/range validation, payload, stub Turnstile token, concurrent activation and retry idempotency: passed")

  {
    const f = await fixture({ id: "required" })
    const { page, trigger } = f
    await trigger.click(); await dialog(page).waitFor()
    await dialog(page).getByLabel("联系方式").fill("old value")
    await dialog(page).getByRole("button", { name: "增加数量" }).click()
    await dialog(page).getByRole("button", { name: "月卡", exact: true }).click()
    assert.equal(await dialog(page).getByRole("spinbutton", { name: "数量" }).inputValue(), "1")
    assert.equal(await dialog(page).getByLabel("联系方式").inputValue(), "")
    await closeFixture(f)
    console.log("Changing specifications resets quantity and customer fields: passed")
  }

  {
    const f = await fixture({ id: "refresh", orderHandler: route => {
      refreshedProduct = true
      return route.fulfill({ status: 400, contentType: "application/json", body: JSON.stringify({ code: 40007, message: "price changed", params: { current_unit_price: 53 } }) })
    } })
    await f.trigger.click(); await dialog(f.page).waitFor()
    await dialog(f.page).getByRole("button", { name: "增加数量" }).click()
    await action(f.page).locator('button[type="submit"]').click()
    await f.page.getByText(/当前价格：¥53\.00/).waitFor()
    await f.page.waitForFunction(() => document.querySelector('[role="dialog"] [data-slot="purchase-action-bar"]')?.textContent?.includes('月卡 × 1'))
    assert.equal(f.requests.length, 1)
    assert.equal(f.requests[0].expected_unit_price, 52)
    await closeFixture(f)
    console.log("Price-change 40007 and refreshed product selection: passed")
  }

  // Selection reconciles after the total crosses the available balance.
  {
    const f = await fixture({ profile: user })
    const { page, trigger } = f
    await trigger.click(); await dialog(page).waitFor()
    const balance = dialog(page).getByRole("radio", { name: /余额支付/ })
    const online = dialog(page).getByRole("radio", { name: /^在线支付/ })
    assert.equal(await balance.getAttribute("aria-checked"), "true")
    await dialog(page).getByRole("button", { name: "增加数量" }).click()
    await page.waitForFunction(() => document.querySelector('[role="radio"][aria-checked="true"]')?.textContent?.includes('在线支付'))
    assert.equal(await balance.isDisabled(), true)
    assert.equal(await online.getAttribute("aria-checked"), "true")
    await closeFixture(f)
  }
  console.log("Balance availability reconciles with total: passed")

  // Zero-price authorization, administrator pickup, sold-out and unavailable payment.
  for (const test of [
    { id: "zero", text: "零元订单，无需付款", enabled: true, expected: { payment_method: "BALANCE", expected_unit_price: 0 } },
    { id: "zero-disallowed", text: "当前价格暂不可购买，请联系站长", enabled: false },
    { id: "standard", profile: { ...user, role: "ADMIN" }, text: "管理员提卡", enabled: true, expected: { expected_unit_price: 52 } },
    { id: "standard", channelsAvailable: false, text: "请选择可用的支付方式", enabled: false },
  ]) {
    const f = await fixture(test)
    const { page, trigger, requests } = f
    await trigger.click(); await dialog(page).waitFor()
    assert.ok((await dialog(page).innerText()).includes(test.text))
    const submit = action(page).locator('button[type="submit"]')
    assert.equal(await submit.isEnabled(), test.enabled)
    if (test.enabled) {
      await submit.click(); await waitUntil(() => requests.length === 1)
      for (const [key, value] of Object.entries(test.expected)) assert.equal(requests[0][key], value)
      if (test.profile?.role === "ADMIN") assert.equal(requests[0].payment_method, undefined)
    }
    await closeFixture(f)
  }
  {
    const f = await fixture({ id: "soldout" })
    assert.equal(await f.trigger.isDisabled(), true)
    assert.match(await f.trigger.innerText(), /已售罄/)
    await closeFixture(f)
  }
  console.log("Zero authorization, admin pickup, sold out and payment unavailable: passed")

  // A blocked provider form leaves a visible retry action above the fixed row.
  {
    const f = await fixture({ orderHandler: route => route.fulfill({ status: 200, contentType: "application/json", body: ok({
      order: { id: "fixture-order", order_type: "NORMAL", status: "PENDING" },
      payment: { order_id: "fixture-order", payment_method: "ONLINE", status: "PENDING", next_action: { type: "form_post", url: "https://payment.invalid/", fields: { token: "fixture" } } },
    }) }) })
    const { page, trigger } = f
    await trigger.click(); await dialog(page).waitFor()
    const before = await rect(action(page))
    await action(page).locator('button[type="submit"]').click()
    await dialog(page).getByRole("button", { name: "继续支付" }).waitFor()
    const after = await rect(action(page))
    assert.ok(Math.abs(before.y - after.y) <= 1 && Math.abs(before.bottom - after.bottom) <= 1)
    assert.equal(await page.evaluate(() => window.__fixtureSubmits), 1)
    await dialog(page).getByRole("button", { name: "继续支付" }).click()
    assert.equal(await page.evaluate(() => window.__fixtureSubmits), 2)
    await closeFixture(f)
  }
  console.log("Payment fallback remains reachable above the action: passed")

  console.log(`Evidence: ${output}`)
} finally {
  if (browser) await browser.close()
  server.kill("SIGTERM")
  await new Promise(resolve => backend.close(resolve))
  rmSync(videoDirectory, { recursive: true, force: true })
}
