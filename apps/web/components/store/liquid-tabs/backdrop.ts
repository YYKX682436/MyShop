import { snapdom } from "@zumer/snapdom"
import type { LiquidGlassRenderer } from "@/lib/vendor/liquid-glass/renderer"

type Rect = { x: number; y: number; width: number; height: number }
type Stamp = { route: string; theme: string; layoutVersion: number; contentVersion: number }
type Cache = { canvas: HTMLCanvasElement; rect: Rect; contentX: number; contentY: number; viewWidth: number; viewHeight: number; version: number; stamp: Stamp; tight: boolean; warnings: string[] }
type Status = { ready: boolean; pending?: boolean; reason?: string; captures: number; lastMs: number; warnings: string[] }
const MARGIN = 360
const PREFETCH = 110
// SnapDOM aligns the clip to its output pixel grid. The maximum rounding
// difference is half a CSS pixel at the minimum capture DPR of 1.
const CLIP_ROUNDING = 0.51
const INVALID_WARNINGS = new Set([
  "image-fallback", "canvas-tainted", "canvas-clamp", "raster-clamp", "embed-fallback",
  "reconcile-risk", "reconcile-failed", "backdrop-filter-failed", "pseudo-failed", "shrink-failed",
  "svg-defs-failed", "iframe-failed", "iframe-cross-origin",
])

function contains(outer: Rect, inner: Rect, margin = 0) {
  return inner.x >= outer.x + margin - CLIP_ROUNDING && inner.y >= outer.y + margin - CLIP_ROUNDING &&
    inner.x + inner.width <= outer.x + outer.width - margin + CLIP_ROUNDING &&
    inner.y + inner.height <= outer.y + outer.height - margin + CLIP_ROUNDING
}

function intersection(a: Rect, b: Rect): Rect | null {
  const x = Math.max(a.x, b.x), y = Math.max(a.y, b.y)
  const right = Math.min(a.x + a.width, b.x + b.width)
  const bottom = Math.min(a.y + a.height, b.y + b.height)
  return right > x && bottom > y ? { x, y, width: right - x, height: bottom - y } : null
}

/** One clipped DOM capture at a time; scrolling crops the current bounded cache. */
export class StoreBackdrop {
  private version = 0
  private stamp: Stamp = { route: location.pathname, theme: document.documentElement.classList.contains("dark") ? "dark" : "light", layoutVersion: 0, contentVersion: 0 }
  private cache: Cache | null = null
  private inFlight = false
  private pending = false
  private disposed = false
  private suspended = false
  private timer: ReturnType<typeof setTimeout> | null = null
  private waitingTimer: ReturnType<typeof setTimeout> | null = null
  private scheduledAt = 0
  private scrollRaf: number | null = null
  private captures = 0
  private lastMs = 0
  private presentedSize: { width: number; height: number; cssWidth: number; cssHeight: number } | null = null
  private failed: { version: number; rect: Rect; reason: string; warnings: string[]; pending?: boolean } | null = null
  private nextTight = false
  private observer: MutationObserver
  private resizeObserver: ResizeObserver
  private frame = document.createElement("canvas")
  private frameContext = this.frame.getContext("2d")

