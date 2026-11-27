export interface NativeReply {
  ok: boolean
  requestId?: string
  error?: string
  [key: string]: unknown
}

export interface NativePaymentRequest {
  targetUrl: string
  kind: "redirect" | "form_post" | "open_in_client" | "deep_link"
  recoveryUrl: string
}

interface NativeWebBridge {
  platform: "android"
  mobile: true
  version: 1
  readonly desktopMode: boolean
  send: (type: string, payload?: Record<string, unknown>) => Promise<NativeReply>
  beginPayment: (payload: NativePaymentRequest) => Promise<NativeReply>
  setDesktopMode: (enabled: boolean) => Promise<NativeReply>
  setOverlayOpen: (open: boolean) => Promise<NativeReply>
  applyViewportMode: (enabled: boolean) => void
  applyInsets: (insets: { top: number; bottom: number; ime: number }) => void
  saveImageFromUrl: (url: string, share: boolean) => Promise<NativeReply>
}

declare global {
  interface Window {
    __AIKAYUN_APP__?: NativeWebBridge
  }
}

export function getNativeApp(): NativeWebBridge | null {
  if (typeof window === "undefined") return null
  const bridge = window.__AIKAYUN_APP__
  return bridge?.platform === "android" && bridge.version === 1 ? bridge : null
}

export function isAikayunApp(): boolean {
  return getNativeApp() !== null
}

const nativeOverlayOwners = new Set<string>()

export function isNativePaymentRecovery(): boolean {
  if (!getNativeApp()) return false
  try {
    return new URL(window.location.href).searchParams.get("paymentReturn") === "1"
  } catch {
    return false
  }
}

export function isNativeDesktopMode(): boolean {
  return getNativeApp()?.desktopMode ?? false
}

export function setNativeOverlayOpen(open: boolean, owner = "default"): void {
  const bridge = getNativeApp()
  if (!bridge) return
  if (open) nativeOverlayOwners.add(owner)
  else nativeOverlayOwners.delete(owner)
  void bridge.setOverlayOpen(nativeOverlayOwners.size > 0).catch(() => {})
}

export async function saveTextInNativeApp(filename: string, text: string): Promise<boolean> {
  const bridge = getNativeApp()
  if (!bridge) return false
  const bytes = new TextEncoder().encode(text)
  await transferBytes(bridge, "saveText", filename, "text/plain;charset=utf-8", bytes)
  return true
}

export async function saveBlobInNativeApp(filename: string, blob: Blob): Promise<boolean> {
  const bridge = getNativeApp()
  if (!bridge) return false
  if (blob.size > MAX_TRANSFER_BYTES) throw new Error("文件过大，无法通过 App 保存")
  await transferBytes(bridge, "saveBlob", filename, blob.type || "application/octet-stream", new Uint8Array(await blob.arrayBuffer()))
  return true
}

export async function shareImageInNativeApp(filename: string, blob: Blob): Promise<boolean> {
  const bridge = getNativeApp()
  if (!bridge) return false
  if (!blob.type.startsWith("image/") || blob.size > MAX_IMAGE_BYTES) throw new Error("图片格式或大小不受支持")
  await transferBytes(bridge, "shareImage", filename, blob.type, new Uint8Array(await blob.arrayBuffer()))
  return true
}

async function transferBytes(
  bridge: NativeWebBridge,
  operation: "saveText" | "saveBlob" | "shareImage",
  filename: string,
  mime: string,
  bytes: Uint8Array,
): Promise<void> {
  const limit = operation === "shareImage" ? MAX_IMAGE_BYTES : MAX_TRANSFER_BYTES
  if (bytes.byteLength > limit) throw new Error("文件过大，无法通过 App 保存")
  const transferId = makeRequestId()
  await requireSuccess(bridge.send("saveBegin", {
    transferId,
    operation,
    filename: safeFilename(filename),
    mime,
    size: bytes.byteLength,
  }))
  try {
    for (let offset = 0, index = 0; offset < bytes.byteLength; offset += CHUNK_BYTES, index += 1) {
      const chunk = bytes.subarray(offset, Math.min(offset + CHUNK_BYTES, bytes.byteLength))
      await requireSuccess(bridge.send("saveChunk", { transferId, index, data: encodeBase64(chunk) }))
    }
    await requireSuccess(bridge.send("saveFinish", { transferId }))
  } catch (error) {
    void bridge.send("saveAbort", { transferId }).catch(() => {})
    throw error
  }
}

function requireSuccess(reply: Promise<NativeReply>): Promise<NativeReply> {
  return reply.then((value) => {
    if (!value.ok) throw new Error(value.error || "App 操作失败")
    return value
  })
}

function safeFilename(value: string): string {
  return value.replace(/[\\/:*?"<>|\u0000-\u001f]/g, "_").trim().slice(0, 100) || "download"
}

function encodeBase64(bytes: Uint8Array): string {
  let binary = ""
  for (let offset = 0; offset < bytes.length; offset += 8192) {
    binary += String.fromCharCode(...bytes.subarray(offset, Math.min(offset + 8192, bytes.length)))
  }
  return btoa(binary)
}

function makeRequestId(): string {
  return typeof crypto !== "undefined" && "randomUUID" in crypto
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(16).slice(2)}`
}

const CHUNK_BYTES = 128 * 1024
const MAX_IMAGE_BYTES = 10 * 1024 * 1024
const MAX_TRANSFER_BYTES = 16 * 1024 * 1024
