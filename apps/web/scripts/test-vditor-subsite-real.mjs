import assert from "node:assert/strict"
import { existsSync } from "node:fs"
import { chromium } from "playwright"

const base = new URL(process.env.BASE_URL || "http://127.0.0.1:3012")
if (!["127.0.0.1", "localhost", "[::1]"].includes(base.hostname) || process.env.MYSHOP_TEST_ISOLATED !== "1") throw new Error("An isolated local environment is required")
if (!process.env.MYSHOP_TEST_SUBSITE_USER || !process.env.MYSHOP_TEST_SUBSITE_PASSWORD) throw new Error("A test subsite account is required")
const chrome = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
const browser = await chromium.launch({ headless:true, executablePath:existsSync(chrome) ? chrome : undefined })
const context = await browser.newContext({ viewport:{width:390,height:844} })
const page = await context.newPage()
page.setDefaultTimeout(20000)
const responses = [], errors = [], unauthorizedCalls = []
page.on("response", response => {
  const path = new URL(response.url()).pathname
  if (path.startsWith("/api/subsite") || path === "/api/user/profile") responses.push({ path,status:response.status() })
})
page.on("pageerror", error => errors.push(error.message))
page.on("request", request => { if (/\/api\/(?:admin\/media|upload\/image)/.test(request.url())) unauthorizedCalls.push(request.url()) })
try {
  await page.goto(new URL("/login?redirect=%2Fmy%2Fsubsite", base).href)
  await page.locator("#account").fill(process.env.MYSHOP_TEST_SUBSITE_USER)
  await page.locator("#password").fill(process.env.MYSHOP_TEST_SUBSITE_PASSWORD)
  const login = page.waitForResponse(response => new URL(response.url()).pathname === "/api/auth/login" && response.request().method() === "POST")
  await page.locator('form button[type="submit"]').click()
  const auth = await login
  assert.ok(auth.ok(), `Login failed: HTTP ${auth.status()}`)
  await page.waitForURL(url => url.pathname === "/my/subsite")
  const editor = page.getByTestId("markdown-editor")
  const text = editor.locator('.vditor-wysiwyg [contenteditable="true"]')
  await text.waitFor()
  assert.equal(await editor.getByRole("button", { name:"选择资源" }).count(), 0)
  assert.equal(await editor.getByRole("button", { name:"插入图片" }).count(), 0)
  const form = editor.locator("xpath=ancestor::form")
  const title = `subsite-editor-test-${Date.now()}`
  await form.getByRole("textbox", { name:"标题（可选）",exact:true }).fill(title)
  await text.click(); await page.keyboard.insertText("分站首行"); await page.keyboard.press("Shift+Enter"); await page.keyboard.insertText("分站第二行")
  const imagesBefore = await text.locator("img").count()
  await text.evaluate(element => {
    const transfer = new DataTransfer()
    transfer.items.add(new File([new Uint8Array([137,80,78,71])], "clipboard.png", { type:"image/png" }))
    element.dispatchEvent(new ClipboardEvent("paste", { bubbles:true,cancelable:true,clipboardData:transfer }))
  })
  assert.equal(await text.locator("img").count(), imagesBefore)
  await page.waitForTimeout(1200)
  const save = page.waitForResponse(response => new URL(response.url()).pathname === "/api/subsite/announcements" && response.request().method() === "POST")
  await form.getByRole("button", { name:"保存草稿",exact:true }).click()
  const saved = await save
  assert.ok(saved.ok(), `Subsite announcement save failed: HTTP ${saved.status()}`)
  const body = await saved.json()
  assert.equal(body.code, 0, body.message)
  const announcementId = body.data.id
  await page.getByRole("button", { name:title }).waitFor()
  await page.getByRole("button", { name:"新建" }).click()
  await page.getByRole("button", { name:title }).click()
  await text.waitFor()
  assert.match(await text.innerText(), /分站首行/)
  assert.match(await text.innerText(), /分站第二行/)
  assert.equal(await form.getByRole("button", { name:"保存草稿",exact:true }).isDisabled(), true)
  await editor.getByRole("button", { name:"预览",exact:true }).click()
  assert.equal(await editor.locator(".prose br").count(), 1, "Subsite saved line break must render in the shared preview")
  await page.waitForTimeout(1200)
  const denied = await context.request.get(new URL("/api/admin/media?page=1&page_size=1", base).href)
  assert.ok([401,403].includes(denied.status()), `Admin media endpoint returned ${denied.status()}`)
  assert.deepEqual(unauthorizedCalls, [])
  assert.ok(await page.locator("body").evaluate(element => element.scrollWidth <= innerWidth))
  console.log(JSON.stringify({ subsite:"actual account: save/reopen and line break passed; admin controls absent and API denied", announcement_id:announcementId, denied_status:denied.status(), responses, errors }))
} catch (error) {
  console.error("Subsite diagnostic:", JSON.stringify({ url:page.url(),body:(await page.locator("body").innerText()).slice(0,1500),responses,errors,unauthorizedCalls }))
  throw error
} finally { await browser.close() }
