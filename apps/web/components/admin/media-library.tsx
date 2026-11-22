"use client"

import { useCallback, useEffect, useRef, useState } from "react"
import { Copy, ImagePlus, Loader2, Search, Trash2, Upload, WandSparkles } from "lucide-react"
import { toast } from "sonner"

import { Button } from "@/components/ui/button"
import { PageHeader } from "@/components/admin/shared"
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { useLocale } from "@/lib/context"
import { ALLOWED_IMAGE_ACCEPT, validateImageFile } from "@/lib/image-upload"
import { adminMediaApi, adminProductApi, type MediaInspection, type MediaItem, type MediaOptimization } from "@/services/api"
import { ProductImage } from "@/components/store/product-image"

const PAGE_SIZE = 24

export function MediaLibrary({ onSelect, showHeader = false }: { onSelect?: (item: MediaItem) => void; showHeader?: boolean }) {
  const { t, locale } = useLocale()
  const [search, setSearch] = useState("")
  const [query, setQuery] = useState("")
  const [order, setOrder] = useState<"asc" | "desc">("desc")
  const [page, setPage] = useState(1)
  const [items, setItems] = useState<MediaItem[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)
  const [revision, setRevision] = useState(0)
  const [uploading, setUploading] = useState(false)
  const [preview, setPreview] = useState<MediaItem | null>(null)
  const [managed, setManaged] = useState<MediaItem | null>(null)
  const [inspection, setInspection] = useState<MediaInspection | null>(null)
  const [optimization, setOptimization] = useState<MediaOptimization | null>(null)
  const [inspectionFailed, setInspectionFailed] = useState(false)
  const [inspectionRevision, setInspectionRevision] = useState(0)
  const [working, setWorking] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)
  const manageDialog = useRef<HTMLDialogElement>(null)

  useEffect(() => {
    const timer = window.setTimeout(() => { setQuery(search.trim()); setPage(1) }, 300)
    return () => window.clearTimeout(timer)
  }, [search])

  useEffect(() => {
    const controller = new AbortController()
    setLoading(true)
    setError(false)
    adminMediaApi.getList({ page, page_size: PAGE_SIZE, query, order }, controller.signal)
      .then((result) => {
        if (controller.signal.aborted) return
        const lastPage = Math.max(1, Math.ceil(result.total / PAGE_SIZE))
        if (page > lastPage) { setPage(lastPage); return }
        setItems(result.items); setTotal(result.total)
      })
      .catch(() => { if (!controller.signal.aborted) setError(true) })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [page, query, order, revision])

  useEffect(() => {
    if (!managed) return
    if (!manageDialog.current?.open) manageDialog.current?.showModal()
    const controller = new AbortController()
    setInspection(null); setInspectionFailed(false)
    adminMediaApi.inspect(managed.url.split("/").pop() || "", controller.signal)
      .then(setInspection)
      .catch(() => { if (!controller.signal.aborted) setInspectionFailed(true) })
    return () => controller.abort()
  }, [managed, inspectionRevision])

  const upload = async (file?: File) => {
    if (!file || uploading) return
    const error = validateImageFile(file)
    if (error) { toast.error(error); return }
    setUploading(true)
    try {
      const uploaded = await adminProductApi.uploadImage(file)
      setSearch(""); setQuery(""); setPage(1); setOrder("desc"); setRevision((value) => value + 1)
      const reason = uploaded.reason === "transparency_preserved" ? t("admin.mediaAlphaPreserved")
        : uploaded.reason === "metadata_preserved" ? t("admin.mediaMetadataPreserved")
        : uploaded.reason === "quality_preserved" ? t("admin.mediaQualityPreserved")
        : uploaded.reason === "format_preserved" ? t("admin.mediaFormatPreserved")
        : uploaded.reason === "size_preserved" ? t("admin.mediaSizePreserved")
        : uploaded.reason === "no_saving" ? t("admin.mediaNoSaving") : t("admin.mediaCompressed")
      toast.success(`${t("admin.uploadSuccess")} · ${formatSize(uploaded.original_size)} → ${formatSize(uploaded.stored_size)} · ${reason}`)
    } catch (error) {
      toast.error(error instanceof Error ? error.message : t("admin.uploadFailed"))
    } finally { setUploading(false); if (inputRef.current) inputRef.current.value = "" }
  }

  const copy = useCallback(async (item: MediaItem) => {
    try {
      await navigator.clipboard.writeText(new URL(item.url, window.location.origin).href)
      toast.success(t("admin.mediaCopied"))
    } catch { toast.error(t("admin.mediaCopyFailed")) }
  }, [t])

  const manage = (item: MediaItem) => {
    setPreview(null)
    setOptimization(null)
    setManaged(item)
  }

  const perform = async (action: "delete" | "optimize") => {
    if (!managed || !inspection || working) return
    setWorking(true)
    try {
      const filename = managed.url.split("/").pop() || ""
      if (action === "delete") {
        await adminMediaApi.delete(filename)
        toast.success(t("admin.mediaDeleted"))
      } else {
        const result = await adminMediaApi.optimize(filename)
        setOptimization(result)
        toast.success(t("admin.mediaOptimized"))
      }
      if (action === "delete") manageDialog.current?.close()
      setRevision((value) => value + 1)
    } catch (error) {
      toast.error(error instanceof Error ? error.message : t("admin.mediaInspectFailed"))
      setInspectionRevision((value) => value + 1)
      setRevision((value) => value + 1)
    } finally { setWorking(false) }
  }

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE))
  return (
    <div className="min-w-0 space-y-4">
      {showHeader && <PageHeader title={t("admin.media")} onRefresh={() => { setLoading(true); setRevision((value) => value + 1) }} refreshing={loading} className="mb-5 sm:mb-6" />}
      <div className="flex flex-wrap items-center gap-2">
        <div className="relative min-w-40 flex-1">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
          <Input type="search" value={search} onChange={(event) => setSearch(event.target.value)} placeholder={t("admin.mediaSearch")} aria-label={t("admin.mediaSearch")} maxLength={120} className="pl-9" />
        </div>
        <select aria-label={t("admin.mediaNewest")} value={order} onChange={(event) => { setOrder(event.target.value as "asc" | "desc"); setPage(1) }} className="h-10 rounded-control border border-input bg-surface px-3 text-sm text-foreground">
          <option value="desc">{t("admin.mediaNewest")}</option><option value="asc">{t("admin.mediaOldest")}</option>
        </select>
        <Button type="button" onClick={() => inputRef.current?.click()} disabled={uploading}>
          {uploading ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" /> : <Upload className="h-4 w-4" aria-hidden="true" />}{t("admin.upload")}
        </Button>
        <input ref={inputRef} type="file" accept={ALLOWED_IMAGE_ACCEPT} className="hidden" onChange={(event) => void upload(event.target.files?.[0])} />
      </div>
      {loading ? <div className="flex min-h-48 items-center justify-center text-sm text-muted-foreground" role="status"><Loader2 className="mr-2 h-4 w-4 animate-spin" aria-hidden="true" />{t("common.loading")}</div>
        : error ? <div className="flex min-h-48 flex-col items-center justify-center gap-3 text-sm text-destructive" role="alert">{t("admin.loadError")}<Button type="button" variant="outline" onClick={() => setRevision((value) => value + 1)}>{t("common.retry")}</Button></div>
          : items.length === 0 ? <div className="flex min-h-48 flex-col items-center justify-center gap-2 rounded-card border border-dashed border-border-subtle text-sm text-muted-foreground"><ImagePlus className="h-7 w-7" aria-hidden="true" />{t("admin.mediaEmpty")}</div>
            : <ul className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5">
              {items.map((item) => <li key={item.url} className="min-w-0 overflow-hidden rounded-card border border-border-subtle bg-surface">
                <button type="button" aria-label={`${t("admin.mediaPreview")}: ${item.name}`} onClick={() => setPreview(item)} className="relative block aspect-square w-full bg-surface-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
                  <ProductImage src={item.url} alt="" sizes="(max-width: 640px) 50vw, 240px" className="object-contain" />
                </button>
                <div className="space-y-1 p-3">
                  <p className="truncate text-sm font-medium" title={item.name}>{item.name}</p>
                  <p className="text-xs text-muted-foreground">{item.format.toUpperCase()} · {formatSize(item.size)}</p>
                  <p className="text-xs text-muted-foreground"><time dateTime={new Date(item.uploaded_at).toISOString()}>{new Date(item.uploaded_at).toLocaleString(locale === "zh" ? "zh-CN" : "en-US")}</time></p>
                  <div className="flex flex-wrap gap-1 pt-1">
                    <Button type="button" size="icon" variant="ghost" className="h-9 w-9" aria-label={t("admin.mediaCopy")} title={t("admin.mediaCopy")} onClick={() => void copy(item)}><Copy aria-hidden="true" /></Button>
                    {showHeader && <Button type="button" size="icon" variant="ghost" className="h-9 w-9" aria-label={t("admin.mediaOptimize")} title={t("admin.mediaOptimize")} onClick={() => manage(item)}><WandSparkles aria-hidden="true" /></Button>}
                    {showHeader && <Button type="button" size="icon" variant="ghost" className="h-9 w-9 text-destructive" aria-label={t("admin.mediaDelete")} title={t("admin.mediaDelete")} onClick={() => manage(item)}><Trash2 aria-hidden="true" /></Button>}
                    {onSelect && <Button type="button" size="sm" variant="outline" onClick={() => onSelect(item)}>{t("admin.mediaUse")}</Button>}
                  </div>
                </div>
              </li>)}
            </ul>}
      {!loading && !error && totalPages > 1 && <div className="flex items-center justify-end gap-3 text-sm text-muted-foreground">
        <Button type="button" variant="outline" size="sm" disabled={page <= 1} onClick={() => setPage((value) => value - 1)}>{t("admin.mediaPrevious")}</Button>
        <span>{page} / {totalPages}</span>
        <Button type="button" variant="outline" size="sm" disabled={page >= totalPages} onClick={() => setPage((value) => value + 1)}>{t("admin.mediaNext")}</Button>
      </div>}
      <Dialog open={!!preview} onOpenChange={(open) => { if (!open) setPreview(null) }}>
        <DialogContent className="flex max-h-[90dvh] max-w-4xl flex-col overflow-hidden">
          <DialogHeader><DialogTitle>{t("admin.mediaPreview")}</DialogTitle><DialogDescription className="break-all">{preview?.name}</DialogDescription></DialogHeader>
          {preview && <><div className="relative min-h-48 h-[min(60dvh,40rem)] w-full overflow-hidden rounded-card bg-surface-muted"><ProductImage src={preview.url} alt={preview.name} sizes="(max-width: 768px) 90vw, 768px" className="object-contain" /></div><div className="flex flex-wrap justify-end gap-2"><Button type="button" variant="outline" onClick={() => void copy(preview)}>{t("admin.mediaCopy")}</Button>{showHeader && <Button type="button" variant="outline" onClick={() => manage(preview)}>{t("admin.mediaOptimize")}</Button>}{showHeader && <Button type="button" variant="destructive" onClick={() => manage(preview)}>{t("admin.mediaDelete")}</Button>}{onSelect && <Button type="button" onClick={() => { onSelect(preview); setPreview(null) }}>{t("admin.mediaUse")}</Button>}</div></>}
        </DialogContent>
      </Dialog>
      <dialog ref={manageDialog} aria-labelledby="media-manage-title" onClose={() => setManaged(null)}
        onCancel={(event) => { if (working) event.preventDefault() }}
        className="media-manage-dialog myshop-dialog static-material max-h-[90dvh] w-[calc(100%-2rem)] max-w-lg overflow-y-auto rounded-surface p-6 text-foreground">
        <div className="space-y-4">
          <div><h2 id="media-manage-title" className="text-lg font-semibold">{t("admin.mediaInspect")}</h2><p className="break-all text-sm text-muted-foreground">{managed?.name}</p></div>
          {optimization ? <div className="space-y-3 text-sm" role="status">
            <p>{formatSize(optimization.original_size)} → {formatSize(optimization.optimized_size)}</p>
            <div><p className="font-medium">{t("admin.mediaChangedCovers")} · {optimization.replaced_covers}</p><ul className="mt-1 text-muted-foreground">{optimization.changed_products.map((product) => <li key={product.id}>{product.title}</li>)}</ul></div>
            <div><p className="font-medium">{t("admin.mediaRetained")}</p>{optimization.retained_references.length ? <ul className="mt-1 text-muted-foreground">{optimization.retained_references.map((ref) => <li key={ref.table}>{ref.source} · {ref.count}</li>)}</ul> : <p className="mt-1 text-muted-foreground">{t("admin.mediaUnused")}</p>}</div>
          </div> : inspectionFailed ? <div role="alert" className="space-y-2 text-sm text-destructive"><p>{t("admin.mediaInspectFailed")}</p><Button type="button" size="sm" variant="outline" onClick={() => setInspectionRevision((value) => value + 1)}>{t("common.retry")}</Button></div>
            : !inspection ? <div role="status" className="flex items-center gap-2 text-sm text-muted-foreground"><Loader2 className="h-4 w-4 animate-spin" />{t("common.loading")}</div>
              : <div className="space-y-3 text-sm">
                <p>{inspection.format.toUpperCase()} · {formatSize(inspection.size)}{inspection.width && inspection.height ? ` · ${inspection.width} × ${inspection.height}` : ""}</p>
                <div><p className="font-medium">{t("admin.mediaReferences")}</p>{inspection.references.length ? <ul className="mt-1 space-y-1 text-muted-foreground">{inspection.references.map((ref) => <li key={ref.table}>{ref.source} · {ref.count}</li>)}</ul> : <p className="mt-1 text-muted-foreground">{t("admin.mediaUnused")}</p>}</div>
                <p>{t("admin.mediaDeleteImpact")}</p>
                <p className="text-muted-foreground">{t("admin.mediaExternalWarning")}</p>
                <p className="text-muted-foreground">{t(inspection.optimizable ? "admin.mediaOptimizationReady" : "admin.mediaOptimizationSkipped")}</p>
              </div>}
          <div className="flex flex-wrap justify-end gap-2">
            <Button type="button" variant="outline" autoFocus disabled={working} onClick={() => manageDialog.current?.close()}>{t(optimization ? "common.close" : "common.cancel")}</Button>
            {!optimization && <Button type="button" variant="outline" disabled={working || !inspection?.optimizable} onClick={() => void perform("optimize")}>{t("admin.mediaConfirmOptimize")}</Button>}
            {!optimization && <Button type="button" variant="destructive" disabled={working || !inspection} onClick={() => void perform("delete")}>{t("admin.mediaConfirmDelete")}</Button>}
          </div>
        </div>
      </dialog>
    </div>
  )
}

function formatSize(bytes: number) {
  return bytes < 1024 ? `${bytes} B` : bytes < 1024 * 1024 ? `${(bytes / 1024).toFixed(1)} KB` : `${(bytes / 1024 / 1024).toFixed(2)} MB`
}

export function MediaPicker({ onSelect, disabled = false, onOpen }: { onSelect: (item: MediaItem) => void; disabled?: boolean; onOpen?: () => void }) {
  const { t } = useLocale()
  const [open, setOpen] = useState(false)
  return <>
    <Button type="button" variant="outline" size="sm" disabled={disabled} onClick={() => { onOpen?.(); setOpen(true) }}><ImagePlus className="h-4 w-4" aria-hidden="true" />{t("admin.mediaSelect")}</Button>
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="flex max-h-[90dvh] max-w-6xl flex-col overflow-hidden">
        <DialogHeader><DialogTitle>{t("admin.mediaSelect")}</DialogTitle><DialogDescription className="sr-only">{t("admin.media")}</DialogDescription></DialogHeader>
        <div className="min-h-0 overflow-y-auto"><MediaLibrary onSelect={(item) => { onSelect(item); setOpen(false) }} /></div>
      </DialogContent>
    </Dialog>
  </>
}
