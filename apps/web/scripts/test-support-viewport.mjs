import assert from "node:assert/strict"
import { chromium, webkit } from "playwright"

// Run against an isolated local MyShop instance. This test creates no messages.
const base = process.env.BASE_URL ?? "http://localhost:3010"
const engine = process.env.BROWSER_ENGINE === "webkit" ? "webkit" : "chromium"
const browser = await (engine === "webkit" ? webkit : chromium).launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 320, height: 568 }, isMobile: true, hasTouch: true })
const page = await context.newPage()

try {
  await page.goto(`${base}/messages/support/new`, { waitUntil: "domcontentloaded" })
  const input = page.getByRole("textbox", { name: "消息内容" })
  await input.waitFor()
  const popup = page.getByRole("button", { name: "我知道了" })
  await page.waitForTimeout(300)
  if (await popup.isVisible().catch(() => false)) await popup.click()
  await input.fill("测试聊天输入")
  const image = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/aL0AAAAASUVORK5CYII=", "base64")
  await page.locator('input[type="file"]').setInputFiles(Array.from({ length: 5 }, (_, i) => ({
    name: `${"长文件名".repeat(8)}${i}.png`, mimeType: "image/png", buffer: image,
  })))
  await input.focus()

  async function checkSendButton(visibleHeight) {
    await page.waitForTimeout(350)
    const button = page.getByRole("button", { name: "发送" })
    const box = await button.boundingBox()
    assert.ok(box && box.y >= 0 && box.y + box.height <= visibleHeight + 1,
      `Send button is outside the visible viewport: ${JSON.stringify(box)}`)
    await button.click({ trial: true })
  }

  // Some mobile browsers resize the layout viewport when the keyboard opens.
  await page.setViewportSize({ width: 320, height: 320 })
  await checkSendButton(320)
  await page.setViewportSize({ width: 320, height: 568 })
  await input.focus()
  // Others shrink only visualViewport. This is a geometry simulation, not a
  // real on-screen keyboard or IME test.
  await page.evaluate(() => {
    Object.defineProperty(window.visualViewport, "height", { configurable: true, get: () => 320 })
    window.visualViewport.dispatchEvent(new Event("resize"))
  })
  await checkSendButton(320)
  console.log(`[PASS] ${engine}: send button remains visible with five attachments in both viewport models`)
} finally {
  await browser.close()
}
