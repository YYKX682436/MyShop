import assert from "node:assert/strict"
import { existsSync, readFileSync } from "node:fs"
import { createServer } from "node:http"
import { extname, resolve } from "node:path"
import { chromium } from "playwright"

// This script writes and deletes dedicated test data. Run only against a
// disposable local database and upload directory, never through production.
const base = new URL(process.env.BASE_URL || "http://127.0.0.1:3012")
if (!["127.0.0.1", "localhost", "[::1]"].includes(base.hostname) || process.env.MYSHOP_TEST_ISOLATED !== "1") {
  throw new Error("A local disposable environment and MYSHOP_TEST_ISOLATED=1 are required")
}
const adminUser = process.env.MYSHOP_TEST_ADMIN_USER
const adminPassword = process.env.MYSHOP_TEST_ADMIN_PASSWORD
if (!adminUser || !adminPassword) throw new Error("Set MYSHOP_TEST_ADMIN_USER and MYSHOP_TEST_ADMIN_PASSWORD")
const subsiteUser = process.env.MYSHOP_TEST_SUBSITE_USER
const subsitePassword = process.env.MYSHOP_TEST_SUBSITE_PASSWORD
if (!process.env.MYSHOP_TEST_IMAGE || !process.env.MYSHOP_TEST_IMAGE_2) throw new Error("Set MYSHOP_TEST_IMAGE and MYSHOP_TEST_IMAGE_2 to the isolated JPG and PNG samples")
function makeSample(path, index) {
  const imagePath = resolve(path)
  if (!existsSync(imagePath)) throw new Error(`Test image ${index} does not exist`)
  const extension = extname(imagePath).toLowerCase()
  const mime = ({ ".png":"image/png", ".jpg":"image/jpeg", ".jpeg":"image/jpeg", ".webp":"image/webp", ".avif":"image/avif", ".heic":"image/heic", ".heif":"image/heif" })[extension]
  if (!mime) throw new Error(`Unsupported test image extension: ${extension}`)
  return { name:`myshop-editor-${Date.now()}[${index}]${extension}`, mimeType:mime, buffer:readFileSync(imagePath) }
}
const samples = [makeSample(process.env.MYSHOP_TEST_IMAGE, 1), makeSample(process.env.MYSHOP_TEST_IMAGE_2, 2)]
assert.equal(samples[0].mimeType, "image/jpeg", "The first sample must be the representative JPG")
assert.equal(samples[1].mimeType, "image/png", "The second sample must be the representative PNG")
let externalImageRequests = 0
const externalImageServer = createServer((req, res) => {
  if (new URL(req.url, "http://127.0.0.1").pathname !== "/image") { res.writeHead(404); res.end(); return }
  externalImageRequests++
  res.writeHead(200, { "Content-Type":samples[0].mimeType, "Cache-Control":"public, max-age=3600" })
  res.end(samples[0].buffer)
})
await new Promise(resolve => externalImageServer.listen(0, "127.0.0.1", resolve))
const externalImageUrl = `http://127.0.0.1:${externalImageServer.address().port}/image?name=a(b`
const chrome = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
const browser = await chromium.launch({ headless: true, executablePath: existsSync(chrome) ? chrome : undefined })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const page = await context.newPage()
page.setDefaultTimeout(30000)
const errors = []
const dependencies = []
const rateLimits = []
const apiRequests = []
let productCreateRequests = 0
function recordApiRequest(method, url) {
  const parsed = new URL(url)
  if (parsed.origin === base.origin && parsed.pathname.startsWith("/api/") && !parsed.pathname.startsWith("/api/uploads")) apiRequests.push({ time:Date.now(),method,path:parsed.pathname })
}
function recordRateLimit(method, path, seconds) {
  const now = Date.now()
  rateLimits.push({ method,path,seconds,at_ms:now,observed_client_requests_in_prior_second:apiRequests.filter(item => now - item.time <= 1000).length })
}
page.on("pageerror", error => errors.push(error.message))
page.on("request", request => {
  recordApiRequest(request.method(), request.url())
  if (request.url().includes("vditor-assets") || /unpkg|jsdelivr/i.test(request.url())) dependencies.push(request.url())
  if (new URL(request.url()).pathname === "/api/admin/products" && request.method() === "POST") productCreateRequests++
})
const marker = `编辑器实测-${Date.now()}`
let productId
let assetId
let assetDeleted = false
let reused = false
let secondAssetId
let secondReused = false
let imageMeasurements = []

