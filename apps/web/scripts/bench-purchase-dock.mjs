import { createServer } from "node:http"
import { spawn } from "node:child_process"
import { existsSync, mkdirSync, writeFileSync } from "node:fs"
import { fileURLToPath } from "node:url"
import { gzipSync } from "node:zlib"
import { chromium } from "playwright"

// Benchmark the built application with an in-memory backend; no real order is sent.
const phase = process.env.PURCHASE_BENCH_PHASE || "after"
const output = process.env.PURCHASE_EVIDENCE_DIR || fileURLToPath(new URL("../../../dist/purchase-dock-2026-11-24/", import.meta.url))
mkdirSync(output, { recursive: true })
const webRoot = fileURLToPath(new URL("..", import.meta.url))
const specs = [
  { id: "year", name: "年卡", price: 52, retail_price: 52, stock_available: 20, unlimited: false },
  { id: "month", name: "月卡", price: 20, retail_price: 20, stock_available: 20, unlimited: false },
]
const product = id => ({ id, title: "测试商品", price: 52, retail_price: 52, cover_url: null,
  description: "测试详情。".repeat(160), detail_md: null, stock_available: 20, unlimited: false,
  has_specs: true, specs, delivery_type: "MANUAL", sales_count: 0, initial_sales: 0,
  customer_input_fields: id === "long" ? Array.from({length: 20}, (_, i) => ({id: `field-${i}`, label: `填写信息 ${i + 1}`, type: "TEXT", required: false})) : [] })
