import assert from "node:assert/strict"
import { existsSync } from "node:fs"
import { chromium } from "playwright"
import { unified } from "unified"
import remarkParse from "remark-parse"
import remarkGfm from "remark-gfm"

// Isolated fixture exercises the actual product form; it does not substitute
// for backend integration or real mobile/IME device testing.
const base = new URL(process.env.BASE_URL ?? "http://localhost:3012")
if (!["localhost", "127.0.0.1"].includes(base.hostname)) throw new Error("Local web server required")
const chrome = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
const browser = await chromium.launch({ headless: true, executablePath: existsSync(chrome) ? chrome : undefined })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
page.setDefaultTimeout(15000)
const ok = data => ({ status: 200, contentType: "application/json", body: JSON.stringify({ code: 0, message: "Success", data }) })
const requests = []
const failures = []
let uploadCount = 0
let releaseUpload
let releaseSourceUpload
let releaseUndoUpload
let releaseFailedUpload
let releasePendingUndo
const releaseMulti = []
let concurrentUploads = 0
let maxConcurrentUploads = 0
let productPayload
let announcementPayload
let optimizationCalls = 0
let mediaAsset = {id:"library",name:"a[1].png",url:"/api/uploads/library.webp",size:500,format:"WEBP",type:"image",status:"ready",usage:["product"],uploaded_at:1,thumbnail_url:null,clear_url:null,original_size:500}
const announcements = [{ id:"announcement-1", title:"旧公告", body:"旧行一\n旧行二\n\n- 清单\n\n![a\\[1\\].png](</api/uploads/legacy.png?x=a(b)>)\n\n[![odd\\[.png](<https://example.org/image?file=a(b&x=1>)](https://example.org/page)\n\n![left\\[.png](</api/uploads/legacy.png?side=1>)![right\\].png](</api/uploads/legacy.png?side=2>)\n\n![reference\\[.png][photo-ref]\n\n[photo-ref]: </api/uploads/legacy.png?x=1(2)> \"说明\"\n[photo-ref]: </api/uploads/wrong.png> \"错误\"\n\n![unsafe](javascript:alert) ![unsafe2](<javascript:alert(1)>)\n\n```mermaid\nA-->B\n```\n\n```plantuml\nAlice -> Bob\n```\n\n| 列一 | 列二 |\n| --- | --- |\n| 单元 | 另一个 |", status:"DRAFT", published_at:null, publish_version:0, audience_type:"ALL", audience_level_ids:[] }]
const image = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScL8WQAAAABJRU5ErkJggg==", "base64")
try {
  page.on("request", request => { requests.push(request.url()) })
  page.on("pageerror", error => { failures.push(error.message) })
  await page.route("**/api/**", async route => {
    const url = new URL(route.request().url()), path = url.pathname, method = route.request().method()
    if (path === "/api/user/profile") return route.fulfill(ok({ id: "admin", username: "admin", role: "SUPER_ADMIN", balance: 0 }))
    if (path === "/api/site/config" || path === "/api/admin/attention-summary") return route.fulfill(ok({}))
    if (path === "/api/admin/categories") return route.fulfill(ok([{ id: "category", name: "测试分类", parent_id: null, children: [] }]))
    if (path === "/api/subsite/mine") return route.fulfill(ok({ id:"site", name:"分站", enabled:true, user_count:0, favicon_url:null }))
    if (path === "/api/subsite/customers" || path === "/api/subsite/orders") return route.fulfill(ok({ content:[], total_pages:0 }))
    if (path === "/api/subsite/pricing/levels/available") return route.fulfill(ok([]))
    if (path === "/api/subsite/overview") return route.fulfill(ok({ order_count:0, estimated_profit:0, pending_profit:0, settled_profit:0 }))
    if (path === "/api/subsite/announcements" && method === "GET") return route.fulfill(ok({ announcements, levels:[] }))
    if (path === "/api/subsite/announcements/announcement-1" && method === "PUT") {
      announcementPayload = route.request().postDataJSON(); Object.assign(announcements[0], {
        title:announcementPayload.title, body:announcementPayload.body, audience_type:announcementPayload.audienceType,
        audience_level_ids:announcementPayload.audienceLevelIds, status:announcementPayload.publish ? "PUBLISHED" : "DRAFT",
      }); return route.fulfill(ok({ id:"announcement-1" }))
    }
    if (path === "/api/admin/user-levels") return route.fulfill(ok([]))
    if (path === "/api/admin/products/pricing-preview") return route.fulfill(ok({ pricing_matrix: [], warnings: [] }))
    if (path === "/api/upload/image") {
      const number = ++uploadCount
      concurrentUploads++
      maxConcurrentUploads = Math.max(maxConcurrentUploads, concurrentUploads)
      if (number === 1) await new Promise(resolve => { releaseUpload = resolve })
      if (number === 4 || number === 5) await new Promise(resolve => { releaseMulti.push(resolve) })
      if (number === 8) await new Promise(resolve => { releaseSourceUpload = resolve })
      if (number === 9) await new Promise(resolve => { releaseUndoUpload = resolve })
      if (number === 10) await new Promise(resolve => { releaseFailedUpload = resolve })
      if (number === 11) await new Promise(resolve => { releasePendingUndo = resolve })
      try {
        if (number === 10) return await route.fulfill({ status: 500, contentType: "application/json", body: JSON.stringify({ code: 500, message: "fixture upload failure" }) })
        return await route.fulfill(ok({ url: `/api/uploads/editor-${number}.webp`, original_size: image.length, stored_size: image.length, reason: number === 1 ? "color_unspecified" : "FIXTURE" }))
      }
      finally { concurrentUploads-- }
    }
    if (path === "/api/admin/media" && method === "GET") return route.fulfill(ok({ items: [mediaAsset], total:1,page:1,page_size:24 }))
    if (path === "/api/admin/media/library/optimize-avatar" && method === "POST") {
      optimizationCalls++
      mediaAsset = { ...mediaAsset, thumbnail_url:"/api/uploads/avatar-thumb.webp" }
      return route.fulfill(ok({ asset_id:"avatar-old", optimized:1, skipped:0, failed:0, display_bytes_saved:410000, physical_bytes_released:0,
        outcomes:[{user_id:"fixture-user",status:"optimized",reason:null,message:null,before_bytes:500000,after_bytes:90000,url:"/api/uploads/avatar-new.webp"}], skipped_reason:null,message:null }))
    }
    if (path === "/api/admin/products" && method === "POST") {
      productPayload = route.request().postDataJSON()
      return route.fulfill(ok({ id: "product-1", product_no: 1, ...productPayload, specs: [], warnings: [], pricing_matrix: [] }))
    }
    if (path.startsWith("/api/uploads/")) return route.fulfill({ status: 200, contentType: "image/png", body: image })
    return route.fulfill(ok({}))
  })

  await page.goto(new URL("/admin/products/new", base).href)
  await page.getByTestId("product-title-input").fill("编辑器验收")
  await page.getByTestId("product-category-select").selectOption("category")
  await page.getByTestId("product-tab-pricing").click()
  const prices = page.getByTestId("product-tab-panel-pricing").locator('tbody tr').first().locator('input[type="number"]')
  await prices.nth(0).fill("1")
  await prices.nth(1).fill("2")
  await page.getByTestId("product-tab-details").click()
  const editor = page.getByTestId("markdown-editor")
  const wysiwyg = editor.locator('.vditor-wysiwyg [contenteditable="true"]')
  await wysiwyg.waitFor()
  await wysiwyg.click()
  await page.keyboard.type("before")
  // Safari can deliver the IME-confirming keydown after compositionend, with
  // isComposing false but VK_PROCESSKEY (229) still set. Our capture handler
  // must leave that keystroke to the editor/browser instead of inserting a BR.
  const imeEnter = await wysiwyg.evaluate(element => {
    const before = element.querySelectorAll("br").length
    let reachedTarget = false
    let prevented = false
    element.addEventListener("keydown", event => {
      reachedTarget = true
      prevented = event.defaultPrevented
      event.stopImmediatePropagation()
    }, { once: true })
    const dispatched = element.dispatchEvent(new KeyboardEvent("keydown", {
      key: "Enter", keyCode: 229, which: 229, shiftKey: true,
      isComposing: false, bubbles: true, cancelable: true,
    }))
    return { reachedTarget, prevented, dispatched, before, after: element.querySelectorAll("br").length }
  })
  assert.deepEqual(imeEnter, { reachedTarget: true, prevented: false, dispatched: true, before: imeEnter.before, after: imeEnter.before })
  await editor.locator('input[type="file"]').setInputFiles({ name: "a[1].png", mimeType: "image/png", buffer: image })
  await page.getByText("图片上传中（1）").waitFor()
  assert.equal(await page.getByRole("button", { name: "保存并完成" }).isDisabled(), true)
  await page.keyboard.type("after")
  await page.getByTestId("product-tab-basic").click()
  assert.equal(await page.getByTestId("product-tab-details").getAttribute("data-state"), "active")
  await wysiwyg.click()
  await page.keyboard.press("End")
  // Simulate an ongoing composition event; this verifies the deferral logic,
  // not a physical Chinese IME or mobile keyboard.
  await wysiwyg.dispatchEvent("compositionstart", { data: "" })
  await page.keyboard.insertText("中")
  releaseUpload()
  await page.waitForTimeout(200)
  assert.equal(await page.getByRole("button", { name: "保存并完成" }).isDisabled(), true)
  await page.keyboard.insertText("文")
  await wysiwyg.dispatchEvent("compositionend", { data: "中文" })
  await page.getByText("图片上传中（1）").waitFor({ state: "hidden" })
  await page.getByText("图片色彩信息无法识别，已按 sRGB 处理，显示颜色可能略有差异").waitFor()
  const afterUpload = await wysiwyg.innerText()
  assert.ok(afterUpload.includes("before") && afterUpload.includes("after"), afterUpload)
  assert.equal(await wysiwyg.locator('img[src="/api/uploads/editor-1.webp"]').count(), 1)
  await page.keyboard.type("tail")
  const afterComposition = await wysiwyg.innerText()
  assert.ok(afterComposition.includes("after") && afterComposition.includes("中文tail"), `composition and selection must survive upload completion: ${afterComposition}`)
  await editor.getByRole("button", { name: "图片链接" }).click()
  await editor.getByLabel("图片链接").fill("https://example.org/photo?name=a(b&size=1")
  await editor.getByRole("button", { name: "插入", exact: true }).click()
  assert.equal(await wysiwyg.locator('img[src="https://example.org/photo?name=a%28b&size=1"]').count(), 1)
  await editor.getByRole("button", { name: "选择资源" }).click()
  assert.equal(await page.getByRole("dialog", { name:"选择资源" }).getByRole("button", { name:"优化头像" }).count(), 0,
    "the resource picker must not expose avatar optimization")
  await page.getByRole("dialog", { name: "选择资源" }).getByRole("button", { name: "使用图片" }).click()
  assert.equal(await wysiwyg.locator('img[src="/api/uploads/library.webp"]').count(), 1)
  // Clipboard and drop events use browser File/DataTransfer APIs with a real
  // image fixture; no upload is synthesized by directly invoking a helper.
  const imageBase64 = image.toString("base64")
  await wysiwyg.evaluate((element, data) => {
    const transfer = new DataTransfer()
    transfer.items.add(new File([Uint8Array.from(atob(data), char => char.charCodeAt(0))], "paste.png", { type: "image/png" }))
    element.dispatchEvent(new ClipboardEvent("paste", { bubbles: true, cancelable: true, clipboardData: transfer }))
  }, imageBase64)
  await wysiwyg.locator('img[src="/api/uploads/editor-2.webp"]').waitFor()
  await wysiwyg.evaluate((element, data) => {
    const transfer = new DataTransfer()
    transfer.items.add(new File([Uint8Array.from(atob(data), char => char.charCodeAt(0))], "drop.png", { type: "image/png" }))
    element.dispatchEvent(new DragEvent("drop", { bubbles: true, cancelable: true, dataTransfer: transfer }))
  }, imageBase64)
  await wysiwyg.locator('img[src="/api/uploads/editor-3.webp"]').waitFor()
  const imageCount = await wysiwyg.locator("img").count()
  await wysiwyg.evaluate(element => {
    const transfer = new DataTransfer()
    transfer.setData("text/plain", "https://example.org/ordinary-page?x=1")
    element.dispatchEvent(new ClipboardEvent("paste", { bubbles:true, cancelable:true, clipboardData:transfer }))
  })
  assert.equal(await wysiwyg.locator("img").count(), imageCount, "pasting a webpage URL must not insert an image")
  await editor.locator('input[type="file"]').setInputFiles([
    { name:"multi-a.png", mimeType:"image/png", buffer:image },
    { name:"multi-b.png", mimeType:"image/png", buffer:image },
    { name:"multi-c.png", mimeType:"image/png", buffer:image },
    { name:"multi-d.png", mimeType:"image/png", buffer:image },
  ])
  for (let i = 0; i < 50 && releaseMulti.length < 2; i++) await page.waitForTimeout(20)
  assert.equal(releaseMulti.length, 2)
  await page.waitForTimeout(100)
  assert.equal(uploadCount, 5, "four-file batch must start at most two simultaneous uploads")
  releaseMulti.forEach(resolve => resolve())
  await wysiwyg.locator('img[src="/api/uploads/editor-4.webp"]').waitFor()
  await wysiwyg.locator('img[src="/api/uploads/editor-5.webp"]').waitFor()
  await wysiwyg.locator('img[src="/api/uploads/editor-6.webp"]').waitFor()
  await wysiwyg.locator('img[src="/api/uploads/editor-7.webp"]').waitFor()
  assert.ok(maxConcurrentUploads <= 2)
  await wysiwyg.click()
  await page.keyboard.press("End")
  await page.keyboard.press("Enter")
  await page.keyboard.type("paragraph")
  await page.keyboard.press("Shift+Enter")
  await page.keyboard.type("line")
  const linePositions = await wysiwyg.evaluate(el => {
    const p = [...el.querySelectorAll("p")].find(node => node.textContent.includes("paragraph"))
    const text = p.firstChild
    const a = document.createRange(); a.setStart(text, 0); a.setEnd(text, 1)
    const b = document.createRange(); const offset = text.data.indexOf("line"); b.setStart(text, offset); b.setEnd(text, offset + 1)
    return { whitespace: getComputedStyle(p).whiteSpace, a: a.getBoundingClientRect().top, b: b.getBoundingClientRect().top }
  })
  assert.ok(linePositions.b > linePositions.a, JSON.stringify(linePositions))
  await page.waitForTimeout(1000)
  await page.keyboard.insertText("撤销内容")
  await page.waitForTimeout(1000)
  await editor.locator('button[data-type="undo"]').click()
  assert.ok(!(await wysiwyg.innerText()).includes("撤销内容"), "undo must remove the latest edit")
  await editor.locator('button[data-type="redo"]').click()
  assert.ok((await wysiwyg.innerText()).includes("撤销内容"), "redo must restore the latest edit")
  await editor.getByRole("button", { name: "预览", exact: true }).click()
  const previewParagraph = editor.locator(".prose p").filter({ hasText: "paragraph" })
  assert.equal(await previewParagraph.locator("br").count(), 1)
  assert.equal(await editor.locator('.prose a[href="https://example.org/ordinary-page?x=1"]').count(), 1, "a pasted web URL must remain a link")
  await editor.getByRole("button", { name: "编辑", exact: true }).click()
  await editor.locator('button[data-type="edit-mode"]').click()
  await editor.locator('button[data-mode="sv"]').click()
  const source = editor.locator("textarea.vditor-sv")
  await source.waitFor()
  if ((await source.inputValue()).includes("MYSHOPALT")) throw new Error(`placeholder leaked when switching to source: ${await source.inputValue()}`)
  assert.match(await source.inputValue(), /paragraph\nline/)
  await source.focus(); await page.keyboard.press("ControlOrMeta+End")
  await editor.locator('input[type="file"]').setInputFiles({ name:"source[2].png",mimeType:"image/png",buffer:image })
  await page.getByText("图片上传中（1）").waitFor()
  await page.keyboard.type("source-continued")
  releaseSourceUpload()
  await page.getByText("图片上传中（1）").waitFor({ state:"hidden" })
  assert.match(await source.inputValue(), /source-continued/)
  assert.match(await source.inputValue(), /editor-8\.webp/)
  await editor.locator('button[data-type="edit-mode"]').click()
  await editor.locator('button[data-mode="wysiwyg"]').click()
  await wysiwyg.waitFor()
  await wysiwyg.evaluate(element => {
    const range = document.createRange(); range.selectNodeContents(element); range.collapse(false)
    const selection = window.getSelection(); selection?.removeAllRanges(); selection?.addRange(range)
  })
  await page.keyboard.press("Enter"); await page.keyboard.press("Enter")
  await editor.locator('input[type="file"]').setInputFiles({ name:"undo-after-upload.png",mimeType:"image/png",buffer:image })
  await page.getByText("图片上传中（1）").waitFor()
  await page.waitForTimeout(1100) // Vditor 4.0.1 default undoDelay is 800 ms.
  releaseUndoUpload()
  await page.getByText("图片上传中（1）").waitFor({ state:"hidden" })
  await page.waitForTimeout(1100)
  await editor.locator('button[data-type="undo"]').click()
  const afterUploadUndo = await wysiwyg.innerHTML()
  assert.ok(!afterUploadUndo.includes("MYSHOPUPLOAD"), "undo must restore a successful token as its image, not its private marker")
  await editor.locator('button[data-type="redo"]').click()
  const afterUploadRedo = await wysiwyg.innerHTML()
  assert.equal(await wysiwyg.locator('img[src="/api/uploads/editor-9.webp"]').count(), 1,
    "redo after upload completion must restore the completed image, never its private marker")
  assert.ok(!afterUploadRedo.includes("MYSHOPUPLOAD"), "redo must not restore a private upload token")

  await wysiwyg.evaluate(element => {
    const range = document.createRange(); range.selectNodeContents(element); range.collapse(false)
    const selection = window.getSelection(); selection?.removeAllRanges(); selection?.addRange(range)
  })
  await editor.locator('input[type="file"]').setInputFiles({ name:"failed-upload.png",mimeType:"image/png",buffer:image })
  await page.getByText("图片上传中（1）").waitFor()
  for (let i = 0; i < 50 && !releaseFailedUpload; i++) await page.waitForTimeout(20)
  assert.ok(releaseFailedUpload, "failed upload request must be in progress")
  await page.waitForTimeout(1100)
  releaseFailedUpload()
  await page.getByText("图片上传中（1）").waitFor({ state:"hidden" })
  await page.waitForTimeout(900)
  assert.ok(!(await wysiwyg.innerHTML()).includes("MYSHOPUPLOAD"), "failed uploads must disappear from the editor")
  await editor.locator('button[data-type="undo"]').click()
  assert.ok(!(await wysiwyg.innerHTML()).includes("MYSHOPUPLOAD"), "undo after failure must not restore an upload token")
  await editor.locator('button[data-type="redo"]').click()
  assert.ok(!(await wysiwyg.innerHTML()).includes("MYSHOPUPLOAD"), "redo after failure must not restore an upload token")

  await wysiwyg.evaluate(element => {
    const range = document.createRange(); range.selectNodeContents(element); range.collapse(false)
    const selection = window.getSelection(); selection?.removeAllRanges(); selection?.addRange(range)
  })
  await editor.locator('input[type="file"]').setInputFiles({ name:"undo-while-uploading.png",mimeType:"image/png",buffer:image })
  await page.getByText("图片上传中（1）").waitFor()
  for (let i = 0; i < 50 && !releasePendingUndo; i++) await page.waitForTimeout(20)
  assert.ok(releasePendingUndo, "pending undo upload request must be in progress")
  await page.waitForTimeout(1100)
  await editor.locator('button[data-type="undo"]').click()
  assert.equal(await page.getByRole("button", { name: "保存并完成" }).isDisabled(), true, "undo while upload is pending must still protect save")
  releasePendingUndo()
  await page.getByText("图片上传中（1）").waitFor({ state:"hidden" })
  await editor.locator('button[data-type="redo"]').click()
  assert.equal(await wysiwyg.locator('img[src="/api/uploads/editor-11.webp"]').count(), 1,
    "redo after a pending upload finishes must restore its completed image")
  assert.ok(!(await wysiwyg.innerHTML()).includes("MYSHOPUPLOAD"))

  await editor.locator('button[data-type="edit-mode"]').click()
  await editor.locator('button[data-mode="sv"]').click()
  const resolvedSource = editor.locator("textarea.vditor-sv")
  await resolvedSource.waitFor()
  assert.ok(!(await resolvedSource.inputValue()).includes("MYSHOPUPLOAD"), "source output must not contain an upload marker")
  assert.match(await resolvedSource.inputValue(), /editor-9\.webp/)
  assert.match(await resolvedSource.inputValue(), /editor-11\.webp/)
  assert.doesNotMatch(await resolvedSource.inputValue(), /editor-10\.webp/)
  await editor.locator('button[data-type="edit-mode"]').click()
  await editor.locator('button[data-mode="wysiwyg"]').click()
  await wysiwyg.waitFor()

  await wysiwyg.click(); await page.keyboard.press("End")
  await page.waitForTimeout(900)
  await page.keyboard.insertText("不应保存的撤销内容")
  await page.waitForTimeout(900)
  await editor.locator('button[data-type="undo"]').click()
  assert.ok(!(await wysiwyg.innerText()).includes("不应保存的撤销内容"))
  await page.getByRole("button", { name: "保存并完成" }).click()
  await page.waitForURL(url => url.pathname === "/admin/products")
  assert.ok(productPayload, "product save must reach the real page's submit handler")
  assert.ok(!productPayload.detail_md.includes("MYSHOPUPLOAD"), productPayload.detail_md)
  assert.ok(!productPayload.detail_md.includes("MYSHOPCARET"), productPayload.detail_md)
  assert.ok(!productPayload.detail_md.includes("不应保存的撤销内容"), "saving immediately after Undo must use the updated document")
  assert.match(productPayload.detail_md, /before/)
  assert.match(productPayload.detail_md, /after/)
  assert.match(productPayload.detail_md, /paragraph/)
  assert.match(productPayload.detail_md, /line/)
  assert.match(productPayload.detail_md, /paragraph\nline/)
  assert.match(productPayload.detail_md, /editor-1\.webp/)
  assert.match(productPayload.detail_md, /editor-2\.webp/)
  assert.match(productPayload.detail_md, /editor-3\.webp/)
  assert.match(productPayload.detail_md, /editor-4\.webp/)
  assert.match(productPayload.detail_md, /editor-5\.webp/)
  assert.match(productPayload.detail_md, /editor-6\.webp/)
  assert.match(productPayload.detail_md, /editor-7\.webp/)
  assert.match(productPayload.detail_md, /editor-8\.webp/)
  assert.match(productPayload.detail_md, /source-continued/)
  assert.match(productPayload.detail_md, /library\.webp/)
  assert.match(productPayload.detail_md, /a%28b/)
  await page.emulateMedia({ colorScheme: "dark" })
  await page.setViewportSize({ width: 390, height: 844 })
  const beforeSubsite = requests.length
  await page.goto(new URL("/my/subsite", base).href)
  try { await page.getByRole("button", { name: /旧公告/ }).click() }
  catch (error) { console.error("Subsite diagnostic:", page.url(), (await page.locator("body").innerText()).slice(0, 1500), failures, requests.slice(beforeSubsite)); throw error }
  const subEditor = page.getByTestId("markdown-editor")
  const subText = subEditor.locator('.vditor-wysiwyg [contenteditable="true"]')
  await subText.waitFor()
  const subForm = subEditor.locator("xpath=ancestor::form")
  assert.equal(await subForm.getByRole("button", { name: "保存草稿", exact: true }).isDisabled(), true, "initializing old Markdown must not mark it dirty")
  assert.equal(await subText.locator('img[alt="a[1].png"]').count(), 1)
  assert.equal(await subText.locator('a[href="https://example.org/page"] img[alt="odd[.png"]').count(), 1)
  assert.equal(await subText.locator('img[src^="javascript:"]').count(), 0)
  await subEditor.getByRole("button", { name:"预览",exact:true }).click()
  assert.equal(await subEditor.locator('.prose img[alt="a[1].png"]').count(), 1)
  await subEditor.getByRole("button", { name:"编辑",exact:true }).click()
  assert.equal(await subForm.getByRole("button", { name:"保存草稿",exact:true }).isDisabled(), true, "previewing old Markdown must not mark it dirty")
  await subEditor.locator('button[data-type="edit-mode"]').click()
  await subEditor.locator('button[data-mode="sv"]').click()
  await subEditor.locator('textarea.vditor-sv').waitFor()
  await subEditor.locator('button[data-type="edit-mode"]').click()
  await subEditor.locator('button[data-mode="wysiwyg"]').click()
  await subText.waitFor()
  assert.equal(await subForm.getByRole("button", { name: "保存草稿", exact: true }).isDisabled(), true, "changing editor mode must not mark unchanged legacy Markdown dirty")
  assert.equal(await subText.locator('img[alt="a[1].png"]').count(), 1)
  assert.equal(await subText.locator('a[href="https://example.org/page"] img[alt="odd[.png"]').count(), 1)
  assert.equal(await subEditor.getByRole("button", { name: "选择资源" }).count(), 0)
  assert.equal(await subEditor.getByRole("button", { name: "插入图片" }).count(), 0)
  await page.getByRole("button", { name: "新建" }).click()
  await page.getByRole("button", { name: /旧公告/ }).click()
  await subText.waitFor()
  assert.equal(await subForm.getByRole("button", { name: "保存草稿", exact: true }).isDisabled(), true)
  await subText.click()
  const existingSubsiteImages = await subText.locator("img").count()
  await subText.evaluate((element, data) => {
    const transfer = new DataTransfer()
    transfer.items.add(new File([Uint8Array.from(atob(data), char => char.charCodeAt(0))], "paste.png", { type:"image/png" }))
    element.dispatchEvent(new ClipboardEvent("paste", { bubbles:true, cancelable:true, clipboardData:transfer }))
  }, imageBase64)
  assert.equal(await subText.locator("img").count(), existingSubsiteImages, "subsite paste must not embed a data URL or call admin upload")
  await subText.locator("p").first().click()
  await page.keyboard.press("End")
  await page.keyboard.insertText("追加中文")
  const code = subText.locator('[data-type="code-block"] code').first()
  await code.click(); await page.keyboard.press("End"); await page.keyboard.press("Shift+Enter"); await page.keyboard.insertText("C-->D")
  const cell = subText.locator("td").first()
  await cell.click(); await page.keyboard.press("End"); await page.keyboard.press("Shift+Enter")
  await page.getByText("表格单元格不支持换行，请使用新行").waitFor()
  await page.keyboard.insertText("新增单元文字")
  await subForm.getByRole("button", { name: "保存草稿", exact: true }).click()
  await page.waitForTimeout(200)
  assert.ok(announcementPayload?.body.includes("追加中文"), JSON.stringify(announcementPayload))
  assert.ok(!announcementPayload.body.includes("data:image"))
  assert.ok(announcementPayload.body.includes("旧行一\n旧行二"), announcementPayload.body)
  assert.match(announcementPayload.body, /A-->B[^\n]*\nC-->D/)
  assert.match(announcementPayload.body, /新增单元文字/)
  assert.ok(!announcementPayload.body.includes("<br>"), "table editing must not generate HTML breaks that the storefront drops")
  assert.ok(!/MYSHOP(?:ALT|URL)/.test(announcementPayload.body), "conversion placeholders must never be saved")
  assert.ok(!announcementPayload.body.includes("javascript:"), "dangerous image protocols must not survive editing")
  const tree = unified().use(remarkParse).use(remarkGfm).parse(announcementPayload.body)
  const imageNodes = []
  const links = []
  const walk = node => { if (node.type === "image") imageNodes.push(node); if (node.type === "link") links.push(node); node.children?.forEach(walk) }
  walk(tree)
  assert.ok(imageNodes.some(node => node.alt === "a[1].png" && node.url === "/api/uploads/legacy.png?x=a(b)"), announcementPayload.body)
  assert.ok(links.some(node => node.url === "https://example.org/page" && node.children.some(child => child.type === "image" && child.alt === "odd[.png" && child.url === "https://example.org/image?file=a(b&x=1")), announcementPayload.body)
  assert.ok(imageNodes.some(node => node.alt === "left[.png" && node.url === "/api/uploads/legacy.png?side=1"))
  assert.ok(imageNodes.some(node => node.alt === "right].png" && node.url === "/api/uploads/legacy.png?side=2"))
  assert.ok(imageNodes.some(node => node.alt === "reference[.png" && node.url === "/api/uploads/legacy.png?x=1(2)" && node.title === "说明"))
  await page.getByRole("button", { name:"新建" }).click()
  await page.getByRole("button", { name:/旧公告/ }).click()
  await subText.waitFor()
  assert.equal(await subText.locator('img[alt="a[1].png"]').count(), 1)
  assert.equal(await subText.locator('a[href="https://example.org/page"] img[alt="odd[.png"]').count(), 1)
  assert.ok(!requests.slice(beforeSubsite).some(url => /\/api\/(?:admin\/media|upload\/image)/.test(url)), "subsite must not call administrator media APIs")
  assert.ok(await page.locator("body").evaluate(el => el.scrollWidth <= window.innerWidth), "mobile layout must not overflow")
  await subEditor.locator('button[data-type="edit-mode"]').click()
  await subEditor.locator('button[data-mode="sv"]').click()
  await subEditor.locator('textarea.vditor-sv').fill("a".repeat(100_001))
  await subEditor.getByRole("alert").waitFor()
  assert.equal(await subForm.getByRole("button", { name:"保存草稿",exact:true }).isDisabled(), true, "overlength notification content must not be submitted")
  for (const file of ["dist/js/i18n/en_US.js", "dist/js/i18n/zh_CN.js", "dist/js/lute/lute.min.js", "dist/js/icons/ant.js", "dist/css/content-theme/light.css", "dist/css/content-theme/dark.css"]) {
    const response = await page.request.get(new URL(`/vditor-assets/4.0.1/${file}`, base).href)
    assert.equal(response.status(), 200, file)
    assert.match(response.headers()["cache-control"], /immutable/)
  }
  assert.equal((await page.request.get(new URL("/vditor-assets/other/dist/js/lute/lute.min.js", base).href)).status(), 404)
  assert.equal((await page.request.get(new URL("/vditor-assets/4.0.1/package.json", base).href)).status(), 404)
  const allowedAssets = new Set(["dist/js/i18n/en_US.js", "dist/js/i18n/zh_CN.js", "dist/js/lute/lute.min.js", "dist/js/icons/ant.js", "dist/css/content-theme/light.css", "dist/css/content-theme/dark.css"])
  assert.ok(requests.filter(url => url.includes("/vditor-assets/")).every(url => allowedAssets.has(new URL(url).pathname.split("/vditor-assets/4.0.1/")[1])), "editor must not load optional remote renderers")
  assert.ok(!requests.some(url => url.includes("unpkg.com")), requests.filter(url => url.includes("unpkg.com")).join("\n"))
  assert.ok(!requests.some(url => /MYSHOP(?:ALT|URL)/.test(url)), "conversion placeholders must never be requested")
  mediaAsset = { ...mediaAsset, usage:["avatar"], thumbnail_url:undefined, size:500000, original_size:500000 }
  assert.ok(!Object.hasOwn(JSON.parse(JSON.stringify(mediaAsset)), "thumbnail_url"), "the production API omits a null thumbnail URL")
  await page.goto(new URL("/admin/media", base).href)
  const optimizeButton = page.getByRole("button", { name:"优化头像" })
  await optimizeButton.waitFor()
  await optimizeButton.click()
  assert.equal(optimizationCalls, 1, `the main admin library must call the avatar-only endpoint; requests=${JSON.stringify(requests.filter(url => url.includes("optimize-avatar")))}`)
  await page.getByText(/展示文件减少/).waitFor()
  await page.getByRole("button", { name:"优化头像" }).waitFor({ state:"detached" })
  assert.deepEqual(failures, [])
  console.log(JSON.stringify({ markdown: productPayload.detail_md, resources: requests.filter(url => url.includes("vditor-assets")), errors: failures }, null, 2))
} finally { await browser.close() }
