import assert from "node:assert/strict"
import { mkdir, writeFile } from "node:fs/promises"
import path from "node:path"
import { performance } from "node:perf_hooks"
import { chromium } from "playwright"

const VIEWPORT = { width: 1440, height: 900 }
const SCROLL_DURATION_MS = 10_000
const CATEGORY_DELAY_MS = Number(process.env.PERF_CATEGORY_DELAY_MS ?? 1_200)
const ENFORCE_CONTRACTS = process.env.PERF_ENFORCE_CONTRACTS !== "false"
const BASE_URL = new URL(process.env.PERF_BASE_URL ?? "http://127.0.0.1:3000")
const LABEL = (process.env.PERF_LABEL || process.env.PERF_SHA || "working-tree").replace(/[^a-zA-Z0-9._-]/g, "_")
const OUTPUT_DIR = path.resolve(process.env.PERF_OUTPUT_DIR || `perf-artifacts/${LABEL}`)
const TRACE_CATEGORIES = [
  "devtools.timeline",
  "disabled-by-default-devtools.timeline",
  "blink.user_timing",
  "v8",
  "toplevel",
  "cc",
  "gpu",
].join(",")

const runtimeMetricsInit = `
(() => {
  window.__myshopPerformance = { longTasks: [], frameDeltas: [], observer: null, frameHandle: null, lastFrame: 0, startedAt: 0 };
  window.__myshopPerformanceStart = () => {
    const state = window.__myshopPerformance;
    state.longTasks = [];
    state.frameDeltas = [];
    state.lastFrame = 0;
    state.startedAt = performance.now();
    if (state.observer) state.observer.disconnect();
    if ('PerformanceObserver' in window) {
      try {
        state.observer = new PerformanceObserver((list) => {
          for (const entry of list.getEntries()) state.longTasks.push({ startTime: entry.startTime, duration: entry.duration });
        });
        state.observer.observe({ type: 'longtask', buffered: false });
      } catch {}
    }
    const sampleFrame = (now) => {
      if (state.lastFrame > 0) state.frameDeltas.push(now - state.lastFrame);
      state.lastFrame = now;
      state.frameHandle = requestAnimationFrame(sampleFrame);
    };
    state.frameHandle = requestAnimationFrame(sampleFrame);
  };
  window.__myshopPerformanceStop = () => {
    const state = window.__myshopPerformance;
    if (state.observer) state.observer.disconnect();
    if (state.frameHandle !== null) cancelAnimationFrame(state.frameHandle);
    const sortedFrames = [...state.frameDeltas].sort((a, b) => a - b);
    const frameAt = (p) => sortedFrames.length ? sortedFrames[Math.min(sortedFrames.length - 1, Math.floor(sortedFrames.length * p))] : 0;
    const droppedFrames = state.frameDeltas.filter((delta) => delta > 25).length;
    return {
      durationMs: performance.now() - state.startedAt,
      longTaskCount: state.longTasks.length,
      longTaskTotalMs: state.longTasks.reduce((sum, item) => sum + item.duration, 0),
      longestTaskMs: Math.max(0, ...state.longTasks.map((item) => item.duration)),
      frameSamples: state.frameDeltas.length,
      droppedFrames,
      droppedFramePercent: state.frameDeltas.length ? droppedFrames * 100 / state.frameDeltas.length : 0,
      frameIntervalP95Ms: frameAt(0.95),
      longTasks: state.longTasks,
    };
  };
})();
`

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

function safePathname(pathname) {
  return pathname.split("/").map((part) => {
    if (/^[0-9a-f]{8}-[0-9a-f-]{20,}$/i.test(part) || /^\d{4,}$/.test(part)) return ":id"
    return part
  }).join("/")
}

