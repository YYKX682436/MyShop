"use client"

import { memo, useEffect, useRef, useState, type ReactNode } from "react"
import { ImagePlus, Link2, Loader2 } from "lucide-react"
import { toast } from "sonner"
import type Vditor from "vditor"
import "vditor/dist/index.css"

import { ALLOWED_IMAGE_ACCEPT, validateImageFile } from "@/lib/image-upload"
import { createMarkdownImage } from "@/lib/markdown-image"
import { sanitizeMarkdownUrl } from "@/lib/markdown-security"
import { useLocale, useTheme } from "@/lib/context"
import { cn } from "@/lib/utils"
import { MarkdownPreview } from "@/components/admin/markdown-preview"
import { installVditorImageCompatibility } from "@/lib/vditor-markdown-compat"

export interface MarkdownMediaSelection { name: string; url: string }
export interface MarkdownMediaPickerProps {
  onSelect: (item: MarkdownMediaSelection) => void
  onOpen: () => void
  disabled: boolean
}

interface MarkdownEditorProps {
  value: string
  onChange: (value: string) => void
  uploadImage?: (file: File) => Promise<string>
  renderMediaPicker?: (props: MarkdownMediaPickerProps) => ReactNode
  onUploadingChange?: (uploading: boolean) => void
  maxLength?: number
  id?: string
}

type SavedSelection = { mode: "sv"; start: number; end: number } | { mode: "wysiwyg" | "ir"; range: Range }
type PendingResult = { token: string; file: File; url?: string; error?: string }
type PendingUpload = { marker: string; file: File; upload: (file: File) => Promise<string> }
type UploadResolution = { markdown: string; html: string }
type UndoPatch = { diffs: [number, string][]; start1: number; start2: number; length1: number; length2: number }
type UndoMode = { hasUndo: boolean; lastText: string; redoStack: UndoPatch[][]; undoStack: UndoPatch[][] }
type UndoAdapter = {
  dmp: {
    patch_apply: (patches: UndoPatch[], text: string) => [string, boolean[]]
    patch_deepCopy: (patches: UndoPatch[]) => UndoPatch[]
    patch_make: (before: string, after: string) => UndoPatch[]
  }
  addToUndoStack: (instance: unknown) => void
  resetIcon: (instance: unknown) => void
  wysiwyg: UndoMode
  ir: UndoMode
  sv: UndoMode
}
type UndoSnapshot = { mode: UndoMode; lastText: string; undoStack: UndoPatch[][]; redoStack: UndoPatch[][]; hasUndo: boolean }

function token() {
  return `MYSHOPUPLOAD${globalThis.crypto.randomUUID().replaceAll("-", "")}END`
}

function imageMarkdown(url: string) {
  // Lute removes angle delimiters when serializing. Percent-encode parentheses
  // so even an unbalanced parenthesis in a valid URL survives Markdown.
  return createMarkdownImage("图片", url.replaceAll("(", "%28").replaceAll(")", "%29"))
}

function editorElement(editor: Vditor) {
  if (editor.getCurrentMode() === "sv") return editor.vditor.sv?.element
  if (editor.getCurrentMode() === "ir") return editor.vditor.ir?.element
  return editor.vditor.wysiwyg?.element
}

function imageHtml(editor: Vditor, markdown: string) {
  const lute = editor.vditor.lute as unknown as { Md2VditorDOM: (value: string) => string }
  const template = document.createElement("template")
  template.innerHTML = lute.Md2VditorDOM(markdown)
  const image = template.content.querySelector("img")
  if (!image) throw new Error("Vditor did not produce an image node")
  return image.outerHTML
}