const channels = [{ id: "wx", channel_code: "test-wx", name: "微信支付", type: "wxpay" }]
const config = { site_name: "测试商城", favicon_url: null, maintenance_enabled: false }
const ok = data => JSON.stringify({ code: 0, message: "ok", data })
const backend = createServer((req, res) => {
  const path = new URL(req.url, "http://fixture").pathname
  const data = path === "/api/site/config" ? config : path === "/api/payment-channels" ? channels
    : path === "/api/categories" ? [] : path.startsWith("/api/products/") ? product(path.split("/").at(-1)) : null
  res.writeHead(200, { "content-type": "application/json" }); res.end(ok(data))
})
await new Promise(resolve => backend.listen(0, "127.0.0.1", resolve))
const reserve = createServer()
await new Promise(resolve => reserve.listen(0, "127.0.0.1", resolve))
const port = reserve.address().port
await new Promise(resolve => reserve.close(resolve))
const base = `http://127.0.0.1:${port}`
config.main_site_url = base
const server = spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "--hostname", "127.0.0.1", "--port", String(port)], {
  cwd: webRoot, env: { ...process.env, BACKEND_URL: `http://127.0.0.1:${backend.address().port}`, PUBLIC_SITE_URL: base, NEXT_TELEMETRY_DISABLED: "1" }, stdio: "ignore",
})
let browser
try {
  for (let i = 0; i < 120; i++) {
    try { if ((await fetch(`${base}/product/standard`)).ok) break } catch { /* startup */ }
    if (i === 119) throw new Error("Built server did not start")
    await new Promise(resolve => setTimeout(resolve, 100))
  }
  const chrome = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
  browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH ?? (existsSync(chrome) ? chrome : undefined) })
  const results = []
  for (const scenario of [{ name: "standard", stubTurnstile: false }, { name: "long", stubTurnstile: true }]) {
    for (let run = 1; run <= (process.env.PURCHASE_TRACE === "1" ? 1 : 3); run++) {
      const context = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 1, hasTouch: true })
      await context.route("https://challenges.cloudflare.com/**", route => route.fulfill({ contentType: "application/javascript", body: `window.turnstile={render(el){el.textContent='Turnstile fixture';return 'fixture'},reset(){},remove(){}}` }))
      await context.route("**/api/**", async route => {
        const path = new URL(route.request().url()).pathname
        const data = path === "/api/site/config" ? { ...config, ...(scenario.stubTurnstile ? { turnstile_site_key: "fixture-key" } : {}) }
          : path === "/api/payment-channels" ? channels : path === "/api/categories" ? [] : path.endsWith("/summary") ? { unread: 0, latest: null } : null
        await route.fulfill({ status: 200, contentType: "application/json", body: ok(data) })
      })
      await context.addInitScript(() => {
        window.__purchaseBenchmark = { samples: [], longTasks: [] }
        new PerformanceObserver(list => { for (const e of list.getEntries()) window.__purchaseBenchmark.longTasks.push({ start: e.startTime, duration: e.duration }) }).observe({ type: "longtask", buffered: true })
        document.addEventListener("click", event => {
          if (!event.target.closest?.(".product-purchase-dock-viewport button")) return
          const start = performance.now(), sample = { start, firstPaintMs: null, settledMs: null }
          window.__purchaseBenchmark.samples.push(sample)
          const tick = () => {
            const dialog = document.querySelector('[role="dialog"]')
            if (dialog) {
              const style = getComputedStyle(dialog), box = dialog.getBoundingClientRect()
              if (box.width && box.height && Number(style.opacity) > 0.01 && sample.firstPaintMs === null) sample.firstPaintMs = performance.now() - start
              const transform = style.transform === "none" ? null : new DOMMatrixReadOnly(style.transform)
              if (sample.firstPaintMs !== null && Number(style.opacity) > 0.99 && (!transform || (Math.abs(transform.a - 1) < .01 && Math.abs(transform.d - 1) < .01 && Math.abs(transform.f) < 1))) {
                sample.settledMs = performance.now() - start
                return
              }
            }
            if (performance.now() - start < 3000) requestAnimationFrame(tick)
          }
          requestAnimationFrame(tick)
        }, true)
      })
      const page = await context.newPage()
      page.on("pageerror", error => console.error("PAGE_ERROR", error.message))
      const cdp = await context.newCDPSession(page)
      await cdp.send("Emulation.setCPUThrottlingRate", { rate: 4 })
      await page.goto(`${base}/product/${scenario.name}`, { waitUntil: "networkidle" })
      const trigger = page.locator(".product-purchase-dock-viewport button").first()
      await trigger.waitFor({ timeout: 8000 }).catch(async error => { console.error("PAGE", await page.title(), (await page.locator("body").innerText()).slice(0, 1000)); throw error })
      await page.waitForFunction(() => !document.querySelector('.product-purchase-dock-viewport button')?.disabled)
      const trace = process.env.PURCHASE_TRACE === "1" && run === 1
      if (trace) await cdp.send("Tracing.start", { categories: "devtools.timeline,blink.user_timing", transferMode: "ReturnAsStream" })
      for (let i = 0; i < 3; i++) {
        await trigger.click()
        await page.getByRole("dialog").waitFor()
        await page.waitForFunction(index => window.__purchaseBenchmark.samples[index]?.settledMs !== null, i)
        await page.keyboard.press("Escape")
        await page.getByRole("dialog").waitFor({ state: "hidden" })
      }
      if (trace) {
        const completed = new Promise(resolve => cdp.once("Tracing.tracingComplete", resolve))
        await cdp.send("Tracing.end")
        const { stream } = await completed
        let contents = "", eof = false
        while (!eof) {
          const chunk = await cdp.send("IO.read", { handle: stream })
          contents += chunk.data; eof = chunk.eof
        }
        await cdp.send("IO.close", { handle: stream })
        writeFileSync(`${output}/${phase}-${scenario.name}-trace.json.gz`, gzipSync(contents))
      }
      const data = await page.evaluate(() => window.__purchaseBenchmark)
      let scrollComparison
      if (scenario.name === "standard" && run === 1 && !phase.startsWith("before")) {
        scrollComparison = await page.evaluate(async () => {
          const dock = document.querySelector(".product-purchase-dock-closed")
          const original = { filter: dock.style.backdropFilter, webkit: dock.style.webkitBackdropFilter, behavior: document.documentElement.style.scrollBehavior }
          const run = async enabled => {
            dock.style.backdropFilter = enabled ? original.filter : "none"
            dock.style.webkitBackdropFilter = enabled ? original.webkit : "none"
            const computedFilter = getComputedStyle(dock).backdropFilter
            const start = performance.now(), times = []
            for (let i = 0; i < 75; i++) {
              await new Promise(resolve => requestAnimationFrame(resolve))
              times.push(performance.now()); window.scrollTo(0, i * 12)
            }
            const deltas = times.slice(1).map((time, i) => time - times[i]).sort((a, b) => a - b)
            return { computedFilter, p95FrameIntervalMs: deltas[Math.floor(deltas.length * .95)], maxFrameIntervalMs: deltas.at(-1),
              longTasks: window.__purchaseBenchmark.longTasks.filter(task => task.start >= start && task.start <= performance.now()).map(task => task.duration) }
          }
          document.documentElement.style.scrollBehavior = "auto"
          const withBlur = await run(true), withoutBlur = await run(false)
          dock.style.backdropFilter = original.filter; dock.style.webkitBackdropFilter = original.webkit
          document.documentElement.style.scrollBehavior = original.behavior
          return { withBlur, withoutBlur }
        })
      }
      results.push({ scenario: scenario.name, run, samples: data.samples.map(sample => ({ ...sample, longTasks: data.longTasks.filter(task => task.start + task.duration >= sample.start && task.start <= sample.start + Math.max(300, sample.settledMs || 0)).map(task => task.duration) })), ...(scrollComparison ? { scrollComparison } : {}) })
      await context.close()
    }
  }
  writeFileSync(`${output}/${phase}-performance.json`, JSON.stringify({ phase, browser: browser.version(), viewport: "390x844", deviceScaleFactor: 1, hasTouch: true, cpuThrottle: 4, turnstile: "local stub", results }, null, 2))
  console.log(JSON.stringify(results))
} finally {
  if (browser) await browser.close()
  server.kill("SIGTERM")
  await new Promise(resolve => backend.close(resolve))
}
