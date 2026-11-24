"use client"

import { useEffect, useRef, useState } from "react"
import { Upload, type DetailedError, type HttpRequest, type HttpResponse } from "tus-js-client"
import { Loader2, Pause, Play, Video, X } from "lucide-react"
import { toast } from "sonner"

import { Button } from "@/components/ui/button"
import { useAuth, useLocale } from "@/lib/context"
import { adminVideoApi, getCsrfToken } from "@/services/api"

type Phase = "idle" | "preparing" | "uploading" | "paused" | "processing" | "failed" | "ready"

/** One selected file at a time; tus persists its URL, never the browser's file permission. */
export function VideoUpload({ onChanged, onLocated }: { onChanged: () => void; onLocated: (id: string) => void }) {
  const { user } = useAuth()
  const { locale } = useLocale()
  const zh = locale === "zh"
  const input = useRef<HTMLInputElement>(null)
  const upload = useRef<Upload | null>(null)
  const [phase, setPhase] = useState<Phase>("idle")
  const [assetId, setAssetId] = useState<string | null>(null)
  const [name, setName] = useState("")
  const [progress, setProgress] = useState(0)
  const [error, setError] = useState("")
  const [processingFailure, setProcessingFailure] = useState(false)
  const [busy, setBusy] = useState(false)

  useEffect(() => () => { void upload.current?.abort() }, [])

  useEffect(() => {
    if (phase !== "processing" || !assetId) return
    let stopped = false
    let timer: ReturnType<typeof setTimeout>
    const check = async () => {
      try {
        const state = await adminVideoApi.status(assetId)
        if (stopped) return
        if (state.status === "READY") {
          setPhase("ready")
          setProgress(100)
          onChanged()
          if (state.reused) onLocated(state.id)
          toast.success(state.reused ? (zh ? "视频已存在，已复用并定位" : "Existing video reused and located") : (zh ? "视频已处理完成" : "Video is ready"))
          return
        }
        if (state.status === "FAILED") {
          setError(state.error || (zh ? "视频处理失败" : "Video processing failed"))
          setProcessingFailure(!state.reused)
          if (state.reused) upload.current = null
          setPhase("failed")
          onChanged()
          return
        }
        timer = setTimeout(check, 2500)
      } catch (failure) {
        if (stopped) return
        setError(failure instanceof Error ? failure.message : (zh ? "无法读取视频状态" : "Could not read video status"))
        timer = setTimeout(check, 5000)
      }
    }
    void check()
    return () => { stopped = true; clearTimeout(timer) }
  }, [assetId, phase, onChanged, onLocated, zh])

  const choose = async (file: File) => {
    if (!user?.id) { toast.error(zh ? "请先登录管理员账号" : "Sign in as an administrator first"); return }
    if (!/\.(mp4|mov|webm)$/i.test(file.name) || file.size === 0) {
      toast.error(zh ? "请选择非空的 MP4、MOV 或 WebM 视频" : "Choose a non-empty MP4, MOV or WebM video")
      return
    }
    upload.current = null
    setAssetId(null); setPhase("preparing"); setName(file.name); setProgress(0); setError(""); setProcessingFailure(false)
    const fingerprint = async () => JSON.stringify(["myshop-video-v1", user.id, file.name, file.size, file.lastModified, adminVideoApi.tusEndpoint])
    const common = {
      endpoint: adminVideoApi.tusEndpoint,
      fingerprint,
      retryDelays: [0, 1000, 3000, 5000, 10000],
      // tus-js-client retries 409/423 and server/network errors by default,
      // but not 429. A busy shared IP can briefly hit the site's rate limit.
      onShouldRetry: (failure: DetailedError) => {
        const status = failure.originalResponse?.getStatus() ?? 0
        return (status < 400 || status >= 500 || status === 408 || status === 409 || status === 423 || status === 429)
          && (typeof navigator === "undefined" || navigator.onLine !== false)
      },
      onAfterResponse: async (request: HttpRequest, response: HttpResponse) => {
        const status = response.getStatus()
        // tus-js-client otherwise treats a transient HEAD failure as a missing
        // upload, deletes its saved fingerprint, and tries a fresh POST. Turn it
        // into a retryable request failure before the client sees the response.
        if (request.getMethod() === "HEAD" && (status === 408 || status === 409 || status === 429 || status >= 500)) {
          if (status === 429) {
            const retryAfter = Number(response.getHeader("Retry-After"))
            await new Promise(resolve => setTimeout(resolve, Math.min(10, Math.max(1, Number.isFinite(retryAfter) ? retryAfter : 1)) * 1000))
          }
          throw new Error(`Video upload status temporarily unavailable (HTTP ${status})`)
        }
      },
      removeFingerprintOnSuccess: true,
      onBeforeRequest: (request: { setHeader: (name: string, value: string) => void }) => {
        const csrf = getCsrfToken()
        if (csrf) request.setHeader("X-CSRF-Token", csrf)
      },
      onProgress: (sent: number, total: number) => setProgress(total ? Math.floor(sent * 100 / total) : 0),
      onSuccess: () => { setError(""); setPhase("processing"); onChanged() },
      onError: (failure: Error) => { setError(failure.message); setProcessingFailure(false); setPhase("failed") },
    }
    try {
      // Resolve a prior URL only after checking it still belongs to this account.
      const finder = new Upload(file, common)
      const previous = await finder.findPreviousUploads()
      let match: (typeof previous)[number] | undefined
      let resumed: Awaited<ReturnType<typeof adminVideoApi.statusByUpload>> | undefined
      for (const candidate of previous) {
        if (!candidate.uploadUrl) continue
        try {
          const parsed = new URL(candidate.uploadUrl, window.location.href)
          if (parsed.origin !== window.location.origin) continue
          const state = await adminVideoApi.statusByUpload(parsed.pathname)
          if (state.status === "FAILED" && state.reused) continue
          if (state.size === file.size && state.name === file.name) { match = candidate; resumed = state; break }
        } catch { /* Stale or unauthorized URLs are not reused. */ }
      }
      if (resumed && match) {
        setAssetId(resumed.id)
        if (resumed.status === "PROCESSING" || resumed.status === "READY") {
          setPhase(resumed.status === "READY" ? "ready" : "processing")
          setProgress(100)
          if (resumed.reused) { onLocated(resumed.id); toast.success(zh ? "视频已存在，已复用并定位" : "Existing video reused and located") }
          onChanged()
          return
        }
        if (resumed.status === "FAILED") {
          setError(resumed.error || (zh ? "视频处理失败" : "Video processing failed"))
          setProcessingFailure(true); setPhase("failed"); onChanged()
          return
        }
        // The existing server session already owns its URL. Never create a
        // second upload for the same asset when a resume request fails.
        const client = new Upload(file, { ...common, endpoint: null, chunkSize: resumed.chunk_bytes, metadata: match.metadata })
        client.resumeFromPreviousUpload(match)
        upload.current = client
        setPhase("uploading")
        client.start()
        return
      }
      const session = await adminVideoApi.create(file.name, file.size)
      setAssetId(session.id)
      const client = new Upload(file, {
        ...common, chunkSize: session.chunk_bytes,
        metadata: { asset_id: session.id, filename: file.name, filetype: file.type || "application/octet-stream" },
      })
      client.options.onUploadUrlAvailable = () => { client.options.endpoint = null }
      upload.current = client
      setPhase("uploading")
      client.start()
      onChanged()
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : (zh ? "视频上传失败" : "Video upload failed"))
      setPhase("failed")
    } finally {
      if (input.current) input.current.value = ""
    }
  }

  const pause = async () => {
    try { await upload.current?.abort(); setPhase("paused") }
    catch (failure) { setError(failure instanceof Error ? failure.message : String(failure)) }
  }
  const resume = async () => {
    setError("")
    if (processingFailure && assetId) {
      setBusy(true)
      try { await adminVideoApi.retry(assetId); setPhase("processing"); onChanged() }
      catch (failure) { setError(failure instanceof Error ? failure.message : String(failure)) }
      finally { setBusy(false) }
    } else if (upload.current) {
      setPhase("uploading"); upload.current.start()
    } else {
      setPhase("idle"); input.current?.click()
    }
  }
  const cancel = async () => {
    setBusy(true)
    try {
      // Terminate through tus so its saved browser fingerprint is removed too.
      await upload.current?.abort(true)
      if (assetId) await adminVideoApi.cancel(assetId)
      upload.current = null; setAssetId(null); setPhase("idle"); setError(""); setProgress(0)
      onChanged()
    } catch (failure) { setError(failure instanceof Error ? failure.message : String(failure)); setPhase("failed") }
    finally { setBusy(false) }
  }

  const active = !["idle", "ready"].includes(phase)
  return <div className="space-y-2" data-testid="video-upload">
    <Button type="button" variant="outline" disabled={active || busy} onClick={() => input.current?.click()}><Video className="h-4 w-4" aria-hidden="true" />{zh ? "上传视频" : "Upload video"}</Button>
    <input ref={input} type="file" accept=".mp4,.mov,.webm,video/mp4,video/quicktime,video/webm" className="hidden" onChange={(event) => { const file = event.target.files?.[0]; if (file) void choose(file) }} />
    {active && <div className="space-y-2 rounded-card border border-border-subtle p-3 text-sm">
      <div className="flex flex-wrap items-center gap-2"><span className="min-w-0 flex-1 truncate" title={name}>{name}</span><span>{phase === "preparing" ? (zh ? "准备中" : "Preparing") : phase === "uploading" ? (zh ? "上传中" : "Uploading") : phase === "paused" ? (zh ? "已暂停" : "Paused") : phase === "processing" ? (zh ? "处理中" : "Processing") : (zh ? "失败" : "Failed")}</span></div>
      {(phase === "uploading" || phase === "paused" || phase === "preparing") && <progress aria-label={zh ? "视频上传进度" : "Video upload progress"} className="h-2 w-full" max={100} value={progress}>{progress}%</progress>}
      {error && <p role="alert" className="break-words text-destructive">{error}</p>}
      <div className="flex flex-wrap gap-2">
        {phase === "uploading" && <Button type="button" size="sm" variant="outline" disabled={busy} onClick={() => void pause()}><Pause className="h-4 w-4" />{zh ? "暂停" : "Pause"}</Button>}
        {(phase === "paused" || phase === "failed") && <Button type="button" size="sm" variant="outline" disabled={busy} onClick={() => void resume()}><Play className="h-4 w-4" />{phase === "paused" ? (zh ? "继续" : "Continue") : (zh ? "重试" : "Retry")}</Button>}
        <Button type="button" size="sm" variant="ghost" disabled={busy || phase === "preparing"} onClick={() => void cancel()}>{busy ? <Loader2 className="h-4 w-4 animate-spin" /> : <X className="h-4 w-4" />}{zh ? "取消" : "Cancel"}</Button>
      </div>
    </div>}
  </div>
}