async function jsonResponse(response) {
  assert.ok(response.ok(), `${response.request().method()} ${new URL(response.url()).pathname}: HTTP ${response.status()}`)
  const body = await response.json()
  assert.equal(body.code, 0, `${new URL(response.url()).pathname}: ${body.message}`)
  return body.data
}
async function login(targetPage, account, password, destination) {
  await targetPage.goto(new URL(`/login?redirect=${encodeURIComponent(destination)}`, base).href)
  await targetPage.locator("#account").fill(account)
  await targetPage.locator("#password").fill(password)
  const loginResponse = targetPage.waitForResponse(response => new URL(response.url()).pathname === "/api/auth/login" && response.request().method() === "POST")
  await targetPage.locator('form button[type="submit"]').click()
  const response = await loginResponse
  const responseText = await response.text()
  let loginBody = {}
  try { loginBody = JSON.parse(responseText) } catch { /* Show only a short non-JSON error below. */ }
  assert.ok(response.ok() && loginBody.code === 0, `Login failed: HTTP ${response.status()}, code ${loginBody.code}, message ${loginBody.message || responseText.slice(0, 200) || "unknown"}`)
  await targetPage.waitForURL(url => url.pathname === destination)
}
async function request(method, path) {
  const csrf = (await context.cookies()).find(cookie => cookie.name === "myshop_csrf")?.value
  for (let attempt = 0; attempt < 4; attempt++) {
    recordApiRequest(method, new URL(path, base).href)
    const response = await context.request.fetch(new URL(path, base).href, {
      method,
      headers: csrf ? { "X-CSRF-Token": csrf } : {},
    })
    if (response.status() === 429 && attempt < 3) {
      const seconds = Math.min(5, Math.max(1, Number(response.headers()["retry-after"]) || 1))
      recordRateLimit(method, path, seconds)
      console.warn(`${method} ${path}: HTTP 429; retrying after ${seconds}s`)
      await new Promise(resolve => setTimeout(resolve, seconds * 1000))
      continue
    }
    assert.ok(response.ok(), `${method} ${path}: HTTP ${response.status()}`)
    const data = await response.json()
    assert.equal(data.code, 0, `${path}: ${data.message}`)
    return data.data
  }
  throw new Error(`${method} ${path}: rate limit retry exhausted`)
}

async function uploadThroughEditor(trigger) {
  for (let attempt = 0; attempt < 4; attempt++) {
    const pending = page.waitForResponse(response => new URL(response.url()).pathname === "/api/upload/image" && response.request().method() === "POST")
    await trigger()
    const response = await pending
    if (response.status() === 429 && attempt < 3) {
      const seconds = Math.min(5, Math.max(1, Number(response.headers()["retry-after"]) || 1))
      recordRateLimit("POST", "/api/upload/image", seconds)
      console.warn(`POST /api/upload/image: HTTP 429; retrying after ${seconds}s`)
      await page.waitForFunction(() => !document.querySelector('[data-testid="markdown-editor"] [role="status"]'))
      await new Promise(resolve => setTimeout(resolve, seconds * 1000))
      continue
    }
    return jsonResponse(response)
  }
  throw new Error("Image upload rate limit retry exhausted")
}

async function searchMedia(container, name) {
  const matches = response => {
    const url = new URL(response.url())
    return url.pathname === "/api/admin/media" && url.searchParams.get("query") === name && response.request().method() === "GET"
  }
  let pending = page.waitForResponse(matches).catch(() => null)
  await container.getByLabel("搜索资源名称").fill(name)
  let response = await pending
  for (let attempt = 0; response?.status() === 429 && attempt < 3; attempt++) {
    const seconds = Math.min(5, Math.max(1, Number(response.headers()["retry-after"]) || 1))
    recordRateLimit("GET", "/api/admin/media", seconds)
    console.warn(`GET /api/admin/media: HTTP 429; retrying after ${seconds}s`)
    await new Promise(resolve => setTimeout(resolve, seconds * 1000))
    pending = page.waitForResponse(matches).catch(() => null)
    await container.getByRole("button", { name:"重试" }).click()
    response = await pending
  }
  assert.ok(response && response.ok(), `Media search failed: HTTP ${response?.status() || "no response"}`)
  const data = await response.json()
  assert.equal(data.code, 0, `Media search: ${data.message}`)
  assert.ok(data.data.items.some(item => item.name === name), "Uploaded image must appear in the real resource list")
  await container.locator("li").filter({ hasText:name }).first().waitFor()
}

