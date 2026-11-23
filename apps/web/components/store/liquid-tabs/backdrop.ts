import { snapdom } from "@zumer/snapdom"
import type { LiquidGlassRenderer } from "@/lib/vendor/liquid-glass/renderer"

type Rect = { x: number; y: number; width: number; height: number }
type Stamp = { route: string; theme: string; layoutVersion: number; contentVersion: number }
type Cache = { canvas: HTMLCanvasElement; rect: Rect; contentX: number; contentY: number; viewWidth: number; viewHeight: number; version: number; stamp: Stamp; tight: boolean; warnings: string[] }
type Status = { ready: boolean; reason?: string; captures: number; lastMs: number; warnings: string[] }
const MARGIN = 360
const PREFETCH = 110
const INVALID_WARNINGS = new Set([
  "image-fallback", "canvas-tainted", "canvas-clamp", "raster-clamp", "embed-fallback",
  "reconcile-risk", "reconcile-failed", "backdrop-filter-failed", "pseudo-failed", "shrink-failed",
  "svg-defs-failed", "iframe-failed", "iframe-cross-origin",
])

function contains(outer: Rect, inner: Rect, margin = 0) {
  return inner.x >= outer.x + margin && inner.y >= outer.y + margin &&
    inner.x + inner.width <= outer.x + outer.width - margin && inner.y + inner.height <= outer.y + outer.height - margin
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
  private scheduledAt = 0
  private scrollRaf: number | null = null
  private captures = 0
  private lastMs = 0
  private failed: { version: number; rect: Rect; reason: string; warnings: string[] } | null = null
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
    const top = Math.max(page.y, Math.floor(frame.y - margin))
    const bottom = Math.min(page.y + page.height, Math.ceil(frame.y + frame.height + margin))
    return { x: frame.x, y: top, width: frame.width, height: Math.max(1, bottom - top) }
  }
  private notify(ready: boolean, reason?: string, warnings: string[] = []) {
    this.status({ ready, reason, captures: this.captures, lastMs: this.lastMs, warnings })
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
    this.notify(false, reason)
    this.schedule(80)
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
    const cache = this.cache
    if (!cache || cache.version !== this.version || !contains(cache.rect, rect)) {
      const failure = this.failed?.version === this.version && contains(this.failed.rect, rect) ? this.failed : null
      this.notify(false, failure?.reason ?? "等待页面背景", failure?.warnings ?? [])
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
      (cache.contentX + rect.x - cache.rect.x) * sx, (cache.contentY + rect.y - cache.rect.y) * sy,
      rect.width * sx, rect.height * sy, 0, 0, this.frame.width, this.frame.height)
    try {
      this.renderer.setBackdropFrame(this.frame, { ...rect, version: cache.version, ...cache.stamp })
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
      this.failed = { version, rect: clip, reason, warnings: [] }
      this.notify(false, reason)
      return
    }
    this.inFlight = true
    this.pending = false
    const started = performance.now()
    try {
      const result = await snapdom(this.source, { clip, dpr: Math.min(window.devicePixelRatio || 1, 1.5), scale: 1, fast: false, invalidate: true, reconcile: true })
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
