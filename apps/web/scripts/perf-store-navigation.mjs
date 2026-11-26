import { existsSync } from "node:fs"
import { chromium } from "playwright"

// Compare the same local page in the same browser with WebGL capture off/on.
// Use only an isolated local preview; this script does not exercise live sales.
const base = new URL(process.env.PERF_BASE_URL ?? "http://127.0.0.1:18722")
if (!["127.0.0.1", "localhost", "[::1]"].includes(base.hostname)) throw new Error("PERF_BASE_URL must be loopback")
const macChrome = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
const executablePath = process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH ?? (existsSync(macChrome) ? macChrome : undefined)
const browser = await chromium.launch({ headless: true, executablePath, args: ["--enable-webgl", "--ignore-gpu-blocklist", "--enable-unsafe-swiftshader"] })
try {
  const results = []
  for (const disabled of [true, false]) {
    const context = await browser.newContext({ viewport: { width: 390, height: 600 }, deviceScaleFactor: 1, hasTouch: true, userAgent: "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36" })
    await context.addInitScript(disabled => {
      window.__navDraws = 0
      window.__navLongTasks = []
      new PerformanceObserver(list => window.__navLongTasks.push(...list.getEntries().map(entry => entry.duration))).observe({ type: "longtask", buffered: true })
      const originalContext = HTMLCanvasElement.prototype.getContext
      HTMLCanvasElement.prototype.getContext = function (type, ...args) {
        if (disabled && type === "webgl" && this.classList.contains("store-liquid-canvas")) return null
        return originalContext.call(this, type, ...args)
      }
      const originalDraw = WebGLRenderingContext.prototype.drawArrays
      WebGLRenderingContext.prototype.drawArrays = function (...args) {
        if (this.canvas.classList.contains("store-liquid-canvas")) window.__navDraws++
        return originalDraw.apply(this, args)
      }
    }, disabled)
    const page = await context.newPage()
    await page.goto(base.href, { waitUntil: "domcontentloaded" })
    await page.locator(`.store-liquid-nav[data-mode="${disabled ? "fallback" : "webgl"}"]`).waitFor({ timeout: 20000 })
    await page.waitForTimeout(1200)
    const sample = await page.evaluate(async () => {
      const nav = document.querySelector(".store-liquid-nav")
      const durations = [Number(nav.dataset.captureMs) || 0].filter(Boolean)
      let lastCaptureCount = Number(nav.dataset.captureCount || 0)
      const observer = new MutationObserver(() => {
        const count = Number(nav.dataset.captureCount || 0)
        const value = Number(nav.dataset.captureMs)
        if (value && count > lastCaptureCount) durations.push(value)
        lastCaptureCount = count
      })
      observer.observe(nav, { attributes: true, attributeFilter: ["data-capture-count"] })
      window.__navLongTasks.length = 0
      const captureStart = Number(nav.dataset.captureCount || 0)
      const start = performance.now()
      const intervals = []
      let previous = performance.now(), running = true
      const tick = now => { if (running) { intervals.push(now - previous); previous = now; requestAnimationFrame(tick) } }
      requestAnimationFrame(tick)
      const maxScroll = Math.min(450, document.documentElement.scrollHeight - innerHeight)
      for (let repeat = 0; repeat < 3; repeat++) {
        for (let step = 0; step <= 30; step++) { scrollTo(0, maxScroll * step / 30); await new Promise(resolve => setTimeout(resolve, 28)) }
        for (let step = 30; step >= 0; step--) { scrollTo(0, maxScroll * step / 30); await new Promise(resolve => setTimeout(resolve, 28)) }
      }
      running = false
      const scrollMs = performance.now() - start
      await new Promise(resolve => setTimeout(resolve, 1800))
      const idleStart = window.__navDraws
      await new Promise(resolve => setTimeout(resolve, 1000))
      observer.disconnect()
      const canvas = document.querySelector(".store-liquid-canvas")
      const gl = canvas.getContext("webgl")
      const debug = gl?.getExtension("WEBGL_debug_renderer_info")
      return {
        scrollMs, frames: intervals.length, frameGapsOver50Ms: intervals.filter(value => value > 50).length,
        maxFrameGapMs: Math.max(0, ...intervals), longTasks: window.__navLongTasks.length,
        longestTaskMs: Math.max(0, ...window.__navLongTasks), capturesAtStart: captureStart,
        capturesDuringScroll: Number(nav.dataset.captureCount || 0) - captureStart, captureDurationsMs: durations,
        drawsAfterIdle: window.__navDraws - idleStart, mode: nav.dataset.mode,
        canvasPixels: [canvas.width, canvas.height], renderer: debug ? gl.getParameter(debug.UNMASKED_RENDERER_WEBGL) : null,
      }
    })
    results.push({ condition: disabled ? "capture-disabled fallback" : "WebGL and page capture", ...sample })
    await context.close()
  }
  console.log(JSON.stringify({ browser: browser.version(), viewport: "390x600", deviceScaleFactor: 1, touchEmulation: true, results }, null, 2))
} finally { await browser.close() }