  constructor(private source: HTMLElement, private surface: HTMLCanvasElement,
    private renderer: LiquidGlassRenderer, private status: (value: Status) => void) {
    if (!this.frameContext) throw new Error("Canvas 2D unavailable")
    this.observer = new MutationObserver(records => {
      // SnapDOM temporarily measures clamped text by changing and restoring
      // text nodes. Compare the first old value with the final value of each
      // node so those internal, net-zero measurements do not cause a loop.
      const first = new Map<Node, Map<string, string | null>>()
      for (const record of records) {
        if (record.type === "childList") { this.invalidate("内容变化"); return }
        const key = record.type === "characterData" ? "#text" : record.attributeName!
        let values = first.get(record.target)
        if (!values) { values = new Map(); first.set(record.target, values) }
        if (!values.has(key)) values.set(key, record.oldValue)
      }
      for (const [node, values] of first) for (const [key, old] of values) {
        const current = key === "#text" ? node.nodeValue : (node as Element).getAttribute(key)
        if (current !== old) { this.invalidate("内容变化"); return }
      }
    })
    this.observer.observe(source, { subtree: true, childList: true, characterData: true, attributes: true,
      characterDataOldValue: true, attributeOldValue: true,
      attributeFilter: ["class", "src", "srcset", "sizes", "hidden", "aria-busy"] })
    this.resizeObserver = new ResizeObserver(() => this.invalidate("页面尺寸变化", "layout"))
    this.resizeObserver.observe(source)
    source.addEventListener("load", this.imageChanged, true)
    source.addEventListener("error", this.imageChanged, true)
    window.addEventListener("scroll", this.onScroll, { passive: true })
    window.addEventListener("resize", this.viewportChanged, { passive: true })
    window.visualViewport?.addEventListener("resize", this.viewportChanged, { passive: true })
    window.visualViewport?.addEventListener("scroll", this.viewportChanged, { passive: true })
    void document.fonts.ready.then(() => { if (!this.disposed) this.invalidate("字体就绪") })
    this.invalidate("初始捕获")
  }

