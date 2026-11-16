/**
 * 设备指纹采集模块
 *
 * 采集 Canvas 2D + WebGL + AudioContext + Navigator + Screen 信号，
 * 合并后 SHA-256 哈希生成 64 位十六进制 deviceId。
 *
 * 只在登录、注册、下单时按需采集，作为本站风控信号。
 * 同页并发请求共享采集过程；不可用时由服务端按 IP 限流。
 */

const CACHE_KEY = "__device_id__"
let deviceIdPromise: Promise<string> | null = null

/** 获取 deviceId（优先 sessionStorage 缓存） */
export function getDeviceId(): Promise<string> {
  if (typeof window === "undefined") return Promise.resolve("")
  if (!deviceIdPromise) {
    deviceIdPromise = (async () => {
      try {
        const cached = sessionStorage.getItem(CACHE_KEY)
        if (cached) return cached
      } catch {
        // 浏览器禁用存储时仍可在本页复用采集结果。
      }
      try {
        const id = await generateDeviceId()
        try { sessionStorage.setItem(CACHE_KEY, id) } catch { /* 本页缓存仍有效 */ }
        return id
      } catch {
        return ""
      }
    })()
  }
  return deviceIdPromise
}

async function generateDeviceId(): Promise<string> {
  const signals: string[] = []

  signals.push(getCanvasFingerprint())
  signals.push(getWebGLFingerprint())
  signals.push(await getAudioFingerprint())
  signals.push(getNavigatorFingerprint())
  signals.push(getScreenFingerprint())

  const combined = signals.join("|")
  return sha256(combined)
}

// ── Canvas 2D ──

function getCanvasFingerprint(): string {
  try {
    const canvas = document.createElement("canvas")
    canvas.width = 200
    canvas.height = 50
    const ctx = canvas.getContext("2d")
    if (!ctx) return "canvas:unsupported"

    ctx.textBaseline = "alphabetic"
    ctx.fillStyle = "#f60"
    ctx.fillRect(125, 1, 62, 20)

    ctx.fillStyle = "#069"
    ctx.font = "11pt Arial"
    ctx.fillText("MyShop fingerprint", 2, 15)

    ctx.fillStyle = "rgba(102, 204, 0, 0.7)"
    ctx.font = "18pt Arial"
    ctx.fillText("MyShop fingerprint", 4, 45)

    return "canvas:" + canvas.toDataURL()
  } catch {
    return "canvas:error"
  }
}

// ── WebGL ──

function getWebGLFingerprint(): string {
  try {
    const canvas = document.createElement("canvas")
    const gl = canvas.getContext("webgl") || canvas.getContext("experimental-webgl")
    if (!gl || !(gl instanceof WebGLRenderingContext)) return "webgl:unsupported"

    const debugInfo = gl.getExtension("WEBGL_debug_renderer_info")
    const renderer = debugInfo ? gl.getParameter(debugInfo.UNMASKED_RENDERER_WEBGL) : "unknown"
    const vendor = debugInfo ? gl.getParameter(debugInfo.UNMASKED_VENDOR_WEBGL) : "unknown"
    const version = gl.getParameter(gl.VERSION)
    const shadingVersion = gl.getParameter(gl.SHADING_LANGUAGE_VERSION)

    return `webgl:${vendor}|${renderer}|${version}|${shadingVersion}`
  } catch {
    return "webgl:error"
  }
}

// ── AudioContext ──

function getAudioFingerprint(): Promise<string> {
  return new Promise((resolve) => {
    try {
      const AudioCtx = window.OfflineAudioContext || (window as unknown as Record<string, unknown>).webkitOfflineAudioContext
      if (!AudioCtx) {
        resolve("audio:unsupported")
        return
      }

      const context = new (AudioCtx as typeof OfflineAudioContext)(1, 44100, 44100)
      const oscillator = context.createOscillator()
      oscillator.type = "triangle"
      oscillator.frequency.setValueAtTime(10000, context.currentTime)

      const compressor = context.createDynamicsCompressor()
      compressor.threshold.setValueAtTime(-50, context.currentTime)
      compressor.knee.setValueAtTime(40, context.currentTime)
      compressor.ratio.setValueAtTime(12, context.currentTime)
      compressor.attack.setValueAtTime(0, context.currentTime)
      compressor.release.setValueAtTime(0.25, context.currentTime)

      oscillator.connect(compressor)
      compressor.connect(context.destination)
      oscillator.start(0)

      const timeout = setTimeout(() => resolve("audio:timeout"), 1000)
      context.oncomplete = (event) => {
        clearTimeout(timeout)
        try {
          const data = event.renderedBuffer.getChannelData(0)
          let sum = 0
          for (let i = 4500; i < 5000; i++) {
            sum += Math.abs(data[i])
          }
          resolve("audio:" + sum.toString())
        } catch {
          resolve("audio:error")
        }
      }
      void context.startRendering().catch(() => {
        clearTimeout(timeout)
        resolve("audio:error")
      })
    } catch {
      resolve("audio:error")
    }
  })
}

// ── Navigator ──

function getNavigatorFingerprint(): string {
  try {
    const nav = navigator
    return `nav:${nav.hardwareConcurrency || 0}|${(nav as unknown as Record<string, unknown>).deviceMemory || 0}|${nav.platform || ""}|${nav.language || ""}|${(nav.languages || []).join(",")}|${nav.maxTouchPoints || 0}`
  } catch {
    return "nav:error"
  }
}

// ── Screen ──

function getScreenFingerprint(): string {
  try {
    const s = window.screen
    return `screen:${s.width}x${s.height}|${s.colorDepth}|${window.devicePixelRatio || 1}`
  } catch {
    return "screen:error"
  }
}

// ── SHA-256 ──

async function sha256(message: string): Promise<string> {
  const encoder = new TextEncoder()
  const data = encoder.encode(message)
  const hashBuffer = await crypto.subtle.digest("SHA-256", data)
  const hashArray = Array.from(new Uint8Array(hashBuffer))
  return hashArray.map((b) => b.toString(16).padStart(2, "0")).join("")
}
