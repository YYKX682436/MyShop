"use client"

import { useRef, useState, type DragEvent, type KeyboardEvent } from "react"
import { AlertCircle, ImagePlus, Loader2, Upload, X } from "lucide-react"
import { toast } from "sonner"
import { cn } from "@/lib/utils"
import { ALLOWED_IMAGE_ACCEPT, validateImageFile } from "@/lib/image-upload"

interface ImageUploadDropzoneProps {
  value: string
  onChange: (value: string) => void
  uploadImage: (file: File) => Promise<string>
  disabled?: boolean
}

export function ImageUploadDropzone({ value, onChange, uploadImage, disabled = false }: ImageUploadDropzoneProps) {
  const inputRef = useRef<HTMLInputElement>(null)
  const dragDepthRef = useRef(0)
  const [isDragActive, setIsDragActive] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [failedUrl, setFailedUrl] = useState<string | null>(null)

  const normalizedValue = value.trim()
  const previewFailed = normalizedValue.length > 0 && failedUrl === normalizedValue
  const busy = disabled || uploading

  const resetInput = () => {
    if (inputRef.current) inputRef.current.value = ""
  }

  const handleFile = async (file: File | undefined) => {
    if (!file || busy) {
      resetInput()
      return
    }

    const validationError = validateImageFile(file)
    if (validationError) {
      toast.error(validationError)
      resetInput()
      return
    }

    setUploading(true)
    try {
      const url = await uploadImage(file)
      if (!url) throw new Error("上传未返回图片地址")
      setFailedUrl(null)
      onChange(url)
      toast.success("上传成功")
    } catch (error: unknown) {
      toast.error(error instanceof Error ? error.message : "上传失败")
    } finally {
      setUploading(false)
      resetInput()
    }
  }

  const openFilePicker = () => {
    if (!busy) inputRef.current?.click()
  }

  const handleZoneKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault()
      openFilePicker()
    }
  }

  const handleDragEnter = (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault()
    if (busy) return
    dragDepthRef.current += 1
    setIsDragActive(true)
  }

  const handleDragOver = (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault()
    if (busy) return
    event.dataTransfer.dropEffect = "copy"
    setIsDragActive(true)
  }

  const handleDragLeave = (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault()
    if (busy) return
    dragDepthRef.current = Math.max(0, dragDepthRef.current - 1)
    if (dragDepthRef.current === 0) setIsDragActive(false)
  }

  const handleDrop = (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault()
    event.stopPropagation()
    dragDepthRef.current = 0
    setIsDragActive(false)
    if (busy) return
    void handleFile(event.dataTransfer.files?.[0])
  }

  const handleUrlChange = (nextValue: string) => {
    setFailedUrl(null)
    onChange(nextValue)
  }

  return (
    <div className="min-w-0">
      <div
        role="button"
        tabIndex={busy ? -1 : 0}
        aria-disabled={busy}
        aria-busy={uploading}
        className={cn(
          "relative flex min-h-52 w-full cursor-pointer flex-col items-center justify-center overflow-hidden rounded-xl border-2 border-dashed bg-muted/20 p-4 text-center transition-colors",
          isDragActive
            ? "border-primary bg-primary/10 ring-4 ring-primary/10"
            : "border-border hover:border-primary/60 hover:bg-primary/5",
          busy && "cursor-not-allowed opacity-70",
        )}
        onClick={openFilePicker}
        onKeyDown={handleZoneKeyDown}
        onDragEnter={handleDragEnter}
        onDragOver={handleDragOver}
        onDragLeave={handleDragLeave}
        onDrop={handleDrop}
      >
        {normalizedValue && !previewFailed ? (
          <img
            src={normalizedValue}
            alt="商品封面预览"
            className="max-h-64 max-w-full rounded-lg object-contain"
            onLoad={() => setFailedUrl(null)}
            onError={() => setFailedUrl(normalizedValue)}
          />
        ) : normalizedValue ? (
          <div className="flex flex-col items-center gap-2 text-sm text-muted-foreground">
            <AlertCircle className="h-8 w-8 text-amber-500" />
            <span className="font-medium text-foreground">图片预览失败</span>
            <span className="max-w-xs break-words text-xs">请检查图片 URL 是否可访问，表单仍可继续保存。</span>
          </div>
        ) : (
          <div className="flex flex-col items-center gap-2 text-muted-foreground">
            <ImagePlus className="h-9 w-9 text-primary/70" />
            <span className="text-sm font-medium text-foreground">将图片拖拽到这里，或点击选择图片</span>
            <span className="text-xs">支持 JPG / PNG / GIF / WebP / BMP</span>
            <span className="text-xs">最大 10MB</span>
          </div>
        )}

        {isDragActive && !busy && (
          <div className="pointer-events-none absolute inset-0 z-10 flex items-center justify-center bg-primary/15 px-4 text-sm font-semibold text-primary">
            松开即可上传
          </div>
        )}
        {uploading && (
          <div className="pointer-events-none absolute inset-0 z-20 flex items-center justify-center gap-2 bg-background/75 text-sm font-medium text-foreground backdrop-blur-[1px]">
            <Loader2 className="h-5 w-5 animate-spin text-primary" />
            正在上传...
          </div>
        )}
      </div>

      <input
        ref={inputRef}
        type="file"
        accept={ALLOWED_IMAGE_ACCEPT}
        className="hidden"
        disabled={busy}
        onChange={(event) => void handleFile(event.target.files?.[0])}
      />

      {normalizedValue && (
        <div className="mt-2 flex items-center gap-2">
          <button
            type="button"
            className="inline-flex items-center gap-1.5 rounded-md border border-input px-2.5 py-1.5 text-xs font-medium text-foreground transition-colors hover:bg-accent disabled:pointer-events-none disabled:opacity-50"
            onClick={openFilePicker}
            disabled={busy}
          >
            <Upload className="h-3.5 w-3.5" />
            更换图片
          </button>
          <button
            type="button"
            className="inline-flex items-center gap-1.5 rounded-md px-2.5 py-1.5 text-xs font-medium text-muted-foreground transition-colors hover:bg-destructive/10 hover:text-destructive disabled:pointer-events-none disabled:opacity-50"
            onClick={() => {
              setFailedUrl(null)
              onChange("")
            }}
            disabled={busy}
          >
            <X className="h-3.5 w-3.5" />
            移除图片
          </button>
        </div>
      )}

      <label className="mt-3 flex flex-col gap-1.5 text-xs font-medium text-muted-foreground">
        图片 URL
        <input
          type="text"
          className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm font-normal text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
          placeholder="https://..."
          value={value}
          onChange={(event) => handleUrlChange(event.target.value)}
          disabled={busy}
        />
      </label>
      <p className="mt-1.5 text-xs text-muted-foreground">图片会用于商品卡和详情页展示；移除图片只会清空 URL，不会删除服务器文件。</p>
    </div>
  )
}
