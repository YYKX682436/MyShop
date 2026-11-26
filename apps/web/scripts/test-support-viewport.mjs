import assert from "node:assert/strict"
import { existsSync } from "node:fs"
import { chromium, webkit } from "playwright"

// Run against an isolated local MyShop instance. This test creates no messages.
const base = process.env.BASE_URL ?? "http://localhost:3010"
const engine = process.env.BROWSER_ENGINE === "webkit" ? "webkit" : "chromium"
const chrome = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
const browser = await (engine === "webkit" ? webkit : chromium).launch({
  headless: true,
  executablePath: engine === "webkit" ? undefined : process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH ?? (existsSync(chrome) ? chrome : undefined),
})
const context = await browser.newContext({ viewport: { width: 320, height: 568 }, isMobile: true, hasTouch: true, userAgent: "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36" })
const page = await context.newPage()

try {
  await page.route("**/api/**", async route => {
    const path = new URL(route.request().url()).pathname
    if (path === "/api/user/profile") {
      await route.fulfill({ status: 401, contentType: "application/json", body: JSON.stringify({ code: 401, message: "unauthorized" }) })
      return
    }
    let data
    if (path === "/api/support/summary") data = { unread: 0, latest: null }
    else if (path === "/api/announcements/summary") data = { unread: 0, latest: null }
    else if (path === "/api/support/general") data = { conversation_id: "fixture-support" }
    else if (path === "/api/support/conversations/fixture-support/activities") data = { items: [], has_more: false, oldest_cursor: null, newest_cursor: null }
    else if (path === "/api/support/conversations/fixture-support") data = {
      id: "fixture-support", site_id: null, site_name: "测试店", customer_name: "访客",
      order_id: null, order_no: null, order_status: null, latest_at: null, latest_type: null,
      preview: "", unread: 0, waiting_for: null, needs_attention: false,
    }
    else return route.continue()
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ code: 0, message: "ok", data }) })
  })
  await page.goto(`${base}/messages`, { waitUntil: "domcontentloaded" })
  const input = page.getByRole("textbox", { name: "消息内容" })
  await input.waitFor()
  await input.fill("测试聊天输入")
  const image = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/aL0AAAAASUVORK5CYII=", "base64")
  await page.locator('input[type="file"]').setInputFiles(Array.from({ length: 5 }, (_, i) => ({
    name: `${"长文件名".repeat(8)}${i}.png`, mimeType: "image/png", buffer: image,
  })))
  await input.focus()

  async function checkSendButton(visibleHeight) {
    await page.waitForTimeout(350)
    const button = page.getByRole("button", { name: "发送消息" })
    const box = await button.boundingBox()
    assert.ok(box && box.y >= 0 && box.y + box.height <= visibleHeight + 1,
      `Send button is outside the visible viewport: ${JSON.stringify(box)}`)
    await button.click({ trial: true })
  }

  async function checkBottomNavigationHidden() {
    await page.waitForTimeout(100)
    const navigation = page.locator(".store-liquid-nav")
    assert.equal(await navigation.getAttribute("aria-hidden"), "true", "bottom navigation should be inert while the keyboard is open")
    assert.equal(await navigation.evaluate(element => getComputedStyle(element.parentElement).visibility), "hidden",
      "bottom navigation should leave the visible chat viewport while the keyboard is open")
  }

  // Some mobile browsers resize the layout viewport when the keyboard opens.
  await page.setViewportSize({ width: 320, height: 320 })
  await checkSendButton(320)
  await checkBottomNavigationHidden()
  await page.setViewportSize({ width: 320, height: 568 })
  await input.focus()
  // Others shrink only visualViewport. This is a geometry simulation, not a
  // real on-screen keyboard or IME test.
  await page.evaluate(() => {
    Object.defineProperty(window.visualViewport, "height", { configurable: true, get: () => 320 })
    window.visualViewport.dispatchEvent(new Event("resize"))
  })
  await checkSendButton(320)
  await checkBottomNavigationHidden()
  console.log(`[PASS] ${engine}: send button remains visible with five attachments in both viewport models`)
} finally {
  await browser.close()
}