function replaceHistoryToken(value: string, marker: string, replacement: string) {
  const escaped = [...marker].map(char => char.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"))
  const splitByCaret = "(?:<wbr>|<span class=\"vditor-wbr\"></span>)*"
  const pattern = new RegExp(escaped.join(splitByCaret), "g")
  return value.replace(pattern, match => {
    const caret = match.includes("<span class=\"vditor-wbr\">") ? "<span class=\"vditor-wbr\"></span>"
      : match.includes("<wbr>") ? "<wbr>" : ""
    return replacement + caret
  })
}

function uploadReplacementText(value: string, resolutions: Map<string, UploadResolution>, mode: "wysiwyg" | "ir" | "sv") {
  let result = value
  for (const [marker, resolution] of resolutions)
    result = replaceHistoryToken(result, marker, mode === "sv" ? resolution.markdown : resolution.html)
  return result
}

/**
 * Vditor 4.0.1 stores snapshots as patches from the latest snapshot to the
 * previous one. Rebuild only the affected mode histories with resolved image
 * content, keeping each undo/redo step and the live redo stack intact.
 */
function normalizeUploadUndoHistory(editor: Vditor, resolutions: Map<string, UploadResolution>) {
  const instance = editor.vditor as unknown as { undo: UndoAdapter; currentMode: "wysiwyg" | "ir" | "sv" }
  const undo = instance.undo
  if (!undo?.dmp || !undo[instance.currentMode]) return null

  // Flush the current rendered value through Vditor's ordinary debounced
  // snapshot path before translating history. This does not clear the stack.
  undo.addToUndoStack(editor.vditor)
  const snapshots: UndoSnapshot[] = (["wysiwyg", "ir", "sv"] as const).map(mode => {
    const source = undo[mode]
    const undoStates = [source.lastText]
    for (let index = source.undoStack.length - 1; index >= 1; index--) {
      const [previous] = undo.dmp.patch_apply(undo.dmp.patch_deepCopy(source.undoStack[index]), undoStates.at(-1)!)
      undoStates.push(previous)
    }
    undoStates.reverse()

    const redoStates = [source.lastText]
    for (let index = source.redoStack.length - 1; index >= 0; index--) {
      const inverse = undo.dmp.patch_deepCopy(source.redoStack[index]).reverse()
      inverse.forEach(patch => patch.diffs.forEach(diff => { diff[0] = -diff[0] }))
      const [next] = undo.dmp.patch_apply(inverse, redoStates.at(-1)!)
      redoStates.push(next)
    }

    const resolvedUndoStates = undoStates.map(text => uploadReplacementText(text, resolutions, mode))
    const resolvedRedoStates = redoStates.map(text => uploadReplacementText(text, resolutions, mode))
    const undoStack = source.undoStack.map((patches, index) => index === 0
      ? undo.dmp.patch_deepCopy(patches)
      : undo.dmp.patch_make(resolvedUndoStates[index], resolvedUndoStates[index - 1]))
    const redoStack: UndoPatch[][] = []
    for (let index = resolvedRedoStates.length - 2; index >= 0; index--)
      redoStack.push(undo.dmp.patch_make(resolvedRedoStates[index + 1], resolvedRedoStates[index]))
    return { mode: source, lastText: resolvedUndoStates.at(-1) ?? "", undoStack, redoStack, hasUndo: source.hasUndo }
  })
  return () => {
    for (const snapshot of snapshots) {
      snapshot.mode.lastText = snapshot.lastText
      snapshot.mode.undoStack = snapshot.undoStack
      snapshot.mode.redoStack = snapshot.redoStack
      snapshot.mode.hasUndo = snapshot.hasUndo
    }
    undo.resetIcon(editor.vditor)
  }
}

/** Replace an upload marker directly, without routing the correction through setValue. */
function replaceMarker(editor: Vditor, marker: string, markdown: string, html: string) {
  const element = editorElement(editor)
  if (!element) return
  const focused = document.activeElement === element
  if (element instanceof HTMLTextAreaElement) {
    const old = element.value
    const at = old.indexOf(marker)
    if (at < 0) return
    const adjust = (offset: number) => offset <= at ? offset : offset >= at + marker.length
      ? offset + markdown.length - marker.length : at + markdown.length
    const start = adjust(element.selectionStart)
    const end = adjust(element.selectionEnd)
    element.value = old.replace(marker, markdown)
    if (focused) { element.focus(); element.setSelectionRange(start, end) }
    return
  }

  const selection = window.getSelection()
  const savedRange = focused && selection?.rangeCount && element.contains(selection.getRangeAt(0).commonAncestorContainer)
    ? selection.getRangeAt(0).cloneRange() : null
  const locate = (offset: number, nodes: Text[], bias: "forward" | "backward") => {
    let remaining = offset
    for (let index = 0; index < nodes.length; index++) {
      const node = nodes[index]
      if (remaining < node.length || (remaining === node.length && (bias === "backward" || index === nodes.length - 1)))
        return { node, offset: remaining }
      remaining -= node.length
    }
    const last = nodes.at(-1)
    return last ? { node: last, offset: last.length } : null
  }
  let searchFrom = 0
  for (;;) {
    const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT)
    const nodes: Text[] = []
    while (walker.nextNode()) nodes.push(walker.currentNode as Text)
    const fullText = nodes.map(node => node.data).join("")
    const at = fullText.indexOf(marker, searchFrom)
    if (at < 0) break
    const start = locate(at, nodes, "forward"); const end = locate(at + marker.length, nodes, "backward")
    if (!start || !end) break
    const range = document.createRange()
    range.setStart(start.node, start.offset); range.setEnd(end.node, end.offset)
    const caretInMarker = Boolean(savedRange?.collapsed && range.comparePoint(savedRange.startContainer, savedRange.startOffset) === 0)
    range.deleteContents()
    let image: HTMLImageElement | null = null
    if (html) {
      const template = document.createElement("template"); template.innerHTML = html
      image = template.content.querySelector("img")
      if (image) range.insertNode(image)
    }
    if (savedRange && !caretInMarker) {
      selection?.removeAllRanges(); selection?.addRange(savedRange)
    } else if (focused) {
      const caret = range.cloneRange()
      if (image) caret.setStartAfter(image)
      caret.collapse(true); selection?.removeAllRanges(); selection?.addRange(caret)
    }
    searchFrom = at
  }
}