async function openAnnouncements(expectedId) {
  await page.goto(new URL("/admin/site-config", base).href)
  await page.waitForTimeout(800)
  for (let attempt = 0; attempt < 4; attempt++) {
    const pending = page.waitForResponse(response => new URL(response.url()).pathname === "/api/admin/announcements" && response.request().method() === "GET").catch(() => null)
    await page.getByRole("tab", { name:"通知管理" }).click()
    const response = await pending
    if (response?.status() === 429 && attempt < 3) {
      const seconds = Math.min(5, Math.max(1, Number(response.headers()["retry-after"]) || 1))
      recordRateLimit("GET", "/api/admin/announcements", seconds)
      console.warn(`GET /api/admin/announcements: HTTP 429; retrying after ${seconds}s`)
      await new Promise(resolve => setTimeout(resolve, seconds * 1000))
      await page.getByRole("tab", { name:"基本信息" }).click()
      continue
    }
    assert.ok(response && response.ok(), `Announcement list failed: HTTP ${response?.status() || "no response"}`)
    const result = await response.json()
    assert.equal(result.code, 0, `Announcement list: ${result.message}`)
    if (expectedId) assert.ok(result.data.announcements.some(item => item.id === expectedId), `Announcement ${expectedId} is absent from the real list`)
    return
  }
  throw new Error("Announcement list rate limit retry exhausted")
}

