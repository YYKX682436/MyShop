"use client"

import { useEffect, useRef, useState, type DragEvent, type KeyboardEvent } from "react"
import { ImagePlus, Loader2 } from "lucide-react"
import { toast } from "sonner"
import { cn } from "@/lib/utils"
import { ALLOWED_IMAGE_ACCEPT, validateImageFile } from "@/lib/image-upload"
import { createMarkdownImage, insertMarkdownImage, type TextSelection } from "@/lib/markdown-image"
import { MarkdownPreview } from "@/components/admin/markdown-preview"

interface MarkdownEditorProps {
  value: string
  onChange: (value: string) => void
  uploadImage: (file: File) => Promise<string>
}

export function MarkdownEditor({ value, onChange, uploadImage }: MarkdownEditorProps) {
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
      if (!url) throw new Error("上传未返回图片地址")
      insertImage(file.name, url)
      toast.success("图片已插入")
    } catch (error: unknown) {
      toast.error(error instanceof Error ? error.message : "上传失败")
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
          <span className="text-xs text-muted-foreground">支持 Markdown 文本和本地图片拖拽</span>
          <button
            type="button"
            className="inline-flex shrink-0 items-center gap-1 rounded-md px-2 py-1 text-xs font-medium text-muted-foreground transition-colors hover:bg-accent hover:text-foreground disabled:pointer-events-none disabled:opacity-50"
            onMouseDown={() => rememberSelection()}
            onClick={openFilePicker}
            disabled={uploading}
          >
            {uploading ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <ImagePlus className="h-3.5 w-3.5" />}
            插入图片
          </button>
        </div>
        <div
          className={cn(
            "relative min-w-0 rounded-lg border border-input bg-background transition-colors",
            isDragActive && "border-primary bg-primary/5 ring-4 ring-primary/10",
          )}
          onDragEnter={handleDragEnter}
          onDragOver={handleDragOver}
          onDragLeave={handleDragLeave}
          onDrop={handleDrop}
        >
          <textarea
            ref={textareaRef}
            className="block min-h-52 w-full resize-y rounded-lg border-0 bg-transparent px-3 py-2 text-sm font-mono text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            placeholder={"支持 Markdown 格式编辑\n# 标题  ## 二级标题  ### 三级标题\n**粗体**  *斜体*  空一行为段落换行\n![图片描述](图片URL) — 可点击「插入图片」或拖拽图片自动生成"}
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
            aria-label="商品详细说明 Markdown 编辑器"
          />
          {isDragActive && (
            <div className="pointer-events-none absolute inset-0 z-10 flex items-center justify-center rounded-lg bg-primary/15 text-sm font-semibold text-primary">
              松开即可插入图片
            </div>
          )}
          {uploading && (
            <div className="pointer-events-none absolute inset-0 z-20 flex items-center justify-center gap-2 rounded-lg bg-background/75 text-sm font-medium text-foreground backdrop-blur-[1px]">
              <Loader2 className="h-5 w-5 animate-spin text-primary" />
              正在上传并插入...
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

      <div className="min-w-0 overflow-hidden rounded-lg border border-border bg-card">
        <div className="border-b border-border px-3 py-2 text-sm font-medium text-foreground">实时预览</div>
        <div className="min-w-0 p-3">
          <MarkdownPreview value={value} />
        </div>
      </div>
    </div>
  )
}