  private imageChanged = (event: Event) => {
    if (event.target instanceof HTMLImageElement) this.invalidate(event.type === "error" ? "图片加载失败" : "图片加载完成")
  }
  private viewportChanged = () => this.invalidate("视口变化", "layout")
  private onScroll = () => {
    if (this.scrollRaf !== null || this.suspended) return
    this.scrollRaf = requestAnimationFrame(() => {
      this.scrollRaf = null
      this.present()
    })
  }
  private surfaceRect(): Rect {
    const r = this.surface.getBoundingClientRect()
    return { x: r.left + window.scrollX, y: r.top + window.scrollY, width: r.width, height: r.height }
  }
  private pageBounds(): Rect {
    const r = this.source.getBoundingClientRect()
    return { x: r.left + window.scrollX, y: r.top + window.scrollY, width: r.width, height: r.height }
  }
  private captureRect(frame: Rect, tight: boolean): Rect {
    const page = this.pageBounds()
    const margin = tight ? 32 : MARGIN
    // A centered canvas can start on a half output pixel. Leave a small
    // horizontal allowance so rounding the clip does not cut off that edge.
    const left = Math.max(page.x, Math.floor(frame.x - 2))
    const right = Math.min(page.x + page.width, Math.ceil(frame.x + frame.width + 2))
    const top = Math.max(page.y, Math.floor(frame.y - margin))
    const bottom = Math.min(page.y + page.height, Math.ceil(frame.y + frame.height + margin))
    return { x: left, y: top, width: Math.max(1, right - left), height: Math.max(1, bottom - top) }
  }
  private notify(ready: boolean, reason?: string, warnings: string[] = []) {
    if (this.waitingTimer !== null) clearTimeout(this.waitingTimer)
    this.waitingTimer = null
    if (!ready) this.presentedSize = null
    this.status({ ready, reason, captures: this.captures, lastMs: this.lastMs, warnings })
  }
  private notifyWaiting(reason: string) {
    const size = this.presentedSize
    const rect = this.surface.getBoundingClientRect()
    // Keep the last complete frame visible while its replacement is captured.
    // A resized surface cannot reuse it because the drawing regions differ.
    if (!size || size.width !== this.surface.width || size.height !== this.surface.height ||
      size.cssWidth !== rect.width || size.cssHeight !== rect.height) {
      this.notify(false, reason)
      return
    }
    this.status({ ready: true, pending: true, reason, captures: this.captures, lastMs: this.lastMs, warnings: [] })
    if (this.waitingTimer === null) this.waitingTimer = setTimeout(() => {
      this.waitingTimer = null
      if (!this.disposed && !this.suspended) this.notify(false, "背景更新超时")
    }, 750)
  }
  invalidate(reason: string, kind: "route" | "theme" | "layout" | "content" = "content") {
    if (this.disposed) return
    this.version++
    if (kind === "route") this.stamp.route = location.pathname
    else if (kind === "theme") this.stamp.theme = document.documentElement.classList.contains("dark") ? "dark" : "light"
    else if (kind === "layout") this.stamp.layoutVersion++
    else this.stamp.contentVersion++
    this.cache = null
    this.failed = null
    this.nextTight = false
    this.notifyWaiting(reason)
    // The route has already committed its DOM; start its replacement frame
    // promptly while ordinary content mutations remain coalesced.
    this.schedule(kind === "route" ? 0 : 80)
  }
  private schedule(delay: number) {
    if (this.disposed || this.suspended) return
    if (this.inFlight) { this.pending = true; return }
    const due = performance.now() + delay
    // Keep the earliest request: repeated scroll events must not postpone an
    // edge prefetch until scrolling stops.
    if (this.timer !== null && this.scheduledAt <= due) return
    if (this.timer !== null) clearTimeout(this.timer)
    this.scheduledAt = due
    this.timer = setTimeout(() => { this.timer = null; this.scheduledAt = 0; void this.capture() }, delay)
  }
  private present() {
    if (this.disposed || this.suspended) return
    const rect = this.surfaceRect()
    const visible = intersection(rect, this.pageBounds())
    if (!visible) { this.notify(false, "背景范围为空"); return }
    const cache = this.cache
    if (!cache || cache.version !== this.version || !contains(cache.rect, visible)) {
      const failure = this.failed?.version === this.version && contains(this.failed.rect, visible) ? this.failed : null
      if (failure?.pending) this.notifyWaiting(failure.reason)
      else if (failure) this.notify(false, failure.reason, failure.warnings)
      else this.notifyWaiting("等待页面背景")
      if (!failure) this.schedule(30)
      return
    }
    const ctx = this.frameContext!
    if (this.frame.width !== this.surface.width || this.frame.height !== this.surface.height) {
      this.frame.width = this.surface.width
      this.frame.height = this.surface.height
    }
    const sx = cache.canvas.width / cache.viewWidth
    const sy = cache.canvas.height / cache.viewHeight
    ctx.clearRect(0, 0, this.frame.width, this.frame.height)
    ctx.drawImage(cache.canvas,
      (cache.contentX + visible.x - cache.rect.x) * sx, (cache.contentY + visible.y - cache.rect.y) * sy,
      visible.width * sx, visible.height * sy,
      (visible.x - rect.x) / rect.width * this.frame.width, (visible.y - rect.y) / rect.height * this.frame.height,
      visible.width / rect.width * this.frame.width, visible.height / rect.height * this.frame.height)
    try {
      this.renderer.setBackdropFrame(this.frame, { ...rect, version: cache.version, ...cache.stamp })
      this.presentedSize = { width: this.surface.width, height: this.surface.height, cssWidth: rect.width, cssHeight: rect.height }
      this.notify(true, undefined, cache.warnings)
    } catch (error) {
      this.notify(false, `背景纹理不可用: ${error instanceof Error ? error.message : String(error)}`)
      return
    }
    const bounds = this.pageBounds()
    const nearTop = rect.y - cache.rect.y < PREFETCH && cache.rect.y > bounds.y + 1
    const nearBottom = cache.rect.y + cache.rect.height - rect.y - rect.height < PREFETCH &&
      cache.rect.y + cache.rect.height < bounds.y + bounds.height - 1
    if (!cache.tight && (nearTop || nearBottom)) this.schedule(100)
  }
  private async capture() {
    if (this.disposed || this.suspended || this.inFlight) return
    const frameRect = this.surfaceRect()
    const visible = intersection(frameRect, this.pageBounds())
    if (!visible) { this.notify(false, "背景范围为空"); return }
    const tight = this.nextTight
    this.nextTight = false
    const clip = this.captureRect(frameRect, tight)
    const version = this.version
    const stamp = { ...this.stamp }
    // An image whose actual current source has not loaded cannot yield a valid frame.
    const missing = Array.from(this.source.querySelectorAll("img")).find(img => {
      const r = img.getBoundingClientRect()
      const overlap = r.right > frameRect.x - window.scrollX && r.left < frameRect.x - window.scrollX + frameRect.width &&
        r.bottom > frameRect.y - window.scrollY && r.top < frameRect.y - window.scrollY + frameRect.height
      return overlap && !!(img.currentSrc || img.src) && (!img.complete || img.naturalWidth === 0)
    })
    if (missing) {
      const reason = `图片${missing.complete ? "加载失败" : "尚未加载"}: ${new URL(missing.currentSrc || missing.src, location.href).origin}`
      this.failed = { version, rect: clip, reason, warnings: [], pending: !missing.complete }
      if (missing.complete) this.notify(false, reason)
      else this.notifyWaiting(reason)
      return
    }
    this.inFlight = true
    this.pending = false
    const started = performance.now()
    try {
      const result = await snapdom(this.source, { clip, dpr: Math.max(1, Math.min(window.devicePixelRatio || 1, 1.5)), scale: 1, fast: false, invalidate: true, reconcile: true })
      const canvas = await result.toCanvas()
      this.lastMs = performance.now() - started
      this.captures++
      const warnings = result.warnings.map(w => {
        const url = w.message.match(/https?:\/\/[^\s]+/)?.[0]
        if (!url) return `${w.code}: ${w.message}`
        try { return `${w.code}: 资源无法捕获 (${new URL(url).origin})` } catch { return `${w.code}: 资源无法捕获` }
      })
      const failedWarnings = result.warnings.filter(w => INVALID_WARNINGS.has(w.code))
      if (this.disposed || this.suspended || version !== this.version) return
      if (failedWarnings.length) {
        if (!tight) {
          // An inaccessible image in the prefetch margin need not disable a
          // clean current frame. Retry one small window, still using clip.
          this.nextTight = true
          this.pending = true
          return
        }
        this.failed = { version, rect: clip, reason: "背景资源无法完整捕获", warnings }
        console.warn("[store-tabs] backdrop capture incomplete", warnings)
        this.notify(false, "背景资源无法完整捕获", warnings)
        return
      }
      const actual = result.meta.clip
      if (!actual || canvas.width < 1 || canvas.height < 1) throw new Error("捕获区域为空")
      if (!contains(actual, visible)) {
        // Do not keep recapturing an unchanged frame if the library cannot
        // provide its requested area; a later scroll or invalidation may retry.
        this.failed = { version, rect: visible, reason: "背景范围不完整", warnings }
        this.notify(false, "背景范围不完整", warnings)
        return
      }
      this.cache = { canvas, rect: { ...actual }, contentX: result.meta.contentX, contentY: result.meta.contentY,
        viewWidth: result.meta.vbW, viewHeight: result.meta.vbH, version, stamp, tight, warnings }
      this.failed = null
      this.present()
    } catch (error) {
      if (!this.disposed && version === this.version) {
        const reason = error instanceof Error ? error.message : String(error)
        this.failed = { version, rect: clip, reason: `背景捕获失败: ${reason}`, warnings: [] }
        console.warn("[store-tabs] backdrop capture failed", reason)
        this.notify(false, `背景捕获失败: ${reason}`)
      }
    } finally {
      this.inFlight = false
      if (this.pending && !this.disposed && !this.suspended) this.schedule(60)
    }
  }
  setSuspended(value: boolean) {
    this.suspended = value
    if (value) {
      if (this.timer !== null) clearTimeout(this.timer)
      if (this.waitingTimer !== null) clearTimeout(this.waitingTimer)
      this.waitingTimer = null
      this.timer = null
      this.scheduledAt = 0
      if (this.scrollRaf !== null) cancelAnimationFrame(this.scrollRaf)
      this.scrollRaf = null
    } else this.invalidate("页面恢复")
  }
  dispose() {
    this.disposed = true
    this.observer.disconnect()
    this.resizeObserver.disconnect()
    if (this.timer !== null) clearTimeout(this.timer)
    if (this.waitingTimer !== null) clearTimeout(this.waitingTimer)
    if (this.scrollRaf !== null) cancelAnimationFrame(this.scrollRaf)
    this.source.removeEventListener("load", this.imageChanged, true)
    this.source.removeEventListener("error", this.imageChanged, true)
    window.removeEventListener("scroll", this.onScroll)
    window.removeEventListener("resize", this.viewportChanged)
    window.visualViewport?.removeEventListener("resize", this.viewportChanged)
    window.visualViewport?.removeEventListener("scroll", this.viewportChanged)
    this.cache = null
    this.frame.width = this.frame.height = 0
  }
}