async function measureProductImage(id, uploaded, sample) {
  assert.ok(uploaded.clear_url, "The image upload must provide a clear version")
  const displayUrl = new URL(uploaded.url, base).href
  const clearUrl = new URL(uploaded.clear_url, base).href
  assert.notEqual(clearUrl, displayUrl, "Display and clear versions must use distinct URLs")
  const client = await browser.newContext({ viewport:{ width:1440, height:900 } })
  const storefront = await client.newPage()
  const imageRequests = []
  const imageResponses = []
  storefront.on("pageerror", error => errors.push(error.message))
  storefront.on("request", req => { recordApiRequest(req.method(), req.url()); if ([displayUrl, clearUrl].includes(req.url())) imageRequests.push(req.url()) })
  storefront.on("response", res => { if ([displayUrl, clearUrl].includes(res.url())) imageResponses.push({ url:res.url(), status:res.status() }) })
  storefront.on("requestfailed", req => { if ([displayUrl, clearUrl].includes(req.url())) imageResponses.push({ url:req.url(), error:req.failure()?.errorText }) })
  const scrollStable = async locator => {
    let lastError
    for (let attempt = 0; attempt < 4; attempt++) {
      try {
        await locator.waitFor({ state:"visible",timeout:5000 })
        await locator.scrollIntoViewIfNeeded({ timeout:5000 })
        return
      } catch (error) { lastError = error; await storefront.waitForTimeout(250) }
    }
    throw lastError
  }
  try {
    const displayResponse = storefront.waitForResponse(response => response.url() === displayUrl).catch(() => null)
    const productUrl = new URL(`/product/${id}`, base).href
    await storefront.goto(productUrl)
    const detail = storefront.locator(".product-description-content .prose").filter({ hasText:"正文第一行" }).first()
    await scrollStable(detail)
    const image = detail.locator(`img[src="${uploaded.url}"]`).first()
    await image.waitFor({ state:"attached" })
    await scrollStable(image)
    const response = await displayResponse
    assert.ok(response, `No display response: ${JSON.stringify(imageResponses)}`)
    assert.ok(response.ok(), `Display image returned HTTP ${response.status()}`)
    await image.waitFor()
    await storefront.waitForFunction(url => performance.getEntriesByName(url).some(entry => entry.responseEnd > 0), displayUrl)
    const responseBytes = (await response.body()).length
    const firstTiming = await storefront.evaluate(url => {
      const entry = performance.getEntriesByName(url).at(-1)
      return { encodedBodySize:entry.encodedBodySize, decodedBodySize:entry.decodedBodySize, transferSize:entry.transferSize }
    }, displayUrl)
    const dimensions = await image.evaluate(element => ({ width:element.naturalWidth,height:element.naturalHeight,currentSrc:element.currentSrc }))
    const clearBeforeClick = imageRequests.filter(url => url === clearUrl).length
    assert.equal(clearBeforeClick, 0, "The clear image must not be requested before opening the viewer")
    assert.ok(await detail.locator("br").count() >= 1, "Saved single line breaks must render")
    assert.match(await detail.innerText(), /删除图片后保留正文/)
    const trigger = image.locator("xpath=ancestor::button")
    await scrollStable(trigger)
    const rectangle = await trigger.boundingBox()
    assert.ok(rectangle)
    const viewport = storefront.viewportSize()
    const left = Math.max(0, rectangle.x), right = Math.min(viewport.width, rectangle.x + rectangle.width)
    const top = Math.max(0, rectangle.y), bottom = Math.min(viewport.height, rectangle.y + rectangle.height)
    assert.ok(right > left && bottom > top, "Image trigger must intersect the viewport")
    const scrollBefore = await storefront.evaluate(() => window.scrollY)
    const clearResponse = storefront.waitForResponse(result => result.url() === clearUrl).catch(() => null)
    // Mouse coordinates avoid an implicit second Playwright scroll between
    // measuring the page position and the actual click on a very tall image.
    await storefront.mouse.click((left + right) / 2, (top + bottom) / 2)
    const viewer = storefront.locator(".product-image-viewer")
    await viewer.locator("img").waitFor()
    const clear = await clearResponse
    assert.ok(clear && clear.ok(), `Clear image request failed: ${JSON.stringify(imageResponses)}`)
    const clearBytes = (await clear.body()).length
    assert.ok(imageRequests.includes(clearUrl), "Opening the viewer must request the clear image")
    await storefront.keyboard.press("Escape")
    await viewer.waitFor({ state:"hidden" })
    await storefront.waitForTimeout(100)
    const focusRestored = await trigger.evaluate(element => document.activeElement === element)
    const scrollAfter = await storefront.evaluate(() => window.scrollY)
    assert.ok(focusRestored && Math.abs(scrollAfter - scrollBefore) < 2, `Viewer focus=${focusRestored}, scroll ${scrollBefore}→${scrollAfter}`)
    // Keep navigation paced like a reader; automated bursts otherwise share
    // the API's normal per-second IP bucket with all server-rendered requests.
    await storefront.waitForTimeout(1200)
    await storefront.goto("about:blank")
    await storefront.goto(productUrl)
    const secondDetail = storefront.locator(".product-description-content .prose").filter({ hasText:"正文第一行" }).first()
    await secondDetail.waitFor()
    // The server-rendered storefront is replaced once during hydration.
    await storefront.waitForTimeout(500)
    await scrollStable(secondDetail)
    const secondImage = secondDetail.locator(`img[src="${uploaded.url}"]`).first()
    await secondImage.waitFor({ state:"attached" })
    await scrollStable(secondImage)
    await secondImage.waitFor()
    await storefront.waitForFunction(url => performance.getEntriesByName(url).some(entry => entry.responseEnd > 0), displayUrl)
    const secondTiming = await storefront.evaluate(url => {
      const entry = performance.getEntriesByName(url).at(-1)
      return { encodedBodySize:entry.encodedBodySize, decodedBodySize:entry.decodedBodySize, transferSize:entry.transferSize }
    }, displayUrl)
    assert.equal(secondTiming.transferSize, 0, "A second navigation must reuse the cached display image")
    return {
      filename:sample.name, mime:sample.mimeType, input_bytes:sample.buffer.length, processor_original_bytes:uploaded.original_size, processor_display_bytes:uploaded.stored_size,
      display_response_body_bytes:responseBytes, display_cache_control:response.headers()["cache-control"], display_dimensions:dimensions,
      first_timing:firstTiming, second_timing:secondTiming,
      clear_requested_before_click:clearBeforeClick, clear_response_body_bytes:clearBytes, focus_restored:focusRestored,
      scroll_before:scrollBefore, scroll_after:scrollAfter,
    }
  } catch (error) {
    const body = await storefront.locator("body").innerText().catch(() => "")
    console.error("Image network diagnostic:", JSON.stringify({ displayUrl, clearUrl, imageRequests, imageResponses, pageUrl:storefront.url(), pageErrors:errors, body:body.slice(0, 600) }))
    throw error
  } finally { await client.close() }
}