function sanitizeUrl(value) {
  try {
    const url = new URL(value)
    return `${url.origin}${safePathname(url.pathname)}`
  } catch {
    return value.startsWith("data:") ? "data:" : value.split(/[?#]/, 1)[0]
  }
}

function sanitizeTraceValue(value, key = "") {
  if (Array.isArray(value)) return value.map((item) => sanitizeTraceValue(item, key))
  if (!value || typeof value !== "object") {
    if (typeof value === "string" && /url|href|documenturl|sourceurl/i.test(key)) return sanitizeUrl(value)
    return value
  }

  return Object.fromEntries(Object.entries(value).map(([childKey, childValue]) => [
    childKey,
    sanitizeTraceValue(childValue, childKey),
  ]))
}

function metricsMap(metrics) {
  return new Map(metrics.metrics.map(({ name, value }) => [name, value]))
}

function metricDelta(before, after, name, scale = 1) {
  const first = before.get(name)
  const last = after.get(name)
  return typeof first === "number" && typeof last === "number" ? (last - first) * scale : null
}

async function readTrace(cdp, stream) {
  let contents = ""
  while (true) {
    const part = await cdp.send("IO.read", { handle: stream })
    contents += part.data || ""
    if (part.eof) break
  }
  await cdp.send("IO.close", { handle: stream })
  return JSON.parse(contents)
}

function traceSummary(events) {
  const completeEvents = events.filter((event) => event.ph === "X" && typeof event.dur === "number")
  const sumDuration = (names) => completeEvents
    .filter((event) => names.has(event.name))
    .reduce((sum, event) => sum + event.dur, 0) / 1000
  const countEvents = (names) => completeEvents.filter((event) => names.has(event.name)).length

  return {
    layoutEvents: countEvents(new Set(["Layout", "UpdateLayoutTree"])),
    layoutMs: sumDuration(new Set(["Layout", "UpdateLayoutTree"])),
    paintEvents: countEvents(new Set(["Paint"])),
    paintMs: sumDuration(new Set(["Paint"])),
    compositeRelatedEvents: countEvents(new Set(["CompositeLayers", "UpdateLayerTree", "Commit", "ActivateLayerTree", "DrawFrame"])),
    compositeRelatedMs: sumDuration(new Set(["CompositeLayers", "UpdateLayerTree", "Commit", "ActivateLayerTree", "DrawFrame"])),
    evaluateScriptEvents: countEvents(new Set(["EvaluateScript"])),
    evaluateScriptMs: sumDuration(new Set(["EvaluateScript"])),
  }
}

async function startCapture(page) {
  const cdp = await page.context().newCDPSession(page)
  const network = new Map()
  let layers = []
  cdp.on("Network.requestWillBeSent", (event) => {
    const existing = network.get(event.requestId)
    const item = {
      url: sanitizeUrl(event.request.url),
      method: event.request.method,
      type: event.type || existing?.type || "Other",
      startedAtMs: event.timestamp * 1000,
      status: null,
      durationMs: null,
      encodedBytes: null,
      failed: null,
    }
    network.set(event.requestId, item)
  })
  cdp.on("Network.responseReceived", (event) => {
    const item = network.get(event.requestId)
    if (item) item.status = event.response.status
  })
  cdp.on("Network.loadingFinished", (event) => {
    const item = network.get(event.requestId)
    if (item) {
      item.durationMs = Math.max(0, event.timestamp * 1000 - item.startedAtMs)
      item.encodedBytes = event.encodedDataLength
    }
  })
  cdp.on("Network.loadingFailed", (event) => {
    const item = network.get(event.requestId)
    if (item) {
      item.durationMs = Math.max(0, event.timestamp * 1000 - item.startedAtMs)
      item.failed = event.errorText || "failed"
    }
  })
  cdp.on("LayerTree.layerTreeDidChange", (event) => { layers = event.layers || [] })

  await cdp.send("Performance.enable")
  await cdp.send("Network.enable")
  await cdp.send("LayerTree.enable")
  const before = metricsMap(await cdp.send("Performance.getMetrics"))
  const traceComplete = new Promise((resolve) => cdp.on("Tracing.tracingComplete", resolve))
  await cdp.send("Tracing.start", { categories: TRACE_CATEGORIES, transferMode: "ReturnAsStream" })

  return {
    startRuntimeMetrics: () => page.evaluate(() => window.__myshopPerformanceStart?.()),
    countRequests(pathSuffix) {
      return [...network.values()].filter((item) => {
        try { return new URL(item.url).pathname.endsWith(pathSuffix) } catch { return false }
      }).length
    },
    async finish(scenarioName) {
      const runtime = await page.evaluate(() => window.__myshopPerformanceStop?.() ?? null)
      const after = metricsMap(await cdp.send("Performance.getMetrics"))
      const activeFilterElements = await page.evaluate(() => [...document.querySelectorAll("*")].filter((element) => {
        const style = getComputedStyle(element)
        return element.getClientRects().length > 0
          && style.visibility !== "hidden"
          && [style.backdropFilter, style.webkitBackdropFilter].some((value) => value && value !== "none")
      }).length)

      await cdp.send("Tracing.end")
      const traceResult = await traceComplete
      const parsedTrace = await readTrace(cdp, traceResult.stream)
      const safeEvents = (parsedTrace.traceEvents || []).map((event) => sanitizeTraceValue(event))
      const layerFilterCount = await Promise.all(layers.map(async (layer) => {
        try {
          const result = await cdp.send("LayerTree.compositingReasons", { layerId: layer.layerId })
          return (result.compositingReasons || []).some((reason) => /backdrop.*filter|filter.*backdrop/i.test(reason))
        } catch {
          return false
        }
      })).then((matches) => matches.filter(Boolean).length)

      const traceFile = path.join(OUTPUT_DIR, `${scenarioName}.trace.json`)
      await writeFile(traceFile, JSON.stringify({ ...parsedTrace, traceEvents: safeEvents }))
      const requests = [...network.values()].map((item) => ({ ...item, startedAtMs: Math.round(item.startedAtMs) }))
        .sort((left, right) => left.startedAtMs - right.startedAtMs)
      const failedRequests = requests.filter((item) => item.failed || (item.status !== null && item.status >= 400))
      const traceMetrics = traceSummary(parsedTrace.traceEvents || [])

      await cdp.detach()
      return {
        runtime,
        cdpMetrics: {
          taskMs: metricDelta(before, after, "TaskDuration", 1000),
          scriptMs: metricDelta(before, after, "ScriptDuration", 1000),
          layoutMs: metricDelta(before, after, "LayoutDuration", 1000),
          recalcStyleMs: metricDelta(before, after, "RecalcStyleDuration", 1000),
          paintMs: metricDelta(before, after, "PaintDuration", 1000),
          layoutCount: metricDelta(before, after, "LayoutCount"),
          recalcStyleCount: metricDelta(before, after, "RecalcStyleCount"),
        },
        traceMetrics,
        backdropFilter: {
          activeElements: activeFilterElements,
          compositorLayers: layerFilterCount,
          compositorLayerCount: layers.length,
        },
        network: {
          requestCount: requests.length,
          failedCount: failedRequests.filter((item) => item.failed !== "net::ERR_ABORTED").length,
          cancelledCount: failedRequests.filter((item) => item.failed === "net::ERR_ABORTED").length,
          requests,
          failures: failedRequests.filter((item) => item.failed !== "net::ERR_ABORTED"),
          cancelled: failedRequests.filter((item) => item.failed === "net::ERR_ABORTED"),
        },
        traceFile,
      }
    },
  }
}

async function installRuntimeObserver(context) {
  await context.addInitScript({ content: runtimeMetricsInit })
}

async function waitForStorePage(page) {
  await page.goto(new URL("/", BASE_URL).href, { waitUntil: "domcontentloaded" })
  await page.locator(".storefront-main").waitFor({ state: "visible", timeout: 30_000 })
}

async function scrollForTenSeconds(page) {
  return page.evaluate(async (durationMs) => {
    const started = performance.now()
    let direction = 1
    await new Promise((resolve) => {
      const frame = (now) => {
        window.scrollBy(0, direction * 14)
        const maxY = Math.max(0, document.documentElement.scrollHeight - window.innerHeight)
        if (window.scrollY >= maxY || window.scrollY <= 0) direction *= -1
        if (now - started >= durationMs) resolve()
        else requestAnimationFrame(frame)
      }
      requestAnimationFrame(frame)
    })
    return performance.now() - started
  }, SCROLL_DURATION_MS)
}

async function markInteractionStart(page, markName) {
  await page.evaluate((name) => {
    performance.clearMarks(name)
    performance.mark(name)
  }, markName)
}

async function elapsedFromMark(page, markName) {
  return page.evaluate((name) => {
    const marks = performance.getEntriesByName(name, "mark")
    return marks.length ? performance.now() - marks[marks.length - 1].startTime : null
  }, markName)
}

async function waitForVisibleWorkspaceShell(page) {
  await page.waitForFunction(() => {
    const element = document.querySelector('[data-testid="product-workspace-sheet"]') || document.querySelector(".myshop-sheet-right")
    if (!element) return false
    const style = getComputedStyle(element)
    const rect = element.getBoundingClientRect()
    return Number(style.opacity) > 0.02 && rect.right > 0 && rect.left < window.innerWidth && rect.height > 0
  }, null, { timeout: 15_000 })
}

async function locateByTestIdOrSelector(page, testId, fallbackSelector) {
  const testIdLocator = page.getByTestId(testId)
  return await testIdLocator.count() > 0 ? testIdLocator : page.locator(fallbackSelector)
}

async function locateTab(page, key) {
  const testIdLocator = page.getByTestId(`product-tab-${key}`)
  if (await testIdLocator.count() > 0) return testIdLocator
  const order = ["basic", "specs", "pricing", "inventory", "details"]
  return page.getByRole("tab").nth(order.indexOf(key))
}

async function locateTabPanel(page, key) {
  const testIdLocator = page.getByTestId(`product-tab-panel-${key}`)
  if (await testIdLocator.count() > 0) return testIdLocator
  return page.locator('[role="tabpanel"][data-state="active"]')
}

async function locateDockItem(page, key) {
  const testIdLocator = page.getByTestId(`store-dock-${key}`)
  if (await testIdLocator.count() > 0) return testIdLocator
  const slot = { home: 0, orders: 1, me: 2 }[key]
  return page.locator(".store-dock-item").nth(slot)
}

async function createAuthSession(browser, role) {
  const admin = role === "admin"
  const storageStatePath = process.env[admin ? "PERF_ADMIN_STORAGE_STATE" : "PERF_USER_STORAGE_STATE"]
  const username = process.env[admin ? "MYSHOP_ADMIN_USERNAME" : "MYSHOP_TEST_USERNAME"]
  const password = process.env[admin ? "MYSHOP_ADMIN_PASSWORD" : "MYSHOP_TEST_PASSWORD"]
  if (!storageStatePath && (!username || !password)) {
    return { context: null, page: null, skipped: `未设置 ${admin ? "MYSHOP_ADMIN_* / PERF_ADMIN_STORAGE_STATE" : "MYSHOP_TEST_* / PERF_USER_STORAGE_STATE"}` }
  }

  const context = await browser.newContext({
    viewport: VIEWPORT,
    deviceScaleFactor: 1,
    locale: "zh-CN",
    ...(storageStatePath ? { storageState: storageStatePath } : {}),
  })
  await installRuntimeObserver(context)
  const page = await context.newPage()
  const destination = admin ? "/admin/dashboard" : "/my/orders"

  try {
    if (!storageStatePath) {
      const loginUrl = new URL(`/login?redirect=${encodeURIComponent(destination)}`, BASE_URL)
      await page.goto(loginUrl.href, { waitUntil: "domcontentloaded" })
      await page.locator("#account").fill(username)
      await page.locator("#password").fill(password)
      await page.locator('form button[type="submit"]').click()
      await page.waitForURL((url) => url.pathname !== "/login", { timeout: 20_000 })
    } else {
      await page.goto(new URL(destination, BASE_URL).href, { waitUntil: "domcontentloaded" })
      if (new URL(page.url()).pathname === "/login") throw new Error("storage state is not authenticated")
    }
    return { context, page, skipped: null }
  } catch {
    await context.close()
    return { context: null, page: null, skipped: "登录未完成；需要有效的已授权存储态或可在此环境完成的登录" }
  }
}

async function runHomeScroll(browser) {
  const context = await browser.newContext({ viewport: VIEWPORT, deviceScaleFactor: 1, locale: "zh-CN" })
  await installRuntimeObserver(context)
  const page = await context.newPage()
  const capture = await startCapture(page)
  await waitForStorePage(page)
  await capture.startRuntimeMetrics()
  const scrollDurationMs = await scrollForTenSeconds(page)
  const measurements = await capture.finish("home-scroll")
  await context.close()
  return { status: "passed", scrollDurationMs: Math.round(scrollDurationMs), ...measurements }
}

async function runDockNavigation(page) {
  await waitForStorePage(page)
  const capture = await startCapture(page)
  await capture.startRuntimeMetrics()
  const targetPaths = { orders: "/my/orders", me: "/my", home: "/" }
  const sequence = ["orders", "me", "home", "orders", "me", "home"]
  const navigations = []

  for (const key of sequence) {
    const before = performance.now()
    const button = await locateDockItem(page, key)
    await button.click()
    await page.waitForURL((url) => url.pathname.replace(/\/+$/, "") === targetPaths[key].replace(/\/+$/, "") || (key === "home" && url.pathname === "/"), { timeout: 15_000 })
    const activeButton = await locateByTestIdOrSelector(page, `store-dock-${key}`, `.store-dock-item[aria-current="page"]`)
    await activeButton.waitFor({ state: "visible", timeout: 10_000 })
    navigations.push({ key, durationMs: Math.round(performance.now() - before) })
  }

  const measurements = await capture.finish("dock-navigation")
  return { status: "passed", sequence, navigations, ...measurements }
}

async function runDashboardScroll(page) {
  await page.goto(new URL("/admin/dashboard", BASE_URL).href, { waitUntil: "domcontentloaded" })
  await page.locator(".admin-floating-toolbar").waitFor({ state: "visible", timeout: 30_000 })
  const capture = await startCapture(page)
  await capture.startRuntimeMetrics()
  const scrollDurationMs = await scrollForTenSeconds(page)
  const measurements = await capture.finish("dashboard-scroll")
  return { status: "passed", scrollDurationMs: Math.round(scrollDurationMs), ...measurements }
}

async function runProductEditor(browser, page, categoryDelayMs) {
  await page.goto(new URL("/admin/products", BASE_URL).href, { waitUntil: "domcontentloaded" })
  const addLink = await locateByTestIdOrSelector(page, "admin-add-product", 'a[href="/admin/products/new"]')
  await addLink.waitFor({ state: "visible", timeout: 30_000 })

  let holdCategories = true
  await page.route("**/*", async (route) => {
    const requestPath = new URL(route.request().url()).pathname
    if (holdCategories && requestPath.endsWith("/admin/categories")) await sleep(categoryDelayMs)
    await route.continue()
  })

  const openCapture = await startCapture(page)
  await openCapture.startRuntimeMetrics()
  await markInteractionStart(page, "myshop-perf-add-click")
  await addLink.click()
  await waitForVisibleWorkspaceShell(page)
  const shellVisibleMs = await elapsedFromMark(page, "myshop-perf-add-click")
  const titleInputTestId = page.getByTestId("product-title-input")
  const titleInput = await titleInputTestId.count() > 0 ? titleInputTestId : page.locator('.admin-editor-form input[type="text"]').nth(1)
  await titleInput.waitFor({ state: "visible", timeout: 15_000 })
  await titleInput.focus()
  const basicInteractiveMs = await elapsedFromMark(page, "myshop-perf-add-click")
  const categorySelect = await locateByTestIdOrSelector(page, "product-category-select", ".admin-editor-form select")
  const categoryDisabledAtBasic = await categorySelect.isDisabled()
  await sleep(Math.min(800, Math.max(200, categoryDelayMs - 300)))
  const initialUserLevelCalls = openCapture.countRequests("/admin/user-levels")
  const initialPreviewCalls = openCapture.countRequests("/admin/products/pricing-preview")
  holdCategories = false
  await sleep(categoryDelayMs + 100)
  const editorOpenMeasurements = await openCapture.finish("product-open-basic")

  const tabsCapture = await startCapture(page)
  await tabsCapture.startRuntimeMetrics()
  const tabTimings = []
  for (const key of ["specs", "pricing", "inventory", "details", "basic"]) {
    const started = performance.now()
    await (await locateTab(page, key)).click()
    const panel = await locateTabPanel(page, key)
    await panel.waitFor({ state: "visible", timeout: 15_000 })
    if (key === "details") await panel.locator("textarea").first().waitFor({ state: "visible", timeout: 15_000 })
    const mountedTabPanels = await page.evaluate(() => {
      const testPanels = document.querySelectorAll('[data-testid^="product-tab-panel-"]')
      return testPanels.length || document.querySelectorAll('[role="tabpanel"]').length
    })
    tabTimings.push({ key, durationMs: Math.round(performance.now() - started), mountedTabPanels })
  }

  await (await locateTab(page, "pricing")).click()
  await (await locateTabPanel(page, "pricing")).waitFor({ state: "visible", timeout: 15_000 })
  await page.waitForFunction(() => performance.getEntriesByType("resource").some((entry) => /\/admin\/user-levels(?:\?|$)/.test(entry.name)), null, { timeout: 15_000 })
  await page.waitForFunction(() => performance.getEntriesByType("resource").some((entry) => /\/admin\/products\/pricing-preview(?:\?|$)/.test(entry.name)), null, { timeout: 15_000 })
  await sleep(300)
  const beforeBasicEdit = await page.evaluate(() => performance.getEntriesByType("resource").filter((entry) => /\/admin\/products\/pricing-preview(?:\?|$)/.test(entry.name)).length)
  await (await locateTab(page, "basic")).click()
  await titleInput.fill("Performance smoke draft")
  await sleep(400)
  const afterBasicEdit = await page.evaluate(() => performance.getEntriesByType("resource").filter((entry) => /\/admin\/products\/pricing-preview(?:\?|$)/.test(entry.name)).length)
  const userLevelCalls = await page.evaluate(() => performance.getEntriesByType("resource").filter((entry) => /\/admin\/user-levels(?:\?|$)/.test(entry.name)).length)
  const previewCalls = afterBasicEdit
  const noUnrelatedPreview = beforeBasicEdit === afterBasicEdit
  const categoryDelayProof = categoryDisabledAtBasic && basicInteractiveMs < categoryDelayMs && shellVisibleMs < categoryDelayMs
  if (ENFORCE_CONTRACTS) {
    assert.equal(initialUserLevelCalls, 0, "user levels must not load before the pricing tab is opened")
    assert.equal(initialPreviewCalls, 0, "pricing preview must not load before the pricing tab is opened")
    assert.equal(userLevelCalls, 1, "user levels must be cached for this editor lifecycle")
    assert.equal(noUnrelatedPreview, true, "basic-tab edits must not trigger pricing preview")
    assert.equal(categoryDelayProof, true, "the Workspace and basic form must be interactive before the delayed categories request resolves")
    assert.equal(tabTimings.every((result) => result.mountedTabPanels === 1), true, "only the active ProductForm tab should remain mounted")
  }
  const editorTabsMeasurements = await tabsCapture.finish("product-tabs")

  const closeCapture = await startCapture(page)
  await closeCapture.startRuntimeMetrics()
  await markInteractionStart(page, "myshop-perf-sheet-close")
  const sheet = await locateByTestIdOrSelector(page, "product-workspace-sheet", ".myshop-sheet-right")
  page.once("dialog", (dialog) => dialog.accept())
  await sheet.getByRole("button", { name: "Close" }).click()
  await sheet.waitFor({ state: "hidden", timeout: 15_000 })
  const sheetCloseMs = await elapsedFromMark(page, "myshop-perf-sheet-close")
  const sheetCloseMeasurements = await closeCapture.finish("workspace-sheet-close")

  return {
    status: "passed",
    shellVisibleMs: Math.round(shellVisibleMs ?? -1),
    basicInteractiveMs: Math.round(basicInteractiveMs ?? -1),
    categoryDelayMs,
    categoryDisabledAtBasic,
    categoryDelayProof,
    initialUserLevelCalls,
    initialPreviewCalls,
    userLevelCalls,
    pricingPreviewCalls: previewCalls,
    noUnrelatedPreview,
    tabTimings,
    sheetCloseMs: Math.round(sheetCloseMs ?? -1),
    editorOpenMeasurements,
    editorTabsMeasurements,
    sheetCloseMeasurements,
  }
}

async function runScenario(report, name, work) {
  try {
    report.scenarios[name] = await work()
  } catch (error) {
    report.scenarios[name] = { status: "failed", error: error instanceof Error ? error.message : String(error) }
    report.failed += 1
  }
}

async function main() {
  await mkdir(OUTPUT_DIR, { recursive: true })
  const browser = await chromium.launch({ headless: process.env.PERF_HEADLESS !== "false" })
  const report = {
    label: LABEL,
    capturedAt: new Date().toISOString(),
    browser: `Chromium ${browser.version()}`,
    viewport: VIEWPORT,
    deviceScaleFactor: 1,
    baseOrigin: BASE_URL.origin,
    scrollDurationTargetMs: SCROLL_DURATION_MS,
    categoryDelayMs: CATEGORY_DELAY_MS,
    scenarios: {},
    failed: 0,
  }

  try {
    await runScenario(report, "A_home_scroll", () => runHomeScroll(browser))

    const user = await createAuthSession(browser, "user")
    if (user.page) {
      await runScenario(report, "B_dock_navigation", () => runDockNavigation(user.page))
      await user.context.close()
    } else {
      report.scenarios.B_dock_navigation = { status: "skipped", reason: user.skipped }
    }

    const admin = await createAuthSession(browser, "admin")
    if (admin.page) {
      await runScenario(report, "C_dashboard_scroll", () => runDashboardScroll(admin.page))
      await runScenario(report, "D_product_open_E_tabs_F_sheet_close", () => runProductEditor(browser, admin.page, CATEGORY_DELAY_MS))
      await admin.context.close()
    } else {
      report.scenarios.C_dashboard_scroll = { status: "skipped", reason: admin.skipped }
      report.scenarios.D_product_open_E_tabs_F_sheet_close = { status: "skipped", reason: admin.skipped }
    }
  } finally {
    await browser.close()
  }

  const reportPath = path.join(OUTPUT_DIR, "report.json")
  await writeFile(reportPath, `${JSON.stringify(report, null, 2)}\n`)
  const summary = Object.fromEntries(Object.entries(report.scenarios).map(([name, result]) => [name, {
    status: result.status,
    shellVisibleMs: result.shellVisibleMs,
    basicInteractiveMs: result.basicInteractiveMs,
    longTaskCount: result.runtime?.longTaskCount,
    longestTaskMs: result.runtime?.longestTaskMs,
    droppedFrames: result.runtime?.droppedFrames,
    scriptMs: result.cdpMetrics?.scriptMs,
    layoutMs: result.cdpMetrics?.layoutMs,
    paintMs: result.cdpMetrics?.paintMs,
    activeFilterElements: result.backdropFilter?.activeElements,
    backdropFilterLayers: result.backdropFilter?.compositorLayers,
    compositorLayerCount: result.backdropFilter?.compositorLayerCount,
    reason: result.reason,
    error: result.error,
  }]))
  process.stdout.write(`${JSON.stringify({ label: LABEL, browser: report.browser, viewport: VIEWPORT, reportPath, scenarios: summary }, null, 2)}\n`)
  process.exitCode = report.failed > 0 ? 1 : 0
}

main().catch((error) => {
  process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`)
  process.exitCode = 1
})
