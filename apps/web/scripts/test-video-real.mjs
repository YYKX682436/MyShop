import assert from "node:assert/strict"
import { existsSync } from "node:fs"
import { resolve } from "node:path"
import { chromium } from "playwright"

// Writes and deletes media: require an explicitly disposable, loopback site.
const base = new URL(process.env.BASE_URL || "http://127.0.0.1:18781")
if (!["127.0.0.1", "localhost", "[::1]"].includes(base.hostname) || process.env.MYSHOP_TEST_ISOLATED !== "1") {
  throw new Error("A local disposable environment and MYSHOP_TEST_ISOLATED=1 are required")
}
const account = process.env.MYSHOP_TEST_ADMIN_USER
const password = process.env.MYSHOP_TEST_ADMIN_PASSWORD
const filePath = resolve(process.env.MYSHOP_TEST_VIDEO || "")
if (!account || !password || !process.env.MYSHOP_TEST_VIDEO || !existsSync(filePath)) throw new Error("Set test administrator credentials and MYSHOP_TEST_VIDEO")
const filename = filePath.split(/[\\/]/).pop()
const chrome = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
const browser = await chromium.launch({ headless: true, executablePath: existsSync(chrome) ? chrome : undefined })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await context.grantPermissions(["clipboard-read", "clipboard-write"], { origin: base.origin })
const page = await context.newPage()
const uploader = page.getByTestId("video-upload")
page.setDefaultTimeout(30000)
const ids = new Set()
const protocol = []
page.on("response", response => {
  if (new URL(response.url()).pathname.startsWith("/api/admin/media/tus")) {
    const headers = response.headers()
    protocol.push({ method:response.request().method(), status:response.status(), location:headers.location, tus:headers["tus-resumable"], offset:headers["upload-offset"] })
  }
})
page.on("requestfailed", request => {
  if (new URL(request.url()).pathname.startsWith("/api/admin/media/tus"))
    protocol.push({ method:request.method(), failure:request.failure()?.errorText })
})
let firstId
let firstUrl
let completed = false

async function json(response) {
  assert.ok(response.ok(), `${new URL(response.url()).pathname}: HTTP ${response.status()}`)
  const body = await response.json()
  assert.equal(body.code, 0, body.message)
  return body.data
}
async function request(method, path) {
  const csrf = (await context.cookies()).find(cookie => cookie.name === "myshop_csrf")?.value
  return context.request.fetch(new URL(path, base).href, { method, headers: csrf ? { "X-CSRF-Token": csrf } : {} })
}
async function state(id) { return json(await request("GET", `/api/admin/media/video-sessions/${id}`)) }
async function waitReady(id) {
  const deadline = Date.now() + 240000
  while (Date.now() < deadline) {
    const value = await state(id)
    if (value.status === "READY") return value
    if (value.status === "FAILED") throw new Error(`Video processing failed: ${value.error}`)
    await new Promise(resolve => setTimeout(resolve, 1500))
  }
  throw new Error("Video did not complete processing within 4 minutes")
}
async function waitFailed(id) {
  const deadline = Date.now() + 60000
  while (Date.now() < deadline) {
    const value = await state(id)
    if (value.status === "FAILED") return value
    await new Promise(resolve => setTimeout(resolve, 300))
  }
  throw new Error("Expected video processing failure did not occur")
}
async function selectVideo() {
  await page.waitForFunction(() => [...document.querySelectorAll("button")].some(button => button.textContent?.includes("上传视频") && !button.disabled))
  const created = page.waitForResponse(response => new URL(response.url()).pathname === "/api/admin/media/video-sessions" && response.request().method() === "POST")
  await page.locator('input[type="file"][accept*="video/quicktime"]').setInputFiles(filePath)
  const result = await json(await created)
  ids.add(result.id)
  return result.id
}

