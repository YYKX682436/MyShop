"use client"

import { memo, useEffect, useRef, useState, type DragEvent, type KeyboardEvent } from "react"
import { ImagePlus, Loader2 } from "lucide-react"
import { toast } from "sonner"
import { cn } from "@/lib/utils"
import { ALLOWED_IMAGE_ACCEPT, validateImageFile } from "@/lib/image-upload"
import { createMarkdownImage, insertMarkdownImage, type TextSelection } from "@/lib/markdown-image"
import { MarkdownPreview } from "@/components/admin/markdown-preview"
import { useLocale } from "@/lib/context"

interface MarkdownEditorProps {
  value: string
  onChange: (value: string) => void
  uploadImage: (file: File) => Promise<string>
  maxLength?: number
  id?: string
}

export const MarkdownEditor = memo(function MarkdownEditor({ value, onChange, uploadImage, maxLength, id }: MarkdownEditorProps) {
  const { t } = useLocale()
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const dragDepthRef = useRef(0)
  const currentValueRef = useRef(value)
  const selectionRef = useRef<TextSelection | null>(null)
  const [isDragActive, setIsDragActive] = useState(false)
  const [uploading, setUploading] = useState(false)

  useEffect(() => {
    currentValueRef.current = value
  }, [value])

  const resetInput = () => {
    if (inputRef.current) inputRef.current.value = ""
  }

  const rememberSelection = (element: HTMLTextAreaElement | null = textareaRef.current) => {
    if (!element) return
    const start = element.selectionStart
    const end = element.selectionEnd
    const text = currentValueRef.current
    if (Number.isInteger(start) && Number.isInteger(end) && start >= 0 && end >= start && end <= text.length) {
      selectionRef.current = { start, end }
    }
  }

  const insertImage = (fileName: string, url: string) => {
    const markdownImage = createMarkdownImage(fileName, url)
    const insertion = insertMarkdownImage(currentValueRef.current, markdownImage, selectionRef.current)
    const nextSelection = { start: insertion.cursorPosition, end: insertion.cursorPosition }
    currentValueRef.current = insertion.value
    selectionRef.current = nextSelection
    onChange(insertion.value)

    requestAnimationFrame(() => {
      const textarea = textareaRef.current
      if (!textarea) return
      textarea.focus()
      textarea.selectionStart = insertion.cursorPosition
      textarea.selectionEnd = insertion.cursorPosition
    })
  }

  const handleFile = async (file: File | undefined) => {
    if (!file || uploading) {
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
      insertImage(file.name, url)
      toast.success(t("admin.imageInserted"))
    } catch (error: unknown) {
      toast.error(error instanceof Error ? error.message : t("admin.uploadFailed"))
    } finally {
      setUploading(false)
      resetInput()
    }
  }

  const openFilePicker = () => {
    if (uploading) return
    rememberSelection()
    inputRef.current?.click()
  }

  const handleEditorKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key !== "Tab") rememberSelection(event.currentTarget)
  }

  const handleDragEnter = (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault()
    if (uploading) return
    dragDepthRef.current += 1
    setIsDragActive(true)
  }

  const handleDragOver = (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault()
    if (uploading) return
    event.dataTransfer.dropEffect = "copy"
    setIsDragActive(true)
  }

  const handleDragLeave = (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault()
    if (uploading) return
    dragDepthRef.current = Math.max(0, dragDepthRef.current - 1)
    if (dragDepthRef.current === 0) setIsDragActive(false)
  }

  const handleDrop = (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault()
    event.stopPropagation()
    dragDepthRef.current = 0
    setIsDragActive(false)
    if (uploading) return
    rememberSelection()
    void handleFile(event.dataTransfer.files?.[0])
  }

  return (
    <div className="grid min-w-0 gap-4 lg:grid-cols-2">
      <div className="min-w-0">
        <div className="mb-1.5 flex items-center justify-between gap-3">
          <span className="text-xs text-muted-foreground">{t("admin.markdownHint")}</span>
          <button
            type="button"
            className="inline-flex shrink-0 items-center gap-1 rounded-control px-2 py-1 text-xs font-medium text-muted-foreground transition-colors hover:bg-surface-muted hover:text-foreground disabled:pointer-events-none disabled:opacity-50"
            onMouseDown={() => rememberSelection()}
            onClick={openFilePicker}
            disabled={uploading}
          >
            {uploading ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <ImagePlus className="h-3.5 w-3.5" />}
            {t("admin.insertImage")}
          </button>
        </div>
        <div
          className={cn(
            "relative min-w-0 rounded-card border border-input bg-surface transition-colors",
            isDragActive && "border-primary bg-primary/5 ring-4 ring-primary/10",
          )}
          onDragEnter={handleDragEnter}
          onDragOver={handleDragOver}
          onDragLeave={handleDragLeave}
          onDrop={handleDrop}
        >
          <textarea
            ref={textareaRef}
            id={id}
            className="block min-h-52 w-full resize-y rounded-card border-0 bg-transparent px-3 py-2 text-sm font-mono text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            placeholder={t("admin.markdownPlaceholder")}
            value={value}
            onChange={(event) => {
              currentValueRef.current = event.target.value
              onChange(event.target.value)
              rememberSelection(event.currentTarget)
            }}
            onFocus={(event) => rememberSelection(event.currentTarget)}
            onClick={(event) => rememberSelection(event.currentTarget)}
            onSelect={(event) => rememberSelection(event.currentTarget)}
            onKeyUp={(event) => rememberSelection(event.currentTarget)}
            onKeyDown={handleEditorKeyDown}
            maxLength={maxLength}
            aria-label={t("admin.markdownEditor")}
          />
          {isDragActive && (
            <div className="pointer-events-none absolute inset-0 z-10 flex items-center justify-center rounded-card bg-primary/15 text-sm font-semibold text-primary">
              {t("admin.releaseToInsert")}
            </div>
          )}
          {uploading && (
            <div className="pointer-events-none absolute inset-0 z-20 flex items-center justify-center gap-2 rounded-card bg-surface text-sm font-medium text-foreground">
              <Loader2 className="h-5 w-5 animate-spin text-primary" />
              {t("admin.uploadingAndInsert")}
            </div>
          )}
        </div>
        <input
          ref={inputRef}
          type="file"
          accept={ALLOWED_IMAGE_ACCEPT}
          className="hidden"
          disabled={uploading}
          onChange={(event) => void handleFile(event.target.files?.[0])}
        />
      </div>

      <div className="min-w-0 overflow-hidden rounded-card border border-border-subtle bg-surface">
        <div className="border-b border-border-subtle px-3 py-2 text-sm font-medium text-foreground">{t("admin.markdownPreview")}</div>
        <div className="min-w-0 p-3">
          <MarkdownPreview value={value} emptyMessage={t("admin.markdownEmpty")} />
        </div>
      </div>
    </div>
  )
})