try {
  await login(page, adminUser, adminPassword, "/admin/products/new")
  await page.getByTestId("product-title-input").fill(marker)
  const category = page.getByTestId("product-category-select")
  await page.waitForFunction(() => [...document.querySelectorAll('[data-testid="product-category-select"] option')].some(option => option.value))
  const categoryValue = await category.locator("option").evaluateAll(options => options.find(option => option.value)?.value)
  assert.ok(categoryValue, "A real product category is required")
  await category.selectOption(categoryValue)
  await page.getByTestId("product-tab-pricing").click()
  const prices = page.getByTestId("product-tab-panel-pricing").locator('tbody tr').first().locator('input[type="number"]')
  await prices.nth(0).fill("1")
  await prices.nth(1).fill("2")
  await page.getByTestId("product-tab-details").click()
  const editor = page.getByTestId("markdown-editor")
  const text = editor.locator('.vditor-wysiwyg [contenteditable="true"]')
  await text.waitFor()
  await text.click()
  await page.keyboard.insertText("正文第一行")
  await page.keyboard.press("Shift+Enter")
  await page.keyboard.insertText("段内第二行")
  await page.keyboard.press("Enter")
  await page.keyboard.insertText("删除图片后保留正文")
  let releaseFirstResponse
  let announceFirstResponse
  let fetchedFirstResponse
  const delayFirstUploadResponse = async route => {
    const response = await route.fetch()
    await new Promise(resolve => { releaseFirstResponse = resolve; announceFirstResponse(response.status()) })
    await route.fulfill({ response })
  }
  await page.route("**/api/upload/image", delayFirstUploadResponse)
  const uploaded = await uploadThroughEditor(async () => {
    fetchedFirstResponse = new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error("The real image upload did not reach the server")), 30000)
      announceFirstResponse = status => { clearTimeout(timer); resolve(status) }
    })
    await editor.locator('input[type="file"]').setInputFiles(samples[0])
    const status = await fetchedFirstResponse
    try {
      if (status === 200) {
        await page.getByText("图片上传中（1）").waitFor()
        assert.equal(await page.getByRole("button", { name:"保存并完成" }).isDisabled(), true, "Save must wait for the actual upload response")
        await page.keyboard.insertText("上传期间继续输入")
        await page.getByTestId("product-form").evaluate(form => form.requestSubmit())
        await page.waitForTimeout(150)
        assert.equal(productCreateRequests, 0, "A submit event must not save while the upload is pending")
      }
    } finally { releaseFirstResponse() }
  })
  await page.unroute("**/api/upload/image", delayFirstUploadResponse)
  assert.ok(uploaded.asset_id && uploaded.url, JSON.stringify(uploaded))
  assetId = uploaded.asset_id
  reused = Boolean(uploaded.reused)
  assert.equal(reused, false, "Use an image not already present in this disposable database")
  await page.waitForFunction(() => !document.querySelector('[data-testid="markdown-editor"] [role="status"]'))
  await text.locator(`img[src="${uploaded.url}"]`).waitFor()
  const secondUploaded = await uploadThroughEditor(() => editor.locator('input[type="file"]').setInputFiles(samples[1]))
  assert.ok(secondUploaded.asset_id && secondUploaded.url, JSON.stringify(secondUploaded))
  secondAssetId = secondUploaded.asset_id
  secondReused = Boolean(secondUploaded.reused)
  assert.equal(secondReused, false, "Use a PNG not already present in this disposable database")
  console.log("TEST_ASSETS", JSON.stringify({ jpg:assetId, png:secondAssetId }))
  await page.waitForFunction(() => !document.querySelector('[data-testid="markdown-editor"] [role="status"]'))
  await text.locator(`img[src="${secondUploaded.url}"]`).waitFor()
  await editor.getByRole("button", { name:"图片链接" }).click()
  await editor.getByLabel("图片链接").fill(externalImageUrl)
  await editor.getByRole("button", { name:"插入",exact:true }).click()
  await text.locator(`img[src="${externalImageUrl.replaceAll("(", "%28")}"]`).waitFor()
  await page.waitForFunction(() => [...document.querySelectorAll('[data-testid="markdown-editor"] img')].some(image => image.src.includes("/image?name=a%28b") && image.complete && image.naturalWidth > 0))
  assert.ok(externalImageRequests > 0, "The explicit external image URL must load directly")
  const reuseResults = []
  for (const mode of ["paste", "drop"]) {
    const data = { bytes:samples[0].buffer.toString("base64"), name:`${mode}[1].jpg`, type:samples[0].mimeType, mode }
    const reusedUpload = await uploadThroughEditor(() => text.evaluate((element, value) => {
      const transfer = new DataTransfer()
      transfer.items.add(new File([Uint8Array.from(atob(value.bytes), char => char.charCodeAt(0))], value.name, { type:value.type }))
      const event = value.mode === "paste"
        ? new ClipboardEvent("paste", { bubbles:true,cancelable:true,clipboardData:transfer })
        : new DragEvent("drop", { bubbles:true,cancelable:true,dataTransfer:transfer })
      element.dispatchEvent(event)
    }, data))
    assert.equal(reusedUpload.reused, true, `${mode} must reuse the already uploaded bytes`)
    assert.equal(reusedUpload.asset_id, assetId)
    reuseResults.push({ mode, asset_id:reusedUpload.asset_id, reused:reusedUpload.reused })
    await page.waitForFunction(() => !document.querySelector('[data-testid="markdown-editor"] [role="status"]'))
  }
  await page.waitForTimeout(900)
  await text.evaluate(element => {
    const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT)
    while (walker.nextNode()) {
      const node = walker.currentNode
      const at = node.textContent.indexOf("上传期间继续输入")
      if (at < 0) continue
      const range = document.createRange(); range.setStart(node, at + "上传期间继续输入".length); range.collapse(true)
      element.focus(); const selection = window.getSelection(); selection.removeAllRanges(); selection.addRange(range)
      return
    }
    throw new Error("Could not locate the text typed during upload")
  })
  await page.keyboard.insertText("撤销重做回读")
  await page.waitForTimeout(900)
  await editor.locator('button[data-type="undo"]').click()
  assert.ok(!(await text.innerText()).includes("撤销重做回读"), "Undo must remove the latest edit")
  await editor.locator('button[data-type="redo"]').click()
  assert.ok((await text.innerText()).includes("撤销重做回读"), "Redo must restore the edit")
  const createResponse = page.waitForResponse(response => new URL(response.url()).pathname === "/api/admin/products" && response.request().method() === "POST")
  await page.getByRole("button", { name: "保存并完成" }).click()
  const created = await jsonResponse(await createResponse)
  productId = created.id
  assert.ok(productId)
  console.log("TEST_PRODUCT", productId)
  await page.waitForURL(url => url.pathname === "/admin/products")

  await page.goto(new URL(`/admin/products/${productId}/edit`, base).href)
  await page.getByTestId("product-tab-details").click()
  await page.getByTestId("markdown-editor").locator('.vditor-wysiwyg [contenteditable="true"]').waitFor()
  assert.match(await page.getByTestId("markdown-editor").innerText(), /正文第一行/)
  assert.match(await page.getByTestId("markdown-editor").innerText(), /段内第二行/)
  const saved = await request("GET", `/api/admin/products/${productId}`)
  assert.match(saved.detail_md, /正文第一行\n段内第二行/)
  assert.ok(saved.detail_md.includes(uploaded.url))
  assert.ok(saved.detail_md.includes(secondUploaded.url))
  assert.ok(saved.detail_md.includes("上传期间继续输入"))
  assert.ok(saved.detail_md.includes("撤销重做回读"), "Redo must survive saving and real API readback")
  assert.ok(saved.detail_md.indexOf(uploaded.url) < saved.detail_md.indexOf("上传期间继续输入"), "The uploaded image must stay at its placeholder before later typing")
  assert.ok(saved.detail_md.includes(externalImageUrl.replaceAll("(", "%28")))
  assert.ok((saved.detail_md.split(uploaded.url).length - 1) >= 3, "File, clipboard and drop insertions must all be saved")

  const jpgMeasurement = await measureProductImage(productId, uploaded, samples[0])
  console.log("IMAGE_MEASUREMENT", JSON.stringify(jpgMeasurement))
  const pngMeasurement = await measureProductImage(productId, secondUploaded, samples[1])
  console.log("IMAGE_MEASUREMENT", JSON.stringify(pngMeasurement))
  imageMeasurements = [jpgMeasurement, pngMeasurement]

  await openAnnouncements()
  const announcementEditor = page.getByTestId("markdown-editor")
  const announcementText = announcementEditor.locator('.vditor-wysiwyg [contenteditable="true"]')
  await announcementText.waitFor()
  const announcementForm = announcementEditor.locator("xpath=ancestor::form")
  await announcementForm.getByRole("textbox", { name:"标题（可选）",exact:true }).fill(marker)
  await announcementText.click()
  await page.keyboard.insertText("公告第一行")
  await page.keyboard.press("Shift+Enter")
  await page.keyboard.insertText("图片删除后保留公告")
  await announcementEditor.getByRole("button", { name: "选择资源" }).click()
  const picker = page.getByRole("dialog", { name: "选择资源" })
  await searchMedia(picker, samples[0].name)
  const mediaCard = picker.locator("li").filter({ hasText:samples[0].name }).first()
  await mediaCard.getByRole("button", { name: "使用图片" }).click()
  assert.ok(await announcementText.locator(`img[src="${uploaded.url}"]`).count())
  const announcementResponse = page.waitForResponse(response => new URL(response.url()).pathname === "/api/admin/announcements" && response.request().method() === "POST")
  await announcementForm.getByRole("button", { name: "保存草稿", exact: true }).click()
  const announcement = await jsonResponse(await announcementResponse)
  assert.ok(announcement.id)
  await page.getByRole("button", { name:"新建" }).click()
  await page.getByRole("button", { name: marker }).click()
  assert.ok(await announcementText.locator(`img[src="${uploaded.url}"]`).count(), "Announcement image must survive reopening")
  assert.equal(await announcementForm.getByRole("button", { name: "保存草稿", exact: true }).isDisabled(), true, "Loading a record must not mark it dirty")

  const inspection = await request("GET", `/api/admin/media/inspection?filename=${encodeURIComponent(assetId)}`)
  assert.ok(inspection.references.some(ref => ref.owner_type === "PRODUCT" && ref.owner_id === productId && ref.field === "detail_md"))
  assert.ok(inspection.references.some(ref => ref.owner_type === "ANNOUNCEMENT" && ref.owner_id === announcement.id && ref.field === "body"))
  await page.goto(new URL("/admin/media", base).href)
  await searchMedia(page, samples[0].name)
  const card = page.locator("li").filter({ hasText:samples[0].name }).first()
  await card.getByRole("button", { name: "删除", exact: true }).click()
  const dialog = page.locator("dialog[open]")
  await dialog.getByText("商品", { exact: false }).first().waitFor()
  await dialog.getByText("公告", { exact: false }).first().waitFor()
  const deletion = page.waitForResponse(response => new URL(response.url()).pathname === "/api/admin/media" && response.request().method() === "DELETE")
  await dialog.getByRole("button", { name: "确认删除" }).click()
  await jsonResponse(await deletion)
  assetDeleted = true
  const afterDeletion = await request("GET", `/api/admin/products/${productId}`)
  assert.ok(!afterDeletion.detail_md.includes(uploaded.url))
  assert.ok(afterDeletion.detail_md.includes(secondUploaded.url), "Deleting one image must preserve the other image")
  assert.ok(afterDeletion.detail_md.includes("正文第一行\n段内第二行"))
  assert.ok(afterDeletion.detail_md.includes("删除图片后保留正文"))
  const afterAnnouncements = await request("GET", "/api/admin/announcements")
  const afterAnnouncement = afterAnnouncements.announcements.find(item => item.id === announcement.id)
  assert.ok(afterAnnouncement && !afterAnnouncement.body.includes(uploaded.url))
  assert.ok(afterAnnouncement.body.includes("图片删除后保留公告"))

  await openAnnouncements(announcement.id)
  await page.getByRole("button", { name:marker }).click()
  const reopenedAnnouncement = page.getByTestId("markdown-editor").locator('.vditor-wysiwyg [contenteditable="true"]')
  await reopenedAnnouncement.waitFor(); await reopenedAnnouncement.click(); await page.keyboard.press("End"); await page.keyboard.insertText("公告保存回读")
  const announcementUpdate = page.waitForResponse(response => new URL(response.url()).pathname === `/api/admin/announcements/${announcement.id}` && response.request().method() === "PUT")
  await page.getByTestId("markdown-editor").locator("xpath=ancestor::form").getByRole("button", { name:"保存草稿",exact:true }).click()
  await jsonResponse(await announcementUpdate)
  const updatedAnnouncements = await request("GET", "/api/admin/announcements")
  assert.ok(updatedAnnouncements.announcements.find(item => item.id === announcement.id)?.body.includes("公告保存回读"))

  await page.goto(new URL(`/admin/products/${productId}/edit`, base).href)
  await page.getByTestId("product-tab-details").click()
  const reedit = page.getByTestId("markdown-editor").locator('.vditor-wysiwyg [contenteditable="true"]')
  await reedit.waitFor(); await reedit.click(); await page.keyboard.press("End"); await page.keyboard.insertText("保存回读")
  const updateResponse = page.waitForResponse(response => new URL(response.url()).pathname === `/api/admin/products/${productId}` && response.request().method() === "PUT")
  await page.getByRole("button", { name: "保存并完成" }).click()
  await jsonResponse(await updateResponse)
  const reread = await request("GET", `/api/admin/products/${productId}`)
  assert.ok(reread.detail_md.includes("保存回读"))

  let subsite = "not configured"
  if (subsiteUser && subsitePassword) {
    const userContext = await browser.newContext({ viewport: { width:390, height:844 } })
    try {
      const userPage = await userContext.newPage()
      const unauthorizedCalls = []
      userPage.on("request", req => { recordApiRequest(req.method(), req.url()); if (/\/api\/(?:admin\/media|upload\/image)/.test(req.url())) unauthorizedCalls.push(req.url()) })
      userPage.on("response", response => {
        if (response.status() === 429) recordRateLimit(response.request().method(), new URL(response.url()).pathname, Number(response.headers()["retry-after"]) || 1)
      })
      await userPage.waitForTimeout(1200)
      await login(userPage, subsiteUser, subsitePassword, "/my/subsite")
      await userPage.waitForTimeout(1200)
      const retry = userPage.getByRole("button", { name:"重试",exact:true })
      if (await retry.isVisible().catch(() => false)) await retry.click()
      const subEditor = userPage.getByTestId("markdown-editor")
      await subEditor.locator('.vditor-wysiwyg [contenteditable="true"]').waitFor()
      assert.equal(await subEditor.getByRole("button", { name: "选择资源" }).count(), 0)
      assert.equal(await subEditor.getByRole("button", { name: "插入图片" }).count(), 0)
      await userPage.waitForTimeout(1200)
      const denied = await userContext.request.get(new URL("/api/admin/media?page=1&page_size=1", base).href)
      assert.ok([401, 403].includes(denied.status()), `Subsite administrator endpoint returned ${denied.status()}`)
      assert.deepEqual(unauthorizedCalls, [])
      assert.ok(await userPage.locator("body").evaluate(el => el.scrollWidth <= window.innerWidth))
      subsite = "actual account: controls absent; admin media API denied"
    } finally { await userContext.close() }
  }
  assert.ok(!dependencies.some(url => /unpkg|jsdelivr/i.test(url)), dependencies.join("\n"))
  assert.deepEqual(errors, [])
  console.log(JSON.stringify({ product_id:productId, announcement_id:announcement.id, asset_id:assetId,
    original_size:uploaded.original_size, stored_size:uploaded.stored_size, reused,
    image_measurements:imageMeasurements, simulated_clipboard_and_drop:reuseResults, external_image_direct_requests:externalImageRequests, rate_limits:rateLimits,
    asset_deleted:assetDeleted, subsite, dependencies }, null, 2))
} finally {
  if (productId) await request("DELETE", `/api/admin/products/${productId}`).catch(error => console.error("Could not clean test product:", error.message))
  if (assetId && !reused && !assetDeleted) await request("DELETE", `/api/admin/media?filename=${encodeURIComponent(assetId)}`).catch(error => console.error("Could not clean test asset:", error.message))
  if (secondAssetId && !secondReused) await request("DELETE", `/api/admin/media?filename=${encodeURIComponent(secondAssetId)}`).catch(error => console.error("Could not clean second test asset:", error.message))
  await browser.close()
  await new Promise(resolve => externalImageServer.close(resolve))
}
