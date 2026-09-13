"use client"

import { useEffect, useMemo, useState } from "react"
import Image from "next/image"

import { useLocale } from "@/lib/context"
import { AFTER_SALE_MAX_FILES, validateAfterSaleFile } from "@/lib/after-sale-upload"

interface AfterSaleAttachmentPickerProps {
  files: File[]
  onChange: (files: File[]) => void
  inputId: string
  resetKey?: string | number
  disabled?: boolean
  onError?: (message: string | null) => void
}

interface FilePreview {
  file: File
  url: string
}

/** Shared client-side picker for both requests and conversation messages. */
export function AfterSaleAttachmentPicker({
  files,
  onChange,
  inputId,
  resetKey,
  disabled = false,
  onError,
}: AfterSaleAttachmentPickerProps) {
  const { t } = useLocale()
  const [fileError, setFileError] = useState<string | null>(null)
  const previews = useMemo<FilePreview[]>(
    () => files.map(file => ({ file, url: URL.createObjectURL(file) })),
    [files],
  )

  useEffect(() => () => previews.forEach(preview => URL.revokeObjectURL(preview.url)), [previews])

  useEffect(() => {
    setFileError(null)
    onError?.(null)
  }, [resetKey]) // eslint-disable-line react-hooks/exhaustive-deps

  const setError = (message: string | null) => {
    setFileError(message)
    onError?.(message)
  }

  const handleFiles = async (selected: FileList | null) => {
    if (!selected || selected.length === 0) return
    setError(null)
    const next = [...files]
    for (const file of Array.from(selected)) {
      if (next.length >= AFTER_SALE_MAX_FILES) {
        setError(t("order.afterSaleTooManyFiles"))
        break
      }
      const validationError = await validateAfterSaleFile(file)
      if (validationError) {
        setError(validationError === "SIZE"
          ? t("order.afterSaleFileTooLarge")
          : t("order.afterSaleFileInvalid"))
        continue
      }
      if (!next.some(existing => fileKey(existing) === fileKey(file))) next.push(file)
    }
    onChange(next)
  }

  const removeFile = (file: File) => {
    onChange(files.filter(candidate => fileKey(candidate) !== fileKey(file)))
    setError(null)
  }

  return (
    <div className="space-y-2">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <label htmlFor={inputId} className="text-sm font-medium text-foreground">{t("order.afterSaleUpload")}</label>
        <span className="text-xs text-muted-foreground">{files.length}/{AFTER_SALE_MAX_FILES}</span>
      </div>
      <input
        id={inputId}
        type="file"
        multiple
        accept=".jpg,.jpeg,.png,.webp,image/jpeg,image/png,image/webp"
        disabled={disabled || files.length >= AFTER_SALE_MAX_FILES}
        onChange={event => { void handleFiles(event.target.files); event.currentTarget.value = "" }}
        className="block w-full rounded-control border border-input bg-surface px-3 py-2 text-sm text-foreground file:mr-3 file:rounded-control file:border-0 file:bg-primary file:px-3 file:py-1.5 file:text-xs file:font-medium file:text-primary-foreground"
      />
      <p className="text-xs text-muted-foreground">{t("order.afterSaleUploadHint")}</p>
      {fileError && <p className="text-xs text-destructive" role="alert">{fileError}</p>}
      {previews.length > 0 && (
        <div className="grid grid-cols-3 gap-2 sm:grid-cols-5" aria-label={t("order.afterSaleUpload")}>
          {previews.map(preview => (
            <div key={fileKey(preview.file)} className="group relative aspect-square overflow-hidden rounded-control border border-border-subtle bg-surface-muted">
              <Image src={preview.url} alt={preview.file.name} fill unoptimized sizes="120px" className="object-cover" />
              <button
                type="button"
                onClick={() => removeFile(preview.file)}
                disabled={disabled}
                className="absolute inset-x-1 bottom-1 rounded bg-black/70 px-1 py-1 text-[10px] font-medium text-white opacity-0 transition-opacity group-hover:opacity-100 focus-visible:opacity-100 disabled:cursor-not-allowed"
              >
                {t("common.remove")}
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

export function fileKey(file: File) {
  return `${file.name}:${file.size}:${file.lastModified}`
}