try {
  await page.goto(new URL("/login?redirect=%2Fadmin%2Fmedia", base).href)
  await page.locator("#account").fill(account)
  await page.locator("#password").fill(password)
  await page.locator('form button[type="submit"]').click()
  await page.waitForURL(url => url.pathname === "/admin/media")
  await page.getByRole("button", { name: "上传视频" }).waitFor()

  // Let one full 8 MiB PATCH finish, pause the second, then reload and select
  // the same local file. HEAD must report the persisted nonzero offset.
  let completedPatch = false
  const observePatch = response => {
    if (response.request().method() === "PATCH" && response.status() === 204
        && Number(response.headers()["upload-offset"]) >= 8 * 1024 * 1024) completedPatch = true
  }
  page.on("response", observePatch)
  let unblock
  let reportBlocked
  const blocked = new Promise(resolve => { reportBlocked = resolve })
  const hold = new Promise(resolve => { unblock = resolve })
  const intercept = async route => {
    if (route.request().method() === "PATCH" && completedPatch) {
      reportBlocked()
      await hold
      await route.continue().catch(() => {})
    } else await route.continue()
  }
  await page.route("**/api/admin/media/tus/*", intercept)
  firstId = await selectVideo()
  let blockTimer
  try {
    await Promise.race([blocked, new Promise((_, reject) => { blockTimer = setTimeout(() => reject(new Error("Second chunk did not start")), 30000) })])
  } finally { clearTimeout(blockTimer) }
  await uploader.getByRole("button", { name: "暂停" }).click()
  unblock()
  await uploader.getByText("已暂停", { exact: true }).waitFor()
  await page.unroute("**/api/admin/media/tus/*", intercept)
  page.off("response", observePatch)
  await page.reload()
  await page.getByRole("button", { name: "上传视频" }).waitFor()
  let injectedRateLimit = false
  let injectedServerFailure = false
  const retryIntercept = async route => {
    if (!injectedRateLimit && route.request().method() === "HEAD") {
      injectedRateLimit = true
      await route.fulfill({ status: 429, headers: { "Retry-After": "1" } })
    } else if (!injectedServerFailure && route.request().method() === "HEAD") {
      injectedServerFailure = true
      await route.fulfill({ status: 503 })
    } else await route.continue()
  }
  await page.route("**/api/admin/media/tus/*", retryIntercept)
  const resumedHead = page.waitForResponse(response => response.ok() && response.request().method() === "HEAD" && new URL(response.url()).pathname.startsWith("/api/admin/media/tus/"))
  await page.locator('input[type="file"][accept*="video/quicktime"]').setInputFiles(filePath)
  const head = await resumedHead
  const offset = Number(head.headers()["upload-offset"])
  assert.ok(injectedRateLimit && injectedServerFailure && head.ok() && offset >= 8 * 1024 * 1024, `Expected retries and persisted offset, got ${head.status()} / ${offset}`)
  await page.unroute("**/api/admin/media/tus/*", retryIntercept)
  const ready = await waitReady(firstId)
  assert.equal(ready.reused, false)
  assert.ok(ready.url)
  firstUrl = ready.url

  // Anonymous file responses must carry usable length, MIME and range status.
  const direct = new URL(firstUrl, base)
  const headFile = await fetch(direct, { method: "HEAD" })
  assert.equal(headFile.status, 200)
  assert.match(headFile.headers.get("content-type") || "", /^video\/mp4/i)
  const fileSize = Number(headFile.headers.get("content-length"))
  assert.ok(fileSize > 0)
  const full = await fetch(direct)
  assert.equal(full.status, 200)
  assert.match(full.headers.get("content-type") || "", /^video\/mp4/i)
  assert.equal(Number(full.headers.get("content-length")), fileSize)
  await full.body?.cancel()
  const range = await fetch(direct, { headers: { Range: "bytes=0-127" } })
  assert.equal(range.status, 206)
  assert.equal((await range.arrayBuffer()).byteLength, 128)
  assert.equal(Number(range.headers.get("content-length")), 128)
  assert.equal(range.headers.get("content-range"), `bytes 0-127/${fileSize}`)
  const invalidRange = await fetch(direct, { headers: { Range: `bytes=${fileSize + 1}-` } })
  assert.equal(invalidRange.status, 416)
  assert.equal(invalidRange.headers.get("content-range"), `bytes */${fileSize}`)

  const previewButton = page.getByRole("button", { name: `预览资源: ${filename}`, disabled: false }).first()
  const card = previewButton.locator("xpath=ancestor::li")
  await card.getByRole("button", { name: "复制链接" }).click()
  assert.equal(await page.evaluate(() => navigator.clipboard.readText()), direct.href)
  await previewButton.click()
  const video = page.locator('dialog video, [role="dialog"] video').first()
  await video.waitFor()
  const playback = await video.evaluate(async element => {
    element.muted = true
    await element.play()
    if (!Number.isFinite(element.duration)) await new Promise((resolve, reject) => { element.addEventListener("loadedmetadata", resolve, { once: true }); element.addEventListener("error", reject, { once: true }) })
    const target = Math.min(4, element.duration / 2)
    element.currentTime = target
    await new Promise((resolve, reject) => { element.addEventListener("seeked", resolve, { once: true }); element.addEventListener("error", reject, { once: true }); setTimeout(() => reject(new Error("seek timed out")), 15000) })
    const result = { duration: element.duration, currentTime: element.currentTime, readyState: element.readyState }
    element.pause()
    return result
  })
  assert.ok(playback.duration >= 8 && playback.currentTime > 1 && playback.readyState >= 2, JSON.stringify(playback))
  await page.keyboard.press("Escape")

  // The same source bytes reuse the ready asset. A deletion followed by the
  // same bytes must instead create a new address, never revive the old one.
  await page.getByRole("button", { name: "上传视频" }).waitFor({ state: "visible" })
  const duplicateId = await selectVideo()
  const duplicate = await waitReady(duplicateId)
  assert.equal(duplicate.reused, true)
  assert.equal(duplicate.id, firstId)
  assert.equal(duplicate.url, firstUrl)
  // Let the UI observe reuse before deleting its target; otherwise this
  // deliberately out-of-band API deletion races the UI's status poll.
  await page.waitForFunction(() => [...document.querySelectorAll("button")].some(button => button.textContent?.includes("上传视频") && !button.disabled))
  const removed = await request("DELETE", `/api/admin/media?filename=${encodeURIComponent(firstId)}`)
  assert.equal((await json(removed)) ?? null, null)
  ids.delete(firstId)
  assert.equal((await fetch(direct, { method: "HEAD" })).status, 404)
  const reuploadedId = await selectVideo()
  const reuploaded = await waitReady(reuploadedId)
  assert.equal(reuploaded.reused, false)
  assert.notEqual(reuploaded.id, firstId)
  assert.notEqual(reuploaded.url, firstUrl)

  let releaseCancel
  let reportCancel
  const cancelBlocked = new Promise(resolve => { reportCancel = resolve })
  const cancelHold = new Promise(resolve => { releaseCancel = resolve })
  const cancelIntercept = async route => {
    if (route.request().method() === "PATCH") {
      reportCancel(); await cancelHold; await route.continue().catch(() => {})
    } else await route.continue()
  }
  await page.route("**/api/admin/media/tus/*", cancelIntercept)
  const cancelledId = await selectVideo()
  let cancelTimer
  try {
    await Promise.race([cancelBlocked, new Promise((_, reject) => { cancelTimer = setTimeout(() => reject(new Error("Cancellation chunk did not start")), 60000) })])
  } finally { clearTimeout(cancelTimer) }
  const cancelResponse = page.waitForResponse(response => new URL(response.url()).pathname === `/api/admin/media/video-sessions/${cancelledId}` && response.request().method() === "DELETE")
  await uploader.getByRole("button", { name: "取消", exact: true }).click()
  await json(await cancelResponse)
  releaseCancel()
  await page.unroute("**/api/admin/media/tus/*", cancelIntercept)
  assert.equal((await request("GET", `/api/admin/media/video-sessions/${cancelledId}`)).status(), 404)
  assert.equal((await fetch(new URL(`/api/uploads/videos/${cancelledId}.mp4`, base), { method: "HEAD" })).status, 404)
  ids.delete(cancelledId)

  const failedCreation = page.waitForResponse(response => new URL(response.url()).pathname === "/api/admin/media/video-sessions" && response.request().method() === "POST")
  await page.locator('input[type="file"][accept*="video/quicktime"]').setInputFiles({ name: "broken.mp4", mimeType: "video/mp4", buffer: Buffer.from("invalid tutorial video") })
  const failedId = (await json(await failedCreation)).id
  ids.add(failedId)
  await uploader.getByRole("alert").waitFor({ timeout: 60000 })
  assert.equal((await waitFailed(failedId)).status, "FAILED")
  const retry = page.waitForResponse(response => new URL(response.url()).pathname === `/api/admin/media/video-sessions/${failedId}/retry` && response.request().method() === "POST")
  await uploader.getByRole("button", { name: "重试", exact: true }).click()
  await json(await retry)
  assert.equal((await waitFailed(failedId)).status, "FAILED")
  await uploader.getByRole("alert").waitFor({ timeout: 60000 })
  const failedCancel = page.waitForResponse(response => new URL(response.url()).pathname === `/api/admin/media/video-sessions/${failedId}` && response.request().method() === "DELETE")
  await uploader.getByRole("button", { name: "取消", exact: true }).click()
  await json(await failedCancel)
  ids.delete(failedId)
  completed = true
  console.log(JSON.stringify({ resume_offset: offset, original_asset: firstId, reused_asset: duplicate.id,
    reuploaded_asset: reuploaded.id, output_bytes: fileSize, get: full.status, head: headFile.status, range: range.status,
    invalid_range: invalidRange.status, playback, cancelled: true, processing_failure_retry: true }, null, 2))
} finally {
  if (!completed) {
    console.error("tus diagnostic:", JSON.stringify(protocol))
    console.error("uploader:", await uploader.innerText().catch(() => "unavailable"))
  }
  for (const id of ids) {
    const removed = await request("DELETE", `/api/admin/media?filename=${encodeURIComponent(id)}`).catch(() => null)
    if (!removed?.ok()) await request("DELETE", `/api/admin/media/video-sessions/${encodeURIComponent(id)}`).catch(() => {})
  }
  await browser.close()
}