export const MarkdownEditor = memo(function MarkdownEditor({ value, onChange, uploadImage, renderMediaPicker, onUploadingChange, maxLength, id }: MarkdownEditorProps) {
  const { t, locale } = useLocale()
  const { resolvedTheme } = useTheme()
  const rootRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const editorRef = useRef<Vditor | null>(null)
  const selectionRef = useRef<SavedSelection | null>(null)
  const onChangeRef = useRef(onChange)
  const onUploadingRef = useRef(onUploadingChange)
  const uploadRef = useRef(uploadImage)
  const valueRef = useRef(value)
  const lastPublishedRef = useRef(value)
  const pendingRef = useRef(new Set<string>())
  const uploadResolutionsRef = useRef(new Map<string, UploadResolution>())
  const queueRef = useRef<PendingUpload[]>([])
  const activeUploadsRef = useRef(0)
  const deferredRef = useRef<PendingResult[]>([])
  const commandBeforeRef = useRef<string | null>(null)
  const composingRef = useRef(false)
  const mountedRef = useRef(false)
  const [ready, setReady] = useState(false)
  const [pendingCount, setPendingCount] = useState(0)
  const [dragging, setDragging] = useState(false)
  const [linkOpen, setLinkOpen] = useState(false)
  const [link, setLink] = useState("")
  const [linkError, setLinkError] = useState("")
  const [previewing, setPreviewing] = useState(false)

  onChangeRef.current = onChange
  onUploadingRef.current = onUploadingChange
  uploadRef.current = uploadImage
  valueRef.current = value

  const publish = (markdown: string) => {
    let clean = markdown
    for (const marker of pendingRef.current) clean = clean.replaceAll(marker, "")
    for (const [marker, resolution] of uploadResolutionsRef.current)
      clean = replaceHistoryToken(clean, marker, resolution.markdown)
    lastPublishedRef.current = clean
    onChangeRef.current(clean)
  }

  const rememberSelection = () => {
    const editor = editorRef.current
    if (!editor) return
    const element = editorElement(editor)
    if (!element) return
    if (element instanceof HTMLTextAreaElement) {
      if (document.activeElement === element) selectionRef.current = { mode: "sv", start: element.selectionStart, end: element.selectionEnd }
    } else {
      const selection = window.getSelection()
      if (selection?.rangeCount && element.contains(selection.getRangeAt(0).commonAncestorContainer)) {
        selectionRef.current = { mode: editor.getCurrentMode() as "wysiwyg" | "ir", range: selection.getRangeAt(0).cloneRange() }
      }
    }
  }

  const labelActiveEditor = (editor: Vditor) => {
    const element = editorElement(editor)
    if (!element) return
    if (id) {
      rootRef.current?.querySelectorAll("[id]").forEach(other => {
        if (other !== element && other.id === id) other.removeAttribute("id")
      })
      element.id = id
    }
    element.setAttribute("aria-label", locale === "zh" ? "Markdown 编辑器" : "Markdown editor")
    element.setAttribute("role", "textbox")
    element.setAttribute("aria-multiline", "true")
  }

  const restoreSelection = () => {
    const editor = editorRef.current
    if (!editor) return
    const element = editorElement(editor)
    if (!element) return
    const saved = selectionRef.current
    element.focus()
    if (saved?.mode === "sv" && element instanceof HTMLTextAreaElement) {
      element.setSelectionRange(saved.start, saved.end)
    } else if (saved && saved.mode !== "sv" && saved.mode === editor.getCurrentMode() && element.contains(saved.range.commonAncestorContainer)) {
      const selection = window.getSelection()
      selection?.removeAllRanges(); selection?.addRange(saved.range)
    }
  }

  const insert = (markdown: string) => {
    const editor = editorRef.current
    if (!editor) return
    restoreSelection()
    editor.insertMD(markdown)
    publish(editor.getValue())
    rememberSelection()
  }

  const flushCommand = () => {
    const editor = editorRef.current
    const before = commandBeforeRef.current
    commandBeforeRef.current = null
    if (!mountedRef.current || !editor || before === null || composingRef.current) return
    const after = editor.getValue()
    if (after !== before) publish(after)
  }

  const publishAfterCommand = (element: EventTarget | null) => {
    const editor = editorRef.current
    if (!editor || !(element instanceof Element) || !element.closest(".vditor-toolbar")) return
    const button = element.closest("button")
    if (button?.dataset.type === "edit-mode" || button?.dataset.mode) return
    commandBeforeRef.current = editor.getValue()
    window.setTimeout(flushCommand, 0)
  }

  const finish = (result: PendingResult) => {
    if (!mountedRef.current) return
    if (composingRef.current) { deferredRef.current.push(result); return }
    const editor = editorRef.current
    if (editor) {
      const url = result.url && sanitizeMarkdownUrl(result.url, "image")
      // Lute 4.0.1 emits invalid HTML for escaped brackets in an image alt.
      // A generic label keeps arbitrary file names out of that parser path.
      const markdown = url ? imageMarkdown(url) : ""
      const resolution = { markdown, html: url ? imageHtml(editor, markdown) : "" }
      uploadResolutionsRef.current.set(result.token, resolution)
      const restoreUndo = normalizeUploadUndoHistory(editor, uploadResolutionsRef.current)
      replaceMarker(editor, result.token, markdown, resolution.html)
      restoreUndo?.()
      pendingRef.current.delete(result.token)
      publish(editor.getValue())
      rememberSelection()
      if (!url) toast.error(result.error || t("admin.uploadFailed"))
    } else {
      pendingRef.current.delete(result.token)
    }
    const count = pendingRef.current.size
    setPendingCount(count)
    if (!count) onUploadingRef.current?.(false)
  }

  const runQueue = () => {
    while (mountedRef.current && activeUploadsRef.current < 2 && queueRef.current.length) {
      const job = queueRef.current.shift()!
      activeUploadsRef.current += 1
      void Promise.resolve().then(() => job.upload(job.file))
        .then(url => finish({ token: job.marker, file: job.file, url }))
        .catch(error => finish({ token: job.marker, file: job.file, error: error instanceof Error ? error.message : t("admin.uploadFailed") }))
        .finally(() => {
          activeUploadsRef.current -= 1
          if (mountedRef.current) runQueue()
        })
    }
  }

  const uploadFiles = (files: File[]) => {
    const upload = uploadRef.current
    if (!upload || !editorRef.current || !files.length) return
    const accepted: { file: File; marker: string }[] = []
    for (const file of files) {
      const error = validateImageFile(file)
      if (error) { toast.error(`${file.name}: ${error}`); continue }
      const marker = token()
      pendingRef.current.add(marker)
      accepted.push({ file, marker })
    }
    if (!accepted.length) return
    onUploadingRef.current?.(true)
    setPendingCount(pendingRef.current.size)
    insert(accepted.map(item => item.marker).join("\n\n"))
    queueRef.current.push(...accepted.map(item => ({ ...item, upload })))
    runQueue()
  }

  useEffect(() => {
    let disposed = false
    let observer: MutationObserver | undefined
    const pending = pendingRef.current
    const queue = queueRef.current
    mountedRef.current = true
    const initial = valueRef.current
    void import("vditor").then(({ default: Editor }) => {
      if (disposed || !rootRef.current) return
      const instance = new Editor(rootRef.current, {
        cdn: "/vditor-assets/4.0.1",
        lang: locale === "en" ? "en_US" : "zh_CN",
        theme: resolvedTheme === "dark" ? "dark" : "classic",
        mode: "wysiwyg",
        minHeight: 240,
        cache: { enable: false },
        value: initial,
        toolbar: ["undo", "redo", "|", "headings", "bold", "italic", "|", "list", "ordered-list", "quote", "link", "table", "|", "edit-mode"],
        toolbarConfig: { pin: false },
        counter: { enable: Boolean(maxLength), max: maxLength },
        preview: {
          mode: "editor",
          hljs: { enable: false },
          render: { media: { enable: false } },
          markdown: { sanitize: true, codeBlockPreview: false, mathBlockPreview: false, inlineMath: false, footnotes: false, callout: false },
        },
        hint: { emoji: {}, extend: [] },
        image: { isPreview: false },
        link: { isOpen: false },
        input: markdown => publish(markdown),
        after: () => {
          if (disposed) { instance.destroy(); return }
          editorRef.current = instance
          installVditorImageCompatibility(instance.vditor.lute)
          // Re-render once with the compatibility layer; never publish the
          // normalized result until the user actually edits the document.
          instance.setValue(valueRef.current, true)
          rootRef.current?.querySelector<HTMLButtonElement>('button[data-mode="ir"]')?.style.setProperty("display", "none")
          labelActiveEditor(instance)
          // Menus are created dynamically by Vditor inside the parent form.
          // Ensure their buttons never submit that form implicitly.
          const setButtonTypes = (node: Element) => {
            if (node instanceof HTMLButtonElement && !node.hasAttribute("type")) node.type = "button"
            node.querySelectorAll("button:not([type])").forEach(button => { (button as HTMLButtonElement).type = "button" })
          }
          if (rootRef.current) setButtonTypes(rootRef.current)
          if (rootRef.current) {
            observer = new MutationObserver(records => {
              for (const record of records) for (const node of record.addedNodes) if (node instanceof Element) setButtonTypes(node)
            })
            observer.observe(rootRef.current, { childList: true, subtree: true })
          }
          lastPublishedRef.current = valueRef.current
          setReady(true)
        },
      })
    }).catch(error => {
      if (!disposed) toast.error(error instanceof Error ? error.message : "编辑器加载失败")
    })
    return () => {
      disposed = true; mountedRef.current = false
      observer?.disconnect()
      editorRef.current?.destroy(); editorRef.current = null
      if (pending.size) onUploadingRef.current?.(false)
      pending.clear(); queue.splice(0); deferredRef.current = []
    }
    // The component is keyed by record. Do not reconstruct on each keystroke.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    const editor = editorRef.current
    if (!editor || value === lastPublishedRef.current || pendingRef.current.size) return
    editor.setValue(value, true)
    lastPublishedRef.current = value
  }, [value, ready])

  useEffect(() => {
    if (ready) editorRef.current?.setTheme(resolvedTheme === "dark" ? "dark" : "classic", resolvedTheme)
  }, [ready, resolvedTheme])

  return <div className="min-w-0 space-y-2" data-testid="markdown-editor">
    <div className="flex flex-wrap items-center gap-2" onPointerDownCapture={rememberSelection}>
      {uploadImage && <button type="button" disabled={!ready || previewing} onClick={() => inputRef.current?.click()}
        className="inline-flex items-center gap-1 rounded-control px-2 py-1 text-xs font-medium text-muted-foreground hover:bg-surface-muted disabled:opacity-50">
        {pendingCount ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" /> : <ImagePlus className="h-4 w-4" aria-hidden="true" />}{t("admin.insertImage")}
      </button>}
      {renderMediaPicker?.({ onOpen: rememberSelection, disabled: !ready || previewing, onSelect: item => {
      const safe = sanitizeMarkdownUrl(item.url, "image")
        if (safe) insert(imageMarkdown(safe)); else toast.error(locale === "zh" ? "图片地址无效" : "Invalid image URL")
      } })}
      <button type="button" disabled={!ready || previewing} onClick={() => { setLinkError(""); setLinkOpen(open => !open) }}
        className="inline-flex items-center gap-1 rounded-control px-2 py-1 text-xs font-medium text-muted-foreground hover:bg-surface-muted disabled:opacity-50">
        <Link2 className="h-4 w-4" aria-hidden="true" />{locale === "zh" ? "图片链接" : "Image URL"}
      </button>
      <button type="button" disabled={!ready} onClick={() => {
        if (!previewing) flushCommand()
        setPreviewing(current => !current)
      }} className="inline-flex items-center gap-1 rounded-control px-2 py-1 text-xs font-medium text-muted-foreground hover:bg-surface-muted disabled:opacity-50">
        {previewing ? (locale === "zh" ? "编辑" : "Edit") : (locale === "zh" ? "预览" : "Preview")}
      </button>
      {pendingCount > 0 && <span role="status" className="text-xs text-muted-foreground">{locale === "zh" ? `图片上传中（${pendingCount}）` : `Uploading images (${pendingCount})`}</span>}
    </div>
    {linkOpen && !previewing && <div className="flex flex-wrap items-start gap-2">
      <label className="sr-only" htmlFor={`${id || "markdown"}-image-link`}>{locale === "zh" ? "图片链接" : "Image URL"}</label>
      <input id={`${id || "markdown"}-image-link`} type="url" value={link} onChange={event => { setLink(event.target.value); setLinkError("") }}
        placeholder="https://" className="min-h-10 min-w-0 flex-1 rounded-control border border-input bg-background px-3 text-base" aria-invalid={Boolean(linkError)} />
      <button type="button" className="min-h-10 rounded-control bg-primary px-3 text-sm text-primary-foreground" onClick={() => {
        const safe = sanitizeMarkdownUrl(link, "image")
        if (!safe) { setLinkError(locale === "zh" ? "请输入有效的图片地址" : "Enter a valid image URL"); return }
        insert(imageMarkdown(safe)); setLink(""); setLinkOpen(false)
      }}>{locale === "zh" ? "插入" : "Insert"}</button>
      {linkError && <span role="alert" className="w-full text-xs text-destructive">{linkError}</span>}
    </div>}
    <div hidden={previewing} className={cn("relative min-w-0", dragging && "ring-4 ring-primary/20")}
      onKeyUp={rememberSelection} onMouseUp={rememberSelection}
      onFocusCapture={() => { if (editorRef.current) labelActiveEditor(editorRef.current) }}
      onBlurCapture={flushCommand}
      onClickCapture={event => publishAfterCommand(event.target)}
      onTouchStartCapture={event => publishAfterCommand(event.target)}
      onKeyDownCapture={event => {
        const editor = editorRef.current
        // Safari may deliver compositionend before the confirming keydown.
        // VK_PROCESSKEY (229) still identifies that IME keystroke.
        const composing = event.nativeEvent.isComposing || event.nativeEvent.keyCode === 229
        if (event.altKey && (event.ctrlKey || event.metaKey) && (event.code === "Digit8" || event.key === "8")) {
          event.preventDefault(); event.stopPropagation(); return
        }
        if (editor && !composing && (event.ctrlKey || event.metaKey) && ["z", "y"].includes(event.key.toLowerCase())) {
          commandBeforeRef.current = editor.getValue()
          window.setTimeout(flushCommand, 0)
        }
        if (!editor || editor.getCurrentMode() !== "wysiwyg" || event.key !== "Enter" || event.ctrlKey || event.metaKey || event.altKey || composing) return
        const element = editorElement(editor)
        const selection = window.getSelection()
        if (!element || !(event.target instanceof Node) || !element.contains(event.target) || !selection?.rangeCount || !element.contains(selection.getRangeAt(0).commonAncestorContainer)) return
        const range = selection.getRangeAt(0)
        const start = range.startContainer instanceof Element ? range.startContainer : range.startContainer.parentElement
        // GFM tables cannot represent cell line breaks without raw HTML,
        // which the storefront deliberately does not render.
        if (start?.closest("td,th")) {
          event.preventDefault(); event.stopPropagation()
          toast.error(locale === "zh" ? "表格单元格不支持换行，请使用新行" : "Table cells cannot contain line breaks; use a new row")
          return
        }
        // Vditor has dedicated handling for fenced/inline code.
        if (!event.shiftKey || start?.closest('code,[data-type="code-block"]')) return
        event.preventDefault(); event.stopPropagation()
        range.deleteContents()
        const lineBreak = document.createElement("br")
        range.insertNode(lineBreak)
        // A trailing BR alone cannot hold the caret reliably in contenteditable;
        // Vditor's own renderer uses a zero-width space for an empty line.
        const caret = document.createTextNode("\u200b")
        lineBreak.after(caret)
        range.setStart(caret, 0); range.collapse(true)
        selection.removeAllRanges(); selection.addRange(range)
        publish(editor.getValue()); rememberSelection()
      }}
      onInputCapture={() => queueMicrotask(() => {
        const editor = editorRef.current
        if (mountedRef.current && editor && !composingRef.current) publish(editor.getValue())
      })}
      onCompositionStart={() => { composingRef.current = true }}
      onCompositionEnd={() => queueMicrotask(() => {
        composingRef.current = false
        const results = deferredRef.current.splice(0)
        for (const result of results) finish(result)
        if (editorRef.current) publish(editorRef.current.getValue())
      })}
      onPasteCapture={event => {
        const files = Array.from(event.clipboardData.files).filter(file => file.type.startsWith("image/"))
        if (files.length) {
          event.preventDefault(); event.stopPropagation()
          if (!uploadImage) { toast.error(locale === "zh" ? "当前账号无法上传图片" : "Image upload is unavailable for this account"); return }
          rememberSelection(); uploadFiles(files)
          return
        }
        const html = event.clipboardData.getData("text/html")
        if (!html) return
        const pasted = new DOMParser().parseFromString(html, "text/html")
        if ([...pasted.querySelectorAll("img")].some(image => !sanitizeMarkdownUrl(image.getAttribute("src") || "", "image"))) {
          event.preventDefault(); event.stopPropagation()
          toast.error(locale === "zh" ? "请通过图片上传插入剪贴板中的本地图片" : "Upload local clipboard images using the image button")
        }
      }}
      onDragOver={event => { if (event.dataTransfer.types.includes("Files")) { event.preventDefault(); setDragging(Boolean(uploadImage)) } }}
      onDragLeave={() => setDragging(false)}
      onDropCapture={event => {
        if (!event.dataTransfer.files.length) return
        event.preventDefault(); event.stopPropagation(); setDragging(false)
        if (!uploadImage) { toast.error(locale === "zh" ? "当前账号无法上传图片" : "Image upload is unavailable for this account"); return }
        const range = document.caretRangeFromPoint?.(event.clientX, event.clientY)
        if (range && rootRef.current?.contains(range.commonAncestorContainer)) {
          const selection = window.getSelection(); selection?.removeAllRanges(); selection?.addRange(range); rememberSelection()
        }
        uploadFiles(Array.from(event.dataTransfer.files))
      }}>
      <div ref={rootRef} />
      {!ready && <div className="absolute inset-0 flex items-center justify-center rounded-card bg-surface text-sm text-muted-foreground" role="status">{t("common.loading")}</div>}
    </div>
    {previewing && <div className="content-surface min-h-52 p-3"><MarkdownPreview value={value} /></div>}
    {maxLength && value.length > maxLength && <p role="alert" className="text-xs text-destructive">
      {locale === "zh" ? `正文不能超过 ${maxLength} 个字符` : `Content cannot exceed ${maxLength} characters`}
    </p>}
    {uploadImage && <input ref={inputRef} type="file" accept={ALLOWED_IMAGE_ACCEPT} multiple className="hidden" onChange={event => {
      uploadFiles(Array.from(event.target.files || [])); event.target.value = ""
    }} />}
  </div>
})
