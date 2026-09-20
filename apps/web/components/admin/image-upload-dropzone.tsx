"use client"

import { memo, useRef, useState, type DragEvent, type KeyboardEvent } from "react"
import Image from "next/image"
import { AlertCircle, ImagePlus, Loader2, Upload, X } from "lucide-react"
import { toast } from "sonner"
import { cn } from "@/lib/utils"
import { ALLOWED_IMAGE_ACCEPT, validateImageFile } from "@/lib/image-upload"
import { useLocale } from "@/lib/context"

interface ImageUploadDropzoneProps {
  value: string
  onChange: (value: string) => void
  uploadImage: (file: File) => Promise<string>
  disabled?: boolean
}

export const ImageUploadDropzone = memo(function ImageUploadDropzone({ value, onChange, uploadImage, disabled = false }: ImageUploadDropzoneProps) {
  const { t } = useLocale()
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
      if (!url) throw new Error(t("admin.uploadUrlMissing"))
      setFailedUrl(null)
      onChange(url)
      toast.success(t("admin.uploadSuccess"))
    } catch (error: unknown) {
      toast.error(error instanceof Error ? error.message : t("admin.uploadFailed"))
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
    <div className="grid min-w-0 gap-4 sm:grid-cols-[11rem_minmax(0,1fr)] sm:items-start">
      <div
        role="button"
        tabIndex={busy ? -1 : 0}
        aria-disabled={busy}
        aria-busy={uploading}
        className={cn(
          "product-image-stage product-image-stage--upload w-full max-w-44 cursor-pointer border-2 border-dashed text-center transition-colors",
          isDragActive
            ? "border-primary bg-primary/10 ring-4 ring-primary/10"
            : "border-border-subtle hover:border-primary/60 hover:bg-primary/5",
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
          <Image
            src={normalizedValue}
            alt={t("admin.coverPreviewAlt")}
            width={512}
            height={512}
            unoptimized
            className="product-image-stage-image h-full w-full"
            onLoad={() => setFailedUrl(null)}
            onError={() => setFailedUrl(normalizedValue)}
          />
        ) : normalizedValue ? (
          <div className="flex h-full flex-col items-center justify-center gap-1.5 p-3 text-sm text-muted-foreground">
            <AlertCircle className="h-7 w-7 text-amber-500" />
            <span className="font-medium text-foreground">{t("admin.previewFailed")}</span>
            <span className="max-w-full break-words text-xs">{t("admin.previewFailedHint")}</span>
          </div>
        ) : (
          <div className="flex h-full flex-col items-center justify-center gap-1.5 p-3 text-muted-foreground">
            <ImagePlus className="h-8 w-8 text-primary/70" />
            <span className="text-sm font-medium text-foreground">{t("admin.dropImage")}</span>
            <span className="text-xs">{t("admin.imageFormats")}</span>
            <span className="text-xs">{t("admin.maxFileSize")}</span>
          </div>
        )}

        {isDragActive && !busy && (
          <div className="pointer-events-none absolute inset-0 z-10 flex items-center justify-center bg-primary/15 px-3 text-sm font-semibold text-primary">
            {t("admin.releaseToUpload")}
          </div>
        )}
        {uploading && (
          <div className="pointer-events-none absolute inset-0 z-20 flex items-center justify-center gap-2 bg-surface text-sm font-medium text-foreground">
            <Loader2 className="h-5 w-5 animate-spin text-primary" />
            {t("admin.uploading")}
          </div>
        )}
      </div>

      <div className="min-w-0">
        <input
          ref={inputRef}
          type="file"
          accept={ALLOWED_IMAGE_ACCEPT}
          className="hidden"
          disabled={busy}
          onChange={(event) => void handleFile(event.target.files?.[0])}
        />

        <label className="flex min-w-0 flex-col gap-1.5 text-xs font-medium text-muted-foreground">
          {t("admin.imageUrl")}
          <input
            type="text"
            className="h-11 w-full rounded-control border border-input bg-surface px-3 text-sm font-normal text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            placeholder="https://..."
            value={value}
            onChange={(event) => handleUrlChange(event.target.value)}
            disabled={busy}
          />
        </label>

        {normalizedValue && (
          <div className="mt-3 flex flex-wrap items-center gap-2">
            <button
              type="button"
              className="inline-flex items-center gap-1.5 rounded-control border border-input bg-surface px-2.5 py-1.5 text-xs font-medium text-foreground transition-colors hover:bg-surface-muted disabled:pointer-events-none disabled:opacity-50"
              onClick={openFilePicker}
              disabled={busy}
            >
              <Upload className="h-3.5 w-3.5" />
              {t("admin.replaceImage")}
            </button>
            <button
              type="button"
              className="inline-flex items-center gap-1.5 rounded-control px-2.5 py-1.5 text-xs font-medium text-muted-foreground transition-colors hover:bg-destructive/10 hover:text-destructive disabled:pointer-events-none disabled:opacity-50"
              onClick={() => {
                setFailedUrl(null)
                onChange("")
              }}
              disabled={busy}
            >
              <X className="h-3.5 w-3.5" />
              {t("admin.removeImage")}
            </button>
          </div>
        )}
        <p className="mt-2 text-xs text-muted-foreground">{t("admin.imageUsageHint")}</p>
      </div>
    </div>
  )
})
