"use client"

import { useCallback, useEffect, useRef, useState } from "react"
import { Copy, ImagePlus, Loader2, Search, Trash2, Upload, Video } from "lucide-react"
import { toast } from "sonner"

import { Button } from "@/components/ui/button"
import { PageHeader } from "@/components/admin/shared"
import { VideoUpload } from "@/components/admin/video-upload"
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { useLocale } from "@/lib/context"
import { ALLOWED_IMAGE_ACCEPT, validateImageFile } from "@/lib/image-upload"
import { adminMediaApi, adminProductApi, adminVideoApi, type MediaInspection, type MediaItem } from "@/services/api"
import { ProductImage } from "@/components/store/product-image"

const PAGE_SIZE = 24

export function MediaLibrary({ onSelect, showHeader = false }: { onSelect?: (item: MediaItem) => void; showHeader?: boolean }) {
  const { t, locale } = useLocale()
  const zh = locale === "zh"
  const selecting = Boolean(onSelect)
  const [search, setSearch] = useState("")
  const [query, setQuery] = useState("")
  const [sort, setSort] = useState<"time" | "size">("time")
  const [order, setOrder] = useState<"asc" | "desc">("desc")
  const [type, setType] = useState("")
  const [usage, setUsage] = useState("")
  const [unused, setUnused] = useState(false)
  const [assetId, setAssetId] = useState<string | undefined>()
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
  const [inspectionFailed, setInspectionFailed] = useState(false)
  const [inspectionRevision, setInspectionRevision] = useState(0)
  const [working, setWorking] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)
  const manageDialog = useRef<HTMLDialogElement>(null)
  const refresh = useCallback(() => setRevision(value => value + 1), [])
  const locate = useCallback((id: string) => {
    setSearch(""); setQuery(""); setPage(1); setOrder("desc"); setSort("time"); setType(""); setUsage(""); setUnused(false); setAssetId(id)
  }, [])

  useEffect(() => {
    const timer = window.setTimeout(() => { setQuery(search.trim()); setPage(1) }, 300)
    return () => window.clearTimeout(timer)
  }, [search])

  useEffect(() => {
    const controller = new AbortController()
    setLoading(true); setError(false)
    adminMediaApi.getList({ page, page_size: PAGE_SIZE, query, order, sort,
      type: selecting ? "image" : type, usage, unused, exclude_avatars: selecting, asset_id: assetId }, controller.signal)
      .then((result) => {
        if (controller.signal.aborted) return
        const lastPage = Math.max(1, Math.ceil(result.total / PAGE_SIZE))
        if (page > lastPage) { setPage(lastPage); return }
        setItems(result.items); setTotal(result.total)
      })
      .catch(() => { if (!controller.signal.aborted) setError(true) })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [page, query, order, sort, type, usage, unused, assetId, selecting, revision])

  useEffect(() => {
    if (!managed) return
    if (!manageDialog.current?.open) manageDialog.current?.showModal()
    const controller = new AbortController()
    setInspection(null); setInspectionFailed(false)
    adminMediaApi.inspect(managed.id, controller.signal)
      .then(setInspection)
      .catch(() => { if (!controller.signal.aborted) setInspectionFailed(true) })
    return () => controller.abort()
  }, [managed, inspectionRevision])

  const upload = async (file?: File) => {
    if (!file || uploading) return
    const validation = validateImageFile(file)
    if (validation) { toast.error(validation); return }
    setUploading(true)
    try {
      const uploaded = await adminProductApi.uploadImage(file)
      setSearch(""); setQuery(""); setPage(1); setOrder("desc"); setSort("time"); setType(""); setUsage(""); setUnused(false)
      setAssetId(uploaded.asset_id); refresh()
      toast.success(uploaded.reused
        ? (zh ? "资源已存在，已复用并定位" : "Existing asset reused and located")
        : `${t("admin.uploadSuccess")} · ${formatSize(uploaded.original_size)} → ${formatSize(uploaded.stored_size)}`)
      if (uploaded.reason === "color_unspecified") toast.warning(zh ? "源文件未声明色彩配置，已按 sRGB 处理" : "The source has no colour profile; sRGB was assumed")
    } catch (failure) {
      toast.error(failure instanceof Error ? failure.message : t("admin.uploadFailed"))
    } finally { setUploading(false); if (inputRef.current) inputRef.current.value = "" }
  }

  const copy = useCallback(async (item: MediaItem) => {
    if (!item.url || item.status !== "ready") return
    try {
      await navigator.clipboard.writeText(new URL(item.url, window.location.origin).href)
      toast.success(t("admin.mediaCopied"))
    } catch { toast.error(t("admin.mediaCopyFailed")) }
  }, [t])

  const manage = (item: MediaItem) => { setPreview(null); setManaged(item) }
  const performDelete = async () => {
    if (!managed || !inspection || working) return
    setWorking(true)
    try {
      if (managed.type === "video" && (managed.status === "uploading" || managed.status === "processing" || managed.status === "failed" || (managed.status === "deleted" && (managed.processing_error === "上传已取消" || managed.reused_asset_id))))
        await adminVideoApi.cancel(managed.id)
      else await adminMediaApi.delete(managed.id)
      toast.success(t("admin.mediaDeleted"))
      manageDialog.current?.close(); refresh()
    } catch (failure) {
      toast.error(failure instanceof Error ? failure.message : t("admin.mediaInspectFailed"))
      setInspectionRevision(value => value + 1); refresh()
    } finally { setWorking(false) }
  }
  const retryVideo = async (item: MediaItem) => {
    try { await adminVideoApi.retry(item.id); toast.success(zh ? "已重新开始处理" : "Processing restarted"); refresh() }
    catch (failure) { toast.error(failure instanceof Error ? failure.message : String(failure)) }
  }
  const retryVideoCleanup = async (item: MediaItem) => {
    try { await adminVideoApi.cleanup(item.id); toast.success(zh ? "临时文件已清理" : "Temporary upload cleaned"); refresh() }
    catch (failure) { toast.error(failure instanceof Error ? failure.message : String(failure)) }
  }

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE))
  const selectClass = "h-10 rounded-control border border-input bg-surface px-3 text-sm text-foreground"
  const usageLabel = (value: string) => ({ product: zh ? "商品" : "Products", avatar: zh ? "头像" : "Avatars", site: zh ? "站点" : "Site", other: zh ? "其他" : "Other" }[value] || value)
  return <div className="min-w-0 space-y-4">
    {showHeader && <PageHeader title={t("admin.media")} onRefresh={() => { setLoading(true); refresh() }} refreshing={loading} className="mb-5 sm:mb-6" />}
    <div className="flex flex-wrap items-center gap-2">
      <div className="relative min-w-40 flex-1">
        <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
        <Input type="search" value={search} onChange={event => { setAssetId(undefined); setSearch(event.target.value) }} placeholder={t("admin.mediaSearch")} aria-label={t("admin.mediaSearch")} maxLength={120} className="pl-9" />
      </div>
      <select aria-label={zh ? "排序" : "Sort"} value={`${sort}-${order}`} onChange={event => { const [s, o] = event.target.value.split("-"); setSort(s as "time" | "size"); setOrder(o as "asc" | "desc"); setPage(1) }} className={selectClass}>
        <option value="time-desc">{t("admin.mediaNewest")}</option><option value="time-asc">{t("admin.mediaOldest")}</option>
        <option value="size-desc">{zh ? "体积从大到小" : "Largest first"}</option><option value="size-asc">{zh ? "体积从小到大" : "Smallest first"}</option>
      </select>
      {showHeader && <select aria-label={zh ? "资源类型" : "Media type"} value={type} onChange={event => { setType(event.target.value); setAssetId(undefined); setPage(1) }} className={selectClass}>
        <option value="">{zh ? "全部类型" : "All types"}</option><option value="image">{zh ? "图片" : "Images"}</option><option value="video">{zh ? "视频" : "Videos"}</option>
      </select>}
      <select aria-label={zh ? "用途" : "Usage"} value={usage} onChange={event => { setUsage(event.target.value); setAssetId(undefined); setPage(1) }} className={selectClass}>
        <option value="">{zh ? "全部用途" : "All uses"}</option><option value="product">{usageLabel("product")}</option>{showHeader && <option value="avatar">{usageLabel("avatar")}</option>}<option value="site">{usageLabel("site")}</option><option value="other">{usageLabel("other")}</option>
      </select>
      <label className="flex items-center gap-1 text-sm"><input type="checkbox" checked={unused} onChange={event => { setUnused(event.target.checked); setAssetId(undefined); setPage(1) }} />{zh ? "未使用" : "Unused"}</label>
      <Button type="button" onClick={() => inputRef.current?.click()} disabled={uploading}>{uploading ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" /> : <Upload className="h-4 w-4" aria-hidden="true" />}{t("admin.upload")}</Button>
      <input ref={inputRef} type="file" accept={ALLOWED_IMAGE_ACCEPT} className="hidden" onChange={event => void upload(event.target.files?.[0])} />
    </div>
    {showHeader && <VideoUpload onChanged={refresh} onLocated={locate} />}
    {assetId && <Button type="button" variant="ghost" size="sm" onClick={() => { setAssetId(undefined); setPage(1) }}>{zh ? "显示全部资源" : "Show all assets"}</Button>}
    {loading ? <div className="flex min-h-48 items-center justify-center text-sm text-muted-foreground" role="status"><Loader2 className="mr-2 h-4 w-4 animate-spin" aria-hidden="true" />{t("common.loading")}</div>
      : error ? <div className="flex min-h-48 flex-col items-center justify-center gap-3 text-sm text-destructive" role="alert">{t("admin.loadError")}<Button type="button" variant="outline" onClick={refresh}>{t("common.retry")}</Button></div>
        : items.length === 0 ? <div className="flex min-h-48 flex-col items-center justify-center gap-2 rounded-card border border-dashed border-border-subtle text-sm text-muted-foreground"><ImagePlus className="h-7 w-7" aria-hidden="true" />{t("admin.mediaEmpty")}</div>
          : <ul className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5">
            {items.map(item => <li key={item.id} className="min-w-0 overflow-hidden rounded-card border border-border-subtle bg-surface">
              <button type="button" disabled={!item.url || item.status !== "ready"} aria-label={`${t("admin.mediaPreview")}: ${item.name}`} onClick={() => setPreview(item)} className="relative block aspect-square w-full bg-surface-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:cursor-default">
                {item.type === "image" && (item.thumbnail_url || item.url) && item.status === "ready" ? <ProductImage src={(item.thumbnail_url || item.url)!} variant="thumb" alt="" sizes="(max-width: 640px) 50vw, 240px" className="object-contain" /> : <span className="absolute inset-0 flex items-center justify-center text-muted-foreground">{item.type === "video" ? <Video className="h-8 w-8" aria-hidden="true" /> : <ImagePlus className="h-8 w-8" aria-hidden="true" />}</span>}
              </button>
              <div className="space-y-1 p-3">
                <p className="truncate text-sm font-medium" title={item.name}>{item.name}</p>
                <p className="text-xs text-muted-foreground">{item.format.toUpperCase() || item.type.toUpperCase()} · {formatSize(item.size)} · {item.usage.map(usageLabel).join(" / ")}</p>
                <p className="text-xs text-muted-foreground"><time dateTime={new Date(item.uploaded_at).toISOString()}>{new Date(item.uploaded_at).toLocaleString(zh ? "zh-CN" : "en-US")}</time></p>
                {item.status !== "ready" && <p className="break-words text-xs text-muted-foreground">{item.cleanup_error || item.processing_error || ({ uploading: zh ? "上传中" : "Uploading", processing: zh ? "处理中" : "Processing", failed: zh ? "处理失败" : "Failed", deleted: zh ? "已删除" : "Deleted" }[item.status])}</p>}
                {item.upload_cleanup_error && <p role="alert" className="break-words text-xs text-destructive">{item.upload_cleanup_error}</p>}
                <div className="flex flex-wrap gap-1 pt-1">
                  {item.url && item.status === "ready" && <Button type="button" size="icon" variant="ghost" className="h-9 w-9" aria-label={t("admin.mediaCopy")} title={t("admin.mediaCopy")} onClick={() => void copy(item)}><Copy aria-hidden="true" /></Button>}
                  {showHeader && (item.status !== "deleted" || item.cleanup_error) && <Button type="button" size="icon" variant="ghost" className="h-9 w-9 text-destructive" aria-label={item.status === "deleted" ? (zh ? "重试清理" : "Retry cleanup") : t("admin.mediaDelete")} onClick={() => manage(item)}><Trash2 aria-hidden="true" /></Button>}
                  {showHeader && item.type === "video" && item.status === "failed" && <Button type="button" size="sm" variant="outline" onClick={() => void retryVideo(item)}>{t("common.retry")}</Button>}
                  {showHeader && item.type === "video" && item.upload_cleanup_error && <Button type="button" size="sm" variant="outline" onClick={() => void retryVideoCleanup(item)}>{zh ? "重试清理上传文件" : "Retry upload cleanup"}</Button>}
                  {onSelect && item.url && item.status === "ready" && <Button type="button" size="sm" variant="outline" onClick={() => onSelect(item)}>{t("admin.mediaUse")}</Button>}
                </div>
              </div>
            </li>)}
          </ul>}
    {!loading && !error && totalPages > 1 && <div className="flex items-center justify-end gap-3 text-sm text-muted-foreground"><Button type="button" variant="outline" size="sm" disabled={page <= 1} onClick={() => setPage(value => value - 1)}>{t("admin.mediaPrevious")}</Button><span>{page} / {totalPages}</span><Button type="button" variant="outline" size="sm" disabled={page >= totalPages} onClick={() => setPage(value => value + 1)}>{t("admin.mediaNext")}</Button></div>}
    <Dialog open={!!preview} onOpenChange={open => { if (!open) setPreview(null) }}>
      <DialogContent className="flex max-h-[90dvh] max-w-4xl flex-col overflow-hidden">
        <DialogHeader><DialogTitle>{t("admin.mediaPreview")}</DialogTitle><DialogDescription className="break-all">{preview?.name}</DialogDescription></DialogHeader>
        {preview?.url && <><div className="relative min-h-48 h-[min(60dvh,40rem)] w-full overflow-hidden rounded-card bg-surface-muted">{preview.type === "video" ? <video controls preload="metadata" width={1280} height={720} src={preview.url} className="h-full w-full object-contain" /> : <ProductImage src={preview.url} alt={preview.name} sizes="(max-width: 768px) 90vw, 768px" className="object-contain" />}</div>
          {preview.type === "image" && <p className="text-xs text-muted-foreground">{zh ? "原文件" : "Original"}: {formatSize(preview.original_size ?? preview.size)} · {zh ? "展示" : "Display"}: {formatSize(preview.size)} · {zh ? "清晰版" : "Clear"}: {formatSize(preview.clear_size ?? preview.size)}</p>}
          <div className="flex flex-wrap justify-end gap-2"><Button type="button" variant="outline" onClick={() => void copy(preview)}>{t("admin.mediaCopy")}</Button>{showHeader && <Button type="button" variant="destructive" onClick={() => manage(preview)}>{t("admin.mediaDelete")}</Button>}{onSelect && <Button type="button" onClick={() => { onSelect(preview); setPreview(null) }}>{t("admin.mediaUse")}</Button>}</div></>}
      </DialogContent>
    </Dialog>
    <dialog ref={manageDialog} aria-labelledby="media-manage-title" onClose={() => setManaged(null)} onCancel={event => { if (working) event.preventDefault() }} className="media-manage-dialog myshop-dialog static-material max-h-[90dvh] w-[calc(100%-2rem)] max-w-lg overflow-y-auto rounded-surface p-6 text-foreground">
      <div className="space-y-4"><div><h2 id="media-manage-title" className="text-lg font-semibold">{t("admin.mediaInspect")}</h2><p className="break-all text-sm text-muted-foreground">{managed?.name}</p></div>
        {inspectionFailed ? <div role="alert" className="space-y-2 text-sm text-destructive"><p>{t("admin.mediaInspectFailed")}</p><Button type="button" size="sm" variant="outline" onClick={() => setInspectionRevision(value => value + 1)}>{t("common.retry")}</Button></div>
          : !inspection ? <div role="status" className="flex items-center gap-2 text-sm text-muted-foreground"><Loader2 className="h-4 w-4 animate-spin" />{t("common.loading")}</div>
            : <div className="space-y-3 text-sm"><p>{inspection.format.toUpperCase()} · {formatSize(inspection.size)}{inspection.width && inspection.height ? ` · ${inspection.width} × ${inspection.height}` : ""}</p>
              <div><p className="font-medium">{t("admin.mediaReferences")}</p>{inspection.references.length ? <ul className="mt-1 space-y-1 text-muted-foreground">{inspection.references.map(ref => <li key={`${ref.owner_type}:${ref.owner_id}:${ref.field}`}>{ref.source} · {ref.owner_id} · {ref.field}</li>)}</ul> : <p className="mt-1 text-muted-foreground">{t("admin.mediaUnused")}</p>}</div>
              {managed?.cleanup_error ? <p role="alert" className="text-destructive">{managed.cleanup_error}</p> : <p>{t("admin.mediaDeleteImpact")}</p>}</div>}
        <div className="flex flex-wrap justify-end gap-2"><Button type="button" variant="outline" autoFocus disabled={working} onClick={() => manageDialog.current?.close()}>{t("common.cancel")}</Button><Button type="button" variant="destructive" disabled={working || !inspection} onClick={() => void performDelete()}>{managed?.cleanup_error ? (zh ? "重试清理" : "Retry cleanup") : t("admin.mediaConfirmDelete")}</Button></div>
      </div>
    </dialog>
  </div>
}

function formatSize(bytes: number) {
  return bytes < 1024 ? `${bytes} B` : bytes < 1024 * 1024 ? `${(bytes / 1024).toFixed(1)} KB` : `${(bytes / 1024 / 1024).toFixed(2)} MB`
}

export function MediaPicker({ onSelect, disabled = false, onOpen }: { onSelect: (item: MediaItem) => void; disabled?: boolean; onOpen?: () => void }) {
  const { t } = useLocale()
  const [open, setOpen] = useState(false)
  return <><Button type="button" variant="outline" size="sm" disabled={disabled} onClick={() => { onOpen?.(); setOpen(true) }}><ImagePlus className="h-4 w-4" aria-hidden="true" />{t("admin.mediaSelect")}</Button>
    <Dialog open={open} onOpenChange={setOpen}><DialogContent className="flex max-h-[90dvh] max-w-6xl flex-col overflow-hidden"><DialogHeader><DialogTitle>{t("admin.mediaSelect")}</DialogTitle><DialogDescription className="sr-only">{t("admin.media")}</DialogDescription></DialogHeader><div className="min-h-0 overflow-y-auto"><MediaLibrary onSelect={item => { onSelect(item); setOpen(false) }} /></div></DialogContent></Dialog>
  </>
}
